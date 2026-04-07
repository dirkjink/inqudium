package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Arrays;

/**
 * Immutable implementation of a time-based sliding window algorithm.
 *
 * <p>Tracks failures in distinct time buckets (1 second per bucket).
 * Internally converts nanosecond timestamps to seconds for bucket addressing.
 */
public record TimeBasedSlidingWindowMetrics(
    int maxFailuresInWindow,
    int windowSizeInSeconds,
    int[] failureBuckets,
    long lastUpdatedSecond
) implements FailureMetrics {

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

  private static long toSeconds(long nanos) {
    return nanos / 1_000_000_000L;
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return fastForward(toSeconds(nowNanos));
  }

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

  @Override
  public boolean isThresholdReached(long nowNanos) {
    TimeBasedSlidingWindowMetrics evaluatedState = fastForward(toSeconds(nowNanos));
    int totalFailuresInWindow = 0;
    for (int count : evaluatedState.failureBuckets()) {
      totalFailuresInWindow += count;
    }
    return totalFailuresInWindow >= maxFailuresInWindow;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new TimeBasedSlidingWindowMetrics(
        maxFailuresInWindow,
        windowSizeInSeconds,
        new int[windowSizeInSeconds],
        toSeconds(nowNanos));
  }

  private TimeBasedSlidingWindowMetrics fastForward(long currentSecond) {
    if (currentSecond <= lastUpdatedSecond) {
      return this;
    }
    long deltaSeconds = currentSecond - lastUpdatedSecond;
    if (deltaSeconds >= windowSizeInSeconds) {
      return new TimeBasedSlidingWindowMetrics(
          maxFailuresInWindow,
          windowSizeInSeconds,
          new int[windowSizeInSeconds],
          currentSecond);
    }
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

  @Override
  public String getTripReason(long nowNanos) {
    TimeBasedSlidingWindowMetrics evaluatedState = fastForward(toSeconds(nowNanos));
    int totalFailuresInWindow = Arrays.stream(evaluatedState.failureBuckets()).sum();
    return "Time-based sliding window threshold reached: Found %d failures in the last %d seconds (Threshold: %d)."
        .formatted(totalFailuresInWindow, windowSizeInSeconds, maxFailuresInWindow);
  }
}
