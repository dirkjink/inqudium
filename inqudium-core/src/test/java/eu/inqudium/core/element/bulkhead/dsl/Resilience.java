package eu.inqudium.core.element.bulkhead.dsl;

import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;

public final class Resilience {

  private Resilience() {
  }

  // --- Bulkhead (NEU) ---
  public static BulkheadNaming isolateWithBulkhead() {
    return new DefaultBulkheadProtection(null);
  }
}