package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Configuration for leaky bucket overflow metrics.
 */
public record LeakyBucketConfig(
    int bucketCapacity,
    double leakRatePerSecond
)
    implements ConfigExtension<LeakyBucketConfig> {
  @Override
  public LeakyBucketConfig self() {
    return this;
  }
}
