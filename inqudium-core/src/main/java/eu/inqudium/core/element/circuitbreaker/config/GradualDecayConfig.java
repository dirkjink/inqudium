package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.GradualDecayMetrics}.
 *
 * <p>Holds the parameters for the gradual-decay algorithm, where each failure increments
 * a counter by one and each success decrements it by one (floored at zero). The circuit
 * trips when the counter reaches {@code maxFailureCount}.
 *
 * @param maxFailureCount    the net failure count threshold at which the circuit opens (must be &gt; 0)
 * @param initialFailureCount the starting value of the failure counter; typically 0
 * @see eu.inqudium.core.element.circuitbreaker.metrics.GradualDecayMetrics
 * @see GradualDecayConfigBuilder
 */
public record GradualDecayConfig(
    int maxFailureCount,
    int initialFailureCount
) implements ConfigExtension<GradualDecayConfig> {

  @Override
  public GradualDecayConfig self() {
    return this;
  }
}
