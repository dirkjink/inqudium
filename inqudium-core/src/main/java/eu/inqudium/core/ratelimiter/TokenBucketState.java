package eu.inqudium.core.ratelimiter;

import java.time.Instant;

/**
 * State of the token bucket rate limiter.
 *
 * @param availableTokens     current number of tokens in the bucket
 * @param lastRefillTimestamp  when tokens were last refilled
 * @since 0.1.0
 */
public record TokenBucketState(int availableTokens, Instant lastRefillTimestamp) {

    /**
     * Creates the initial state with a full bucket.
     *
     * @param config the rate limiter configuration
     * @return initial state with bucket at capacity
     */
    public static TokenBucketState initial(RateLimiterConfig config) {
        return new TokenBucketState(config.getBucketSize(), config.getClock().instant());
    }
}
