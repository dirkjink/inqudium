package eu.inqudium.core.element.bulkhead.dsl;

/**
 * @deprecated Replaced by {@link eu.inqudium.config.dsl.BulkheadBuilderBase}'s
 *             constructor-arg name and the surrounding {@code Inqudium.configure()} DSL
 *             (ADR-025). Retained because {@link DefaultBulkheadProtection} and the legacy
 *             top-level {@code Resilience} DSL still reference it; removed alongside the
 *             legacy bulkhead DSL once the {@code Resilience} DSL is dismantled.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public interface BulkheadNaming {
    /**
     * Assigns a mandatory unique identifier to this Circuit Breaker.
     */
    BulkheadProtection named(String name);
}
