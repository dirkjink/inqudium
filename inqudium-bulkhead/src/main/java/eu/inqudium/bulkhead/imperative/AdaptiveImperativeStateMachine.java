package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.*;
import eu.inqudium.core.bulkhead.event.BulkheadLimitChangedTraceEvent;

import java.time.Duration;
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
  private volatile int oldLimit = 0;

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
          // Pass the baton: ensure we don't drop a signal if a permit was freed
          // exactly when this thread timed out.
          notFull.signal();
          handleAcquireFailure(callId, startWait);
          return false;
        }
        nanos = notFull.awaitNanos(nanos);
      }
      activeCalls++;

      return handleAcquireSuccess(callId, startWait);
    } catch (InterruptedException e) {
      // Pass the baton: ensure we don't drop a signal when interrupted
      notFull.signal();
      Thread.currentThread().interrupt();
      handleAcquireFailure(callId, startWait);
      throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), limitAlgorithm.getLimit());
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void onCallComplete(String callId, Duration rtt, Throwable error) {
    // Feed the outcome back to the adaptive algorithm to adjust limits
    limitAlgorithm.update(rtt, error == null);
    int newLimit = limitAlgorithm.getLimit();
    if (oldLimit != newLimit) {
      eventPublisher.publishTrace(() -> new BulkheadLimitChangedTraceEvent(
          callId,
          name,
          oldLimit,
          newLimit,
          rtt.toNanos(),
          clock.instant()
      ));

      // If the capacity increased, wake up waiting threads to utilize the new slots
      if (newLimit > oldLimit) {
        lock.lock();
        try {
          notFull.signalAll();
        } finally {
          lock.unlock();
        }
      }
    }
    oldLimit = newLimit;
  }

  @Override
  protected void releasePermitInternal() {
    lock.lock();
    try {
      if (activeCalls > 0) {
        activeCalls--;
        notFull.signal(); // Wake up one waiting thread since a single permit freed up
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