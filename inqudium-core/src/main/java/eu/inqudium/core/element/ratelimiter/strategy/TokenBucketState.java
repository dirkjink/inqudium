package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimiterState;

import java.time.Instant;

public record TokenBucketState(
        int availablePermits,
        Instant lastRefillTime,
        long epoch
) implements RateLimiterState {

    public TokenBucketState withAvailablePermits(int permits) {
        return new TokenBucketState(permits, lastRefillTime, epoch);
    }

    public TokenBucketState withRefill(int newPermits, Instant newRefillTime) {
        return new TokenBucketState(newPermits, newRefillTime, epoch);
    }

    @Override
    public TokenBucketState withNextEpoch(Instant now) {
        return new TokenBucketState(availablePermits, now, epoch + 1);
    }

    public TokenBucketState withNextEpoch(int permits, Instant now) {
        return new TokenBucketState(permits, now, epoch + 1);
    }
}
