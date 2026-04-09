package eu.inqudium.core.element.timelimiter.dsl;

import java.time.Duration;

/**
 * The immutable configuration for a Time Limiter instance.
 */
public record TimeLimiterConfig(
    Duration timeoutDuration,
    boolean cancelRunningTask
) {
}
