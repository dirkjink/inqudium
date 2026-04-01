package eu.inqudium.core.element.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of a single retry-protected execution's state.
 *
 * <p>Each execution gets its own snapshot, tracking the attempt lifecycle.
 * Like TimeLimiter, there is no shared mutable state between executions.
 *
 * @param state            the current retry state
 * @param attemptNumber    the current attempt number (1-based; 1 = initial call)
 * @param totalAttempts    total number of attempts made so far (including current)
 * @param lastFailure      the most recent failure ({@code null} if no failure yet)
 * @param failures         all recorded failures in order
 * @param startTime        when the overall execution started
 * @param attemptStartTime when the current attempt started
 * @param nextRetryDelay   the computed delay before the next retry ({@code null} if not waiting)
 */
public record RetrySnapshot(
    RetryState state,
    int attemptNumber,
    int totalAttempts,
    Throwable lastFailure,
    List<Throwable> failures,
    Instant startTime,
    Instant attemptStartTime,
    Duration nextRetryDelay
) {

  /**
   * Creates the initial snapshot in IDLE state.
   */
  public static RetrySnapshot idle() {
    return new RetrySnapshot(
        RetryState.IDLE, 0, 0, null, List.of(), null, null, null);
  }

  // --- Wither methods for immutable state transitions ---

  /**
   * Transitions to ATTEMPTING for the given attempt.
   */
  public RetrySnapshot withAttemptStarted(int attemptNumber, Instant now) {
    Instant start = this.startTime != null ? this.startTime : now;
    return new RetrySnapshot(
        RetryState.ATTEMPTING, attemptNumber, attemptNumber,
        lastFailure, failures, start, now, null);
  }

  /**
   * Transitions to COMPLETED.
   */
  public RetrySnapshot withCompleted() {
    return new RetrySnapshot(
        RetryState.COMPLETED, attemptNumber, totalAttempts,
        lastFailure, failures, startTime, attemptStartTime, null);
  }

  /**
   * Transitions to WAITING_FOR_RETRY with the given failure and computed delay.
   */
  public RetrySnapshot withRetryScheduled(Throwable failure, Duration delay) {
    List<Throwable> updatedFailures = new java.util.ArrayList<>(failures);
    updatedFailures.add(failure);
    return new RetrySnapshot(
        RetryState.WAITING_FOR_RETRY, attemptNumber, totalAttempts,
        failure, List.copyOf(updatedFailures), startTime, attemptStartTime, delay);
  }

  /**
   * Transitions to FAILED (non-retryable exception).
   */
  public RetrySnapshot withFailed(Throwable failure) {
    List<Throwable> updatedFailures = new java.util.ArrayList<>(failures);
    updatedFailures.add(failure);
    return new RetrySnapshot(
        RetryState.FAILED, attemptNumber, totalAttempts,
        failure, List.copyOf(updatedFailures), startTime, attemptStartTime, null);
  }

  /**
   * Transitions to EXHAUSTED (all retries consumed).
   */
  public RetrySnapshot withExhausted(Throwable failure) {
    List<Throwable> updatedFailures = new java.util.ArrayList<>(failures);
    updatedFailures.add(failure);
    return new RetrySnapshot(
        RetryState.EXHAUSTED, attemptNumber, totalAttempts,
        failure, List.copyOf(updatedFailures), startTime, attemptStartTime, null);
  }

  /**
   * Transitions to WAITING_FOR_RETRY for result-based retries (no exception).
   */
  public RetrySnapshot withResultRetryScheduled(Duration delay) {
    return new RetrySnapshot(
        RetryState.WAITING_FOR_RETRY, attemptNumber, totalAttempts,
        lastFailure, failures, startTime, attemptStartTime, delay);
  }

  // --- Query helpers ---

  /**
   * Returns the total elapsed duration since the execution started.
   */
  public Duration totalElapsed(Instant now) {
    if (startTime == null) {
      return Duration.ZERO;
    }
    return Duration.between(startTime, now);
  }

  /**
   * Returns the number of retries performed (totalAttempts - 1, or 0 if none).
   */
  public int retryCount() {
    return Math.max(0, totalAttempts - 1);
  }

  /**
   * Returns {@code true} if any retries have been made.
   */
  public boolean hasRetried() {
    return totalAttempts > 1;
  }
}
