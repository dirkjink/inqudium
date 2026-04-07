package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.RequestBasedEwmaMetrics}.
 *
 * <p>Holds the parameters for the request-based EWMA algorithm, which computes a smoothed
 * failure rate that decays per request (not by wall-clock time). Each failure contributes
 * a sample of 1.0 and each success a sample of 0.0 to the exponentially weighted moving average.
 *
 * @param failureRatePercent  the percentage threshold (1–100); the circuit trips when the
 *                            EWMA failure rate reaches or exceeds this value
 * @param smoothingFactor     the EWMA alpha (0 &lt; alpha ≤ 1); higher values make the average
 *                            more reactive to recent observations, lower values make it more stable
 * @param minimumNumberOfCalls the minimum number of recorded outcomes before the threshold
 *                             is evaluated; prevents premature tripping on small sample sizes
 *                             (must be &gt; 0)
 * @see eu.inqudium.core.element.circuitbreaker.metrics.RequestBasedEwmaMetrics
 * @see RequestBasedEwmaConfigBuilder
 */
public record RequestBasedEwmaConfig(
    double failureRatePercent,
    double smoothingFactor,
    int minimumNumberOfCalls
) implements ConfigExtension<RequestBasedEwmaConfig> {

  @Override
  public RequestBasedEwmaConfig self() {
    return this;
  }
}
