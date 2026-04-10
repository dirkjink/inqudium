package eu.inqudium.core.element.retry.dsl;

import java.time.Duration;

/**
 * The immutable configuration for a Retry instance.
 */
public record RetryConfig(
        String name,
        int maxAttempts,
        Duration baseWaitDuration,
        double backoffMultiplier
) {
}
