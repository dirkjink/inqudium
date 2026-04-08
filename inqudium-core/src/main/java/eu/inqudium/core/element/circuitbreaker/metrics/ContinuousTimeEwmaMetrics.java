package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.algo.ContinuousTimeEwma;

import java.time.Duration;
import java.util.Locale;

/**
 * Immutable implementation of a failure tracking strategy using a Continuous-Time
 * Exponentially Weighted Moving Average (EWMA).
 *
 * <h2>Algorithm Overview</h2>
 * <p>Unlike {@link RequestBasedEwmaMetrics}, which decays per request, this strategy
 * decays based on <em>actual elapsed wall-clock time</em>. The EWMA is parameterized
 * by a <strong>time constant (τ / tau)</strong> — a {@link Duration} that controls how
 * quickly the influence of past observations fades. After one time constant has elapsed,
 * the weight of a past observation drops to approximately 1/e ≈ 36.8% of its original
 * influence.
 *
 * <p>The continuous-time EWMA formula (applied by {@link ContinuousTimeEwma}) is:
 * <pre>
 *   decay = exp(−Δt / τ)
 *   newRate = decay × previousRate + (1 − decay) × sample
 * </pre>
 * where Δt is the elapsed time since the last update and τ is the time constant.
 *
 * <h2>Threshold Interpretation</h2>
 * <p>The {@code failureRatePercent} is expressed as a percentage (1–100), internally
 * compared as a decimal (÷ 100) against the EWMA rate. It is rounded at construction.
 *
 * <h2>Decay on Threshold Evaluation</h2>
 * <p>A distinctive feature of this implementation: when checking
 * {@link #isThresholdReached(long)}, the rate is <strong>decayed forward</strong> to the
 * query time by computing the EWMA with a 0.0 sample. This means that if no new calls
 * arrive for a long period, the failure rate naturally drifts toward zero — the circuit
 * can "self-heal" through the passage of time alone.
 *
 * <h2>Minimum Calls Guard</h2>
 * <p>The threshold is not evaluated until at least {@code minimumNumberOfCalls} outcomes
 * have been recorded. The call count is capped at {@code minimumNumberOfCalls} to avoid
 * unbounded growth.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Request rates are variable or bursty, and you want the failure rate to decay
 *       naturally even during idle periods.</li>
 *   <li>Time-based smoothing is more meaningful than request-based smoothing for your
 *       use case.</li>
 *   <li>You want precise control over how fast history is forgotten via the tau parameter.</li>
 * </ul>
 *
 * @param failureRatePercent   the threshold as a percentage (1–100); rounded at construction
 * @param ewmaCalculator       the stateless continuous-time EWMA calculator holding tau
 * @param minimumNumberOfCalls minimum samples required before the threshold is evaluated
 * @param currentRate          the current EWMA failure rate (0.0–1.0 scale)
 * @param callsCount           how many outcomes have been recorded (capped at minimumNumberOfCalls)
 * @param lastUpdateNanos      the nanosecond timestamp of the most recent recording
 */
public record ContinuousTimeEwmaMetrics(
    double failureRatePercent,
    ContinuousTimeEwma ewmaCalculator,
    int minimumNumberOfCalls,
    double currentRate,
    int callsCount,
    long lastUpdateNanos
) implements FailureMetrics {

  /**
   * Creates an initial instance with a zero failure rate, anchored at the given time.
   *
   * @param failureThreshold     the failure rate percentage (1–100) at which the circuit trips
   * @param timeConstant         the EWMA time constant (tau); controls decay speed
   * @param minimumNumberOfCalls minimum number of calls before evaluation; must be > 0
   * @param nowNanos             the initial timestamp anchor
   * @return a fresh instance ready to accept outcomes
   * @throws IllegalArgumentException if {@code minimumNumberOfCalls <= 0}
   */
  public static ContinuousTimeEwmaMetrics initial(
      double failureThreshold,
      Duration timeConstant,
      int minimumNumberOfCalls,
      long nowNanos
  ) {
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new ContinuousTimeEwmaMetrics(
        Math.round(failureThreshold),
        new ContinuousTimeEwma(timeConstant),
        minimumNumberOfCalls,
        0.0,
        0,
        nowNanos
    );
  }

  /**
   * Records a successful call (sample = 0.0), pulling the EWMA rate downward.
   *
   * @param nowNanos the current timestamp; used to compute time-based decay since the last update
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(nowNanos, 0.0);
  }

  /**
   * Records a failed call (sample = 1.0), pulling the EWMA rate upward.
   *
   * @param nowNanos the current timestamp; used to compute time-based decay since the last update
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(nowNanos, 1.0);
  }

  /**
   * Internal helper that applies a continuous-time EWMA update.
   *
   * <p>The EWMA calculator uses the elapsed time between {@code lastUpdateNanos} and
   * {@code nowNanos} to determine the decay factor, then blends the new sample into
   * the running rate.
   *
   * <p>The {@code lastUpdateNanos} is updated to the maximum of the old and new timestamps,
   * guarding against clock regression.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @param sample   0.0 for success, 1.0 for failure
   * @return a new instance with the updated rate, call count, and timestamp
   */
  private ContinuousTimeEwmaMetrics recordOutcome(long nowNanos, double sample) {
    double newRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, nowNanos, sample);
    int newCount = Math.min(minimumNumberOfCalls, callsCount + 1);
    long newLastUpdateNanos = Math.max(lastUpdateNanos, nowNanos);

    return new ContinuousTimeEwmaMetrics(
        failureRatePercent, ewmaCalculator, minimumNumberOfCalls,
        newRate, newCount, newLastUpdateNanos
    );
  }

  /**
   * Evaluates whether the time-decayed failure rate meets or exceeds the threshold.
   *
   * <p>First checks the minimum-calls guard. Then decays the current rate forward to
   * {@code nowNanos} by computing the EWMA with a 0.0 sample (pure decay, no new data).
   * This allows the rate to decrease naturally during idle periods.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return {@code true} if the decayed rate >= threshold and minimum calls have been met
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    if (callsCount < minimumNumberOfCalls) {
      return false;
    }
    // Decay the rate to the current time without adding a new sample
    double decayedRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, nowNanos, 0.0);
    double rateThreshold = failureRatePercent / 100.0;
    return decayedRate >= rateThreshold;
  }

  /**
   * Resets the EWMA rate to 0.0, the call count to 0, and re-anchors the timestamp.
   *
   * @param nowNanos the new timestamp anchor
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return new ContinuousTimeEwmaMetrics(
        failureRatePercent, ewmaCalculator, minimumNumberOfCalls,
        0.0, 0, nowNanos
    );
  }

  /**
   * Produces a diagnostic message showing the time-decayed EWMA rate, threshold, and tau.
   * The rate is decayed to {@code nowNanos} for an accurate snapshot.
   *
   * @param nowNanos the current timestamp in nanoseconds
   */
  @Override
  public String getTripReason(long nowNanos) {
    double decayedRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, nowNanos, 0.0);
    return String.format(
        Locale.ROOT,"Continuous-time EWMA threshold reached: " +
            "Current failure rate is %.1f%% (Threshold: %f%%). Time Constant (Tau): %s.",
        decayedRate * 100.0, failureRatePercent, ewmaCalculator.tauDurationNanos());
  }
}
