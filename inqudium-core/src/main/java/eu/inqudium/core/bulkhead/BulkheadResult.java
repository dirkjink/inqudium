package eu.inqudium.core.bulkhead;

/**
 * Result of a bulkhead permit acquisition attempt.
 *
 * @param permitted    whether the call is allowed to proceed
 * @param updatedState the bulkhead state after this attempt
 * @since 0.1.0
 */
public record BulkheadResult(boolean permitted, BulkheadState updatedState) {

  public static BulkheadResult permitted(BulkheadState state) {
    return new BulkheadResult(true, state);
  }

  public static BulkheadResult denied(BulkheadState state) {
    return new BulkheadResult(false, state);
  }
}
