package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.ReservationResult;

import java.time.Duration;
import java.time.Instant;

/**
 * Token Bucket rate limiting strategy.
 *
 * <p>Permits are consumed from the bucket and refilled at a fixed rate.
 * The bucket can go into "debt" (negative permits) for reservations,
 * bounded by a debt floor of {@code -capacity}.
 *
 * <h2>Drain vs. Reset semantics</h2>
 * <ul>
 *   <li><strong>drain:</strong> Removes all available permits but honors existing
 *       reservations. Parked threads continue waiting and eventually proceed.
 *       The epoch is unchanged — no invalidation occurs.</li>
 *   <li><strong>reset:</strong> Restores the bucket to full capacity and increments
 *       the epoch, invalidating all pending reservations. Parked threads wake up
 *       and must re-acquire permits from the fresh bucket.</li>
 * </ul>
 */
public class TokenBucketStrategy implements RateLimiterStrategy<TokenBucketState> {

    @Override
    public TokenBucketState initial(RateLimiterConfig<TokenBucketState> config, Instant now) {
        return new TokenBucketState(config.capacity(), now, 0L);
    }

    /**
     * Refills the bucket based on elapsed time since the last refill.
     * Only complete refill periods count — partial periods are not credited.
     */
    private TokenBucketState refill(TokenBucketState state, RateLimiterConfig<TokenBucketState> config, Instant now) {
        long elapsedNanos = Duration.between(state.lastRefillTime(), now).toNanos();
        if (elapsedNanos <= 0) return state;

        long periodNanos = config.refillPeriod().toNanos();
        long completePeriods = elapsedNanos / periodNanos;

        if (completePeriods <= 0) return state;

        Instant newRefillTime = state.lastRefillTime().plusNanos(completePeriods * periodNanos);
        long tokensToAdd = completePeriods * config.refillPermits();

        // Fast-path: if enough periods have passed to fill the bucket from any debt level
        if (tokensToAdd >= (long) config.capacity() - Math.min(state.availablePermits(), 0)) {
            return state.withRefill(config.capacity(), newRefillTime);
        }

        int newPermits = (int) Math.min((long) state.availablePermits() + tokensToAdd, config.capacity());
        return state.withRefill(newPermits, newRefillTime);
    }

    @Override
    public RateLimitPermission<TokenBucketState> tryAcquirePermissions(
            TokenBucketState state, RateLimiterConfig<TokenBucketState> config, Instant now, int permits) {
        validatePermits(permits, config);
        TokenBucketState refilled = refill(state, config, now);

        if (refilled.availablePermits() >= permits) {
            return RateLimitPermission.permitted(
                    refilled.withAvailablePermits(refilled.availablePermits() - permits));
        }
        return RateLimitPermission.rejected(refilled, estimateWaitDuration(refilled, config, permits));
    }

    @Override
    public ReservationResult<TokenBucketState> reservePermissions(
            TokenBucketState state, RateLimiterConfig<TokenBucketState> config, Instant now, int permits, Duration timeout) {
        validatePermits(permits, config);
        TokenBucketState refilled = refill(state, config, now);

        if (refilled.availablePermits() >= permits) {
            return ReservationResult.immediate(
                    refilled.withAvailablePermits(refilled.availablePermits() - permits));
        }

        Duration waitDuration = estimateWaitDuration(refilled, config, permits);
        if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
            return ReservationResult.timedOut(refilled, waitDuration);
        }

        // Debt floor prevents unbounded permit debt. If consuming these permits
        // would push below -capacity, reject even if the wait duration would fit
        // within the timeout — the system is too backlogged.
        int debtFloor = -config.capacity();
        if (refilled.availablePermits() - permits < debtFloor) {
            return ReservationResult.timedOut(refilled, waitDuration);
        }

        return ReservationResult.delayed(
                refilled.withAvailablePermits(refilled.availablePermits() - permits), waitDuration);
    }

    /**
     * Fix 8: Drain removes all available permits but does NOT increment the epoch.
     *
     * <p>This means existing reservations (parked threads) are honored — they
     * continue waiting and proceed when their wait duration expires. Only future
     * callers are affected by the empty bucket.
     *
     * <p>Use {@link #reset} to invalidate all pending reservations.
     */
    @Override
    public TokenBucketState drain(TokenBucketState state, RateLimiterConfig<TokenBucketState> config, Instant now) {
        // Refill first to establish a consistent time baseline, then zero out
        TokenBucketState refilled = refill(state, config, now);
        return refilled.withAvailablePermits(0);
    }

    /**
     * Resets the bucket to full capacity and increments the epoch.
     * All pending reservations are invalidated — parked threads will retry.
     */
    @Override
    public TokenBucketState reset(TokenBucketState state, RateLimiterConfig<TokenBucketState> config, Instant now) {
        return state.withNextEpoch(config.capacity(), now);
    }

    @Override
    public TokenBucketState refund(TokenBucketState state, RateLimiterConfig<TokenBucketState> config, int permits) {
        if (permits < 1) return state;
        int newPermits = Math.min(state.availablePermits() + permits, config.capacity());
        return state.withAvailablePermits(newPermits);
    }

    @Override
    public int availablePermits(TokenBucketState state, RateLimiterConfig<TokenBucketState> config, Instant now) {
        return refill(state, config, now).availablePermits();
    }

    /**
     * Estimates how long a caller would need to wait for the required permits
     * to become available through natural refilling.
     *
     * <p><strong>Fix 11 — Note on debt interaction:</strong> When {@code availablePermits}
     * is negative (due to prior reservations that pushed the bucket into debt), the
     * deficit correctly includes that debt. However, the estimated wait duration is
     * theoretical — the {@code debtFloor} check in {@link #reservePermissions} may
     * reject the reservation before this wait would ever occur. The debtFloor acts
     * as a backpressure mechanism: even if the wait fits within the timeout, a
     * request is rejected if accepting it would create excessive permit debt.
     */
    private Duration estimateWaitDuration(
            TokenBucketState state, RateLimiterConfig<TokenBucketState> config, int requiredPermits) {
        if (state.availablePermits() >= requiredPermits) return Duration.ZERO;

        // deficit includes any existing debt (negative availablePermits)
        int deficit = requiredPermits - state.availablePermits();
        long cyclesNeeded = ((long) deficit + config.refillPermits() - 1) / config.refillPermits();
        return config.refillPeriod().multipliedBy(cyclesNeeded);
    }

    private void validatePermits(int permits, RateLimiterConfig<TokenBucketState> config) {
        if (permits < 1) throw new IllegalArgumentException("permits must be >= 1");
        if (permits > config.capacity()) throw new IllegalArgumentException("permits exceeds capacity");
    }
}
