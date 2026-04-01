package eu.inqudium.core.element.bulkhead.strategy;

import eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.algo.VegasLimitAlgorithm;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free, non-blocking adaptive bulkhead strategy for reactive and coroutine paradigms.
 *
 * <p>Combines the CAS-based permit management of {@link AtomicNonBlockingBulkheadStrategy}
 * with a dynamic concurrency limit from a pluggable {@link InqLimitAlgorithm} (AIMD, Vegas).
 *
 * <h2>How it works</h2>
 * <p>The {@link #tryAcquire()} CAS loop reads the algorithm's current limit on every
 * iteration via {@link InqLimitAlgorithm#getLimit()}, which is a single volatile read
 * (both {@link AimdLimitAlgorithm} and {@link VegasLimitAlgorithm} store their state
 * in an {@link java.util.concurrent.atomic.AtomicReference}). This means the acquire
 * decision naturally adapts to limit changes without any locking or signaling — a
 * fundamental advantage over the blocking {@code AdaptiveBulkheadStrategy}, which needs
 * a {@link java.util.concurrent.locks.Condition} to wake parked threads on limit changes.
 *
 * <h2>Feedback loop</h2>
 * <p>The reactive facade must call {@link #onCallComplete(Duration, boolean)} after every
 * completed request to feed the algorithm:
 * <pre>{@code
 * // Reactor example (pseudo-code)
 * Mono<T> decorated = Mono.defer(() -> {
 *     if (!strategy.tryAcquire()) {
 *         return Mono.error(new InqBulkheadFullException(...));
 *     }
 *     long start = System.nanoTime();
 *     return upstream
 *         .doOnSuccess(v -> {
 *             strategy.onCallComplete(Duration.ofNanos(System.nanoTime() - start), true);
 *             strategy.release();
 *         })
 *         .doOnError(e -> {
 *             strategy.onCallComplete(Duration.ofNanos(System.nanoTime() - start), false);
 *             strategy.release();
 *         })
 *         .doOnCancel(() -> strategy.release());
 * });
 * }</pre>
 *
 * <h2>Over-limit transient state</h2>
 * <p>When the algorithm decreases the limit (e.g., from 20 to 10) while 15 calls are
 * in flight, {@code activeCalls} (15) exceeds the new limit (10). The strategy does
 * <strong>not</strong> forcibly revoke already-granted permits — it simply refuses new
 * ones until enough in-flight calls complete to bring the count below the new limit.
 * This is the same behavior as the blocking {@code AdaptiveBulkheadStrategy}.
 *
 * <h2>Thread safety</h2>
 * <p>All operations are lock-free. {@code activeCalls} is managed via CAS on an
 * {@link AtomicInteger}. The {@link InqLimitAlgorithm} is thread-safe by contract
 * (CAS-based internally). No coordination between the two is needed because the
 * CAS loop re-reads the limit on every iteration.
 *
 * @see AtomicNonBlockingBulkheadStrategy
 * @since 0.3.0
 */
public final class AdaptiveNonBlockingBulkheadStrategy implements NonBlockingBulkheadStrategy {

  private final InqLimitAlgorithm limitAlgorithm;
  private final AtomicInteger activeCalls = new AtomicInteger(0);

  /**
   * Creates an adaptive non-blocking strategy.
   *
   * @param limitAlgorithm the algorithm computing the dynamic concurrency limit
   *                       (e.g., {@link AimdLimitAlgorithm}, {@link VegasLimitAlgorithm})
   */
  public AdaptiveNonBlockingBulkheadStrategy(InqLimitAlgorithm limitAlgorithm) {
    this.limitAlgorithm = Objects.requireNonNull(limitAlgorithm, "limitAlgorithm must not be null");
  }

  /**
   * Attempts to acquire a permit without blocking.
   *
   * <p>CAS loop: reads the algorithm's current limit and the active count on each
   * iteration. Succeeds if {@code activeCalls < limit}, fails immediately if at or
   * over capacity.
   *
   * <p>The limit is read inside the loop (not cached before it) because the algorithm
   * may adjust it concurrently via {@link #onCallComplete}. A stale limit read is
   * harmless: the CAS will either succeed (correct decision) or fail and retry with
   * a fresh read.
   *
   * @return {@code true} if a permit was acquired, {@code false} if at capacity
   */
  @Override
  public boolean tryAcquire() {
    while (true) {
      int current = activeCalls.get();
      int limit = limitAlgorithm.getLimit();
      if (current >= limit) {
        return false;
      }
      if (activeCalls.compareAndSet(current, current + 1)) {
        return true;
      }
      // CAS failed — another thread modified activeCalls. Retry with fresh reads.
    }
  }

  @Override
  public void release() {
    releaseInternal();
  }

  @Override
  public void rollback() {
    releaseInternal();
  }

  /**
   * Feeds the call outcome to the algorithm for limit recalculation.
   *
   * <p>This is what makes this strategy adaptive. Without calling this method,
   * the algorithm never updates and the strategy behaves like a static limiter
   * at the algorithm's initial limit.
   *
   * <p>The algorithm's {@link InqLimitAlgorithm#update(Duration, boolean)} is
   * CAS-based internally — no lock needed. The updated limit is visible to
   * concurrent {@link #tryAcquire()} calls on the next CAS iteration.
   */
  @Override
  public void onCallComplete(Duration rtt, boolean isSuccess) {
    limitAlgorithm.update(rtt, isSuccess);
  }

  @Override
  public int availablePermits() {
    return Math.max(0, limitAlgorithm.getLimit() - activeCalls.get());
  }

  @Override
  public int concurrentCalls() {
    return activeCalls.get();
  }

  /**
   * Returns the algorithm's current dynamic limit.
   * May differ between consecutive calls as the algorithm adjusts.
   */
  @Override
  public int maxConcurrentCalls() {
    return limitAlgorithm.getLimit();
  }

  /**
   * Atomic decrement-if-positive. Over-release guard: if {@code activeCalls}
   * is already 0, the CAS loop is a no-op.
   */
  private void releaseInternal() {
    while (true) {
      int current = activeCalls.get();
      if (current <= 0) {
        return;
      }
      if (activeCalls.compareAndSet(current, current - 1)) {
        return;
      }
    }
  }
}
