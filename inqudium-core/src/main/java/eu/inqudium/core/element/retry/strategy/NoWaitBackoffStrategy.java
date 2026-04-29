package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;

/**
 * Zero delay — retry immediately without any wait.
 */
public record NoWaitBackoffStrategy() implements BackoffStrategy {

    @Override
    public Duration computeDelay(int attemptIndex) {
        return Duration.ZERO;
    }
}
