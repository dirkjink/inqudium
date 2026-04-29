package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;

import java.time.Duration;

/**
 * @deprecated Replaced by {@link eu.inqudium.config.snapshot.BulkheadSnapshot} as part of the
 *             configuration redesign (ADR-025). Retained because
 *             {@code eu.inqudium.imperative.circuitbreaker.CircuitBreaker} and the legacy
 *             {@code Resilience} DSL still reference it; removed once those callers are
 *             migrated to the new architecture.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
@SuppressWarnings("deprecation")
public record InqImperativeBulkheadConfig(
        GeneralConfig general,
        InqBulkheadConfig bulkhead
) implements ConfigExtension<InqImperativeBulkheadConfig>, InqElementConfig {
    @Override
    public String name() {
        return bulkhead.name();
    }

    public BulkheadStrategy strategy() {
        return bulkhead.strategy();
    }

    public int maxConcurrentCalls() {
        return bulkhead.maxConcurrentCalls();
    }

    public Duration maxWaitDuration() {
        return bulkhead.maxWaitDuration();
    }

    public InqLimitAlgorithm limitAlgorithm() {
        return bulkhead.limitAlgorithm();
    }

    @Override
    public InqElementType elementType() {
        return bulkhead.elementType();
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return bulkhead.eventPublisher();
    }

    public BulkheadEventConfig eventConfig() {
        return bulkhead.eventConfig();
    }

    @Override
    public InqImperativeBulkheadConfig inference() {
        // Strategy choice is explicit through the BulkheadBuilder.semaphore /
        // .codel / .adaptive / .adaptiveNonBlocking DSL on BulkheadSnapshot.strategy(); this
        // legacy InqImperativeBulkheadConfig path is kept only for the deprecated
        // CircuitBreaker / Resilience facade that still references it. When no strategy is
        // set explicitly, fall back to a semaphore.
        BulkheadStrategy strategy = strategy();
        if (strategy == null) {
            strategy = new SemaphoreBulkheadStrategy(maxConcurrentCalls());
        }
        return new InqImperativeBulkheadConfig(
                this.general,
                new InqBulkheadConfig(
                        this.general,
                        this.bulkhead.common(),
                        this.bulkhead.maxConcurrentCalls(),
                        strategy,
                        this.bulkhead.maxWaitDuration(),
                        this.bulkhead.limitAlgorithm(),
                        this.bulkhead.eventConfig()
                )
        );
    }

    @Override
    public InqImperativeBulkheadConfig self() {
        return this;
    }
}

