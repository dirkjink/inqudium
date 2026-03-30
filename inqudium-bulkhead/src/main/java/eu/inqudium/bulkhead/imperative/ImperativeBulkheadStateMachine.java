package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.AbstractBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The imperative implementation of the state machine using a thread-blocking Semaphore.
 *
 * <p>FIX #2: An {@link AtomicInteger} tracks the number of acquired permits to prevent
 * over-release. Without this guard, double-release bugs would silently increase the
 * semaphore's capacity beyond {@code maxConcurrentCalls}, effectively disabling the
 * bulkhead's protection and causing {@link #getConcurrentCalls()} to return negative values.
 */
public final class ImperativeBulkheadStateMachine extends AbstractBulkheadStateMachine {

  private final Semaphore semaphore;
  private final AtomicInteger acquiredPermits;

  public ImperativeBulkheadStateMachine(String name, BulkheadConfig config) {
    super(name, config);
    this.semaphore = new Semaphore(maxConcurrentCalls, true);
    this.acquiredPermits = new AtomicInteger(0);
  }

  @Override
  public boolean tryAcquireNonBlocking(String callId) {
    // Attempt to acquire instantly without blocking the thread
    if (semaphore.tryAcquire()) {
      acquiredPermits.incrementAndGet();
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
      acquiredPermits.incrementAndGet();
      return handleAcquireSuccess(callId);
    } else {
      handleAcquireFailure(callId);
      return false;
    }
  }

  /**
   * FIX #2: Guard against over-release by checking if any permits are actually held.
   * Only releases the semaphore if the tracked count is positive.
   */
  @Override
  protected void releasePermitInternal() {
    if (acquiredPermits.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
      semaphore.release();
    }
  }

  /**
   * FIX #2: Same guard applies to rollback — prevents semaphore inflation on
   * double-rollback or rollback-without-acquire scenarios.
   */
  @Override
  protected void rollbackPermit() {
    if (acquiredPermits.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
      semaphore.release();
    }
  }

  @Override
  public int getAvailablePermits() {
    return semaphore.availablePermits();
  }

  @Override
  public int getConcurrentCalls() {
    return acquiredPermits.get();
  }
}
