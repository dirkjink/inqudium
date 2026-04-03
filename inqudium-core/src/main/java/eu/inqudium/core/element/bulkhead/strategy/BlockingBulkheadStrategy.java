package eu.inqudium.core.element.bulkhead.strategy;


import java.time.Duration;

/**
 * Blocking variant of {@link BulkheadStrategy} for imperative (thread-per-request) paradigms.
 *
 * <p>The {@link #tryAcquire(Duration)} method may park the calling thread for up to
 * the specified timeout. This is the natural fit for synchronous code where the
 * calling thread can afford to block (especially with virtual threads).
 *
 * <p>All three built-in strategies implement this interface:
 * <ul>
 *   <li>{@link SemaphoreBulkheadStrategy} — {@link java.util.concurrent.Semaphore#tryAcquire(long, java.util.concurrent.TimeUnit)}</li>
 *   <li>{@link AdaptiveBulkheadStrategy} — {@link java.util.concurrent.locks.Condition#awaitNanos(long)}</li>
 *   <li>{@link CoDelBulkheadStrategy} — {@link java.util.concurrent.locks.Condition#awaitNanos(long)} with CoDel evaluation</li>
 * </ul>
 *
 * @see NonBlockingBulkheadStrategy
 * @since 0.3.0
 */
public interface BlockingBulkheadStrategy extends BulkheadStrategy {

  /**
   * Attempts to acquire a permit, potentially blocking up to the timeout.
   *
   * <p>Returns {@code null} if a permit was successfully acquired (the happy path —
   * no object allocation). Returns a {@link RejectionContext} captured at the exact
   * moment of rejection if the bulkhead denied the request.
   *
   * @param timeout the maximum duration to wait; {@link Duration#ZERO} for a non-blocking
   *                attempt (returns immediately if no permit is available)
   * @return {@code null} if a permit was acquired, or a {@link RejectionContext}
   * describing why the request was rejected
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  RejectionContext tryAcquire(Duration timeout) throws InterruptedException;
}
