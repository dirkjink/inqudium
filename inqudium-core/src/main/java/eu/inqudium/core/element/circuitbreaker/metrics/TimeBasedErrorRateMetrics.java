package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

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
    long failureThreshold,
    int windowSizeInSeconds,
    int minimumNumberOfCalls,
    int[] successBuckets,
    int[] failureBuckets,
    long lastUpdatedEpochSecond
) implements FailureMetrics {

  // ======================== Fix 1: Defensive array copy on construction ========================

  public TimeBasedErrorRateMetrics {
    successBuckets = Arrays.copyOf(successBuckets, successBuckets.length);
    failureBuckets = Arrays.copyOf(failureBuckets, failureBuckets.length);
  }

  // ======================== Fix 1: Defensive array copy on access ========================

  public static TimeBasedErrorRateMetrics initial(double failureThreshold,
                                                  int windowSizeInSeconds,
                                                  int minimumNumberOfCalls,
                                                  Instant now) {
    if (windowSizeInSeconds <= 0) {
      throw new IllegalArgumentException("windowSizeInSeconds must be greater than 0");
    }
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new TimeBasedErrorRateMetrics(
        Math.round(failureThreshold),
        windowSizeInSeconds,
        minimumNumberOfCalls,
        new int[windowSizeInSeconds],
        new int[windowSizeInSeconds],
        now.getEpochSecond()
    );
  }

  @Override
  public int[] successBuckets() {
    return Arrays.copyOf(successBuckets, successBuckets.length);
  }

  // ======================== Fix 1: Structural equals and hashCode ========================

  @Override
  public int[] failureBuckets() {
    return Arrays.copyOf(failureBuckets, failureBuckets.length);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    TimeBasedErrorRateMetrics that = (TimeBasedErrorRateMetrics) o;
    return failureThreshold == that.failureThreshold &&
        windowSizeInSeconds == that.windowSizeInSeconds &&
        minimumNumberOfCalls == that.minimumNumberOfCalls &&
        lastUpdatedEpochSecond == that.lastUpdatedEpochSecond &&
        Objects.deepEquals(successBuckets, that.successBuckets) &&
        Objects.deepEquals(failureBuckets, that.failureBuckets);
  }

  @Override
  public int hashCode() {
    return Objects.hash(failureThreshold,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        Arrays.hashCode(successBuckets),
        Arrays.hashCode(failureBuckets),
        lastUpdatedEpochSecond);
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

    // Access internal fields directly (not the defensive-copy accessors)
    // since we are inside the record and will immediately copy anyway.
    int[] newSuccesses = Arrays.copyOf(updatedState.successBuckets, windowSizeInSeconds);
    int[] newFailures = Arrays.copyOf(updatedState.failureBuckets, windowSizeInSeconds);

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
    long newLastUpdatedEpochSecond = Math.max(updatedState.lastUpdatedEpochSecond, currentEpochSecond);

    return new TimeBasedErrorRateMetrics(
        failureThreshold,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        newSuccesses,
        newFailures,
        newLastUpdatedEpochSecond
    );
  }

  // ======================== Fix 9: Shared evaluation to avoid double fastForward ========================

  private EvaluationResult evaluate(Instant now) {
    TimeBasedErrorRateMetrics evaluated = fastForward(now.getEpochSecond());
    int totalSuccesses = Arrays.stream(evaluated.successBuckets).sum();
    int totalFailures = Arrays.stream(evaluated.failureBuckets).sum();
    return new EvaluationResult(totalSuccesses, totalFailures, totalSuccesses + totalFailures);
  }

  @Override
  public boolean isThresholdReached(Instant now) {
    EvaluationResult eval = evaluate(now);

    if (eval.totalCalls() < minimumNumberOfCalls) {
      return false;
    }

    double failureRate = (double) eval.totalFailures() / eval.totalCalls();
    double rateThreshold = failureThreshold / 100.0;

    return failureRate >= rateThreshold;
  }

  @Override
  public String getTripReason(Instant now) {
    EvaluationResult eval = evaluate(now);

    if (eval.totalCalls() == 0) {
      return "Circuit tripped, but no calls were recorded in the current window.";
    }

    return "Failure rate of %.1f%% (%d failures out of %d calls) in the last %d seconds exceeded the threshold of %d%%."
        .formatted(eval.failureRatePercent(), eval.totalFailures(), eval.totalCalls(),
            windowSizeInSeconds, failureThreshold);
  }

  @Override
  public FailureMetrics reset(Instant now) {
    return new TimeBasedErrorRateMetrics(
        failureThreshold,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        new int[windowSizeInSeconds],
        new int[windowSizeInSeconds],
        now.getEpochSecond()
    );
  }

  // ======================== Reset ========================

  private TimeBasedErrorRateMetrics fastForward(long currentEpochSecond) {
    if (currentEpochSecond <= lastUpdatedEpochSecond) {
      return this;
    }

    long deltaSeconds = currentEpochSecond - lastUpdatedEpochSecond;

    if (deltaSeconds >= windowSizeInSeconds) {
      return new TimeBasedErrorRateMetrics(
          failureThreshold,
          windowSizeInSeconds,
          minimumNumberOfCalls,
          new int[windowSizeInSeconds],
          new int[windowSizeInSeconds],
          currentEpochSecond
      );
    }

    // Access internal fields directly — we are inside the record
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
        failureThreshold,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        newSuccesses,
        newFailures,
        currentEpochSecond
    );
  }

  // ======================== Internal: Sliding Window ========================

  /**
   * Internal evaluation result that captures aggregated metrics after fast-forwarding.
   * Used by both {@link #isThresholdReached} and {@link #getTripReason} to avoid
   * redundant array copy and summation operations.
   */
  private record EvaluationResult(int totalSuccesses, int totalFailures, int totalCalls) {
    double failureRatePercent() {
      return totalCalls == 0 ? 0.0 : ((double) totalFailures / totalCalls) * 100.0;
    }
  }
}
