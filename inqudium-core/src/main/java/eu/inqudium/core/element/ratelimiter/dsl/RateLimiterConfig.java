package eu.inqudium.core.element.ratelimiter.dsl;

import java.time.Duration;

/**
 * The immutable configuration for a Rate Limiter instance.
 */
public record RateLimiterConfig(
    int limitForPeriod,
    Duration limitRefreshPeriod,
    Duration timeoutDuration
) {
}
