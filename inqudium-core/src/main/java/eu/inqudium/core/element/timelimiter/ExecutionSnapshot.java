package eu.inqudium.core.element.timelimiter;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable snapshot of a single time-limited execution's state.
 *
 * <p>Each execution gets its own snapshot, tracking its lifecycle
 * from {@link ExecutionState#IDLE} through to a terminal state.
 * Unlike CircuitBreaker or RateLimiter, there is no shared mutable
 * state between executions — each snapshot is independent.
 *
 * @param state     the current execution state
 * @param startTime when the execution was started ({@code null} if IDLE)
 * @param deadline  the absolute instant at which the execution times out ({@code null} if IDLE)
 * @param endTime   when the execution reached a terminal state ({@code null} if not terminal)
 * @param failure   the exception that caused a FAILED state ({@code null} otherwise)
 */
public record ExecutionSnapshot(
    ExecutionState state,
    Instant startTime,
    Instant deadline,
    Instant endTime,
    Throwable failure
) {

  /**
   * Creates an initial snapshot in the IDLE state.
   */
  public static ExecutionSnapshot idle() {
    return new ExecutionSnapshot(ExecutionState.IDLE, null, null, null, null);
  }

  // --- Wither methods for immutable state transitions ---

  /**
   * Transitions to RUNNING with the given start time and computed deadline.
   */
  public ExecutionSnapshot withStarted(Instant startTime, Duration timeout) {
    return new ExecutionSnapshot(
        ExecutionState.RUNNING,
        startTime,
        startTime.plus(timeout),
        null,
        null
    );
  }

  /**
   * Transitions to COMPLETED with the given end time.
   */
  public ExecutionSnapshot withCompleted(Instant endTime) {
    return new ExecutionSnapshot(ExecutionState.COMPLETED, startTime, deadline, endTime, null);
  }

  /**
   * Transitions to FAILED with the given end time and cause.
   */
  public ExecutionSnapshot withFailed(Instant endTime, Throwable cause) {
    return new ExecutionSnapshot(ExecutionState.FAILED, startTime, deadline, endTime, cause);
  }

  /**
   * Transitions to TIMED_OUT with the given end time.
   */
  public ExecutionSnapshot withTimedOut(Instant endTime) {
    return new ExecutionSnapshot(ExecutionState.TIMED_OUT, startTime, deadline, endTime, null);
  }

  /**
   * Transitions to CANCELLED with the given end time.
   */
  public ExecutionSnapshot withCancelled(Instant endTime) {
    return new ExecutionSnapshot(ExecutionState.CANCELLED, startTime, deadline, endTime, null);
  }

  // --- Query helpers ---

  /**
   * Returns the elapsed duration from start to end (or to the given instant if not yet terminal).
   */
  public Duration elapsed(Instant now) {
    if (startTime == null) {
      return Duration.ZERO;
    }
    Instant end = endTime != null ? endTime : now;
    return Duration.between(startTime, end);
  }

  /**
   * Returns the remaining time until the deadline (negative if past).
   * Returns {@link Duration#ZERO} if no deadline is set.
   */
  public Duration remaining(Instant now) {
    if (deadline == null) {
      return Duration.ZERO;
    }
    return Duration.between(now, deadline);
  }

  /**
   * Returns {@code true} if the deadline has been exceeded.
   */
  public boolean isDeadlineExceeded(Instant now) {
    return deadline != null && !now.isBefore(deadline);
  }
}
