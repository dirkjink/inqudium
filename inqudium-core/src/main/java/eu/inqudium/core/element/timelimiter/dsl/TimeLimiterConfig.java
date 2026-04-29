package eu.inqudium.core.element.timelimiter.dsl;

import java.time.Duration;

/**
 * The immutable configuration for a Time Limiter instance.
 */
public record TimeLimiterConfig(
        String name,
        Duration timeoutDuration,
        boolean cancelRunningTask
) {
}
