package eu.inqudium.core.element.bulkhead.strategy;

/**
 * The reason a bulkhead rejected a permit acquisition attempt.
 *
 * <p>Captured inside the strategy's decision logic at the exact moment of rejection,
 * so it always reflects the true cause — unlike a post-hoc snapshot of
 * {@link BulkheadStrategy#concurrentCalls()} which may already be stale.
 *
 * @since 0.3.0
 */
public enum RejectionReason {

    /**
     * The number of active calls had reached or exceeded the concurrency limit
     * at the instant the CAS or lock-guarded check was performed.
     *
     * <p>Applies to all strategy types (semaphore, atomic, adaptive).
     */
    CAPACITY_REACHED,

    /**
     * A permit was not available within the caller's specified timeout.
     *
     * <p>Only applies to {@link BlockingBulkheadStrategy} implementations. The caller
     * was willing to wait, but the bulkhead did not free a permit in time.
     */
    TIMEOUT_EXPIRED,

    /**
     * The CoDel algorithm determined that this request's sojourn time (time spent
     * waiting for a permit) exceeded the target delay for longer than one interval,
     * indicating sustained congestion.
     *
     * <p>A CoDel drop is fundamentally different from a capacity rejection: a permit
     * <em>was</em> available, but the request is rejected anyway to shed load and
     * break the congestion cycle. This distinction is critical for diagnostics —
     * seeing {@code CODEL_SOJOURN_EXCEEDED} with low active calls is expected behavior,
     * not a bug.
     *
     * <p>Only applies to {@link CoDelBulkheadStrategy}.
     */
    CODEL_SOJOURN_EXCEEDED
}
