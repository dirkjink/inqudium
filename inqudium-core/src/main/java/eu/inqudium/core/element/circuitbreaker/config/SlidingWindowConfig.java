package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.SlidingWindowMetrics}.
 *
 * <p>Holds the parameters for the count-based sliding window algorithm, which maintains
 * a circular buffer of the last {@code windowSize} call outcomes and trips when the
 * failure count within that buffer reaches {@code maxFailuresInWindow}.
 *
 * @param maxFailuresInWindow  the failure count threshold within the window; the circuit
 *                             opens when this many failures are present in the buffer
 * @param windowSize           the total number of call outcomes retained (circular buffer size);
 *                             must be &gt; 0
 * @param minimumNumberOfCalls the minimum number of recorded outcomes before the threshold
 *                             is evaluated; must be between 1 and {@code windowSize} inclusive
 * @see eu.inqudium.core.element.circuitbreaker.metrics.SlidingWindowMetrics
 * @see SlidingWindowConfigBuilder
 */
public record SlidingWindowConfig(
    int maxFailuresInWindow,
    int windowSize,
    int minimumNumberOfCalls
) implements ConfigExtension<SlidingWindowConfig> {

  @Override
  public SlidingWindowConfig self() {
    return this;
  }
}
