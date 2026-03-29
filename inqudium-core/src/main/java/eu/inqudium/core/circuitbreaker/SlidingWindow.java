package eu.inqudium.core.circuitbreaker;

/**
 * Contract for the sliding window that records call outcomes and computes
 * failure and slow call rates.
 *
 * <p>Two implementations exist: {@link CountBasedSlidingWindow} (circular buffer)
 * and {@link TimeBasedSlidingWindow} (time buckets). Both are pure — no locks,
 * no atomics. The paradigm module provides synchronization (ADR-016).
 *
 * @since 0.1.0
 */
public interface SlidingWindow {

  /**
   * Records a call outcome and returns the updated snapshot.
   *
   * @param outcome the call outcome to record
   * @return the current window state after recording
   */
  WindowSnapshot record(CallOutcome outcome);

  /**
   * Returns the current snapshot without recording a new outcome.
   *
   * @return the current window state
   */
  WindowSnapshot snapshot();

  /**
   * Resets the window to its initial (empty) state.
   * Called when the circuit breaker transitions back to CLOSED.
   */
  void reset();
}
