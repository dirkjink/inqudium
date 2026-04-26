package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;

import java.time.Duration;

/**
 * @deprecated Orphaned by REFACTORING.md step 1.10 — its only consumer was
 *             {@link InqBulkheadConfig}, which is itself deprecated. Revisit when the
 *             strategy hot-swap DSL is settled in phase&nbsp;2 (step&nbsp;2.10).
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public record VegasLimitAlgorithmConfig(int initialLimit,
                                        int minLimit,
                                        int maxLimit,
                                        Duration smoothingTimeConstant,
                                        Duration baselineDriftTimeConstant,
                                        Duration errorRateSmoothingTimeConstant,
                                        double errorRateThreshold,
                                        double minUtilizationThreshold) implements ConfigExtension<VegasLimitAlgorithmConfig> {
    @Override
    public VegasLimitAlgorithmConfig self() {
        return this;
    }
}
