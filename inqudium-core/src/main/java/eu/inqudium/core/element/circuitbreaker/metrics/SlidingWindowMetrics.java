package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Arrays;

/**
 * Immutable implementation of a count-based sliding window failure tracking strategy.
 *
 * <h2>Algorithm Overview</h2>
 * <p>Maintains a fixed-size circular buffer of the last {@code windowSize} call outcomes
 * (success or failure). Each new outcome overwrites the oldest entry in the buffer.
 * The circuit trips when the number of failures within the window reaches or exceeds
 * {@code maxFailuresInWindow}.
 *
 * <p>This provides a "moving picture" of recent system health that naturally forgets
 * old outcomes as new ones arrive, without any dependency on wall-clock time.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>You want failure detection based on a fixed number of recent requests rather than
 *       a time period — useful when request rates are highly variable.</li>
 *   <li>You need the circuit to self-heal as old failures rotate out of the window,
 *       even without explicit successes.</li>
 *   <li>A minimum-calls guard is desired to prevent tripping on insufficient data.</li>
 * </ul>
 *
 * <h2>Circular Buffer Mechanics</h2>
 * <p>The buffer is implemented as a {@code boolean[]} where {@code true} represents a
 * failure and {@code false} a success. The {@code headIndex} points to the most recently
 * written slot. When the window is full ({@code size == windowSize}), writing a new
 * outcome to the next slot evicts the oldest entry; the evicted value is subtracted
 * from {@code failureCount} before the new value is added, keeping the running tally
 * accurate in O(1) without re-scanning the array.
 *
 * <h2>Time Independence</h2>
 * <p>This algorithm is purely count-based. The {@code nowNanos} parameter is accepted
 * but ignored in all methods.
 *
 * @param maxFailuresInWindow  the failure count threshold within the window
 * @param windowSize           the total number of call outcomes retained in the circular buffer
 * @param minimumNumberOfCalls the minimum number of recorded calls before the threshold can trigger
 * @param window               the circular buffer of outcomes ({@code true} = failure)
 * @param headIndex            the index of the most recently written slot (-1 when empty)
 * @param size                 the number of outcomes currently stored (0..windowSize)
 * @param failureCount         the running count of failures currently in the window
 */
public record SlidingWindowMetrics(
    int maxFailuresInWindow,
    int windowSize,
    int minimumNumberOfCalls,
    boolean[] window,
    int headIndex,
    int size,
    int failureCount
) implements FailureMetrics {

  /**
   * Creates an empty sliding window with the given configuration.
   *
   * @param maxFailuresInWindow  the number of failures in the window that triggers the circuit
   * @param windowSize           how many recent outcomes to keep track of
   * @param minimumNumberOfCalls the minimum sample size before evaluation is meaningful;
   *                             must be between 1 and {@code windowSize} inclusive
   * @return a fresh instance with an empty window
   * @throws IllegalArgumentException if {@code windowSize <= 0} or
   *                                  {@code minimumNumberOfCalls} is outside [1, windowSize]
   */
  public static SlidingWindowMetrics initial(int maxFailuresInWindow, int windowSize, int minimumNumberOfCalls) {
    if (windowSize <= 0) {
      throw new IllegalArgumentException("windowSize must be greater than 0");
    }
    if (minimumNumberOfCalls <= 0 || minimumNumberOfCalls > windowSize) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be between 1 and windowSize");
    }
    return new SlidingWindowMetrics(
        maxFailuresInWindow,
        windowSize,
        minimumNumberOfCalls,
        new boolean[windowSize],
        -1,  // headIndex starts at -1 indicating an empty buffer
        0,
        0);
  }

  /**
   * Records a successful call by pushing {@code false} into the circular buffer.
   * If the evicted (oldest) entry was a failure, the failure count decreases by one.
   *
   * @param nowNanos ignored — this algorithm is count-based, not time-based
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(false);
  }

  /**
   * Records a failed call by pushing {@code true} into the circular buffer.
   * The failure count increases by one, minus one if the evicted entry was also a failure.
   *
   * @param nowNanos ignored — this algorithm is count-based, not time-based
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(true);
  }

  /**
   * Core recording logic shared by both success and failure paths.
   *
   * <p>Steps:
   * <ol>
   *   <li>Copy the existing window array (immutability).</li>
   *   <li>Advance the head index, wrapping around at {@code windowSize}.</li>
   *   <li>If the window is already full, read the value at the new head position
   *       — that is the oldest entry being evicted.</li>
   *   <li>Write the new outcome to the head position.</li>
   *   <li>Adjust {@code failureCount}: add 1 if the new outcome is a failure,
   *       subtract 1 if the evicted outcome was a failure.</li>
   * </ol>
   *
   * @param isFailure whether the current call outcome is a failure
   * @return a new instance reflecting the updated window state
   */
  private SlidingWindowMetrics recordOutcome(boolean isFailure) {
    boolean[] newWindow = Arrays.copyOf(window, windowSize);
    int newHeadIndex = (headIndex + 1) % windowSize;

    // Only account for eviction when the buffer is already full
    boolean oldOutcome = (size == windowSize) && newWindow[newHeadIndex];
    newWindow[newHeadIndex] = isFailure;

    int newSize = Math.min(windowSize, size + 1);
    int newFailureCount = failureCount + (isFailure ? 1 : 0) - (oldOutcome ? 1 : 0);

    return new SlidingWindowMetrics(
        maxFailuresInWindow, windowSize, minimumNumberOfCalls,
        newWindow, newHeadIndex, newSize, newFailureCount);
  }

  /**
   * Returns {@code true} if the window contains at least {@code minimumNumberOfCalls}
   * entries and the failure count meets or exceeds {@code maxFailuresInWindow}.
   *
   * @param nowNanos ignored — this algorithm is count-based, not time-based
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    if (size < minimumNumberOfCalls) {
      return false;
    }
    return failureCount >= maxFailuresInWindow;
  }

  /**
   * Resets the window to empty, preserving the original configuration.
   *
   * @param nowNanos ignored — this algorithm is count-based, not time-based
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return new SlidingWindowMetrics(
        maxFailuresInWindow, windowSize, minimumNumberOfCalls,
        new boolean[windowSize], -1, 0, 0);
  }

  /**
   * Produces a diagnostic message showing the current failure count in the window.
   *
   * @param nowNanos ignored — this algorithm is count-based, not time-based
   */
  @Override
  public String getTripReason(long nowNanos) {
    return "Sliding window threshold reached: Found %d failures in the last %d calls (Threshold: %d)."
        .formatted(failureCount, size, maxFailuresInWindow);
  }
}
