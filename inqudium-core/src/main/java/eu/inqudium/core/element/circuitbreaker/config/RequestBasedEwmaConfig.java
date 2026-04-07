package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Configuration for request-sequence based EWMA metrics.
 */
public record RequestBasedEwmaConfig(
    double failureRatePercent,
    double smoothingFactor,
    int minimumNumberOfCalls
)
    implements ConfigExtension<RequestBasedEwmaConfig> {
  @Override
  public RequestBasedEwmaConfig self() {
    return this;
  }
}
