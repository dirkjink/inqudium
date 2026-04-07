package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;
import java.util.Arrays;

/**
 * Immutable implementation of a time-based sliding window algorithm.
 *
 * <p>Tracks failures in distinct time buckets (1 second per bucket).
 * When time advances, older buckets are automatically cleared.
 */
public record TimeBasedSlidingWindowMetrics(
    long failureThreshold,
    int windowSizeInSeconds,
    int[] failureBuckets,
    long lastUpdatedEpochSecond
) implements FailureMetrics {

  /**
   * Creates an initial, empty time-based sliding window.
   *
   * @param windowSizeInSeconds the total duration of the window in seconds
   * @param now                 the current timestamp to align the initial window
   */
  public static TimeBasedSlidingWindowMetrics initial(double failureThreshold, int windowSizeInSeconds, Instant now) {
    if (windowSizeInSeconds <= 0) {
      throw new IllegalArgumentException("windowSizeInSeconds must be greater than 0");
    }
    return new TimeBasedSlidingWindowMetrics(
        Math.round(failureThreshold),
        windowSizeInSeconds,
        new int[windowSizeInSeconds],
        now.getEpochSecond()
    );
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    // A success does not add a failure, but we must fast-forward the time window
    // to clear out any old buckets if time has passed.
    return fastForward(now.getEpochSecond());
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    long currentEpochSecond = now.getEpochSecond();

    // First, fast-forward the state to clear expired buckets
    TimeBasedSlidingWindowMetrics updatedState = fastForward(currentEpochSecond);

    // Create a new copy of the buckets to mutate
    int[] newBuckets = Arrays.copyOf(updatedState.failureBuckets(), windowSizeInSeconds);

    // Increment the failure count for the current second's bucket
    int currentBucketIndex = (int) (currentEpochSecond % windowSizeInSeconds);

    // Prevent negative index for times before 1970
    if (currentBucketIndex < 0) {
      currentBucketIndex += windowSizeInSeconds;
    }

    newBuckets[currentBucketIndex]++;

    // Ensure the internal clock never moves backwards.
    // If we receive a record from the past, we keep our most advanced known timestamp.
    long newLastUpdatedEpochSecond = Math.max(updatedState.lastUpdatedEpochSecond(), currentEpochSecond);

    return new TimeBasedSlidingWindowMetrics(
        failureThreshold,
        windowSizeInSeconds,
        newBuckets,
        newLastUpdatedEpochSecond
    );
  }

  @Override
  public boolean isThresholdReached(Instant now) {
    // We must evaluate the threshold against the *current* time.
    // This ensures that if we haven't received calls for a while, old failures
    // are still ignored correctly.
    TimeBasedSlidingWindowMetrics evaluatedState = fastForward(now.getEpochSecond());

    int totalFailuresInWindow = 0;
    for (int count : evaluatedState.failureBuckets()) {
      totalFailuresInWindow += count;
    }

    return totalFailuresInWindow >= failureThreshold;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    return new TimeBasedSlidingWindowMetrics(
        failureThreshold,
        windowSizeInSeconds,
        new int[windowSizeInSeconds],
        now.getEpochSecond()
    );
  }

  /**
   * Helper method that creates a new state where buckets older than the window
   * size have been cleared out based on the time difference.
   */
  private TimeBasedSlidingWindowMetrics fastForward(long currentEpochSecond) {
    // If time goes backwards (e.g., NTP sync), we ignore the jump to avoid corrupting the window
    if (currentEpochSecond <= lastUpdatedEpochSecond) {
      return this;
    }

    long deltaSeconds = currentEpochSecond - lastUpdatedEpochSecond;

    // If the elapsed time is greater than or equal to the entire window, wipe everything
    if (deltaSeconds >= windowSizeInSeconds) {
      return new TimeBasedSlidingWindowMetrics(
          failureThreshold,
          windowSizeInSeconds,
          new int[windowSizeInSeconds],
          currentEpochSecond);
    }

    // Otherwise, clear only the buckets that have elapsed since the last update
    int[] newBuckets = Arrays.copyOf(failureBuckets, windowSizeInSeconds);
    for (long i = 1; i <= deltaSeconds; i++) {
      long timeToClear = lastUpdatedEpochSecond + i;
      int indexToClear = (int) (timeToClear % windowSizeInSeconds);
      if (indexToClear < 0) {
        indexToClear += windowSizeInSeconds;
      }
      newBuckets[indexToClear] = 0;
    }

    return new TimeBasedSlidingWindowMetrics(
        failureThreshold,
        windowSizeInSeconds,
        newBuckets,
        currentEpochSecond);
  }

  public String getTripReason(Instant now) {
    TimeBasedSlidingWindowMetrics evaluatedState = fastForward(now.getEpochSecond());

    int totalFailuresInWindow = Arrays.stream(evaluatedState.failureBuckets()).sum();

    return "Time-based sliding window threshold reached: Found %d failures in the last %d seconds (Threshold: %d)."
        .formatted(totalFailuresInWindow, windowSizeInSeconds, failureThreshold);
  }
}