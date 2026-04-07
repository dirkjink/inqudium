package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedSlidingWindowMetrics}.
 *
 * <p>Holds the parameters for the time-based sliding window algorithm, which tracks
 * failures in 1-second buckets over a rolling window. The circuit trips when the
 * total failure count across all active buckets reaches {@code maxFailuresInWindow}.
 *
 * <p>Unlike {@link TimeBasedErrorRateConfig}, this configuration defines an <em>absolute</em>
 * failure count threshold rather than a percentage-based error rate, and does not track
 * successes.
 *
 * @param maxFailuresInWindow  the absolute failure count threshold; the circuit opens when
 *                             the sum of failures across all buckets reaches this value
 * @param windowSizeInSeconds  the duration of the sliding window in seconds (also the number
 *                             of 1-second buckets); must be &gt; 0
 * @see eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedSlidingWindowMetrics
 * @see TimeBasedSlidingWindowConfigBuilder
 */
public record TimeBasedSlidingWindowConfig(
    int maxFailuresInWindow,
    int windowSizeInSeconds
) implements ConfigExtension<TimeBasedSlidingWindowConfig> {

  @Override
  public TimeBasedSlidingWindowConfig self() {
    return this;
  }
}
