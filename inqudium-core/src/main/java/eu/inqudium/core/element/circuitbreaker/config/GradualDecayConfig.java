package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Configuration for gradual failure decay where one success offsets one failure.
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
