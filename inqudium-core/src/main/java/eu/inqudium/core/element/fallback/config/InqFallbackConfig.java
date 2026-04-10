package eu.inqudium.core.element.fallback.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

public record InqFallbackConfig(
        GeneralConfig general,
        InqElementCommonConfig common
) implements InqElementConfig, ConfigExtension<InqFallbackConfig> {
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
    public InqFallbackConfig self() {
        return this;
    }

    @Override
    public InqFallbackConfig inference() {
        return new InqFallbackConfig(
                this.general,
                this.common
        );
    }
}

