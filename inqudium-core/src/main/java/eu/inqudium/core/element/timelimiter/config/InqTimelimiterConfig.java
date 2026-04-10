package eu.inqudium.core.element.timelimiter.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

public record InqTimelimiterConfig(
        GeneralConfig general,
        InqElementCommonConfig common
) implements InqElementConfig, ConfigExtension<InqTimelimiterConfig> {
    @Override
    public String name() {
        return common.name();
    }

    @Override
    public InqElementType elementType() {
        return common.elementType();
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return common.eventPublisher();
    }

    @Override
    public Boolean enableExceptionOptimization() {
        return common.enableExceptionOptimization();
    }

    @Override
    public InqTimelimiterConfig self() {
        return this;
    }

    @Override
    public InqTimelimiterConfig inference() {
        return new InqTimelimiterConfig(
                this.general,
                this.common
        );
    }
}

