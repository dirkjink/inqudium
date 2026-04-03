package eu.inqudium.imperative.bulkhead.strategy;

import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Adaptive bulkhead strategy with a dynamically adjusted concurrency limit.
 *
 * <p>Uses a pluggable {@link InqLimitAlgorithm} (AIMD, Vegas) that recalculates
 * the optimal limit after every completed call. The scheduling primitive is a
 * {@link ReentrantLock} with a {@link Condition}, because a Semaphore cannot be
 * atomically resized.
 *
 * <h2>Targeted wakeup on limit increase</h2>
 * <p>When the limit increases by N, exactly N threads are signaled (not signalAll)
 * to avoid thundering herd effects.
 *
 * @since 0.3.0
 */
public final class AdaptiveBulkheadStrategy implements BlockingBulkheadStrategy {

  private final InqLimitAlgorithm limitAlgorithm;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notFull = lock.newCondition();

  /**
   * Number of calls currently holding a permit. Volatile because it is read
   * outside the lock in {@link #onCallComplete} to provide a snapshot to the
   * algorithm. All writes happen under the lock.
   */
  private volatile int activeCalls = 0;

  /**
   * The algorithm's limit as of the last {@link #onCallComplete} invocation.
   * Used to detect limit changes and signal waiting threads. Only accessed
   * under the lock — no volatile needed.
   */
  private int oldLimit;

  public AdaptiveBulkheadStrategy(InqLimitAlgorithm limitAlgorithm) {
    this.limitAlgorithm = Objects.requireNonNull(limitAlgorithm, "limitAlgorithm must not be null");
    this.oldLimit = limitAlgorithm.getLimit();
  }

  @Override
  public RejectionContext tryAcquire(Duration timeout) throws InterruptedException {
    long nanos = timeout.toNanos();
    long startNanos = System.nanoTime();

    lock.lockInterruptibly();
    try {
      while (activeCalls >= limitAlgorithm.getLimit()) {
        if (nanos <= 0L) {
          int limit = limitAlgorithm.getLimit();
          int active = activeCalls;
          notFull.signal(); // pass the baton

          if (timeout.isZero()) {
            return RejectionContext.capacityReached(limit, active);
          }
          long waitedNanos = System.nanoTime() - startNanos;
          return RejectionContext.timeoutExpired(limit, active, waitedNanos);
        }
        nanos = notFull.awaitNanos(nanos);
      }
      activeCalls++;
      return null; // permit acquired — no allocation

    } catch (InterruptedException e) {
      notFull.signal(); // pass the baton
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void release() {
    lock.lock();
    try {
      if (activeCalls > 0) {
        activeCalls--;
        notFull.signal();
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void rollback() {
    release();
  }

  /**
   * Combined feedback and release in the correct order.
   *
   * <p>Guarantees that the algorithm receives the in-flight count <em>before</em>
   * the permit is released, and that the permit is always released even if the
   * algorithm update throws.
   *
   * @param rttNanos  the round-trip time of the completed call in nanoseconds
   * @param isSuccess {@code true} if the call succeeded
   */
  public void completeAndRelease(long rttNanos, boolean isSuccess) {
    try {
      onCallComplete(rttNanos, isSuccess);
    } finally {
      release();
    }
  }

  @Override
  public void onCallComplete(long rttNanos, boolean isSuccess) {
    // Step 1: Feed the algorithm (outside lock — algorithm is CAS-based)
    limitAlgorithm.update(rttNanos, isSuccess, activeCalls);

    // Step 2: Detect and react to limit changes (under lock)
    lock.lock();
    try {
      int newLimit = limitAlgorithm.getLimit();
      int capturedOldLimit = oldLimit;

      if (capturedOldLimit != newLimit) {
        oldLimit = newLimit;

        if (newLimit > capturedOldLimit) {
          int newlyAvailableSlots = newLimit - capturedOldLimit;
          for (int i = 0; i < newlyAvailableSlots; i++) {
            notFull.signal();
          }
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int availablePermits() {
    lock.lock();
    try {
      return Math.max(0, limitAlgorithm.getLimit() - activeCalls);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int concurrentCalls() {
    lock.lock();
    try {
      return activeCalls;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int maxConcurrentCalls() {
    return limitAlgorithm.getLimit();
  }
}
