package eu.inqudium.core.element.bulkhead.strategy;

/**
 * Non-blocking variant of {@link BulkheadStrategy} for reactive and coroutine paradigms.
 *
 * <p>The {@link #tryAcquire()} method returns immediately with a yes/no decision —
 * it never parks the calling thread. This is the required contract for paradigms
 * where blocking is prohibited:
 * <ul>
 *   <li><strong>Project Reactor / RxJava:</strong> The subscription callback runs on
 *       an event loop thread that must not block. Permit acquisition must be instant;
 *       if no permit is available, the {@code Mono} is completed with an error signal.</li>
 *   <li><strong>Kotlin Coroutines:</strong> Suspension points use cooperative scheduling,
 *       not thread blocking. A non-blocking check followed by {@code delay()} for retry
 *       fits the model; a blocking {@code tryAcquire(timeout)} would pin the carrier thread.</li>
 * </ul>
 *
 * <p>Implementations should use lock-free primitives (e.g., {@link java.util.concurrent.atomic.AtomicInteger}
 * with CAS) to avoid any form of thread contention on the acquire path.
 *
 * @see BlockingBulkheadStrategy
 * @since 0.3.0
 */
public interface NonBlockingBulkheadStrategy extends BulkheadStrategy {

  /**
   * Attempts to acquire a permit without blocking.
   *
   * <p>Returns immediately. If a permit is available, it is claimed atomically
   * and {@code true} is returned. If the bulkhead is at capacity, {@code false}
   * is returned without any waiting or retry.
   *
   * @return {@code true} if a permit was acquired, {@code false} if full
   */
  boolean tryAcquire();
}
