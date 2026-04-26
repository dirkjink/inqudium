package eu.inqudium.imperative.core.dsl;

import eu.inqudium.core.element.bulkhead.dsl.BulkheadNaming;
import eu.inqudium.core.element.bulkhead.dsl.DefaultBulkheadProtection;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder;

/**
 * @deprecated Replaced by {@code Inqudium.configure()} (ADR-025). The legacy {@code Resilience}
 *             DSL stays in place because nothing yet imports its replacement at the call sites
 *             that still rely on the old bulkhead/circuit-breaker stacks. Removed alongside
 *             those stacks in REFACTORING.md step 3.1.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
@SuppressWarnings("deprecation")
public final class Resilience {

    private Resilience() {
    }

    public static BulkheadNaming isolateWithBulkhead() {
        return new DefaultBulkheadProtection(InqImperativeBulkheadConfigBuilder.bulkhead());
    }
}