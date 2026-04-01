package eu.inqudium.core.circuitbreaker.metrics;

import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;
import java.time.Instant;
import java.util.Arrays;

/**
 * Immutable implementation of a Time-Based Error Rate algorithm.
 *
 * <p>Tracks both successes and failures in distinct time buckets (1 second per bucket).
 * When time advances, older buckets are automatically cleared. The threshold is
 * evaluated as a percentage of total calls within the active time window.
 *
 * <p>Note: The {@link CircuitBreakerConfig#failureThreshold()} is interpreted as a
 * percentage from 1 to 100 (e.g., a threshold of 50 means 50% failure rate).
 */
public record TimeBasedErrorRateMetrics(
    int windowSizeInSeconds,
    int minimumNumberOfCalls,
    int[] successBuckets,
    int[] failureBuckets,
    long lastUpdatedEpochSecond
) implements FailureMetrics {

  public static TimeBasedErrorRateMetrics initial(int windowSizeInSeconds, int minimumNumberOfCalls, Instant now) {
    if (windowSizeInSeconds <= 0) {
      throw new IllegalArgumentException("windowSizeInSeconds must be greater than 0");
    }
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new TimeBasedErrorRateMetrics(
        windowSizeInSeconds,
        minimumNumberOfCalls,
        new int[windowSizeInSeconds],
        new int[windowSizeInSeconds],
        now.getEpochSecond()
    );
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    return recordOutcome(now, true);
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    return recordOutcome(now, false);
  }

  private TimeBasedErrorRateMetrics recordOutcome(Instant now, boolean isSuccess) {
    long currentEpochSecond = now.getEpochSecond();

    // Fast-forward to clear expired buckets
    TimeBasedErrorRateMetrics updatedState = fastForward(currentEpochSecond);

    // Create new copies of the buckets to mutate
    int[] newSuccesses = Arrays.copyOf(updatedState.successBuckets(), windowSizeInSeconds);
    int[] newFailures = Arrays.copyOf(updatedState.failureBuckets(), windowSizeInSeconds);

    // Determine the correct bucket index
    int currentBucketIndex = (int) (currentEpochSecond % windowSizeInSeconds);
    if (currentBucketIndex < 0) {
      currentBucketIndex += windowSizeInSeconds;
    }

    // Increment the respective counter
    if (isSuccess) {
      newSuccesses[currentBucketIndex]++;
    } else {
      newFailures[currentBucketIndex]++;
    }

    // Ensure the internal clock never moves backwards
    long newLastUpdatedEpochSecond = Math.max(updatedState.lastUpdatedEpochSecond(), currentEpochSecond);

    return new TimeBasedErrorRateMetrics(
        windowSizeInSeconds,
        minimumNumberOfCalls,
        newSuccesses,
        newFailures,
        newLastUpdatedEpochSecond
    );
  }

  @Override
  public boolean isThresholdReached(CircuitBreakerConfig config, Instant now) {
    // Fast-forward first to ensure old data falls out of the window before evaluating
    TimeBasedErrorRateMetrics evaluatedState = fastForward(now.getEpochSecond());

    int totalSuccesses = Arrays.stream(evaluatedState.successBuckets()).sum();
    int totalFailures = Arrays.stream(evaluatedState.failureBuckets()).sum();
    int totalCalls = totalSuccesses + totalFailures;

    if (totalCalls < minimumNumberOfCalls) {
      return false;
    }

    double failureRate = (double) totalFailures / totalCalls;
    double rateThreshold = config.failureThreshold() / 100.0;

    return failureRate >= rateThreshold;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    return initial(windowSizeInSeconds, minimumNumberOfCalls, now);
  }

  private TimeBasedErrorRateMetrics fastForward(long currentEpochSecond) {
    if (currentEpochSecond <= lastUpdatedEpochSecond) {
      return this;
    }

    long deltaSeconds = currentEpochSecond - lastUpdatedEpochSecond;

    if (deltaSeconds >= windowSizeInSeconds) {
      return new TimeBasedErrorRateMetrics(
          windowSizeInSeconds,
          minimumNumberOfCalls,
          new int[windowSizeInSeconds],
          new int[windowSizeInSeconds],
          currentEpochSecond
      );
    }

    int[] newSuccesses = Arrays.copyOf(successBuckets, windowSizeInSeconds);
    int[] newFailures = Arrays.copyOf(failureBuckets, windowSizeInSeconds);

    for (long i = 1; i <= deltaSeconds; i++) {
      long timeToClear = lastUpdatedEpochSecond + i;
      int indexToClear = (int) (timeToClear % windowSizeInSeconds);
      if (indexToClear < 0) {
        indexToClear += windowSizeInSeconds;
      }
      newSuccesses[indexToClear] = 0;
      newFailures[indexToClear] = 0;
    }

    return new TimeBasedErrorRateMetrics(
        windowSizeInSeconds,
        minimumNumberOfCalls,
        newSuccesses,
        newFailures,
        currentEpochSecond
    );
  }

  @Override
  public String getTripReason(CircuitBreakerConfig config, Instant now) {
    TimeBasedErrorRateMetrics evaluatedState = fastForward(now.getEpochSecond());

    int totalSuccesses = java.util.Arrays.stream(evaluatedState.successBuckets()).sum();
    int totalFailures = java.util.Arrays.stream(evaluatedState.failureBuckets()).sum();
    int totalCalls = totalSuccesses + totalFailures;

    if (totalCalls == 0) {
      return "Circuit tripped, but no calls were recorded in the current window.";
    }

    double failureRate = ((double) totalFailures / totalCalls) * 100.0;

    return "Failure rate of %.1f%% (%d failures out of %d calls) in the last %d seconds exceeded the threshold of %d%%."
        .formatted(failureRate, totalFailures, totalCalls, windowSizeInSeconds, config.failureThreshold());
  }
}
