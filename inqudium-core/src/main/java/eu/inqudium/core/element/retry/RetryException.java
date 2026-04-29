package eu.inqudium.core.element.retry;

import java.util.List;

/**
 * Exception thrown when all retry attempts have been exhausted.
 *
 * <p>The {@link #getCause()} returns the last recorded failure.
 * All failures are available via {@link #getFailures()}.
 */
public class RetryException extends RuntimeException {

    private final String retryName;
    private final String instanceId;
    private final int attempts;
    private final List<Throwable> failures;
    private final boolean resultBased;

    /**
     * Creates a new RetryException.
     *
     * @param retryName   the retry name
     * @param instanceId  unique instance identifier (Fix 7)
     * @param attempts    total number of attempts made
     * @param lastFailure the last recorded failure
     * @param failures    all recorded failures in order
     * @param resultBased whether exhaustion was caused by unacceptable results (Fix 8)
     */
    public RetryException(
            String retryName,
            String instanceId,
            int attempts,
            Throwable lastFailure,
            List<Throwable> failures,
            boolean resultBased) {
        super(buildMessage(retryName, attempts, lastFailure, resultBased), lastFailure);
        this.retryName = retryName;
        this.instanceId = instanceId;
        this.attempts = attempts;
        this.failures = List.copyOf(failures);
        this.resultBased = resultBased;
    }

    private static String buildMessage(String retryName, int attempts, Throwable lastFailure, boolean resultBased) {
        if (resultBased) {
            return "Retry '%s' exhausted all %d attempts due to unacceptable results. Last: %s"
                    .formatted(retryName, attempts, lastFailure.getMessage());
        }
        return "Retry '%s' exhausted all %d attempts. Last failure: %s"
                .formatted(retryName, attempts, lastFailure.getMessage());
    }

    public String getRetryName() {
        return retryName;
    }

    /**
     * Fix 7: Returns the unique instance identifier of the retry
     * that produced this exception.
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Returns the total number of attempts made.
     */
    public int getAttempts() {
        return attempts;
    }

    /**
     * Returns all recorded failures in order.
     */
    public List<Throwable> getFailures() {
        return failures;
    }

    /**
     * Fix 8: Returns whether this exhaustion was caused by unacceptable results
     * rather than exception-based failures.
     */
    public boolean isResultBased() {
        return resultBased;
    }
}
