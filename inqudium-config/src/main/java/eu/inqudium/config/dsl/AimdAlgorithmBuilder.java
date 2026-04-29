package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * Fluent sub-builder that produces an {@link AimdLimitAlgorithmConfig}.
 *
 * <p>Every setter validates its argument at the call site (ADR-027 class&nbsp;1) so the
 * exception's stack trace points at the offending DSL line rather than at the snapshot's
 * compact constructor. The defaults applied at construction time match the {@code balanced}
 * preset of {@link eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm}, so a user who
 * calls {@code .aimd(a -> {})} without further customization gets a usable, production-ready
 * algorithm.
 *
 * <h2>Presets</h2>
 *
 * <p>Three named presets — {@link #protective()}, {@link #balanced()}, {@link #permissive()} —
 * mirror the static factories on {@code AimdLimitAlgorithm} and produce the same parameter
 * sets. They are baselines: call a preset first, then refine individual fields with the
 * per-field setters. Calling a preset <em>after</em> an individual setter throws
 * {@link IllegalStateException}, matching the discipline enforced by the top-level
 * {@code BulkheadBuilder}.
 *
 * <p>Builders are not thread-safe — they are short-lived per DSL invocation, populated on a
 * single thread, and consumed once.
 */
public final class AimdAlgorithmBuilder {

    private int initialLimit = 50;
    private int minLimit = 5;
    private int maxLimit = 500;
    private double backoffRatio = 0.7;
    private Duration smoothingTimeConstant = Duration.ofSeconds(2);
    private double errorRateThreshold = 0.1;
    private boolean windowedIncrease = true;
    private double minUtilizationThreshold = 0.6;

    private boolean customized;

    AimdAlgorithmBuilder() {
        // Package-private — instantiated only by AdaptiveConfigBuilder /
        // AdaptiveNonBlockingConfigBuilder when the user calls .aimd(...).
    }

    /**
     * Apply the {@code protective} baseline — slow growth, aggressive backoff, tolerant error
     * detection. Must be called before any individual field setter.
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any field setter has already been called on this builder.
     */
    public AimdAlgorithmBuilder protective() {
        guardPresetOrdering();
        this.initialLimit = 20;
        this.minLimit = 1;
        this.maxLimit = 200;
        this.backoffRatio = 0.5;
        this.smoothingTimeConstant = Duration.ofSeconds(5);
        this.errorRateThreshold = 0.15;
        this.windowedIncrease = true;
        this.minUtilizationThreshold = 0.5;
        return this;
    }

    /**
     * Apply the {@code balanced} baseline — the recommended production default. Equivalent to
     * the values applied at construction time, so calling it on an otherwise untouched builder
     * is a no-op (other than marking the builder as preset-applied for style).
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any field setter has already been called on this builder.
     */
    public AimdAlgorithmBuilder balanced() {
        guardPresetOrdering();
        this.initialLimit = 50;
        this.minLimit = 5;
        this.maxLimit = 500;
        this.backoffRatio = 0.7;
        this.smoothingTimeConstant = Duration.ofSeconds(2);
        this.errorRateThreshold = 0.1;
        this.windowedIncrease = true;
        this.minUtilizationThreshold = 0.6;
        return this;
    }

    /**
     * Apply the {@code permissive} baseline — fast growth, gentle backoff, strict error
     * detection. Must be called before any individual field setter.
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any field setter has already been called on this builder.
     */
    public AimdAlgorithmBuilder permissive() {
        guardPresetOrdering();
        this.initialLimit = 100;
        this.minLimit = 10;
        this.maxLimit = 1000;
        this.backoffRatio = 0.85;
        this.smoothingTimeConstant = Duration.ofSeconds(1);
        this.errorRateThreshold = 0.05;
        this.windowedIncrease = false;
        this.minUtilizationThreshold = 0.75;
        return this;
    }

    /**
     * @param value the starting concurrency limit; strictly positive.
     * @return this builder, for chaining.
     * @throws IllegalArgumentException if {@code value} is zero or negative.
     */
    public AimdAlgorithmBuilder initialLimit(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "initialLimit must be positive, got: " + value);
        }
        this.initialLimit = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the lower bound the algorithm will never go below; strictly positive.
     * @return this builder, for chaining.
     * @throws IllegalArgumentException if {@code value} is zero or negative.
     */
    public AimdAlgorithmBuilder minLimit(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "minLimit must be positive, got: " + value);
        }
        this.minLimit = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the upper bound the algorithm will never exceed; strictly positive.
     * @return this builder, for chaining.
     * @throws IllegalArgumentException if {@code value} is zero or negative.
     */
    public AimdAlgorithmBuilder maxLimit(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "maxLimit must be positive, got: " + value);
        }
        this.maxLimit = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the multiplicative-decrease factor on overload; in {@code (0.0, 1.0]}.
     * @return this builder, for chaining.
     * @throws IllegalArgumentException if {@code value} is outside the open-closed interval
     *                                  {@code (0.0, 1.0]}.
     */
    public AimdAlgorithmBuilder backoffRatio(double value) {
        if (!(value > 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                    "backoffRatio must be in (0.0, 1.0], got: " + value);
        }
        this.backoffRatio = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the exponential-smoothing time constant for RTT samples; non-null and
     *              strictly positive.
     * @return this builder, for chaining.
     */
    public AimdAlgorithmBuilder smoothingTimeConstant(Duration value) {
        Objects.requireNonNull(value, "smoothingTimeConstant");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "smoothingTimeConstant must be strictly positive, got: " + value);
        }
        this.smoothingTimeConstant = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the error-rate threshold above which the algorithm treats responses as
     *              overload; in {@code [0.0, 1.0]}.
     * @return this builder, for chaining.
     */
    public AimdAlgorithmBuilder errorRateThreshold(double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                    "errorRateThreshold must be in [0.0, 1.0], got: " + value);
        }
        this.errorRateThreshold = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value {@code true} to gate additive increases on a wait window, {@code false} for
     *              unconstrained increases.
     * @return this builder, for chaining.
     */
    public AimdAlgorithmBuilder windowedIncrease(boolean value) {
        this.windowedIncrease = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the lower utilization bound below which the algorithm pauses additive
     *              increases; in {@code [0.0, 1.0]}.
     * @return this builder, for chaining.
     */
    public AimdAlgorithmBuilder minUtilizationThreshold(double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                    "minUtilizationThreshold must be in [0.0, 1.0], got: " + value);
        }
        this.minUtilizationThreshold = value;
        this.customized = true;
        return this;
    }

    /**
     * Materialize the algorithm config from the current builder state. Called by the parent
     * {@link AdaptiveConfigBuilder} or {@link AdaptiveNonBlockingConfigBuilder}; user code
     * does not call this method directly.
     *
     * @return the AIMD algorithm config; defaults match the {@code balanced} preset.
     */
    AimdLimitAlgorithmConfig build() {
        return new AimdLimitAlgorithmConfig(
                initialLimit, minLimit, maxLimit, backoffRatio,
                smoothingTimeConstant, errorRateThreshold,
                windowedIncrease, minUtilizationThreshold);
    }

    private void guardPresetOrdering() {
        if (customized) {
            throw new IllegalStateException(
                    "Cannot apply a preset after individual setters have been called. "
                            + "Presets are baselines: call them first, then customize. "
                            + "Example: .aimd(a -> a.protective().maxLimit(150))");
        }
    }
}
