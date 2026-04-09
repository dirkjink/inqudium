package eu.inqudium.core.element.bulkhead.dsl;

public final class Resilience {

  private Resilience() {
  }

  // --- Bulkhead (NEU) ---
  public static BulkheadProtection isolateWithBulkhead() {
    return new DefaultBulkheadProtection();
  }
}