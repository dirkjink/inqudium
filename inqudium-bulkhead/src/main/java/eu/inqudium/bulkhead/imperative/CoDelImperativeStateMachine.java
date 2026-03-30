package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.*;
import eu.inqudium.core.bulkhead.event.BulkheadCodelRejectedTraceEvent;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * An imperative state machine utilizing a simplified Controlled Delay (CoDel) mechanism.
 */
public final class CoDelImperativeStateMachine
    extends AbstractBulkheadStateMachine implements BlockingBulkheadStateMachine {

  private final long targetDelayNanos;
  private final long intervalNanos;
  private final LongSupplier nanoTimeSource;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition permitAvailable = lock.newCondition();

  // Tracks the exact timestamp when the system first exceeded the target delay.
  // A value of 0 means the system is currently healthy (below target).
  // Protected by the lock, so an AtomicLong is not necessary.
  private long firstAboveTargetNanos = 0L;
  private int activeCalls = 0;

  public CoDelImperativeStateMachine(String name, BulkheadConfig config, Duration targetDelay, Duration interval) {
    super(name, config);
    this.targetDelayNanos = targetDelay.toNanos();
    this.intervalNanos = interval.toNanos();
    this.nanoTimeSource = config.getNanoTimeSource();
  }

  @Override
  public boolean tryAcquire(String callId, Duration timeout) throws InterruptedException {
    long remainingNanos = timeout.toNanos();
    long waitStartNanos = nanoTimeSource.getAsLong();

    lock.lockInterruptibly();
    try {
      // 1. Wait for a permit to become available
      while (activeCalls >= maxConcurrentCalls) {
        if (remainingNanos <= 0L) {
          // Pass the baton: ensure we don't drop a signal if a permit was freed
          // exactly when this thread timed out.
          permitAvailable.signal();
          handleAcquireFailure(callId, waitStartNanos);
          return false;
        }
        remainingNanos = permitAvailable.awaitNanos(remainingNanos);
      }

      // 2. CoDel Wait Time Evaluation (Sojourn Time)
      long now = nanoTimeSource.getAsLong();
      long waitTimeNanos = now - waitStartNanos;

      if (waitTimeNanos > targetDelayNanos) {
        if (firstAboveTargetNanos == 0L) {
          // Start the interval stopwatch
          firstAboveTargetNanos = now;
        } else if (now - firstAboveTargetNanos > intervalNanos) {
          // Enter dropping state
          permitAvailable.signal();

          eventPublisher.publishTrace(() -> new BulkheadCodelRejectedTraceEvent(
              callId,
              name,
              waitTimeNanos,
              targetDelayNanos,
              config.getClock().instant()
          ));

          handleAcquireFailure(callId, waitStartNanos);
          return false;
        }
      } else {
        // Reset the CoDel stopwatch
        firstAboveTargetNanos = 0L;
      }

      // 3. Grant the permit
      activeCalls++;
      return handleAcquireSuccess(callId, waitStartNanos);

    } catch (InterruptedException e) {
      // Pass the baton: ensure we don't drop a signal when interrupted
      permitAvailable.signal();
      Thread.currentThread().interrupt();
      handleAcquireFailure(callId, waitStartNanos);
      throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), maxConcurrentCalls);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void releasePermitInternal() {
    lock.lock();
    try {
      if (activeCalls > 0) {
        activeCalls--;
        permitAvailable.signal();
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
      return Math.max(0, maxConcurrentCalls - activeCalls);
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