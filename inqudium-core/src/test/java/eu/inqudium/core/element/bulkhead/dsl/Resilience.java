package eu.inqudium.core.element.bulkhead.dsl;

public final class Resilience {

    private Resilience() {
    }

    // --- Bulkhead (NEU) ---
    public static BulkheadNaming isolateWithBulkhead() {
        return new DefaultBulkheadProtection(null);
    }
}