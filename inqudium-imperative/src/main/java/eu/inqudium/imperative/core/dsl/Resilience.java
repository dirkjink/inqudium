package eu.inqudium.imperative.core.dsl;

import eu.inqudium.core.element.bulkhead.dsl.BulkheadNaming;
import eu.inqudium.core.element.bulkhead.dsl.DefaultBulkheadProtection;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder;

public final class Resilience {

    private Resilience() {
    }

    public static BulkheadNaming isolateWithBulkhead() {
        return new DefaultBulkheadProtection(InqImperativeBulkheadConfigBuilder.bulkhead());
    }
}