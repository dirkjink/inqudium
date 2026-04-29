package eu.inqudium.core.element.bulkhead.dsl;

import eu.inqudium.core.config.InqConfig;

import java.time.Duration;

/**
 * The immutable configuration for a Bulkhead instance.
 *
 * @deprecated Replaced by {@link eu.inqudium.config.snapshot.BulkheadSnapshot} as part of the
 *             configuration redesign (ADR-025). Retained because the legacy DSL types
 *             {@link BulkheadNaming} / {@link BulkheadProtection} / {@link DefaultBulkheadProtection}
 *             still reference it; removed alongside the legacy bulkhead DSL once the
 *             top-level {@code Resilience} DSL is dismantled.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public record BulkheadConfig(
        String name,
        int maxConcurrentCalls,
        Duration maxWaitDuration,
        InqConfig inqConfig
) {
}
