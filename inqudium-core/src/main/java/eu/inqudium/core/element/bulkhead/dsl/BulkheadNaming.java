package eu.inqudium.core.element.bulkhead.dsl;

public interface BulkheadNaming {
  /**
   * Assigns a mandatory unique identifier to this Circuit Breaker.
   */
  BulkheadProtection named(String name);
}
