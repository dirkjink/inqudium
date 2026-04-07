package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Arrays;

/**
 * Immutable implementation of a time-based sliding window failure tracking strategy.
 *
 * <h2>Algorithm Overview</h2>
 * <p>Tracks failures across a rolling time window divided into 1-second buckets.
 * The circuit trips when the total number of failures across all active buckets
 * reaches or exceeds {@code maxFailuresInWindow}. Unlike the count-based
 * {@link SlidingWindowMetrics}, this strategy forgets data based on the passage of
 * time, not the arrival of new requests.
 *
 * <h2>Bucket Mechanics</h2>
 * <p>The window is represented as an {@code int[]} of size {@code windowSizeInSeconds},
 * addressed by {@code second % windowSizeInSeconds} (modular arithmetic). Each bucket
 * accumulates the number of failures that occurred during that particular second.
 *
 * <p>A "fast-forward" step ensures consistency: whenever time advances, all buckets
 * between the last update and the current second are cleared, effectively expiring
 * stale data. If the gap exceeds the window size, all buckets are reset at once.
 *
 * <h2>Success Handling</h2>
 * <p>Successes do not directly modify any bucket counter. A recorded success only
 * triggers the fast-forward (clearing expired buckets), ensuring the window stays
 * up to date even if only successes are flowing.
 *
 * <h2>Time Conversion</h2>
 * <p>Internally, nanosecond timestamps are converted to seconds via integer division
 * ({@code nanos / 1_000_000_000L}). Negative bucket indices (possible with modular
 * arithmetic on certain nanos values) are corrected by adding {@code windowSizeInSeconds}.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>You want failure detection based on a rolling time window (e.g., "no more than
 *       5 failures in the last 30 seconds").</li>
 *   <li>Request rates vary widely, and a count-based window would behave inconsistently.</li>
 *   <li>You do not need error-rate tracking (success counts are not stored) — only
 *       absolute failure counts matter.</li>
 * </ul>
 *
 * @param maxFailuresInWindow  the failure count threshold; circuit trips when total failures
 *                             across all buckets reaches this value
 * @param windowSizeInSeconds  the number of 1-second buckets in the window (also the window
 *                             duration in seconds)
 * @param failureBuckets       the array of per-second failure counts (circular, size = windowSizeInSeconds)
 * @param lastUpdatedSecond    the second (converted from nanos) of the most recent update
 */
public record TimeBasedSlidingWindowMetrics(
    int maxFailuresInWindow,
    int windowSizeInSeconds,
    int[] failureBuckets,
    long lastUpdatedSecond
) implements FailureMetrics {

  /**
   * Creates an empty time-based sliding window anchored at the given time.
   *
   * @param maxFailuresInWindow  the failure threshold
   * @param windowSizeInSeconds  the window duration in seconds; must be > 0
   * @param nowNanos             the initial timestamp anchor (in nanoseconds)
   * @return a fresh instance with all buckets zeroed
   * @throws IllegalArgumentException if {@code windowSizeInSeconds <= 0}
   */
  public static TimeBasedSlidingWindowMetrics initial(int maxFailuresInWindow, int windowSizeInSeconds, long nowNanos) {
    if (windowSizeInSeconds <= 0) {
      throw new IllegalArgumentException("windowSizeInSeconds must be greater than 0");
    }
    return new TimeBasedSlidingWindowMetrics(
        maxFailuresInWindow,
        windowSizeInSeconds,
        new int[windowSizeInSeconds],
        toSeconds(nowNanos));
  }

  /**
   * Converts a nanosecond timestamp to whole seconds via integer division.
   */
  private static long toSeconds(long nanos) {
    return nanos / 1_000_000_000L;
  }

  /**
   * Records a successful call. No failure count is incremented, but the window
   * is fast-forwarded to the current time to expire stale buckets.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return a new instance with stale buckets cleared (if time has advanced)
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return fastForward(toSeconds(nowNanos));
  }

  /**
   * Records a failed call. The window is first fast-forwarded to the current time
   * (clearing stale buckets), then the failure count in the current second's bucket
   * is incremented by one.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return a new instance with the updated failure bucket
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    long currentSecond = toSeconds(nowNanos);
    TimeBasedSlidingWindowMetrics updatedState = fastForward(currentSecond);

    int[] newBuckets = Arrays.copyOf(updatedState.failureBuckets(), windowSizeInSeconds);
    int currentBucketIndex = (int) (currentSecond % windowSizeInSeconds);
    if (currentBucketIndex < 0) {
      currentBucketIndex += windowSizeInSeconds;
    }
    newBuckets[currentBucketIndex]++;

    long newLastUpdatedSecond = Math.max(updatedState.lastUpdatedSecond(), currentSecond);
    return new TimeBasedSlidingWindowMetrics(maxFailuresInWindow,
        windowSizeInSeconds,
        newBuckets,
        newLastUpdatedSecond);
  }

  /**
   * Evaluates whether the total failures across all active buckets meet or exceed
   * the threshold. The window is fast-forwarded to the query time first.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return {@code true} if the sum of all bucket counts >= maxFailuresInWindow
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    TimeBasedSlidingWindowMetrics evaluatedState = fastForward(toSeconds(nowNanos));
    int totalFailuresInWindow = 0;
    for (int count : evaluatedState.failureBuckets()) {
      totalFailuresInWindow += count;
    }
    return totalFailuresInWindow >= maxFailuresInWindow;
  }

  /**
   * Resets all buckets to zero and re-anchors the timestamp.
   *
   * @param nowNanos the new timestamp anchor
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return new TimeBasedSlidingWindowMetrics(
        maxFailuresInWindow,
        windowSizeInSeconds,
        new int[windowSizeInSeconds],
        toSeconds(nowNanos));
  }

  /**
   * Advances the window to the given second, clearing any buckets that have become
   * stale since the last update.
   *
   * <p>Three cases:
   * <ol>
   *   <li><strong>No time has passed</strong> ({@code currentSecond <= lastUpdatedSecond}):
   *       returns {@code this} unchanged.</li>
   *   <li><strong>Gap exceeds window size</strong>: all data is stale, so return a
   *       completely zeroed instance anchored at {@code currentSecond}.</li>
   *   <li><strong>Partial advance</strong>: iterate from {@code lastUpdatedSecond + 1}
   *       to {@code currentSecond}, zeroing each corresponding bucket index.
   *       This clears exactly the buckets that have "rotated out" of the window.</li>
   * </ol>
   *
   * @param currentSecond the target second to advance to
   * @return a new instance with expired buckets cleared
   */
  private TimeBasedSlidingWindowMetrics fastForward(long currentSecond) {
    if (currentSecond <= lastUpdatedSecond) {
      return this;
    }
    long deltaSeconds = currentSecond - lastUpdatedSecond;
    if (deltaSeconds >= windowSizeInSeconds) {
      // Entire window has expired — reset all buckets
      return new TimeBasedSlidingWindowMetrics(
          maxFailuresInWindow,
          windowSizeInSeconds,
          new int[windowSizeInSeconds],
          currentSecond);
    }
    // Clear only the buckets that have expired since the last update
    int[] newBuckets = Arrays.copyOf(failureBuckets, windowSizeInSeconds);
    for (long i = 1; i <= deltaSeconds; i++) {
      long timeToClear = lastUpdatedSecond + i;
      int indexToClear = (int) (timeToClear % windowSizeInSeconds);
      if (indexToClear < 0) {
        indexToClear += windowSizeInSeconds;
      }
      newBuckets[indexToClear] = 0;
    }
    return new TimeBasedSlidingWindowMetrics(maxFailuresInWindow,
        windowSizeInSeconds,
        newBuckets,
        currentSecond);
  }

  /**
   * Produces a diagnostic message showing the total failures in the window versus the threshold.
   * The window is fast-forwarded to {@code nowNanos} for accuracy.
   *
   * @param nowNanos the current timestamp in nanoseconds
   */
  @Override
  public String getTripReason(long nowNanos) {
    TimeBasedSlidingWindowMetrics evaluatedState = fastForward(toSeconds(nowNanos));
    int totalFailuresInWindow = Arrays.stream(evaluatedState.failureBuckets()).sum();
    return "Time-based sliding window threshold reached: Found %d failures in the last %d seconds (Threshold: %d)."
        .formatted(totalFailuresInWindow, windowSizeInSeconds, maxFailuresInWindow);
  }
}
