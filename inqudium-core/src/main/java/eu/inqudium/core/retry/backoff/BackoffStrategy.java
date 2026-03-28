package eu.inqudium.core.retry.backoff;

import java.time.Duration;

/**
 * Computes the delay before the next retry attempt.
 *
 * <p>Pure function — no side effects, no threading. The paradigm module decides
 * how to wait (ADR-018).
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface BackoffStrategy {

    /**
     * Computes the delay before the next retry attempt.
     *
     * @param attemptNumber   1-based attempt number (1 = first retry, not the initial call)
     * @param initialInterval the base interval from RetryConfig
     * @return the computed delay (before maxInterval capping)
     */
    Duration computeDelay(int attemptNumber, Duration initialInterval);

    /** Returns a fixed backoff strategy: every retry waits the same duration. */
    static BackoffStrategy fixed() {
        return new FixedBackoff();
    }

    /** Returns an exponential backoff strategy with default multiplier (2.0). */
    static BackoffStrategy exponential() {
        return new ExponentialBackoff();
    }

    /** Returns an exponential backoff strategy with a custom multiplier. */
    static BackoffStrategy exponential(double multiplier) {
        return new ExponentialBackoff(multiplier);
    }

    /** Returns exponential backoff with full jitter: {@code random(0, baseDelay)}. */
    static BackoffStrategy exponentialWithFullJitter() {
        return RandomizedBackoff.fullJitter(new ExponentialBackoff());
    }

    /** Returns exponential backoff with equal jitter: {@code baseDelay/2 + random(0, baseDelay/2)}. Recommended default. */
    static BackoffStrategy exponentialWithEqualJitter() {
        return RandomizedBackoff.equalJitter(new ExponentialBackoff());
    }

    /** Returns exponential backoff with decorrelated jitter. Stateful — per-call instances. */
    static BackoffStrategy exponentialWithDecorrelatedJitter() {
        return RandomizedBackoff.decorrelatedJitter(new ExponentialBackoff());
    }
}
