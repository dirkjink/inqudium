package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * Fluent sub-builder that produces a {@link VegasLimitAlgorithmConfig}.
 *
 * <p>Defaults match the {@code balanced} preset of
 * {@link eu.inqudium.core.element.bulkhead.algo.VegasLimitAlgorithm} so that
 * {@code .vegas(v -> {})} produces a usable algorithm out of the box. Setters validate at the
 * call site (ADR-027 class&nbsp;1).
 *
 * <h2>Presets</h2>
 *
 * <p>Three named presets — {@link #protective()}, {@link #balanced()}, {@link #permissive()} —
 * mirror the static factories on {@code VegasLimitAlgorithm} and produce the same parameter
 * sets. Presets are baselines: call them first, then refine with the per-field setters. A
 * preset called <em>after</em> any field setter throws {@link IllegalStateException}, matching
 * the discipline enforced by the top-level {@code BulkheadBuilder}.
 */
public final class VegasAlgorithmBuilder {

    private int initialLimit = 50;
    private int minLimit = 5;
    private int maxLimit = 500;
    private Duration smoothingTimeConstant = Duration.ofSeconds(1);
    private Duration baselineDriftTimeConstant = Duration.ofSeconds(10);
    private Duration errorRateSmoothingTimeConstant = Duration.ofSeconds(5);
    private double errorRateThreshold = 0.1;
    private double minUtilizationThreshold = 0.6;

    private boolean customized;

    VegasAlgorithmBuilder() {
        // Package-private — instantiated only by AdaptiveConfigBuilder /
        // AdaptiveNonBlockingConfigBuilder when the user calls .vegas(...).
    }

    /**
     * Apply the {@code protective} baseline — heavy noise filtering, very slow baseline drift,
     * tolerant error fallback. Must be called before any individual field setter.
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any field setter has already been called on this builder.
     */
    public VegasAlgorithmBuilder protective() {
        guardPresetOrdering();
        this.initialLimit = 20;
        this.minLimit = 1;
        this.maxLimit = 200;
        this.smoothingTimeConstant = Duration.ofSeconds(2);
        this.baselineDriftTimeConstant = Duration.ofSeconds(30);
        this.errorRateSmoothingTimeConstant = Duration.ofSeconds(10);
        this.errorRateThreshold = 0.15;
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
    public VegasAlgorithmBuilder balanced() {
        guardPresetOrdering();
        this.initialLimit = 50;
        this.minLimit = 5;
        this.maxLimit = 500;
        this.smoothingTimeConstant = Duration.ofSeconds(1);
        this.baselineDriftTimeConstant = Duration.ofSeconds(10);
        this.errorRateSmoothingTimeConstant = Duration.ofSeconds(5);
        this.errorRateThreshold = 0.1;
        this.minUtilizationThreshold = 0.6;
        return this;
    }

    /**
     * Apply the {@code permissive} baseline — fast RTT smoothing, fast baseline drift, strict
     * error fallback. Must be called before any individual field setter.
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any field setter has already been called on this builder.
     */
    public VegasAlgorithmBuilder permissive() {
        guardPresetOrdering();
        this.initialLimit = 100;
        this.minLimit = 10;
        this.maxLimit = 1000;
        this.smoothingTimeConstant = Duration.ofMillis(500);
        this.baselineDriftTimeConstant = Duration.ofSeconds(5);
        this.errorRateSmoothingTimeConstant = Duration.ofSeconds(3);
        this.errorRateThreshold = 0.05;
        this.minUtilizationThreshold = 0.75;
        return this;
    }

    /**
     * @param value the starting concurrency limit; strictly positive.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder initialLimit(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "initialLimit must be positive, got: " + value);
        }
        this.initialLimit = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the lower bound; strictly positive.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder minLimit(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "minLimit must be positive, got: " + value);
        }
        this.minLimit = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the upper bound; strictly positive.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder maxLimit(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "maxLimit must be positive, got: " + value);
        }
        this.maxLimit = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the smoothing time constant for limit updates; non-null and strictly
     *              positive.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder smoothingTimeConstant(Duration value) {
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
     * @param value the time constant for the baseline RTT estimate; non-null and strictly
     *              positive.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder baselineDriftTimeConstant(Duration value) {
        Objects.requireNonNull(value, "baselineDriftTimeConstant");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "baselineDriftTimeConstant must be strictly positive, got: " + value);
        }
        this.baselineDriftTimeConstant = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the time constant for the error-rate estimate; non-null and strictly
     *              positive.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder errorRateSmoothingTimeConstant(Duration value) {
        Objects.requireNonNull(value, "errorRateSmoothingTimeConstant");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "errorRateSmoothingTimeConstant must be strictly positive, got: " + value);
        }
        this.errorRateSmoothingTimeConstant = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value error rate above which the algorithm treats responses as overload;
     *              in {@code [0.0, 1.0]}.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder errorRateThreshold(double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                    "errorRateThreshold must be in [0.0, 1.0], got: " + value);
        }
        this.errorRateThreshold = value;
        this.customized = true;
        return this;
    }

    /**
     * @param value the lower utilization bound below which the algorithm pauses limit
     *              increases; in {@code [0.0, 1.0]}.
     * @return this builder, for chaining.
     */
    public VegasAlgorithmBuilder minUtilizationThreshold(double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                    "minUtilizationThreshold must be in [0.0, 1.0], got: " + value);
        }
        this.minUtilizationThreshold = value;
        this.customized = true;
        return this;
    }

    /**
     * Materialize the algorithm config; called by the parent strategy builder. User code does
     * not call this method directly.
     */
    VegasLimitAlgorithmConfig build() {
        return new VegasLimitAlgorithmConfig(
                initialLimit, minLimit, maxLimit,
                smoothingTimeConstant, baselineDriftTimeConstant,
                errorRateSmoothingTimeConstant,
                errorRateThreshold, minUtilizationThreshold);
    }

    private void guardPresetOrdering() {
        if (customized) {
            throw new IllegalStateException(
                    "Cannot apply a preset after individual setters have been called. "
                            + "Presets are baselines: call them first, then customize. "
                            + "Example: .vegas(v -> v.protective().maxLimit(150))");
        }
    }
}
