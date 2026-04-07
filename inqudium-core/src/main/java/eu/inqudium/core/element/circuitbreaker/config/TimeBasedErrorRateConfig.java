package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Configuration for time-bucketed error rate metrics.
 */
public record TimeBasedErrorRateConfig(
    double failureRatePercent,
    int windowSizeInSeconds,
    int minimumNumberOfCalls
)
    implements ConfigExtension<TimeBasedErrorRateConfig> {

  @Override
  public TimeBasedErrorRateConfig self() {
    return this;
  }
}
