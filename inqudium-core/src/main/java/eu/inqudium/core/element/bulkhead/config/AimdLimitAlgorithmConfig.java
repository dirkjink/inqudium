package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;

import java.time.Duration;

public record AimdLimitAlgorithmConfig(
        int initialLimit,
        int minLimit,
        int maxLimit,
        double backoffRatio,
        Duration smoothingTimeConstant,
        double errorRateThreshold,
        boolean windowedIncrease,
        double minUtilizationThreshold
) implements ConfigExtension<AimdLimitAlgorithmConfig> {
    @Override
    public AimdLimitAlgorithmConfig self() {
        return this;
    }
}
