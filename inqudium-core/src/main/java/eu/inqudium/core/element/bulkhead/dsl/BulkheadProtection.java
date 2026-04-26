package eu.inqudium.core.element.bulkhead.dsl;

import java.time.Duration;

/**
 * @deprecated Replaced by {@link eu.inqudium.config.dsl.BulkheadBuilderBase} as part of the
 *             configuration redesign (ADR-025). Retained because {@link BulkheadNaming} and
 *             {@link DefaultBulkheadProtection} still reference it; removed alongside the
 *             legacy bulkhead DSL in REFACTORING.md step 3.1.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public interface BulkheadProtection {

    // Modifiers
    BulkheadProtection limitingConcurrentCallsTo(int maxCalls);

    BulkheadProtection waitingAtMostFor(Duration maxWait);

    // Terminal Operations (Profiles)
    BulkheadConfig applyProtectiveProfile();

    BulkheadConfig applyBalancedProfile();

    BulkheadConfig applyPermissiveProfile();

    // Terminal Operation for custom configuration
    BulkheadConfig apply();
}
