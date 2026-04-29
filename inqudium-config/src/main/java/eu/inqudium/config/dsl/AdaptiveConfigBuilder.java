package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.LimitAlgorithm;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fluent sub-builder that produces an {@link AdaptiveStrategyConfig} (the blocking variant).
 *
 * <p>Hosts two nested sub-builders for the {@link LimitAlgorithm} choice — AIMD or Vegas. The
 * choice is mandatory: an adaptive strategy without an algorithm has no semantics, and the
 * builder rejects an empty {@code .adaptive(a -> {})} block at materialization time with an
 * {@link IllegalStateException} that asks the user to call {@code .aimd(...)} or
 * {@code .vegas(...)}.
 *
 * <p>Calling both {@code aimd(...)} and {@code vegas(...)} in sequence is last-writer-wins,
 * matching the rest of the DSL's setter discipline.
 */
public final class AdaptiveConfigBuilder {

    private LimitAlgorithm algorithm;

    AdaptiveConfigBuilder() {
        // Package-private — instantiated only by BulkheadBuilderBase when the user calls
        // .adaptive(...).
    }

    /**
     * Configure the AIMD algorithm. Replaces any previous algorithm choice on this builder
     * (last-writer-wins).
     *
     * @param configurer the algorithm-builder lambda; non-null.
     * @return this builder, for chaining.
     */
    public AdaptiveConfigBuilder aimd(Consumer<AimdAlgorithmBuilder> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        AimdAlgorithmBuilder sub = new AimdAlgorithmBuilder();
        configurer.accept(sub);
        this.algorithm = sub.build();
        return this;
    }

    /**
     * Configure the Vegas algorithm. Replaces any previous algorithm choice on this builder
     * (last-writer-wins).
     *
     * @param configurer the algorithm-builder lambda; non-null.
     * @return this builder, for chaining.
     */
    public AdaptiveConfigBuilder vegas(Consumer<VegasAlgorithmBuilder> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        VegasAlgorithmBuilder sub = new VegasAlgorithmBuilder();
        configurer.accept(sub);
        this.algorithm = sub.build();
        return this;
    }

    /**
     * Materialize the strategy config; called by {@link BulkheadBuilderBase} when the user's
     * {@code .adaptive(...)} call returns. User code does not call this method directly.
     *
     * @throws IllegalStateException if the user did not call {@code .aimd(...)} or
     *                               {@code .vegas(...)} inside the {@code .adaptive(...)}
     *                               block. Adaptive strategies have no implicit default
     *                               algorithm — the choice must be explicit per ADR-032.
     */
    AdaptiveStrategyConfig build() {
        if (algorithm == null) {
            throw new IllegalStateException(
                    "adaptive strategy requires an explicit algorithm choice — "
                            + "call .aimd(...) or .vegas(...) inside the builder");
        }
        return new AdaptiveStrategyConfig(algorithm);
    }
}
