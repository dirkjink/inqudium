package eu.inqudium.core.bulkhead.strategy;

import eu.inqudium.core.bulkhead.algo.InqLimitAlgorithm;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Adaptive bulkhead strategy with a dynamically adjusted concurrency limit.
 *
 * <p>Uses a pluggable {@link InqLimitAlgorithm} (AIMD, Vegas) that recalculates
 * the optimal limit after every completed call. The scheduling primitive is a
 * {@link ReentrantLock} with a {@link Condition}, because a {@link java.util.concurrent.Semaphore}
 * cannot be atomically resized.
 *
 * <h2>Wait loop condition</h2>
 * <p>The condition {@code activeCalls >= limitAlgorithm.getLimit()} is re-evaluated
 * on every wakeup, naturally adapting to limit changes:
 * <ul>
 *   <li>Limit increase → waiting thread wakes and may now acquire</li>
 *   <li>Limit decrease → waiting thread re-checks, finds still over-capacity, loops</li>
 * </ul>
 *
 * <h2>Targeted wakeup on limit increase</h2>
 * <p>When the limit increases by N, exactly N threads are signaled (not signalAll)
 * to avoid thundering herd effects.
 *
 * <h2>Thread safety</h2>
 * <p>All mutable state ({@code activeCalls}, {@code oldLimit}) is protected by the lock.
 * The {@link InqLimitAlgorithm} is thread-safe by contract (CAS-based internally).
 *
 * @since 0.3.0
 */
public final class AdaptiveBulkheadStrategy implements BulkheadStrategy {

  private final InqLimitAlgorithm limitAlgorithm;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notFull = lock.newCondition();

  // Mutable state — all protected by lock
  private int activeCalls = 0;
  private int oldLimit;

  public AdaptiveBulkheadStrategy(InqLimitAlgorithm limitAlgorithm) {
    this.limitAlgorithm = Objects.requireNonNull(limitAlgorithm, "limitAlgorithm must not be null");
    this.oldLimit = limitAlgorithm.getLimit();
  }

  @Override
  public boolean tryAcquire(Duration timeout) throws InterruptedException {
    long nanos = timeout.toNanos();

    lock.lockInterruptibly();
    try {
      // Wait loop: park until activeCalls < dynamic limit
      while (activeCalls >= limitAlgorithm.getLimit()) {
        if (nanos <= 0L) {
          // Timeout — pass the baton to the next waiter
          notFull.signal();
          return false;
        }
        nanos = notFull.awaitNanos(nanos);
      }

      // Capacity available — grant the permit
      activeCalls++;
      return true;

    } catch (InterruptedException e) {
      // Pass the baton: the interruption consumed the signal
      notFull.signal();
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
   * Feeds the call outcome to the algorithm, detects limit changes, and signals waiters.
   *
   * <p>The algorithm update is performed outside the lock (it's CAS-based internally).
   * The limit change detection and signaling are under the lock.
   */
  @Override
  public void onCallComplete(Duration rtt, boolean isSuccess) {
    // Step 1: Feed the algorithm (outside lock)
    limitAlgorithm.update(rtt, isSuccess);

    // Step 2: Detect and react to limit changes (under lock)
    lock.lock();
    try {
      int newLimit = limitAlgorithm.getLimit();
      int capturedOldLimit = oldLimit;

      if (capturedOldLimit != newLimit) {
        oldLimit = newLimit;

        // Targeted wakeup: signal exactly the number of newly created slots
        if (newLimit > capturedOldLimit) {
          int newlyAvailableSlots = newLimit - capturedOldLimit;
          for (int i = 0; i < newlyAvailableSlots; i++) {
            notFull.signal();
          }
        }
        // On decrease: waiters will re-evaluate on their next awaitNanos return
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
