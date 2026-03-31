package eu.inqudium.core.ratelimiter;

import java.time.Instant;

/**
 * Immutable snapshot of the rate limiter's internal state.
 *
 * <p>This is the central data structure of the functional core.
 * All state transitions produce a new snapshot rather than mutating in place.
 *
 * <p>The token bucket model works as follows: {@code availablePermits}
 * tracks the current fill level. On each permission check the core first
 * refills tokens based on elapsed time since {@code lastRefillTime}, then
 * checks whether a token can be consumed.
 *
 * @param availablePermits current number of permits in the bucket
 * @param lastRefillTime   timestamp of the last (possibly virtual) refill
 */
public record RateLimiterSnapshot(
        int availablePermits,
        Instant lastRefillTime
) {

    /**
     * Creates the initial snapshot with a full bucket.
     */
    public static RateLimiterSnapshot initial(RateLimiterConfig config, Instant now) {
        return new RateLimiterSnapshot(config.capacity(), now);
    }

    // --- Wither methods for immutable updates ---

    public RateLimiterSnapshot withAvailablePermits(int permits) {
        return new RateLimiterSnapshot(permits, lastRefillTime);
    }

    public RateLimiterSnapshot withLastRefillTime(Instant time) {
        return new RateLimiterSnapshot(availablePermits, time);
    }

    public RateLimiterSnapshot withPermitConsumed() {
        return new RateLimiterSnapshot(availablePermits - 1, lastRefillTime);
    }

    public RateLimiterSnapshot withRefill(int newPermits, Instant newRefillTime) {
        return new RateLimiterSnapshot(newPermits, newRefillTime);
    }
}
