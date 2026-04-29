package eu.inqudium.core.element.bulkhead.dsl;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;

import java.time.Duration;

/**
 * @deprecated Replaced by {@link eu.inqudium.config.dsl.BulkheadBuilderBase} as part of the
 *             configuration redesign (ADR-025). Retained because the legacy
 *             {@code AsyncLayerAction} pipeline and the top-level {@code Resilience} DSL still
 *             reference it; removed alongside the legacy bulkhead DSL once the
 *             {@code Resilience} DSL is dismantled.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
@SuppressWarnings("deprecation")
public final class DefaultBulkheadProtection implements BulkheadNaming, BulkheadProtection {

    private final InqBulkheadConfigBuilder<?, ?> inqBuilder;
    private String name;
    private int maxConcurrentCalls = 25; // Fallback
    private Duration maxWaitDuration = Duration.ofSeconds(0); // Fallback: Fail fast

    public DefaultBulkheadProtection(InqBulkheadConfigBuilder<?, ?> inqBuilder) {
        this.inqBuilder = inqBuilder;
    }

    @Override
    public BulkheadProtection named(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Bulkhead name must not be blank");
        }
        this.name = name;
        return this;
    }

    @Override
    public BulkheadProtection limitingConcurrentCallsTo(int maxCalls) {
        this.maxConcurrentCalls = maxCalls;
        return this;
    }

    @Override
    public BulkheadProtection waitingAtMostFor(Duration maxWait) {
        this.maxWaitDuration = maxWait;
        return this;
    }

    @Override
    public BulkheadConfig applyProtectiveProfile() {
        InqBulkheadConfig cfg = new InternalBulkheadConfigBuilder().protective().build();
        return new BulkheadConfig(name, cfg.maxConcurrentCalls(), cfg.maxWaitDuration(), createInqConfig());
    }

    @Override
    public BulkheadConfig applyBalancedProfile() {
        InqBulkheadConfig cfg = new InternalBulkheadConfigBuilder().balanced().build();
        return new BulkheadConfig(name, cfg.maxConcurrentCalls(), cfg.maxWaitDuration(), createInqConfig());
    }

    @Override
    public BulkheadConfig applyPermissiveProfile() {
        InqBulkheadConfig cfg = new InternalBulkheadConfigBuilder().permissive().build();
        return new BulkheadConfig(name, cfg.maxConcurrentCalls(), cfg.maxWaitDuration(), createInqConfig());
    }

    @Override
    public BulkheadConfig apply() {
        return new BulkheadConfig(name, maxConcurrentCalls, maxWaitDuration, createInqConfig());
    }

    private InqConfig createInqConfig() {
        if (inqBuilder == null) return null;
        return InqConfig.configure()
                .general()
                .with(inqBuilder, b -> b
                        .name(name)
                        .maxConcurrentCalls(maxConcurrentCalls)
                        .maxWaitDuration(maxWaitDuration)
                )
                .build();
    }
}

class InternalBulkheadConfigBuilder
        extends InqBulkheadConfigBuilder<InternalBulkheadConfigBuilder, InqBulkheadConfig> {

    InternalBulkheadConfigBuilder() {
    }

    @Override
    public InqBulkheadConfig build() {
        return common().inference();
    }

    @Override
    protected InternalBulkheadConfigBuilder self() {
        return this;
    }
}