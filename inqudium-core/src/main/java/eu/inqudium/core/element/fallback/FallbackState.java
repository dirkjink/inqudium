package eu.inqudium.core.element.fallback;

/**
 * Represents the lifecycle states of a single fallback-protected execution.
 *
 * <pre>
 *   IDLE ──[start]──► EXECUTING ──[success]───────────► SUCCEEDED
 *                         │
 *                         ├──[failure matched]──► FALLING_BACK ──[ok]──► RECOVERED
 *                         │                            │
 *                         │                            └──[fail]──► FALLBACK_FAILED
 *                         │
 *                         └──[no handler matched]──► UNHANDLED
 * </pre>
 */
public enum FallbackState {

  /**
   * The execution has not started yet.
   */
  IDLE,

  /**
   * The primary operation is currently executing.
   */
  EXECUTING,

  /**
   * The primary operation succeeded without needing a fallback.
   */
  SUCCEEDED,

  /**
   * A fallback handler is currently executing.
   */
  FALLING_BACK,

  /**
   * A fallback handler recovered from the primary failure.
   */
  RECOVERED,

  /**
   * The fallback handler itself failed.
   */
  FALLBACK_FAILED,

  /**
   * No matching fallback handler was found for the exception.
   */
  UNHANDLED;

  /**
   * Returns {@code true} if this state represents a terminal outcome.
   */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == RECOVERED
        || this == FALLBACK_FAILED || this == UNHANDLED;
  }

  /**
   * Returns {@code true} if this state represents a successful outcome
   * (either primary success or recovered via fallback).
   */
  public boolean isSuccess() {
    return this == SUCCEEDED || this == RECOVERED;
  }
}
