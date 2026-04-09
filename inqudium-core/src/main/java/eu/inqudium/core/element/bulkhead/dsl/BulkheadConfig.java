package eu.inqudium.core.element.bulkhead.dsl;

import eu.inqudium.core.config.InqConfig;

import java.time.Duration;

/**
 * The immutable configuration for a Bulkhead instance.
 */
public record BulkheadConfig(
    int maxConcurrentCalls,
    Duration maxWaitDuration,
    InqConfig inqConfig
) {


}
