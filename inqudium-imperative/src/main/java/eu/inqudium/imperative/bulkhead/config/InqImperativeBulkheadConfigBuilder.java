package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;

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
