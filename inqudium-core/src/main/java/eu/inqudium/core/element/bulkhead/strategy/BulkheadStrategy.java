package eu.inqudium.core.element.bulkhead.strategy;

import java.time.Duration;

/**
 * Base interface for bulkhead permit management strategies.
 *
 * <p>Defines the shared lifecycle (release, rollback, feedback) and introspection
 * methods that all strategies — blocking and non-blocking — must provide.
 *
 * <p>The permit acquisition method is deliberately absent from this interface
 * because its signature differs fundamentally between paradigms:
 * <ul>
 *   <li>{@link BlockingBulkheadStrategy#tryAcquire(Duration)} — may park the
 *       calling thread for the given timeout; throws {@link InterruptedException}</li>
 *   <li>{@link NonBlockingBulkheadStrategy#tryAcquire()} — immediate yes/no decision;
 *       never blocks, never throws</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>All implementations must be thread-safe. The facade calls {@link #release()}
 * and {@link #onCallComplete} from arbitrary threads concurrently.
 *
 * @see BlockingBulkheadStrategy
 * @see NonBlockingBulkheadStrategy
 * @since 0.3.0
 */
public interface BulkheadStrategy {

    /**
     * Releases a previously acquired permit.
     *
     * <p>Must be safe to call even if no permit was acquired (over-release guard).
     * Implementations must never throw — a release failure indicates a state machine
     * defect and is handled by the facade.
     */
    void release();

    /**
     * Rolls back a permit that was acquired but whose telemetry (acquire event)
     * could not be published. Mechanically identical to {@link #release()} but
     * semantically distinct: the business call never started.
     */
    void rollback();

    /**
     * Hook for adaptive strategies to receive execution feedback.
     *
     * <p>Called after the business call completes, <em>before</em> {@link #release()}.
     * The ordering matters for adaptive strategies: calling {@code release()} first would
     * cause the algorithm to see an artificially low in-flight count. Prefer the
     * {@code completeAndRelease(long, boolean)} method on concrete adaptive strategies
     * (where available) to guarantee correct ordering.
     *
     * <p>Static strategies ignore this (no-op default).
     *
     * <p><b>Zero/negative RTT:</b> If {@code rttNanos} is zero or negative (e.g., because
     * {@link System#nanoTime()} returned the same value for start and end), the adaptive
     * algorithm will silently discard the sample. The call is not counted toward any
     * limit adjustment. Callers should be aware that on some platforms,
     * {@code System.nanoTime()} has microsecond granularity, making zero-duration
     * measurements possible for very fast calls.
     *
     * @param rttNanos  the round-trip time of the business call in nanoseconds;
     *                  must be positive for the sample to be processed
     * @param isSuccess {@code true} if the call succeeded
     */
    default void onCallComplete(long rttNanos, boolean isSuccess) {
        // No-op for static strategies
    }

    /**
     * Returns the number of permits currently available for immediate acquisition.
     * Point-in-time snapshot for monitoring only.
     */
    int availablePermits();

    /**
     * Returns the number of calls currently holding a permit.
     * Point-in-time snapshot for monitoring only.
     */
    int concurrentCalls();

    /**
     * Returns the effective maximum concurrent calls.
     * For static strategies, this is the configured value.
     * For adaptive strategies, this is the current algorithm-computed limit.
     */
    int maxConcurrentCalls();
}
