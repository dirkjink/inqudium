package eu.inqudium.core.bulkhead;

/**
 * Behavioral contract for bulkhead permit management.
 *
 * <p>Unlike other behaviors, the bulkhead has acquire/release semantics —
 * a permit is held for the duration of the call. Every successful
 * {@link #tryAcquire} must be paired with exactly one {@link #release}
 * in a finally block (ADR-020).
 *
 * <p>Pure function — no blocking, no synchronization. The paradigm module
 * owns the state and provides thread-safety.
 *
 * @since 0.1.0
 */
public interface BulkheadBehavior {

    /**
     * Attempts to acquire a concurrency permit.
     *
     * @param state  current bulkhead state
     * @param config bulkhead configuration
     * @return result with permit status and updated state
     */
    BulkheadResult tryAcquire(BulkheadState state, BulkheadConfig config);

    /**
     * Releases a previously acquired permit.
     *
     * <p>Must be called exactly once per successful {@link #tryAcquire},
     * in a finally block.
     *
     * @param state current bulkhead state
     * @return updated state with decremented concurrent call count
     */
    BulkheadState release(BulkheadState state);

    /**
     * Returns the default semaphore-based behavior.
     *
     * @return the default behavior
     */
    static BulkheadBehavior defaultBehavior() {
        return DefaultBulkheadBehavior.INSTANCE;
    }
}

/**
 * Default semaphore-based bulkhead implementation.
 */
final class DefaultBulkheadBehavior implements BulkheadBehavior {

    static final DefaultBulkheadBehavior INSTANCE = new DefaultBulkheadBehavior();

    private DefaultBulkheadBehavior() {}

    @Override
    public BulkheadResult tryAcquire(BulkheadState state, BulkheadConfig config) {
        if (state.concurrentCalls() < config.getMaxConcurrentCalls()) {
            var newState = new BulkheadState(state.concurrentCalls() + 1);
            return BulkheadResult.permitted(newState);
        }
        return BulkheadResult.denied(state);
    }

    @Override
    public BulkheadState release(BulkheadState state) {
        int updated = Math.max(0, state.concurrentCalls() - 1);
        return new BulkheadState(updated);
    }
}
