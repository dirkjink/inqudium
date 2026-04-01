package eu.inqudium.core.bulkhead.strategy;

import java.time.Duration;

/**
 * Strategy interface for bulkhead permit management.
 *
 * <p>Implementations define <em>how</em> concurrent access is controlled:
 * which concurrency primitive is used (Semaphore, ReentrantLock + Condition),
 * whether the limit is static or dynamic, and whether queue management
 * (e.g., CoDel) is applied.
 *
 * <p><strong>Contract:</strong> Implementations handle only permit mechanics.
 * All telemetry (events, traces, rollback traces) is managed by the
 * {@link eu.inqudium.imperative.bulkhead.imperative.ImperativeBulkhead} facade.
 * This separation ensures that a crashing event publisher never corrupts
 * the permit state.
 *
 * <h2>Built-in strategies</h2>
 * <table>
 *   <tr><th>Strategy</th><th>Limit</th><th>Primitive</th><th>Use case</th></tr>
 *   <tr><td>{@link SemaphoreBulkheadStrategy}</td><td>Static</td><td>Semaphore (fair)</td>
 *       <td>Known, stable downstream capacity</td></tr>
 *   <tr><td>{@link AdaptiveBulkheadStrategy}</td><td>Dynamic</td><td>ReentrantLock + Condition</td>
 *       <td>Unknown/variable capacity (AIMD, Vegas)</td></tr>
 *   <tr><td>{@link CoDelBulkheadStrategy}</td><td>Static</td><td>ReentrantLock + Condition</td>
 *       <td>Queue depth is the bottleneck</td></tr>
 * </table>
 *
 * <h2>Thread safety</h2>
 * <p>All implementations must be thread-safe. The facade calls {@link #tryAcquire}
 * and {@link #release} from arbitrary threads concurrently.
 *
 * @since 0.3.0
 */
public interface BulkheadStrategy {

  /**
   * Attempts to acquire a permit, potentially blocking up to the timeout.
   *
   * @param timeout the maximum duration to wait; {@link Duration#ZERO} for non-blocking
   * @return {@code true} if a permit was acquired, {@code false} if the bulkhead is full
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  boolean tryAcquire(Duration timeout) throws InterruptedException;

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
   * <p>Called after the business call completes, before {@link #release()}.
   * Static strategies ignore this (no-op default).
   *
   * @param rtt       the round-trip time of the business call
   * @param isSuccess {@code true} if the call succeeded
   */
  default void onCallComplete(Duration rtt, boolean isSuccess) {
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
