package eu.inqudium.core.element.timelimiter;

/**
 * Represents the lifecycle states of a single time-limited execution.
 *
 * <pre>
 *   IDLE ──[start]──► RUNNING ──[complete]──► COMPLETED
 *                        │
 *                        ├──[fail]──────────► FAILED
 *                        │
 *                        ├──[timeout]───────► TIMED_OUT
 *                        │
 *                        └──[cancel]────────► CANCELLED
 * </pre>
 */
public enum ExecutionState {

  /**
   * The execution has not started yet.
   */
  IDLE,

  /**
   * The execution is currently in progress.
   */
  RUNNING,

  /**
   * The execution completed successfully within the time limit.
   */
  COMPLETED,

  /**
   * The execution failed with an exception (but within the time limit).
   */
  FAILED,

  /**
   * The execution exceeded the configured timeout.
   */
  TIMED_OUT,

  /**
   * The execution was cancelled (typically after a timeout).
   */
  CANCELLED;

  /**
   * Returns {@code true} if this state represents a terminal outcome.
   */
  public boolean isTerminal() {
    return this != IDLE && this != RUNNING;
  }

  /**
   * Returns {@code true} if this state represents a successful outcome.
   */
  public boolean isSuccess() {
    return this == COMPLETED;
  }
}
