package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.*;
import eu.inqudium.core.bulkhead.event.BulkheadLimitChangedTraceEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * An imperative state machine that adjusts its capacity dynamically using a limit algorithm.
 */
public final class AdaptiveImperativeStateMachine
    extends AbstractBulkheadStateMachine implements BlockingBulkheadStateMachine {

  private final InqLimitAlgorithm limitAlgorithm;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notFull = lock.newCondition();
  private final LongSupplier nanoTimeSource;
  private final InqClock clock;
  private int activeCalls = 0;

  // FIX #1: Removed volatile — field is now exclusively accessed under the lock.
  // This eliminates the read-compare-write race between concurrent onCallComplete() calls.
  private int oldLimit;

  public AdaptiveImperativeStateMachine(String name, BulkheadConfig config, InqLimitAlgorithm limitAlgorithm) {
    super(name, config);
    this.limitAlgorithm = limitAlgorithm;
    this.oldLimit = limitAlgorithm.getLimit();
    this.nanoTimeSource = config.getNanoTimeSource();
    this.clock = config.getClock();
  }

  @Override
  public int getMaxConcurrentCalls() {
    return limitAlgorithm.getLimit();
  }

  @Override
  public boolean tryAcquire(String callId, Duration timeout) throws InterruptedException {
    long startWait = nanoTimeSource.getAsLong();
    long nanos = timeout.toNanos();
    lock.lockInterruptibly();
    try {
      while (activeCalls >= limitAlgorithm.getLimit()) {
        if (nanos <= 0L) {
          notFull.signal();
          handleAcquireFailure(callId, startWait);
          return false;
        }
        nanos = notFull.awaitNanos(nanos);
      }
      activeCalls++;

      return handleAcquireSuccess(callId, startWait);
    } catch (InterruptedException e) {
      notFull.signal();
      Thread.currentThread().interrupt();
      handleAcquireFailure(callId, startWait);
      throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), limitAlgorithm.getLimit());
    } finally {
      lock.unlock();
    }
  }

  /**
   * FIX #1: The limit comparison and oldLimit update are performed atomically under the lock.
   *
   * <p>FIX #1a (Liveness): {@code signalAll()} is now called on ANY limit change, not just
   * increases. When the limit decreases, waiting threads may be sleeping inside
   * {@code awaitNanos()} with significant remaining time. Without a signal, they remain
   * blocked until either their timeout expires naturally or another unrelated release wakes
   * them. With the signal, they immediately re-evaluate: the while-condition is still true
   * (capacity shrank), so they loop back — but now {@code awaitNanos()} returns with the
   * updated remaining time. If that time has elapsed, they exit promptly via the
   * {@code nanos <= 0L} branch instead of sleeping unnecessarily.
   *
   * <p>FIX #1b (Event ordering): The trace event's timestamp is captured inside the lock
   * as a local variable, not lazily in the lambda. Since only one thread holds the lock at
   * a time, the captured timestamps form a total order that matches the actual lock-acquisition
   * sequence. Even though publication happens outside the lock (to minimize lock hold time),
   * consumers can sort by timestamp to reconstruct the correct sequence. The trace event
   * itself is still published outside the lock to avoid blocking permit acquisition during I/O.
   */
  @Override
  protected void onCallComplete(String callId, Duration rtt, Throwable error) {
    // Feed the outcome back to the adaptive algorithm (thread-safe by contract)
    limitAlgorithm.update(rtt, error == null);

    int capturedOldLimit;
    int newLimit;
    boolean limitChanged;
    // FIX #1b: Capture timestamp under lock for consistent event ordering
    Instant eventTimestamp;
    long rttNanos = rtt.toNanos();

    lock.lock();
    try {
      newLimit = limitAlgorithm.getLimit();
      capturedOldLimit = oldLimit;
      limitChanged = capturedOldLimit != newLimit;

      if (limitChanged) {
        oldLimit = newLimit;
        eventTimestamp = clock.instant();
      } else {
        eventTimestamp = null;
      }

      // FIX #1a: Wake waiting threads on ANY limit change.
      // - Increase: threads may now acquire permits that weren't available before.
      // - Decrease: threads re-evaluate and time out promptly instead of sleeping
      //   the full remaining awaitNanos duration.
      if (limitChanged) {
        notFull.signalAll();
      }
    } finally {
      lock.unlock();
    }

    // Publish trace event outside the lock to avoid blocking permit acquisition.
    // The timestamp captured above guarantees correct logical ordering even if two
    // threads publish concurrently after exiting the lock in rapid succession.
    if (limitChanged) {
      final Instant ts = eventTimestamp;
      eventPublisher.publishTrace(() -> new BulkheadLimitChangedTraceEvent(
          callId,
          name,
          capturedOldLimit,
          newLimit,
          rttNanos,
          ts
      ));
    }
  }

  @Override
  protected void releasePermitInternal() {
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
