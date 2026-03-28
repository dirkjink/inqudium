package eu.inqudium.core.retry;

import eu.inqudium.core.exception.InqException;

import java.time.Duration;
import java.util.Optional;

/**
 * Behavioral contract for retry decisions.
 *
 * <p>Pure function — decides whether to retry and computes the delay.
 * Does not sleep or schedule. The paradigm module decides how to wait (ADR-018).
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface RetryBehavior {

    /**
     * Decides whether to retry after a failure.
     *
     * @param attemptNumber the current attempt number (1 = initial call, 2 = first retry)
     * @param exception     the exception from the current attempt
     * @param config        the retry configuration
     * @return the duration to wait before the next attempt, or empty if no retry
     */
    Optional<Duration> shouldRetry(int attemptNumber, Throwable exception, RetryConfig config);

    /**
     * Returns the default retry behavior implementation.
     *
     * <p>Checks in order: max attempts → InqException exclusion → ignoreOn →
     * retryOn → predicate → compute delay with maxInterval cap.
     *
     * @return the default behavior
     */
    static RetryBehavior defaultBehavior() {
        return DefaultRetryBehavior.INSTANCE;
    }
}

/**
 * Default implementation of the retry decision logic.
 */
final class DefaultRetryBehavior implements RetryBehavior {

    static final DefaultRetryBehavior INSTANCE = new DefaultRetryBehavior();

    private DefaultRetryBehavior() {}

    @Override
    public Optional<Duration> shouldRetry(int attemptNumber, Throwable exception, RetryConfig config) {
        // 1. Max attempts exhausted?
        if (attemptNumber >= config.getMaxAttempts()) {
            return Optional.empty();
        }

        // 2. InqException exclusion (default: do not retry on Inqudium interventions)
        if (!config.isRetryOnInqExceptions() && exception instanceof InqException) {
            return Optional.empty();
        }

        // 3. Explicitly ignored exception types
        for (var ignoredType : config.getIgnoreOn()) {
            if (ignoredType.isInstance(exception)) {
                return Optional.empty();
            }
        }

        // 4. Retry allowlist (if non-empty, only listed types are retried)
        if (!config.getRetryOn().isEmpty()) {
            boolean matches = false;
            for (var retryType : config.getRetryOn()) {
                if (retryType.isInstance(exception)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                return Optional.empty();
            }
        }

        // 5. Custom predicate
        var predicate = config.getRetryOnPredicate();
        if (predicate != null && !predicate.test(exception)) {
            return Optional.empty();
        }

        // 6. Compute delay (attemptNumber for backoff is 1-based retry count)
        int retryNumber = attemptNumber; // attempt 1 = initial call failed, computing delay for retry 1
        Duration delay = config.getBackoffStrategy()
                .computeDelay(retryNumber, config.getInitialInterval());

        // Cap at maxInterval
        if (delay.compareTo(config.getMaxInterval()) > 0) {
            delay = config.getMaxInterval();
        }

        return Optional.of(delay);
    }
}
