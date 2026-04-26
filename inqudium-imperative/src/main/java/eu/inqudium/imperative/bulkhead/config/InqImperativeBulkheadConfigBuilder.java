package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;

/**
 * @deprecated Replaced by
 *             {@link eu.inqudium.imperative.bulkhead.dsl.DefaultImperativeBulkheadBuilder}
 *             accessed via {@code Inqudium.configure().imperative(...)} (ADR-025). Retained
 *             because {@code eu.inqudium.imperative.circuitbreaker.CircuitBreaker}, the legacy
 *             {@code Resilience} DSL, and the legacy {@code Bulkhead.of(...)} factory all rely
 *             on it; removed in REFACTORING.md step 3.1.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
@SuppressWarnings("deprecation")
public class InqImperativeBulkheadConfigBuilder
        extends InqBulkheadConfigBuilder<InqImperativeBulkheadConfigBuilder,
        InqImperativeBulkheadConfig> {

    InqImperativeBulkheadConfigBuilder() {
    }

    public static InqImperativeBulkheadConfigBuilder bulkhead() {
        return standard();
    }

    public static InqImperativeBulkheadConfigBuilder standard() {
        InqBulkheadConfig cfg = new InqImperativeBulkheadConfigBuilder().balanced().common();
        return new InqImperativeBulkheadConfigBuilder()
                .maxConcurrentCalls(cfg.maxConcurrentCalls())
                .maxWaitDuration(cfg.maxWaitDuration())
                .eventConfig(BulkheadEventConfig.standard());
    }

    @Override
    public InqImperativeBulkheadConfig build() {
        return new InqImperativeBulkheadConfig(getGeneralConfig(), common()).inference();
    }

    @Override
    protected InqImperativeBulkheadConfigBuilder self() {
        return this;
    }
}
