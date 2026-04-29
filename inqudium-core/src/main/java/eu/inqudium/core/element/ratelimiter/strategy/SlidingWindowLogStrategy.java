package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.ReservationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

public class SlidingWindowLogStrategy implements RateLimiterStrategy<SlidingWindowLogState> {

    private static final long[] EMPTY_LOG = new long[0];

    @Override
    public SlidingWindowLogState initial(RateLimiterConfig<SlidingWindowLogState> config, Instant now) {
        return new SlidingWindowLogState(EMPTY_LOG, 0L);
    }

    /**
     * Bereinigt das Array um alle Zeitstempel, die älter als das Fenster sind.
     */
    private SlidingWindowLogState refresh(SlidingWindowLogState state, RateLimiterConfig<?> config, long nowMs) {
        long cutoffMs = nowMs - config.refillPeriod().toMillis();
        long[] timestamps = state.timestampsMs();

        int validStartIndex = 0;
        while (validStartIndex < timestamps.length && timestamps[validStartIndex] <= cutoffMs) {
            validStartIndex++;
        }

        if (validStartIndex == 0) return state;
        if (validStartIndex == timestamps.length) return state.withTimestamps(EMPTY_LOG);

        long[] validTimestamps = new long[timestamps.length - validStartIndex];
        System.arraycopy(timestamps, validStartIndex, validTimestamps, 0, validTimestamps.length);
        return state.withTimestamps(validTimestamps);
    }

    @Override
    public RateLimitPermission<SlidingWindowLogState> tryAcquirePermissions(
            SlidingWindowLogState state, RateLimiterConfig<SlidingWindowLogState> config, Instant now, int permits) {

        validatePermits(permits, config);
        long nowMs = now.toEpochMilli();
        SlidingWindowLogState refreshed = refresh(state, config, nowMs);

        if (refreshed.timestampsMs().length + permits <= config.capacity()) {
            return RateLimitPermission.permitted(refreshed.append(nowMs, permits));
        }

        return RateLimitPermission.rejected(refreshed, estimateWaitDuration(refreshed, config, nowMs, permits));
    }

    @Override
    public ReservationResult<SlidingWindowLogState> reservePermissions(
            SlidingWindowLogState state, RateLimiterConfig<SlidingWindowLogState> config, Instant now, int permits, Duration timeout) {

        validatePermits(permits, config);
        long nowMs = now.toEpochMilli();
        SlidingWindowLogState refreshed = refresh(state, config, nowMs);

        if (refreshed.timestampsMs().length + permits <= config.capacity()) {
            return ReservationResult.immediate(refreshed.append(nowMs, permits));
        }

        Duration waitDuration = estimateWaitDuration(refreshed, config, nowMs, permits);
        if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
            return ReservationResult.timedOut(refreshed, waitDuration);
        }

        // Bei Sliding Window Log bedeutet eine Reservierung, dass wir den Zeitstempel in die
        // Zukunft legen (auf den Moment, wo die Ausführung stattfinden darf).
        long futureExecutionMs = nowMs + waitDuration.toMillis();
        return ReservationResult.delayed(refreshed.append(futureExecutionMs, permits), waitDuration);
    }

    @Override
    public SlidingWindowLogState drain(SlidingWindowLogState state, RateLimiterConfig<SlidingWindowLogState> config, Instant now) {
        // Entleeren bedeutet hier, den Log künstlich mit aktuellen Zeitstempeln bis zur Kapazität zu füllen
        long nowMs = now.toEpochMilli();
        long[] fullLog = new long[config.capacity()];
        Arrays.fill(fullLog, nowMs);
        return state.withNextEpoch(fullLog, now);
    }

    @Override
    public SlidingWindowLogState reset(SlidingWindowLogState state, RateLimiterConfig<SlidingWindowLogState> config, Instant now) {
        return state.withNextEpoch(EMPTY_LOG, now);
    }

    @Override
    public SlidingWindowLogState refund(SlidingWindowLogState state, RateLimiterConfig<SlidingWindowLogState> config, int permits) {
        if (permits < 1 || state.timestampsMs().length == 0) return state;
        long[] current = state.timestampsMs();
        int toRemove = Math.min(permits, current.length);
        long[] refunded = new long[current.length - toRemove];
        System.arraycopy(current, 0, refunded, 0, refunded.length); // Die ältesten behalten, die neuesten entfernen
        return state.withTimestamps(refunded);
    }

    @Override
    public int availablePermits(SlidingWindowLogState state, RateLimiterConfig<SlidingWindowLogState> config, Instant now) {
        SlidingWindowLogState refreshed = refresh(state, config, now.toEpochMilli());
        return Math.max(0, config.capacity() - refreshed.timestampsMs().length);
    }

    private Duration estimateWaitDuration(SlidingWindowLogState state, RateLimiterConfig<?> config, long nowMs, int permits) {
        long[] timestamps = state.timestampsMs();
        int targetSize = config.capacity() - permits;

        if (targetSize < 0) return Duration.ofDays(999); // Safety fallback

        // Um Platz für 'permits' zu machen, muss das Element an Index (length - targetSize - 1) ablaufen
        int indexOfMustExpire = timestamps.length - targetSize - 1;
        if (indexOfMustExpire < 0) return Duration.ZERO;

        long expiryTimeMs = timestamps[indexOfMustExpire] + config.refillPeriod().toMillis();
        long waitMs = expiryTimeMs - nowMs;
        return waitMs > 0 ? Duration.ofMillis(waitMs) : Duration.ZERO;
    }

    private void validatePermits(int permits, RateLimiterConfig<?> config) {
        if (permits < 1) throw new IllegalArgumentException("permits must be >= 1");
        if (permits > config.capacity()) throw new IllegalArgumentException("permits exceeds capacity");
    }
}
