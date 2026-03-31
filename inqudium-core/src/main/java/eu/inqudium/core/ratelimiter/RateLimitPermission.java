package eu.inqudium.core.ratelimiter;

import java.time.Duration;

/**
 * Result of a permission check against the rate limiter.
 *
 * <p>Contains the updated snapshot and whether the call is permitted.
 * When not permitted, {@link #waitDuration()} indicates how long the
 * caller would need to wait before a permit becomes available.
 *
 * @param snapshot     the updated snapshot (with refill applied)
 * @param permitted    whether the call may proceed immediately
 * @param waitDuration estimated wait time until a permit is available
 *                     ({@link Duration#ZERO} when permitted)
 */
public record RateLimitPermission(
        RateLimiterSnapshot snapshot,
        boolean permitted,
        Duration waitDuration
) {

    public static RateLimitPermission permitted(RateLimiterSnapshot snapshot) {
        return new RateLimitPermission(snapshot, true, Duration.ZERO);
    }

    public static RateLimitPermission rejected(RateLimiterSnapshot snapshot, Duration waitDuration) {
        return new RateLimitPermission(snapshot, false, waitDuration);
    }
}
