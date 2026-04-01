package eu.inqudium.core.element.retry;

/**
 * Represents the lifecycle states of a single retry-protected execution.
 *
 * <pre>
 *   IDLE ──[start]──► ATTEMPTING ──[success]──► COMPLETED
 *                         │
 *                         ├──[retryable failure]──► WAITING_FOR_RETRY ──► ATTEMPTING
 *                         │
 *                         ├──[non-retryable failure]──► FAILED
 *                         │
 *                         └──[retries exhausted]──► EXHAUSTED
 * </pre>
 */
public enum RetryState {

  /**
   * The execution has not started yet.
   */
  IDLE,

  /**
   * An attempt is currently in progress.
   */
  ATTEMPTING,

  /**
   * An attempt failed; waiting for the backoff delay before the next retry.
   */
  WAITING_FOR_RETRY,

  /**
   * The operation completed successfully (possibly after retries).
   */
  COMPLETED,

  /**
   * The operation failed with a non-retryable exception.
   */
  FAILED,

  /**
   * All retry attempts have been exhausted.
   */
  EXHAUSTED;

  /**
   * Returns {@code true} if this state represents a terminal outcome.
   */
  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == EXHAUSTED;
  }

  /**
   * Returns {@code true} if this state represents a successful outcome.
   */
  public boolean isSuccess() {
    return this == COMPLETED;
  }
}
