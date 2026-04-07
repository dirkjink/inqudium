package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.algo.RequestBasedEwma;

/**
 * Immutable implementation of a failure tracking strategy using a Request-Based
 * Exponentially Weighted Moving Average (EWMA).
 *
 * <h2>Algorithm Overview</h2>
 * <p>Computes a smoothed failure rate that decays based on the <em>number of requests</em>
 * rather than elapsed wall-clock time. Each call outcome is treated as a discrete sample:
 * a failure contributes a value of 1.0, and a success contributes 0.0. The EWMA formula
 * blends each new sample into the running average using a smoothing factor (alpha),
 * giving more weight to recent observations while older ones fade exponentially.
 *
 * <p>The formula applied per sample is:
 * <pre>
 *   newRate = alpha × sample + (1 - alpha) × previousRate
 * </pre>
 *
 * <h2>Threshold Interpretation</h2>
 * <p>The {@code failureRatePercent} field is expressed as a percentage (1–100) and is
 * internally converted to a decimal (÷ 100) for comparison against the EWMA rate.
 * Note: the stored value is rounded via {@link Math#round(double)} at construction time.
 *
 * <h2>Minimum Calls Guard</h2>
 * <p>The threshold is not evaluated until at least {@code minimumNumberOfCalls} outcomes
 * have been recorded. This prevents the circuit from tripping on a tiny sample (e.g.,
 * 1 failure out of 1 call = 100% failure rate). The {@code callsCount} field tracks
 * recorded calls and is capped at {@code minimumNumberOfCalls} to avoid overflow on
 * very long-running instances.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Request volume is the natural unit of observation (as opposed to time).</li>
 *   <li>You want a statistically smoothed failure rate that emphasizes recent history
 *       without maintaining a full window buffer.</li>
 *   <li>The downstream call rate is fairly consistent, so request-based decay provides
 *       stable behavior.</li>
 * </ul>
 *
 * <h2>Time Independence</h2>
 * <p>This algorithm is entirely request-driven. The {@code nowNanos} parameter is
 * accepted but ignored in every method. If you need time-based decay, see
 * {@link ContinuousTimeEwmaMetrics}.
 *
 * @param failureRatePercent the threshold as a percentage (1–100); stored as rounded value
 * @param ewmaCalculator     the stateless EWMA calculator holding the smoothing factor (alpha)
 * @param minimumNumberOfCalls minimum samples required before the threshold is evaluated
 * @param currentRate        the current EWMA failure rate (0.0–1.0 scale)
 * @param callsCount         how many outcomes have been recorded (capped at minimumNumberOfCalls)
 */
public record RequestBasedEwmaMetrics(
    double failureRatePercent,
    RequestBasedEwma ewmaCalculator,
    int minimumNumberOfCalls,
    double currentRate,
    int callsCount
) implements FailureMetrics {

  /**
   * Creates an initial instance with a zero failure rate and zero recorded calls.
   *
   * @param failureThreshold     the failure rate percentage (1–100) at which the circuit trips
   * @param smoothingFactor      the EWMA alpha value (0 < alpha ≤ 1); higher = more reactive
   * @param minimumNumberOfCalls the minimum number of calls before evaluation; must be > 0
   * @return a fresh instance ready to accept outcomes
   * @throws IllegalArgumentException if {@code minimumNumberOfCalls <= 0}
   */
  public static RequestBasedEwmaMetrics initial(
      double failureThreshold,
      double smoothingFactor,
      int minimumNumberOfCalls
  ) {
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new RequestBasedEwmaMetrics(
        Math.round(failureThreshold),
        new RequestBasedEwma(smoothingFactor),
        minimumNumberOfCalls,
        0.0,
        0
    );
  }

  /**
   * Records a successful call by feeding a sample of 0.0 into the EWMA.
   * This pulls the running average downward.
   *
   * @param nowNanos ignored — this algorithm is request-based, not time-based
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(0.0);
  }

  /**
   * Records a failed call by feeding a sample of 1.0 into the EWMA.
   * This pulls the running average upward.
   *
   * @param nowNanos ignored — this algorithm is request-based, not time-based
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(1.0);
  }

  /**
   * Internal helper that applies a single EWMA update and increments the call count.
   *
   * @param sample 0.0 for success, 1.0 for failure
   * @return a new instance with the updated rate and call count
   */
  private RequestBasedEwmaMetrics recordOutcome(double sample) {
    double newRate = ewmaCalculator.calculate(currentRate, sample);
    // Cap the count at minimumNumberOfCalls to prevent unnecessary growth
    int newCount = Math.min(minimumNumberOfCalls, callsCount + 1);
    return new RequestBasedEwmaMetrics(failureRatePercent, ewmaCalculator, minimumNumberOfCalls, newRate, newCount);
  }

  /**
   * Evaluates whether the EWMA failure rate has reached or exceeded the threshold.
   * Returns {@code false} unconditionally if fewer than {@code minimumNumberOfCalls}
   * outcomes have been recorded.
   *
   * @param nowNanos ignored — this algorithm is request-based, not time-based
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    if (callsCount < minimumNumberOfCalls) {
      return false;
    }
    double rateThreshold = failureRatePercent / 100.0;
    return currentRate >= rateThreshold;
  }

  /**
   * Resets the EWMA rate to 0.0 and the call count to 0, preserving configuration.
   *
   * @param nowNanos ignored — this algorithm is request-based, not time-based
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return new RequestBasedEwmaMetrics(failureRatePercent, ewmaCalculator, minimumNumberOfCalls, 0.0, 0);
  }

  /**
   * Produces a diagnostic message showing the current EWMA rate, threshold, and alpha.
   *
   * @param nowNanos ignored — this algorithm is request-based, not time-based
   */
  @Override
  public String getTripReason(long nowNanos) {
    return "Request-based EWMA threshold reached: Current failure rate is %.1f%% (Threshold: %f%%). Alpha: %.2f."
        .formatted(currentRate * 100.0, failureRatePercent, ewmaCalculator.alpha());
  }
}
