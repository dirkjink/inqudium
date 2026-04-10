package eu.inqudium.core.element.trafficshaper.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

public record InqTrafficshaperConfig(
        GeneralConfig general,
        InqElementCommonConfig common
) implements InqElementConfig, ConfigExtension<InqTrafficshaperConfig> {
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
    public InqTrafficshaperConfig self() {
        return this;
    }

    @Override
    public InqTrafficshaperConfig inference() {
        return new InqTrafficshaperConfig(
                this.general,
                this.common
        );
    }
}

