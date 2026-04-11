package eu.inqudium.imperative.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.event.InqEventPublisher;

public record InqImperativeCircuitBreakerConfig(
        GeneralConfig general,
        InqCircuitBreakerConfig circuitBreaker
) implements ConfigExtension<InqImperativeCircuitBreakerConfig>, InqElementConfig {

    @Override
    public InqImperativeCircuitBreakerConfig self() {
        return this;
    }

    @Override
    public String name() {
        return circuitBreaker.name();
    }

    @Override
    public InqElementType elementType() {
        return circuitBreaker.elementType();
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return circuitBreaker.eventPublisher();
    }
}
