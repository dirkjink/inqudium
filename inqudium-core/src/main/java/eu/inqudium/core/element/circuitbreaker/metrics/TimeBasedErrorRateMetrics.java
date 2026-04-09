package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * Immutable implementation of a time-based error rate failure tracking strategy.
 *
 * <h2>Algorithm Overview</h2>
 * <p>Tracks both successes and failures in parallel arrays of 1-second time buckets,
 * then computes the failure <em>rate</em> (failures ÷ total calls) over the rolling
 * window. The circuit trips when this rate meets or exceeds {@code failureRatePercent}
 * and at least {@code minimumNumberOfCalls} have been recorded in the window.
 *
 * <p>This differs from {@link TimeBasedSlidingWindowMetrics} in two key ways:
 * <ol>
 *   <li>It tracks <strong>both</strong> successes and failures (not just failures).</li>
 *   <li>It evaluates a <strong>percentage-based threshold</strong> (error rate) rather
 *       than an absolute failure count.</li>
 * </ol>
 *
 * <h2>Bucket Mechanics</h2>
 * <p>Two parallel {@code int[]} arrays of size {@code windowSizeInSeconds} are maintained:
 * one for success counts and one for failure counts. Bucket addressing uses modular
 * arithmetic ({@code second % windowSizeInSeconds}), with negative indices corrected.
 * A fast-forward step clears both arrays' stale buckets when time advances.
 *
 * <h2>Defensive Copying</h2>
 * <p>Because Java records expose their components via accessor methods, and arrays are
 * mutable, this record performs defensive copies in three places:
 * <ul>
 *   <li>The compact constructor copies both arrays on creation.</li>
 *   <li>The overridden {@link #successBuckets()} and {@link #failureBuckets()} accessors
 *       return copies to prevent external mutation.</li>
 * </ul>
 * <p>Custom {@link #equals(Object)} and {@link #hashCode()} implementations use
 * {@link Objects#deepEquals} and {@link Arrays#hashCode} respectively, since the default
 * record implementations would compare array references rather than contents.
 *
 * <h2>Threshold Interpretation</h2>
 * <p>The {@code failureRatePercent} is expressed as a percentage (1–100). Internally it
 * is divided by 100.0 for comparison against the computed rate. The minimum-calls guard
 * prevents premature tripping on small sample sizes.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>You need a time-windowed failure <em>rate</em> (not just a count).</li>
 *   <li>Success volume matters — a high failure count is acceptable when accompanied
 *       by a much higher success count.</li>
 *   <li>You want a minimum-calls safety net to avoid tripping on low-traffic periods.</li>
 * </ul>
 *
 * @param failureRatePercent   the percentage threshold (1–100) above which the circuit trips
 * @param windowSizeInSeconds  the number of 1-second buckets (also the window duration)
 * @param minimumNumberOfCalls minimum total calls in the window before the threshold is evaluated
 * @param successBuckets       per-second success counts (defensively copied)
 * @param failureBuckets       per-second failure counts (defensively copied)
 * @param lastUpdatedSecond    the second (from nanos) of the most recent recording
 */
public record TimeBasedErrorRateMetrics(
    double failureRatePercent,
    int windowSizeInSeconds,
    int minimumNumberOfCalls,
    int[] successBuckets,
    int[] failureBuckets,
    long lastUpdatedSecond
) implements FailureMetrics {

  /**
   * Compact constructor — performs defensive copies of both bucket arrays to ensure
   * true immutability despite arrays being inherently mutable in Java.
   */
  public TimeBasedErrorRateMetrics {
    successBuckets = Arrays.copyOf(successBuckets, successBuckets.length);
    failureBuckets = Arrays.copyOf(failureBuckets, failureBuckets.length);
  }

  /**
   * Creates an initial instance with empty buckets, anchored at the given time.
   *
   * @param failureRatePercent   the error rate percentage threshold (1–100)
   * @param windowSizeInSeconds  the window duration in seconds; must be > 0
   * @param minimumNumberOfCalls minimum calls required before threshold evaluation; must be > 0
   * @param nowNanos             the initial timestamp anchor (in nanoseconds)
   * @return a fresh instance with all buckets zeroed
   * @throws IllegalArgumentException if windowSizeInSeconds or minimumNumberOfCalls <= 0
   */
  public static TimeBasedErrorRateMetrics initial(double failureRatePercent,
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
        failureRatePercent,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        new int[windowSizeInSeconds],
        new int[windowSizeInSeconds],
        toSeconds(nowNanos));
  }

  /**
   * Returns a factory function that produces a fresh instance of this metrics strategy.
   *
   * @return a {@link LongFunction} that accepts a nanosecond timestamp and produces a
   *         fresh {@link FailureMetrics} instance with identical configuration
   */
  @Override
  public LongFunction<FailureMetrics> metricsFactory() {
    return (long nowNanos)-> TimeBasedErrorRateMetrics.initial(
        failureRatePercent,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        nowNanos
    );
  }

  /**
   * Converts a nanosecond timestamp to whole seconds via integer division.
   */
  private static long toSeconds(long nanos) {
    return nanos / 1_000_000_000L;
  }

  /**
   * Returns a defensive copy of the success buckets array.
   * Overrides the default record accessor to prevent external mutation.
   */
  @Override
  public int[] successBuckets() {
    return Arrays.copyOf(successBuckets, successBuckets.length);
  }

  /**
   * Returns a defensive copy of the failure buckets array.
   * Overrides the default record accessor to prevent external mutation.
   */
  @Override
  public int[] failureBuckets() {
    return Arrays.copyOf(failureBuckets, failureBuckets.length);
  }

  /**
   * Content-based equality that compares array contents (not references).
   * Necessary because the default record {@code equals} uses reference equality for arrays.
   */
  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    TimeBasedErrorRateMetrics that = (TimeBasedErrorRateMetrics) o;
    return failureRatePercent == that.failureRatePercent &&
        windowSizeInSeconds == that.windowSizeInSeconds &&
        minimumNumberOfCalls == that.minimumNumberOfCalls &&
        lastUpdatedSecond == that.lastUpdatedSecond &&
        Objects.deepEquals(successBuckets, that.successBuckets) &&
        Objects.deepEquals(failureBuckets, that.failureBuckets);
  }

  /**
   * Content-based hash code that incorporates array contents (not references).
   * Consistent with the custom {@link #equals(Object)} implementation.
   */
  @Override
  public int hashCode() {
    return Objects.hash(failureRatePercent, windowSizeInSeconds, minimumNumberOfCalls,
        Arrays.hashCode(successBuckets), Arrays.hashCode(failureBuckets), lastUpdatedSecond);
  }

  /**
   * Records a successful call by incrementing the success count in the current
   * second's bucket (after fast-forwarding to expire stale buckets).
   *
   * @param nowNanos the current timestamp in nanoseconds
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(nowNanos, true);
  }

  /**
   * Records a failed call by incrementing the failure count in the current
   * second's bucket (after fast-forwarding to expire stale buckets).
   *
   * @param nowNanos the current timestamp in nanoseconds
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(nowNanos, false);
  }

  /**
   * Shared recording logic for both success and failure paths.
   *
   * <p>Steps:
   * <ol>
   *   <li>Convert {@code nowNanos} to seconds.</li>
   *   <li>Fast-forward to clear any stale buckets between the last update and now.</li>
   *   <li>Copy both bucket arrays from the fast-forwarded state.</li>
   *   <li>Determine the current bucket index via modular arithmetic.</li>
   *   <li>Increment the appropriate counter (success or failure) in that bucket.</li>
   *   <li>Return a new instance with the updated arrays and timestamp.</li>
   * </ol>
   *
   * @param nowNanos  the current timestamp in nanoseconds
   * @param isSuccess {@code true} for a successful outcome, {@code false} for a failure
   * @return a new instance with the updated bucket
   */
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
        failureRatePercent,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        newSuccesses,
        newFailures,
        newLastUpdatedSecond);
  }

  /**
   * Aggregates the current window's success and failure totals into a convenient
   * evaluation snapshot, after fast-forwarding to the query time.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return an {@link EvaluationResult} with totals and the derived failure rate
   */
  private EvaluationResult evaluate(long nowNanos) {
    TimeBasedErrorRateMetrics evaluated = fastForward(toSeconds(nowNanos));
    int totalSuccesses = Arrays.stream(evaluated.successBuckets).sum();
    int totalFailures = Arrays.stream(evaluated.failureBuckets).sum();
    return new EvaluationResult(totalSuccesses, totalFailures, totalSuccesses + totalFailures);
  }

  /**
   * Evaluates whether the error rate in the current window meets or exceeds the threshold.
   *
   * <p>Returns {@code false} if the total number of calls in the window is below
   * {@code minimumNumberOfCalls}. Otherwise computes the failure rate as
   * {@code totalFailures / totalCalls} and compares against the threshold.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return {@code true} if the error rate >= threshold and minimum calls have been met
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    EvaluationResult eval = evaluate(nowNanos);
    if (eval.totalCalls() < minimumNumberOfCalls) {
      return false;
    }
    double failureRate = (double) eval.totalFailures() / eval.totalCalls();
    double rateThreshold = failureRatePercent / 100.0;
    return failureRate >= rateThreshold;
  }

  /**
   * Produces a diagnostic message showing the computed failure rate, raw counts,
   * window size, and threshold. Handles the edge case of zero calls gracefully.
   *
   * @param nowNanos the current timestamp in nanoseconds
   */
  @Override
  public String getTripReason(long nowNanos) {
    EvaluationResult eval = evaluate(nowNanos);
    if (eval.totalCalls() == 0) {
      return "Circuit tripped, but no calls were recorded in the current window.";
    }
    return String.format(
        Locale.ROOT,"Failure rate of %.1f%% (%d failures out of %d calls) " +
            "in the last %d seconds exceeded the threshold of %f%%.",
        eval.failureRatePercent(), eval.totalFailures(), eval.totalCalls(),
            windowSizeInSeconds, failureRatePercent);
  }

  /**
   * Resets both bucket arrays to zero and re-anchors the timestamp.
   *
   * @param nowNanos the new timestamp anchor
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return new TimeBasedErrorRateMetrics(
        failureRatePercent,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        new int[windowSizeInSeconds],
        new int[windowSizeInSeconds],
        toSeconds(nowNanos));
  }

  /**
   * Advances the window to the given second, clearing stale buckets in both arrays.
   *
   * <p>Three cases:
   * <ol>
   *   <li><strong>No time has passed</strong> ({@code currentSecond <= lastUpdatedSecond}):
   *       returns {@code this} unchanged.</li>
   *   <li><strong>Gap exceeds window size</strong>: all data is stale; returns a new
   *       instance with zeroed arrays.</li>
   *   <li><strong>Partial advance</strong>: iterates from {@code lastUpdatedSecond + 1}
   *       to {@code currentSecond}, zeroing both the success and failure bucket at each
   *       expired index.</li>
   * </ol>
   *
   * @param currentSecond the target second to advance to
   * @return a new instance with expired buckets cleared
   */
  private TimeBasedErrorRateMetrics fastForward(long currentSecond) {
    if (currentSecond <= lastUpdatedSecond) {
      return this;
    }
    long deltaSeconds = currentSecond - lastUpdatedSecond;
    if (deltaSeconds >= windowSizeInSeconds) {
      // Entire window has expired — wipe everything
      return new TimeBasedErrorRateMetrics(
          failureRatePercent,
          windowSizeInSeconds,
          minimumNumberOfCalls,
          new int[windowSizeInSeconds],
          new int[windowSizeInSeconds],
          currentSecond);
    }
    // Clear only the buckets that have rotated out of the window
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
        failureRatePercent,
        windowSizeInSeconds,
        minimumNumberOfCalls,
        newSuccesses,
        newFailures,
        currentSecond);
  }

  /**
   * Internal value object that holds the aggregated results of evaluating the window.
   * Used by {@link #isThresholdReached(long)} and {@link #getTripReason(long)} to
   * avoid duplicating the summation logic.
   *
   * @param totalSuccesses the sum of all success buckets in the current window
   * @param totalFailures  the sum of all failure buckets in the current window
   * @param totalCalls     the total number of calls (successes + failures)
   */
  private record EvaluationResult(int totalSuccesses, int totalFailures, int totalCalls) {
    /**
     * Computes the failure rate as a percentage (0.0–100.0).
     * Returns 0.0 if no calls have been recorded to avoid division by zero.
     */
    double failureRatePercent() {
      return totalCalls == 0 ? 0.0 : ((double) totalFailures / totalCalls) * 100.0;
    }
  }
}
