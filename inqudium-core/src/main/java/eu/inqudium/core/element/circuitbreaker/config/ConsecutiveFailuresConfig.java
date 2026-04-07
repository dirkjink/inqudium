package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Configuration for the initial state of consecutive failure tracking.
 * Typically starts with 0 failures.
 */
public record ConsecutiveFailuresConfig(
    double failureRateThreshold,
    int initialConsecutiveFailures
) implements ConfigExtension<ConsecutiveFailuresConfig> {
  @Override
  public ConsecutiveFailuresConfig self() {
    return this;
  }
}