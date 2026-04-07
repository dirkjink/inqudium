package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

import java.time.Duration;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.ContinuousTimeEwmaMetrics}.
 *
 * <p>Holds the parameters for the continuous-time EWMA algorithm, which computes a smoothed
 * failure rate that decays based on elapsed wall-clock time. The decay speed is controlled
 * by the time constant (tau): after one tau has elapsed, a past observation retains
 * approximately 1/e ≈ 36.8% of its original weight.
 *
 * @param failureRatePercent   the percentage threshold (1–100); the circuit trips when the
 *                             time-decayed EWMA rate reaches or exceeds this value
 * @param timeConstant         the EWMA time constant (tau) as a {@link Duration}; shorter
 *                             durations make the average more reactive, longer durations
 *                             make it more stable
 * @param minimumNumberOfCalls the minimum number of recorded outcomes before the threshold
 *                             is evaluated (must be &gt; 0)
 * @see eu.inqudium.core.element.circuitbreaker.metrics.ContinuousTimeEwmaMetrics
 * @see ContinuousTimeEwmaConfigBuilder
 */
public record ContinuousTimeEwmaConfig(
    double failureRatePercent,
    Duration timeConstant,
    int minimumNumberOfCalls
) implements ConfigExtension<ContinuousTimeEwmaConfig> {

  @Override
  public ContinuousTimeEwmaConfig self() {
    return this;
  }
}
