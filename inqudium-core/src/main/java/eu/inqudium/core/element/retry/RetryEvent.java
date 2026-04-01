package eu.inqudium.core.element.retry;

import java.time.Duration;
import java.time.Instant;

/**
 * Event emitted by the retry mechanism for observability.
 *
 * @param name          the retry name
 * @param type          the event type
 * @param attemptNumber the current attempt number (1-based)
 * @param retryDelay    the delay before the next attempt (for RETRY_SCHEDULED events)
 * @param elapsed       total elapsed time since the first attempt
 * @param failure       the exception that triggered this event ({@code null} for success)
 * @param timestamp     when the event occurred
 */
public record RetryEvent(
    String name,
    Type type,
    int attemptNumber,
    Duration retryDelay,
    Duration elapsed,
    Throwable failure,
    Instant timestamp
) {

  public static RetryEvent attemptStarted(String name, int attemptNumber, Duration elapsed, Instant now) {
    return new RetryEvent(name, Type.ATTEMPT_STARTED, attemptNumber, Duration.ZERO, elapsed, null, now);
  }

  public static RetryEvent attemptSucceeded(String name, int attemptNumber, Duration elapsed, Instant now) {
    return new RetryEvent(name, Type.ATTEMPT_SUCCEEDED, attemptNumber, Duration.ZERO, elapsed, null, now);
  }

  public static RetryEvent retryScheduled(String name, int attemptNumber, Duration retryDelay,
                                          Duration elapsed, Throwable failure, Instant now) {
    return new RetryEvent(name, Type.RETRY_SCHEDULED, attemptNumber, retryDelay, elapsed, failure, now);
  }

  public static RetryEvent failedNonRetryable(String name, int attemptNumber, Duration elapsed,
                                              Throwable failure, Instant now) {
    return new RetryEvent(name, Type.FAILED_NON_RETRYABLE, attemptNumber, Duration.ZERO, elapsed, failure, now);
  }

  public static RetryEvent retriesExhausted(String name, int attemptNumber, Duration elapsed,
                                            Throwable failure, Instant now) {
    return new RetryEvent(name, Type.RETRIES_EXHAUSTED, attemptNumber, Duration.ZERO, elapsed, failure, now);
  }

  public static RetryEvent resultRetryScheduled(String name, int attemptNumber, Duration retryDelay,
                                                Duration elapsed, Instant now) {
    return new RetryEvent(name, Type.RESULT_RETRY_SCHEDULED, attemptNumber, retryDelay, elapsed, null, now);
  }

  @Override
  public String toString() {
    return "Retry '%s': %s — attempt %d, elapsed %s ms at %s"
        .formatted(name, type, attemptNumber, elapsed.toMillis(), timestamp);
  }

  public enum Type {
    /**
     * An attempt started.
     */
    ATTEMPT_STARTED,
    /**
     * An attempt succeeded.
     */
    ATTEMPT_SUCCEEDED,
    /**
     * An attempt failed; a retry has been scheduled.
     */
    RETRY_SCHEDULED,
    /**
     * An attempt failed with a non-retryable exception.
     */
    FAILED_NON_RETRYABLE,
    /**
     * All attempts exhausted.
     */
    RETRIES_EXHAUSTED,
    /**
     * A result-based retry has been scheduled.
     */
    RESULT_RETRY_SCHEDULED
  }
}
