package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * Fluent sub-builder that produces a {@link VegasLimitAlgorithmConfig}.
 *
 * <p>Defaults match the {@code balanced} preset of the deprecated phase-1
 * {@code VegasLimitAlgorithmConfigBuilder} so that {@code .vegas(v -> {})} produces a usable
 * algorithm out of the box. Setters validate at the call site (ADR-027 class&nbsp;1).
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

    VegasAlgorithmBuilder() {
        // Package-private — instantiated only by AdaptiveConfigBuilder /
        // AdaptiveNonBlockingConfigBuilder when the user calls .vegas(...).
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
}
