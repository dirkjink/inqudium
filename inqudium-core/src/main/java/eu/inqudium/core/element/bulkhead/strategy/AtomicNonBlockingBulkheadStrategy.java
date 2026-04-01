package eu.inqudium.core.element.bulkhead.strategy;

import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free, non-blocking bulkhead strategy for reactive and coroutine paradigms.
 *
 * <p>Uses a single {@link AtomicInteger} with CAS (Compare-And-Swap) for permit
 * management. No locks, no semaphores, no thread parking — every operation
 * completes in bounded time without blocking the calling thread.
 *
 * <h2>Why CAS instead of Semaphore?</h2>
 * <p>A {@link java.util.concurrent.Semaphore} with {@code tryAcquire()} (no timeout)
 * would also be non-blocking, but it carries unnecessary overhead:
 * <ul>
 *   <li>AQS infrastructure (node allocation, queue management) even though we never queue</li>
 *   <li>The over-release problem — {@code Semaphore.release()} unconditionally increments,
 *       requiring a shadow counter anyway</li>
 *   <li>Fairness semantics that are meaningless for instant yes/no decisions</li>
 * </ul>
 *
 * <p>A raw {@link AtomicInteger} with {@code compareAndSet} is the minimal primitive
 * for this use case: one volatile read + one CAS per acquire, one CAS per release.
 * The over-release guard is built into the CAS loop (decrement-if-positive).
 *
 * <h2>Reactive integration</h2>
 * <p>This strategy is designed for use with reactive bulkhead facades (Reactor, RxJava,
 * Kotlin Coroutines) where the acquire decision must be instant:
 * <pre>{@code
 * // Reactor example (pseudo-code)
 * Mono<T> decoratedMono = Mono.defer(() -> {
 *     if (!strategy.tryAcquire()) {
 *         return Mono.error(new InqBulkheadFullException(...));
 *     }
 *     return upstream
 *         .doOnTerminate(() -> strategy.release())
 *         .doOnCancel(() -> strategy.release());
 * });
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>All operations are wait-free in the absence of contention and lock-free under
 * contention. The CAS retry loop in {@link #tryAcquire()} and {@link #releaseInternal()}
 * is bounded by the number of concurrent threads — each failing CAS means another
 * thread succeeded, so progress is always made system-wide.
 *
 * <h2>Adaptive support</h2>
 * <p>The {@link #onCallComplete} hook is a no-op. For adaptive non-blocking strategies,
 * subclass or compose with an {@link InqLimitAlgorithm} and override the hook. The
 * max limit would then be read from the algorithm instead of the fixed
 * {@code maxConcurrentCalls} field. This class intentionally keeps things simple for
 * the static-limit case.
 *
 * @see NonBlockingBulkheadStrategy
 * @since 0.3.0
 */
public final class AtomicNonBlockingBulkheadStrategy implements NonBlockingBulkheadStrategy {

  private final int maxConcurrent;

  /**
   * The number of permits currently held. Managed exclusively via CAS.
   *
   * <p>Acquire: increment if current < maxConcurrent (CAS loop).
   * Release: decrement if current > 0 (CAS loop, over-release guard).
   *
   * <p>The value is always in [0, maxConcurrent] under correct usage. The
   * decrement-if-positive guard in {@link #releaseInternal()} prevents it
   * from going negative even under double-release bugs.
   */
  private final AtomicInteger activeCalls = new AtomicInteger(0);

  /**
   * Creates a non-blocking strategy with a fixed concurrency limit.
   *
   * @param maxConcurrentCalls the maximum number of concurrent permits;
   *                           0 creates a "closed" bulkhead that rejects everything
   * @throws IllegalArgumentException if maxConcurrentCalls is negative
   */
  public AtomicNonBlockingBulkheadStrategy(int maxConcurrentCalls) {
    if (maxConcurrentCalls < 0) {
      throw new IllegalArgumentException(
          "maxConcurrentCalls must be >= 0, got " + maxConcurrentCalls);
    }
    this.maxConcurrent = maxConcurrentCalls;
  }

  /**
   * Attempts to acquire a permit without blocking.
   *
   * <p>Uses a CAS loop to atomically increment {@code activeCalls} only if it
   * is below {@code maxConcurrent}. The loop is lock-free: each iteration either
   * succeeds (permit granted), fails definitively (at capacity), or retries
   * (another thread modified the counter concurrently — guaranteed progress).
   *
   * @return {@code true} if a permit was acquired, {@code false} if at capacity
   */
  @Override
  public boolean tryAcquire() {
    while (true) {
      int current = activeCalls.get();
      if (current >= maxConcurrent) {
        return false;
      }
      if (activeCalls.compareAndSet(current, current + 1)) {
        return true;
      }
      // CAS failed — another thread modified activeCalls concurrently.
      // Retry: re-read and re-evaluate. This is the standard lock-free
      // pattern; the loop terminates because every failing CAS means
      // another thread made progress.
    }
  }

  /**
   * Releases a previously acquired permit.
   *
   * <p>Uses the atomic decrement-if-positive pattern to prevent over-release:
   * if {@code activeCalls} is already 0 (no permits held), the CAS produces
   * a no-op instead of going negative.
   */
  @Override
  public void release() {
    releaseInternal();
  }

  @Override
  public void rollback() {
    releaseInternal();
  }

  /**
   * Atomically decrements if positive. This is the over-release guard:
   * double-release or release-without-acquire silently becomes a no-op
   * instead of corrupting the counter.
   */
  private void releaseInternal() {
    while (true) {
      int current = activeCalls.get();
      if (current <= 0) {
        return; // No permit held — over-release guard
      }
      if (activeCalls.compareAndSet(current, current - 1)) {
        return;
      }
      // CAS failed — retry
    }
  }

  @Override
  public int availablePermits() {
    return Math.max(0, maxConcurrent - activeCalls.get());
  }

  @Override
  public int concurrentCalls() {
    return activeCalls.get();
  }

  @Override
  public int maxConcurrentCalls() {
    return maxConcurrent;
  }
}
