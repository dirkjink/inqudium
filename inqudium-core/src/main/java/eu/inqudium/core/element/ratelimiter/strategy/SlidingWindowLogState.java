package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimiterState;

import java.time.Instant;

public record SlidingWindowLogState(
        long[] timestampsMs,
        long epoch
) implements RateLimiterState {

    public SlidingWindowLogState withNextEpoch(Instant now) {
        return new SlidingWindowLogState(timestampsMs, epoch + 1);
    }

    public SlidingWindowLogState withNextEpoch(long[] newTimestamps, Instant now) {
        return new SlidingWindowLogState(newTimestamps, epoch + 1);
    }

    public SlidingWindowLogState withTimestamps(long[] newTimestamps) {
        return new SlidingWindowLogState(newTimestamps, epoch);
    }

    /**
     * Erzeugt ein neues Array mit angehängten Zeitstempeln.
     */
    public SlidingWindowLogState append(long nowMs, int permits) {
        long[] next = new long[timestampsMs.length + permits];
        System.arraycopy(timestampsMs, 0, next, 0, timestampsMs.length);
        for (int i = 0; i < permits; i++) {
            next[timestampsMs.length + i] = nowMs;
        }
        return new SlidingWindowLogState(next, epoch);
    }
}
