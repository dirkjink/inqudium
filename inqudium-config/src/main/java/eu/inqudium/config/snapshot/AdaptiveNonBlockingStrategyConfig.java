package eu.inqudium.config.snapshot;

import java.util.Objects;

/**
 * Configuration for the non-blocking adaptive bulkhead strategy.
 *
 * <p>Identical in shape to {@link AdaptiveStrategyConfig} — the limit decision is delegated
 * to the embedded {@link LimitAlgorithm} — but the strategy fails fast when the limit is
 * saturated rather than blocking the caller for up to {@code maxWaitDuration}. The two
 * variants are kept as separate sealed-interface implementations so the materialization
 * switch can dispatch to two distinct strategy implementations without inspecting an extra
 * boolean.
 *
 * @param algorithm the limit algorithm; non-null.
 */
public record AdaptiveNonBlockingStrategyConfig(LimitAlgorithm algorithm)
        implements BulkheadStrategyConfig {

    public AdaptiveNonBlockingStrategyConfig {
        Objects.requireNonNull(algorithm, "algorithm");
    }
}
