package eu.inqudium.config.snapshot;

import java.util.Objects;

/**
 * Configuration for the blocking adaptive bulkhead strategy.
 *
 * <p>The adaptive strategy delegates the limit decision to its embedded
 * {@link LimitAlgorithm} (AIMD or Vegas) and blocks callers up to the snapshot's
 * {@code maxWaitDuration} when the limit is currently saturated. The strategy itself has no
 * additional tunables beyond the algorithm — every adaptive-specific knob lives on the
 * {@code algorithm} sub-config.
 *
 * @param algorithm the limit algorithm; non-null.
 */
public record AdaptiveStrategyConfig(LimitAlgorithm algorithm)
        implements BulkheadStrategyConfig {

    public AdaptiveStrategyConfig {
        Objects.requireNonNull(algorithm, "algorithm");
    }
}
