package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable implementation of a Time-Based Error Rate algorithm.
 *
 * <p>Tracks both successes and failures in distinct time buckets (1 second per bucket).
 * Internally converts nanosecond timestamps to seconds for bucket addressing.
 *
 * <p>The failure threshold is interpreted as a percentage from 1 to 100.
 */
public record TimeBasedErrorRateMetrics(
    long failureThreshold,
    int windowSizeInSeconds,
    int minimumNumberOfCalls,
    int[] successBuckets,
    int[] failureBuckets,
    long lastUpdatedSecond
) implements FailureMetrics {

  // Defensive array copy on construction
  public TimeBasedErrorRateMetrics {
    successBuckets = Arrays.copyOf(successBuckets, successBuckets.length);
    failureBuckets = Arrays.copyOf(failureBuckets, failureBuckets.length);
  }

  public static TimeBasedErrorRateMetrics initial(double failureThreshold,
                                                  int windowSizeInSeconds,
                                                  int minimumNumberOfCalls,
                                                  long nowNanos) {
    if (windowSizeInSeconds <= 0) {
      throw new IllegalArgumentException("windowSizeInSeconds must be greater than 0");
    }
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new TimeBasedErrorRateMetrics(
        Math.round(failureThreshold), windowSizeInSeconds, minimumNumberOfCalls,
        new int[windowSizeInSeconds], new int[windowSizeInSeconds], toSeconds(nowNanos));
  }

  private static long toSeconds(long nanos) {
    return nanos / 1_000_000_000L;
  }

  // Defensive array copy on access
  @Override
  public int[] successBuckets() {
    return Arrays.copyOf(successBuckets, successBuckets.length);
  }

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
        lastUpdatedSecond == that.lastUpdatedSecond &&
        Objects.deepEquals(successBuckets, that.successBuckets) &&
        Objects.deepEquals(failureBuckets, that.failureBuckets);
  }

  @Override
  public int hashCode() {
    return Objects.hash(failureThreshold, windowSizeInSeconds, minimumNumberOfCalls,
        Arrays.hashCode(successBuckets), Arrays.hashCode(failureBuckets), lastUpdatedSecond);
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(nowNanos, true);
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(nowNanos, false);
  }

  private TimeBasedErrorRateMetrics recordOutcome(long nowNanos, boolean isSuccess) {
    long currentSecond = toSeconds(nowNanos);
    TimeBasedErrorRateMetrics updatedState = fastForward(currentSecond);

    int[] newSuccesses = Arrays.copyOf(updatedState.successBuckets, windowSizeInSeconds);
    int[] newFailures = Arrays.copyOf(updatedState.failureBuckets, windowSizeInSeconds);

    int currentBucketIndex = (int) (currentSecond % windowSizeInSeconds);
    if (currentBucketIndex < 0) {
      currentBucketIndex += windowSizeInSeconds;
    }

    if (isSuccess) {
      newSuccesses[currentBucketIndex]++;
    } else {
      newFailures[currentBucketIndex]++;
    }

    long newLastUpdatedSecond = Math.max(updatedState.lastUpdatedSecond, currentSecond);
    return new TimeBasedErrorRateMetrics(
        failureThreshold, windowSizeInSeconds, minimumNumberOfCalls,
        newSuccesses, newFailures, newLastUpdatedSecond);
  }

  private EvaluationResult evaluate(long nowNanos) {
    TimeBasedErrorRateMetrics evaluated = fastForward(toSeconds(nowNanos));
    int totalSuccesses = Arrays.stream(evaluated.successBuckets).sum();
    int totalFailures = Arrays.stream(evaluated.failureBuckets).sum();
    return new EvaluationResult(totalSuccesses, totalFailures, totalSuccesses + totalFailures);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    EvaluationResult eval = evaluate(nowNanos);
    if (eval.totalCalls() < minimumNumberOfCalls) {
      return false;
    }
    double failureRate = (double) eval.totalFailures() / eval.totalCalls();
    double rateThreshold = failureThreshold / 100.0;
    return failureRate >= rateThreshold;
  }

  @Override
  public String getTripReason(long nowNanos) {
    EvaluationResult eval = evaluate(nowNanos);
    if (eval.totalCalls() == 0) {
      return "Circuit tripped, but no calls were recorded in the current window.";
    }
    return "Failure rate of %.1f%% (%d failures out of %d calls) in the last %d seconds exceeded the threshold of %d%%."
        .formatted(eval.failureRatePercent(), eval.totalFailures(), eval.totalCalls(),
            windowSizeInSeconds, failureThreshold);
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new TimeBasedErrorRateMetrics(
        failureThreshold, windowSizeInSeconds, minimumNumberOfCalls,
        new int[windowSizeInSeconds], new int[windowSizeInSeconds], toSeconds(nowNanos));
  }

  private TimeBasedErrorRateMetrics fastForward(long currentSecond) {
    if (currentSecond <= lastUpdatedSecond) {
      return this;
    }
    long deltaSeconds = currentSecond - lastUpdatedSecond;
    if (deltaSeconds >= windowSizeInSeconds) {
      return new TimeBasedErrorRateMetrics(
          failureThreshold, windowSizeInSeconds, minimumNumberOfCalls,
          new int[windowSizeInSeconds], new int[windowSizeInSeconds], currentSecond);
    }
    int[] newSuccesses = Arrays.copyOf(successBuckets, windowSizeInSeconds);
    int[] newFailures = Arrays.copyOf(failureBuckets, windowSizeInSeconds);
    for (long i = 1; i <= deltaSeconds; i++) {
      long timeToClear = lastUpdatedSecond + i;
      int indexToClear = (int) (timeToClear % windowSizeInSeconds);
      if (indexToClear < 0) {
        indexToClear += windowSizeInSeconds;
      }
      newSuccesses[indexToClear] = 0;
      newFailures[indexToClear] = 0;
    }
    return new TimeBasedErrorRateMetrics(
        failureThreshold, windowSizeInSeconds, minimumNumberOfCalls,
        newSuccesses, newFailures, currentSecond);
  }

  private record EvaluationResult(int totalSuccesses, int totalFailures, int totalCalls) {
    double failureRatePercent() {
      return totalCalls == 0 ? 0.0 : ((double) totalFailures / totalCalls) * 100.0;
    }
  }
}
