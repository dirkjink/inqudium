package eu.inqudium.config.snapshot;

/**
 * Marker config for the semaphore-based bulkhead strategy.
 *
 * <p>Carries no fields: the semaphore strategy is fully described by the snapshot's
 * {@link BulkheadSnapshot#maxConcurrentCalls() maxConcurrentCalls} and
 * {@link BulkheadSnapshot#maxWaitDuration() maxWaitDuration}; it has no strategy-specific
 * tunables. Choosing this config is the documented "no special strategy" default and what
 * every snapshot built without an explicit strategy DSL gets.
 */
public record SemaphoreStrategyConfig() implements BulkheadStrategyConfig {
}
