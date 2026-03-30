package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.AbstractBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The imperative implementation of the state machine using a thread-blocking Semaphore.
 */
public final class ImperativeBulkheadStateMachine extends AbstractBulkheadStateMachine {

  private final Semaphore semaphore;

  public ImperativeBulkheadStateMachine(String name, BulkheadConfig config) {
    super(name, config);
    this.semaphore = new Semaphore(maxConcurrentCalls, true);
  }

  @Override
  public boolean tryAcquireNonBlocking(String callId) {
    // Attempt to acquire instantly without blocking the thread
    if (semaphore.tryAcquire()) {
      return handleAcquireSuccess(callId);
    } else {
      handleAcquireFailure(callId);
      return false;
    }
  }

  @Override
  public boolean tryAcquireBlocking(String callId, Duration timeout) throws InterruptedException {
    boolean acquired;
    try {
      acquired = timeout.isZero()
          ? semaphore.tryAcquire()
          : semaphore.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleAcquireFailure(callId);
      throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), maxConcurrentCalls);
    }

    if (acquired) {
      return handleAcquireSuccess(callId);
    } else {
      handleAcquireFailure(callId);
      return false;
    }
  }

  @Override
  protected void releasePermitInternal() {
    semaphore.release();
  }

  @Override
  protected void rollbackPermit() {
    semaphore.release();
  }

  @Override
  public int getAvailablePermits() {
    return semaphore.availablePermits();
  }

  @Override
  public int getConcurrentCalls() {
    return maxConcurrentCalls - semaphore.availablePermits();
  }
}