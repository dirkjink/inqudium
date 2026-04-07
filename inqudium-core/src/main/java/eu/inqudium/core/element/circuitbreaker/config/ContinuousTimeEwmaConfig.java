package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

import java.time.Duration;

/**
 * Configuration for chronologically decaying EWMA metrics.
 */
public record ContinuousTimeEwmaConfig(
    double failureRatePercent,
    Duration timeConstant,
    int minimumNumberOfCalls
)
    implements ConfigExtension<ContinuousTimeEwmaConfig> {
  @Override
  public ContinuousTimeEwmaConfig self() {
    return this;
  }
}
