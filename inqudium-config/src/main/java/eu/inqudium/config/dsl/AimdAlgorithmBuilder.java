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
 * preset of the deprecated phase-1 {@code AimdLimitAlgorithmConfigBuilder}, so a user who
 * calls {@code .aimd(a -> {})} without further customization gets a usable, production-ready
 * algorithm.
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

    AimdAlgorithmBuilder() {
        // Package-private — instantiated only by AdaptiveConfigBuilder /
        // AdaptiveNonBlockingConfigBuilder when the user calls .aimd(...).
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
        return this;
    }

    /**
     * @param value {@code true} to gate additive increases on a wait window, {@code false} for
     *              unconstrained increases.
     * @return this builder, for chaining.
     */
    public AimdAlgorithmBuilder windowedIncrease(boolean value) {
        this.windowedIncrease = value;
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
        return this;
    }

    /**
     * Materialize the algorithm config from the current builder state. Called by the parent
     * {@link AdaptiveConfigBuilder} or {@link AdaptiveNonBlockingConfigBuilder}; user code
     * does not call this method directly.
     *
     * @return the AIMD algorithm config; defaults match the deprecated phase-1
     *         {@code balanced} preset.
     */
    AimdLimitAlgorithmConfig build() {
        return new AimdLimitAlgorithmConfig(
                initialLimit, minLimit, maxLimit, backoffRatio,
                smoothingTimeConstant, errorRateThreshold,
                windowedIncrease, minUtilizationThreshold);
    }
}
