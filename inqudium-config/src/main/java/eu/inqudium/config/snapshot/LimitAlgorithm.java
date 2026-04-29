package eu.inqudium.config.snapshot;

/**
 * Limit-algorithm choice for the adaptive bulkhead strategies.
 *
 * <p>Sealed so the {@link AdaptiveStrategyConfig} and
 * {@link AdaptiveNonBlockingStrategyConfig} materialization paths can switch exhaustively
 * between the two supported algorithms without an open-ended {@code default} branch. The two
 * permitted records carry the per-algorithm tunables and nothing else.
 */
public sealed interface LimitAlgorithm
        permits AimdLimitAlgorithmConfig, VegasLimitAlgorithmConfig {
}
