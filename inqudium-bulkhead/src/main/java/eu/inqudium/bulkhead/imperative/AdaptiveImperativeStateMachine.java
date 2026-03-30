package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.AbstractBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.bulkhead.InqLimitAlgorithm;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An imperative state machine that adjusts its capacity dynamically using a limit algorithm.
 */
public final class AdaptiveImperativeStateMachine extends AbstractBulkheadStateMachine {

  private final InqLimitAlgorithm limitAlgorithm;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notFull = lock.newCondition();
  private int activeCalls = 0;

  public AdaptiveImperativeStateMachine(String name, BulkheadConfig config, InqLimitAlgorithm limitAlgorithm) {
    super(name, config);
    this.limitAlgorithm = limitAlgorithm;
  }

  /**
   * FIX #7: Return the dynamic limit from the algorithm, not the static config value.
   *
   * <p>Without this override, callers (including {@code InqBulkheadFullException}) would
   * see the initial config value, which becomes misleading once the algorithm has
   * adjusted the limit up or down.
   */
  @Override
  public int getMaxConcurrentCalls() {
    return limitAlgorithm.getLimit();
  }

  /**
   * FIX #4: Changed from {@code lock.tryLock()} to {@code lock.lock()} to prevent
   * false rejection events caused by lock contention.
   *
   * <p>The original implementation would treat a failed {@code tryLock()} (another thread
   * momentarily holding the lock) as a bulkhead-full rejection, publishing a spurious
   * {@code BulkheadOnRejectEvent} and returning false — even when plenty of permits
   * were available. This corrupted metrics and caused false monitoring alerts.
   *
   * <p>Using {@code lock.lock()} is acceptable for a "non-blocking" acquire because the
   * lock is only held for the brief duration of the counter check, not for the downstream
   * call execution. The worst-case wait is microseconds, not the milliseconds/seconds
   * of a true blocking acquire.
   */
  @Override
  public boolean tryAcquireNonBlocking(String callId) {
    lock.lock();
    try {
      if (activeCalls < limitAlgorithm.getLimit()) {
        activeCalls++;
        return handleAcquireSuccess(callId);
      }
    } finally {
      lock.unlock();
    }
    handleAcquireFailure(callId);
    return false;
  }

  @Override
  public boolean tryAcquireBlocking(String callId, Duration timeout) throws InterruptedException {
    long nanos = timeout.toNanos();
    lock.lockInterruptibly();
    try {
      while (activeCalls >= limitAlgorithm.getLimit()) {
        if (nanos <= 0L) {
          handleAcquireFailure(callId);
          return false;
        }
        nanos = notFull.awaitNanos(nanos);
      }
      activeCalls++;
      return handleAcquireSuccess(callId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleAcquireFailure(callId);
      throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), limitAlgorithm.getLimit());
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void onCallComplete(Duration rtt, Throwable error) {
    // Feed the outcome back to the adaptive algorithm to adjust limits
    limitAlgorithm.update(rtt, error == null);
  }

  @Override
  protected void releasePermitInternal() {
    lock.lock();
    try {
      if (activeCalls > 0) {
        activeCalls--;
        notFull.signal(); // Wake up one waiting thread since a permit freed up
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void rollbackPermit() {
    releasePermitInternal();
  }

  @Override
  public int getAvailablePermits() {
    lock.lock();
    try {
      return Math.max(0, limitAlgorithm.getLimit() - activeCalls);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int getConcurrentCalls() {
    lock.lock();
    try {
      return activeCalls;
    } finally {
      lock.unlock();
    }
  }
}
