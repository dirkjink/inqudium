package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;

import java.time.Duration;

/**
 * @deprecated Orphaned by REFACTORING.md step 1.10 — its only consumer was the legacy
 *             {@code InqBulkheadConfig} pipeline. Will be either reintroduced in the new
 *             shape (likely a sub-record on {@code BulkheadSnapshot}) or deleted when the
 *             strategy hot-swap design settles in phase&nbsp;2 (REFACTORING.md
 *             step&nbsp;2.10).
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public record CoDelBulkheadStrategyConfig(Duration targetDelay,
                                          Duration interval) implements ConfigExtension<CoDelBulkheadStrategyConfig> {
    @Override
    public CoDelBulkheadStrategyConfig self() {
        return this;
    }
}
