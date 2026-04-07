package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.ConsecutiveFailuresMetrics}.
 *
 * <p>Holds the parameters that govern the consecutive-failures algorithm: the maximum
 * number of uninterrupted failures before the circuit trips, and an optional initial
 * counter value for pre-loading the breaker closer to its trip point.
 *
 * <p>Implements {@link ConfigExtension} so it can be attached as a typed extension to
 * the circuit breaker's general configuration infrastructure.
 *
 * @param maxConsecutiveFailures  the threshold; the circuit opens once this many back-to-back
 *                                failures have been recorded (must be &gt; 0)
 * @param initialConsecutiveFailures the starting value of the consecutive failure counter;
 *                                   typically 0, but can be set higher to pre-load the breaker
 *                                   (e.g., after a restart against a known-degraded dependency)
 * @see eu.inqudium.core.element.circuitbreaker.metrics.ConsecutiveFailuresMetrics
 * @see ConsecutiveFailuresConfigBuilder
 */
public record ConsecutiveFailuresConfig(
    int maxConsecutiveFailures,
    int initialConsecutiveFailures
) implements ConfigExtension<ConsecutiveFailuresConfig> {

  @Override
  public ConsecutiveFailuresConfig self() {
    return this;
  }
}
