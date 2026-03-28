package eu.inqudium.core.bulkhead;

/**
 * State of the bulkhead — the current number of in-flight concurrent calls.
 *
 * @param concurrentCalls current number of calls holding a permit
 * @since 0.1.0
 */
public record BulkheadState(int concurrentCalls) {

    /** Creates the initial state with zero concurrent calls. */
    public static BulkheadState initial() {
        return new BulkheadState(0);
    }
}
