package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;

import java.time.Duration;

public record CoDelBulkheadStrategyConfig(Duration targetDelay,
                                          Duration interval) implements ConfigExtension<CoDelBulkheadStrategyConfig> {
    @Override
    public CoDelBulkheadStrategyConfig self() {
        return this;
    }
}
