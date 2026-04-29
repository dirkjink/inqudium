package eu.inqudium.core.element.ratelimiter.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

public record InqRatelimiterConfig(
        GeneralConfig general,
        InqElementCommonConfig common
) implements InqElementConfig, ConfigExtension<InqRatelimiterConfig> {
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
    public InqRatelimiterConfig self() {
        return this;
    }

    @Override
    public InqRatelimiterConfig inference() {
        return new InqRatelimiterConfig(
                this.general,
                this.common
        );
    }
}

