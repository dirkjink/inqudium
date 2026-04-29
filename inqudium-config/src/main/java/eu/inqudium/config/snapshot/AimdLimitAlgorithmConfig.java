package eu.inqudium.config.snapshot;

import java.time.Duration;
import java.util.Objects;

/**
 * AIMD (Additive-Increase / Multiplicative-Decrease) limit-algorithm tunables for the
 * adaptive bulkhead strategies.
 *
 * <p>Compact-constructor invariants reject obviously-invalid combinations up-front so the
 * runtime never has to defensively re-check them.
 *
 * @param initialLimit            starting concurrency limit; strictly positive.
 * @param minLimit                lower bound the algorithm will never go below; strictly
 *                                positive and {@code <= initialLimit}.
 * @param maxLimit                upper bound the algorithm will never exceed; strictly
 *                                positive and {@code >= initialLimit}.
 * @param backoffRatio            multiplicative-decrease factor on overload; in
 *                                {@code (0.0, 1.0]}.
 * @param smoothingTimeConstant   exponential-smoothing time constant for RTT samples;
 *                                non-null, strictly positive.
 * @param errorRateThreshold      error rate above which the algorithm treats responses as
 *                                overload; in {@code [0.0, 1.0]}.
 * @param windowedIncrease        {@code true} to gate additive increases on a wait window,
 *                                {@code false} for unconstrained increases.
 * @param minUtilizationThreshold lower utilization bound below which the algorithm pauses
 *                                additive increases; in {@code [0.0, 1.0]}.
 */
public record AimdLimitAlgorithmConfig(
        int initialLimit,
        int minLimit,
        int maxLimit,
        double backoffRatio,
        Duration smoothingTimeConstant,
        double errorRateThreshold,
        boolean windowedIncrease,
        double minUtilizationThreshold) implements LimitAlgorithm {

    public AimdLimitAlgorithmConfig {
        if (initialLimit <= 0) {
            throw new IllegalArgumentException(
                    "initialLimit must be strictly positive, got: " + initialLimit);
        }
        if (minLimit <= 0) {
            throw new IllegalArgumentException(
                    "minLimit must be strictly positive, got: " + minLimit);
        }
        if (maxLimit <= 0) {
            throw new IllegalArgumentException(
                    "maxLimit must be strictly positive, got: " + maxLimit);
        }
        if (minLimit > initialLimit) {
            throw new IllegalArgumentException(
                    "minLimit (" + minLimit + ") must be <= initialLimit (" + initialLimit + ")");
        }
        if (maxLimit < initialLimit) {
            throw new IllegalArgumentException(
                    "maxLimit (" + maxLimit + ") must be >= initialLimit (" + initialLimit + ")");
        }
        if (!(backoffRatio > 0.0 && backoffRatio <= 1.0)) {
            throw new IllegalArgumentException(
                    "backoffRatio must be in (0.0, 1.0], got: " + backoffRatio);
        }
        Objects.requireNonNull(smoothingTimeConstant, "smoothingTimeConstant");
        if (smoothingTimeConstant.isZero() || smoothingTimeConstant.isNegative()) {
            throw new IllegalArgumentException(
                    "smoothingTimeConstant must be strictly positive, got: "
                            + smoothingTimeConstant);
        }
        if (!(errorRateThreshold >= 0.0 && errorRateThreshold <= 1.0)) {
            throw new IllegalArgumentException(
                    "errorRateThreshold must be in [0.0, 1.0], got: " + errorRateThreshold);
        }
        if (!(minUtilizationThreshold >= 0.0 && minUtilizationThreshold <= 1.0)) {
            throw new IllegalArgumentException(
                    "minUtilizationThreshold must be in [0.0, 1.0], got: "
                            + minUtilizationThreshold);
        }
    }
}
