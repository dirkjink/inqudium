package eu.inqudium.config.snapshot;

/**
 * Strategy choice carried by a {@link BulkheadSnapshot} (ADR-032).
 *
 * <p>Sealed so the materialization path in the imperative paradigm — and any future paradigm
 * that grows its own bulkhead — can switch exhaustively over the four supported strategies
 * without an open-ended {@code default} branch. Each permitted record carries the
 * configuration its strategy needs and nothing else: the semaphore variant has no fields,
 * CoDel carries its target-delay and interval, the adaptive variants carry a
 * {@link LimitAlgorithm} sub-config.
 *
 * <p>The strategy is selected at hot-phase materialization time via a paradigm-private
 * factory. Adding a new strategy variant therefore touches three places: the new config record
 * (here), the materialization switch in the paradigm's strategy factory, and the strategy
 * implementation itself. The sealed-type discipline ensures a missing branch in the factory
 * is a compile-time error.
 */
public sealed interface BulkheadStrategyConfig
        permits SemaphoreStrategyConfig,
                CoDelStrategyConfig,
                AdaptiveStrategyConfig,
                AdaptiveNonBlockingStrategyConfig {
}
