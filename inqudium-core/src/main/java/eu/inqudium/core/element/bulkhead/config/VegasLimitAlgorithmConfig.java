package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;

import java.time.Duration;

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
