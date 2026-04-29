package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.LimitAlgorithm;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fluent sub-builder that produces an {@link AdaptiveNonBlockingStrategyConfig}.
 *
 * <p>Same shape and contract as {@link AdaptiveConfigBuilder}: two algorithm choices via
 * {@code aimd(...)} or {@code vegas(...)}, last-writer-wins between the two, mandatory at
 * materialization time. The strategy itself differs: non-blocking variants fail fast on
 * saturation rather than parking the caller for {@code maxWaitDuration}.
 */
public final class AdaptiveNonBlockingConfigBuilder {

    private LimitAlgorithm algorithm;

    AdaptiveNonBlockingConfigBuilder() {
        // Package-private — instantiated only by BulkheadBuilderBase when the user calls
        // .adaptiveNonBlocking(...).
    }

    /**
     * Configure the AIMD algorithm. Replaces any previous algorithm choice
     * (last-writer-wins).
     */
    public AdaptiveNonBlockingConfigBuilder aimd(Consumer<AimdAlgorithmBuilder> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        AimdAlgorithmBuilder sub = new AimdAlgorithmBuilder();
        configurer.accept(sub);
        this.algorithm = sub.build();
        return this;
    }

    /**
     * Configure the Vegas algorithm. Replaces any previous algorithm choice
     * (last-writer-wins).
     */
    public AdaptiveNonBlockingConfigBuilder vegas(Consumer<VegasAlgorithmBuilder> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        VegasAlgorithmBuilder sub = new VegasAlgorithmBuilder();
        configurer.accept(sub);
        this.algorithm = sub.build();
        return this;
    }

    /**
     * @throws IllegalStateException if neither {@code aimd(...)} nor {@code vegas(...)} was
     *                               called inside the builder block.
     */
    AdaptiveNonBlockingStrategyConfig build() {
        if (algorithm == null) {
            throw new IllegalStateException(
                    "adaptiveNonBlocking strategy requires an explicit algorithm choice — "
                            + "call .aimd(...) or .vegas(...) inside the builder");
        }
        return new AdaptiveNonBlockingStrategyConfig(algorithm);
    }
}
