package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.LeakyBucketMetrics}.
 *
 * <p>Holds the parameters for the leaky bucket algorithm, where failures add water
 * (1.0 per failure) to a bucket that drains at a constant rate over time. The circuit
 * trips when the water level reaches or exceeds the bucket capacity.
 *
 * @param bucketCapacity    the maximum water level (threshold); when the level reaches
 *                          or exceeds this value the circuit opens (must be &gt; 0)
 * @param leakRatePerSecond how many units of water drain per second; controls how quickly
 *                          past failures are forgotten (must be ≥ 0)
 * @see eu.inqudium.core.element.circuitbreaker.metrics.LeakyBucketMetrics
 * @see LeakyBucketConfigBuilder
 */
public record LeakyBucketConfig(
        int bucketCapacity,
        double leakRatePerSecond
) implements ConfigExtension<LeakyBucketConfig> {

    @Override
    public LeakyBucketConfig self() {
        return this;
    }
}
