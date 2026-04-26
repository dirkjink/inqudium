package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

/**
 * @deprecated Replaced by {@link eu.inqudium.config.snapshot.BulkheadSnapshot} as part of the
 *             configuration redesign (ADR-025) and the bulkhead migration in step&nbsp;1.6.
 *             Retained because the imperative
 *             {@link eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfig
 *             InqImperativeBulkheadConfig}, the legacy
 *             {@code eu.inqudium.imperative.bulkhead.Bulkhead} interface, and
 *             {@code CoDelBulkheadStrategy} still reference it; will be removed when the
 *             circuit breaker is migrated to the new architecture (REFACTORING.md step 3.1)
 *             and the legacy bulkhead surface alongside it can be dropped.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public record InqBulkheadConfig(
        GeneralConfig general,
        InqElementCommonConfig common,
        int maxConcurrentCalls,
        BulkheadStrategy strategy,
        Duration maxWaitDuration,
        InqLimitAlgorithm limitAlgorithm,
        BulkheadEventConfig eventConfig
) implements InqElementConfig, ConfigExtension<InqBulkheadConfig> {
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
    public InqBulkheadConfig self() {
        return this;
    }

    @Override
    public InqBulkheadConfig inference() {
        BulkheadEventConfig eventConfigInference = eventConfig;
        if (eventConfig == null) {
            eventConfigInference = BulkheadEventConfig.standard();
        }
        return new InqBulkheadConfig(
                this.general,
                this.common,
                this.maxConcurrentCalls,
                this.strategy,
                this.maxWaitDuration,
                this.limitAlgorithm,
                eventConfigInference
        );
    }
}

