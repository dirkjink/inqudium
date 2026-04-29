package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;
import java.util.Objects;

/**
 * @deprecated Replaced by {@link eu.inqudium.config.dsl.BulkheadBuilderBase} as part of the
 *             configuration redesign (ADR-025). Retained because
 *             {@link eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder}
 *             still extends it; both are removed once the legacy resilience surface (top-level
 *             {@code Resilience} DSL, pre-{@code Inqudium.configure()} bulkhead and
 *             circuit-breaker stacks) is dismantled.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public abstract class InqBulkheadConfigBuilder
        <B extends InqBulkheadConfigBuilder<B, E>, E extends ConfigExtension<E>>
        extends ExtensionBuilder<E> {

    private GeneralConfig generalConfig;
    private String name;
    private InqEventPublisher eventPublisher;
    private BulkheadStrategy strategy;
    private int maxConcurrentCalls;
    private Duration maxWaitDuration;
    private InqLimitAlgorithm limitAlgorithm;
    private BulkheadEventConfig eventConfig;

    protected InqBulkheadConfigBuilder() {
    }

    @Override
    protected void general(GeneralConfig generalConfig) {
        this.generalConfig = generalConfig;
    }

    /**
     * Provides subclasses with read access to the general configuration.
     *
     * @return the general configuration, may be null if not yet injected
     */
    protected GeneralConfig getGeneralConfig() {
        return this.generalConfig;
    }

    protected abstract B self();

    public B protective() {
        this.maxConcurrentCalls = 10;
        this.maxWaitDuration = Duration.ZERO;
        return self();
    }

    public B balanced() {
        this.maxConcurrentCalls = 50;
        this.maxWaitDuration = Duration.ofMillis(500);
        return self();
    }

    public B permissive() {
        this.maxConcurrentCalls = 200;
        this.maxWaitDuration = Duration.ofSeconds(5);
        return self();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Individual Setters — each guards its own value immediately
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sets the name of this bulkhead element, used for identification in
     * logging, metrics, and event publishing.
     *
     * @param name the bulkhead name, must not be null or blank
     * @return this builder
     * @throws NullPointerException     if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public B name(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        return self();
    }

    /**
     * Sets the bulkhead strategy directly. Note: when using this method, the
     * strategy's internal concurrency limit is NOT automatically synchronized
     * with {@link #maxConcurrentCalls(int)}.
     *
     * @param strategy the bulkhead strategy to use
     * @return this builder
     */
    public B strategy(BulkheadStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        this.strategy = strategy;
        return self();
    }

    public B eventConfig(BulkheadEventConfig bulkheadEventConfig) {
        Objects.requireNonNull(bulkheadEventConfig, "eventConfig must not be null");
        this.eventConfig = bulkheadEventConfig;
        return self();
    }

    /**
     * Sets the maximum duration a thread will wait for a bulkhead permit before
     * being rejected. Use {@link Duration#ZERO} for immediate rejection when no
     * permit is available.
     *
     * @param maxWaitDuration the maximum wait duration, must not be null or negative
     * @return this builder
     * @throws NullPointerException     if {@code maxWaitDuration} is null
     * @throws IllegalArgumentException if {@code maxWaitDuration} is negative
     */
    public B maxWaitDuration(Duration maxWaitDuration) {
        Objects.requireNonNull(maxWaitDuration, "maxWaitDuration must not be null");
        if (maxWaitDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "maxWaitDuration must not be negative, got: " + maxWaitDuration);
        }
        this.maxWaitDuration = maxWaitDuration;
        return self();
    }

    /**
     * Sets the maximum number of calls that may execute concurrently through
     * this bulkhead. Additional calls will either wait or be rejected depending
     * on the configured {@link #maxWaitDuration(Duration)}.
     *
     * @param maxConcurrentCalls the maximum concurrent calls, must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code maxConcurrentCalls} is not positive
     */
    public B maxConcurrentCalls(int maxConcurrentCalls) {
        if (maxConcurrentCalls <= 0) {
            throw new IllegalArgumentException(
                    "maxConcurrentCalls must be positive, got: " + maxConcurrentCalls);
        }
        this.maxConcurrentCalls = maxConcurrentCalls;
        return self();
    }

    /**
     * Sets an optional adaptive limit algorithm that dynamically adjusts the
     * concurrency limit at runtime based on observed throughput and error rates.
     * When set, this overrides the static {@link #maxConcurrentCalls(int)} ceiling.
     *
     * @param limitAlgorithm the limit algorithm, or {@code null} to disable adaptive limiting
     * @return this builder
     */
    public B limitAlgorithm(InqLimitAlgorithm limitAlgorithm) {
        this.limitAlgorithm = limitAlgorithm;
        return self();
    }

    /**
     * Sets a custom event publisher for this bulkhead. If not set, a default
     * publisher is derived from the element name and type during inference.
     *
     * @param eventPublisher the event publisher, or {@code null} for automatic derivation
     * @return this builder
     */
    public B eventPublisher(InqEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        return self();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Build helper
    // ──────────────────────────────────────────────────────────────────────────

    protected InqBulkheadConfig common() {
        // Resolve strategy: factory takes precedence if set, ensuring coupling
        BulkheadStrategy resolvedStrategy = this.strategy;

        InqElementCommonConfig common =
                new InqElementCommonConfig(name,
                        InqElementType.BULKHEAD,
                        eventPublisher);

        InqBulkheadConfig config = new InqBulkheadConfig(
                this.generalConfig,
                common.inference(),
                maxConcurrentCalls,
                resolvedStrategy,
                maxWaitDuration,
                limitAlgorithm,
                eventConfig
        ).inference();

        validate(config);
        return config;
    }

    /**
     * Validates cross-field constraints on the fully inferred configuration.
     * Individual field constraints are already enforced by each setter.
     */
    private void validate(InqBulkheadConfig config) {
        // Detect unconfigured fields — name is required and has no sensible default
        if (config.common() == null || config.common().name() == null) {
            throw new IllegalStateException(
                    "name must be set. Call name(...) before building the bulkhead.");
        }

        if (config.maxConcurrentCalls() <= 0) {
            throw new IllegalStateException(
                    "maxConcurrentCalls has not been set to a valid value.");
        }
    }
}
