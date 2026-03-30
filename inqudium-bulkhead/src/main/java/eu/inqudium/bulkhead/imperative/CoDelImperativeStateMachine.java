package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.AbstractBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An imperative state machine utilizing a simplified Controlled Delay (CoDel) mechanism.
 *
 * <p>Instead of strictly limiting concurrency, CoDel monitors the wait time (sojourn time)
 * of threads trying to acquire a permit. If the wait time exceeds a target delay
 * consistently over a specified interval, it enters a dropping state and actively rejects
 * requests to drain the queue and restore low latency.
 *
 * @since 0.2.0
 */
public final class CoDelImperativeStateMachine extends AbstractBulkheadStateMachine {

  private final long targetDelayNanos;
  private final long intervalNanos;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition permitAvailable = lock.newCondition();

  private int activeCalls = 0;

  // Tracks when the system first exceeded the target delay
  private final AtomicLong firstAboveTargetNanos = new AtomicLong(0);

  public CoDelImperativeStateMachine(String name, BulkheadConfig config, Duration targetDelay, Duration interval) {
    super(name, config);
    this.targetDelayNanos = targetDelay.toNanos();
    this.intervalNanos = interval.toNanos();
  }

  @Override
  public boolean tryAcquireNonBlocking(String callId) {
    lock.lock();
    try {
      if (activeCalls < maxConcurrentCalls) {
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
    long remainingNanos = timeout.toNanos();
    long waitStartNanos = System.nanoTime();

    lock.lockInterruptibly();
    try {
      while (activeCalls >= maxConcurrentCalls) {
        if (remainingNanos <= 0L) {
          handleAcquireFailure(callId);
          return false;
        }
        remainingNanos = permitAvailable.awaitNanos(remainingNanos);
      }

      // -- CoDel Wait Time Evaluation --
      long waitTimeNanos = System.nanoTime() - waitStartNanos;
      long now = System.nanoTime();

      if (waitTimeNanos > targetDelayNanos) {
        long firstAbove = firstAboveTargetNanos.get();
        if (firstAbove == 0) {
          // First time we breached the target delay, start the interval clock
          firstAboveTargetNanos.compareAndSet(0, now);
        } else if (now - firstAbove > intervalNanos) {
          // We have been above the target delay for longer than the interval.
          // Enter dropping state: reject this request to shed load.
          handleAcquireFailure(callId);
          return false;
        }
      } else {
        // System is healthy, wait time is below target. Reset the CoDel clock.
        firstAboveTargetNanos.set(0);
      }

      activeCalls++;
      return handleAcquireSuccess(callId);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleAcquireFailure(callId);
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
        permitAvailable.signal(); // Wake up the next thread in line
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