package eu.inqudium.config.snapshot;

import java.time.Duration;
import java.util.Objects;

/**
 * Vegas limit-algorithm tunables for the adaptive bulkhead strategies.
 *
 * <p>Compact-constructor invariants reject obviously-invalid combinations up-front.
 *
 * @param initialLimit                   starting concurrency limit; strictly positive.
 * @param minLimit                       lower bound; strictly positive and
 *                                       {@code <= initialLimit}.
 * @param maxLimit                       upper bound; strictly positive and
 *                                       {@code >= initialLimit}.
 * @param smoothingTimeConstant          exponential-smoothing time constant for the limit
 *                                       update; non-null, strictly positive.
 * @param baselineDriftTimeConstant      time constant for the baseline RTT estimate; non-null,
 *                                       strictly positive.
 * @param errorRateSmoothingTimeConstant time constant for the error-rate estimate; non-null,
 *                                       strictly positive.
 * @param errorRateThreshold             error rate above which the algorithm treats responses
 *                                       as overload; in {@code [0.0, 1.0]}.
 * @param minUtilizationThreshold        lower utilization bound below which the algorithm
 *                                       pauses limit increases; in {@code [0.0, 1.0]}.
 */
public record VegasLimitAlgorithmConfig(
        int initialLimit,
        int minLimit,
        int maxLimit,
        Duration smoothingTimeConstant,
        Duration baselineDriftTimeConstant,
        Duration errorRateSmoothingTimeConstant,
        double errorRateThreshold,
        double minUtilizationThreshold) implements LimitAlgorithm {

    public VegasLimitAlgorithmConfig {
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
        Objects.requireNonNull(smoothingTimeConstant, "smoothingTimeConstant");
        if (smoothingTimeConstant.isZero() || smoothingTimeConstant.isNegative()) {
            throw new IllegalArgumentException(
                    "smoothingTimeConstant must be strictly positive, got: "
                            + smoothingTimeConstant);
        }
        Objects.requireNonNull(baselineDriftTimeConstant, "baselineDriftTimeConstant");
        if (baselineDriftTimeConstant.isZero() || baselineDriftTimeConstant.isNegative()) {
            throw new IllegalArgumentException(
                    "baselineDriftTimeConstant must be strictly positive, got: "
                            + baselineDriftTimeConstant);
        }
        Objects.requireNonNull(errorRateSmoothingTimeConstant, "errorRateSmoothingTimeConstant");
        if (errorRateSmoothingTimeConstant.isZero() || errorRateSmoothingTimeConstant.isNegative()) {
            throw new IllegalArgumentException(
                    "errorRateSmoothingTimeConstant must be strictly positive, got: "
                            + errorRateSmoothingTimeConstant);
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
