package eu.inqudium.core.element.retry;

import java.time.Duration;

/**
 * The decision made by the retry core after an attempt completes.
 *
 * <p>This sealed hierarchy represents the four possible outcomes:
 * <ul>
 *   <li>{@link Accept} — the result is acceptable; the operation is complete.</li>
 *   <li>{@link DoRetry} — a retry should be attempted after the given delay.</li>
 *   <li>{@link DoNotRetry} — the exception is not retryable; propagate it.</li>
 *   <li>{@link RetriesExhausted} — all attempts consumed; propagate the last exception.</li>
 * </ul>
 */
public sealed interface RetryDecision {

    /**
     * Returns the updated snapshot reflecting this decision.
     */
    RetrySnapshot snapshot();

    /**
     * Fix 1: The result is acceptable — no retry needed.
     * This replaces the previous null-return pattern in evaluateResult,
     * making the API consistent with evaluateFailure.
     *
     * @param snapshot the updated snapshot (in COMPLETED state)
     */
    record Accept(RetrySnapshot snapshot) implements RetryDecision {
    }

    /**
     * Retry after the given delay.
     *
     * @param snapshot   the updated snapshot (in WAITING_FOR_RETRY state)
     * @param delay      the backoff delay before the next attempt
     * @param retryIndex the zero-based index of this retry (0 = first retry)
     */
    record DoRetry(RetrySnapshot snapshot, Duration delay, int retryIndex) implements RetryDecision {
    }

    /**
     * Do not retry — the exception is not retryable.
     *
     * @param snapshot the updated snapshot (in FAILED state)
     * @param failure  the non-retryable exception
     */
    record DoNotRetry(RetrySnapshot snapshot, Throwable failure) implements RetryDecision {
    }

    /**
     * All retries exhausted — propagate the last failure.
     *
     * @param snapshot    the updated snapshot (in EXHAUSTED state)
     * @param failure     the last recorded failure
     * @param resultBased whether exhaustion was caused by unacceptable results
     *                    rather than exceptions (Fix 8)
     */
    record RetriesExhausted(
            RetrySnapshot snapshot,
            Throwable failure,
            boolean resultBased
    ) implements RetryDecision {

        /**
         * Convenience constructor for exception-based exhaustion (backward compatible).
         */
        public RetriesExhausted(RetrySnapshot snapshot, Throwable failure) {
            this(snapshot, failure, false);
        }
    }
}
