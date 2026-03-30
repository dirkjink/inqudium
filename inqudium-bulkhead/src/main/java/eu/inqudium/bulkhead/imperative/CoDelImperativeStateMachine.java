package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.*;
import eu.inqudium.core.bulkhead.event.BulkheadCodelRejectedTraceEvent;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * An imperative state machine utilizing a simplified Controlled Delay (CoDel) mechanism.
 *
 * <h2>FIX #3 (idle detection via acquireThreads counter)</h2>
 * <p>The original implementation never reset {@code firstAboveTargetNanos} unless a request
 * arrived with sojourn time below the target delay. After a congestion episode followed by
 * an idle period, the stale timestamp would cause instant drops on the next request batch.
 *
 * <p>The first correction used {@code lock.hasWaiters(permitAvailable)} to detect idle state,
 * but this was unreliable: {@code hasWaiters} only sees threads blocked in {@code await()},
 * missing threads that are blocked on {@code lock.lockInterruptibly()} or are between lock
 * acquisition and the first {@code await()} call. This could cause premature reset during
 * lock contention, aborting a chain-drain in progress.
 *
 * <p>The fix replaces {@code hasWaiters} with an explicit {@code acquireThreads} counter that
 * tracks every thread currently inside the {@code tryAcquire} critical section. This counter
 * is incremented immediately after lock acquisition and decremented in a finally block before
 * unlock, guaranteeing exact bookkeeping regardless of how the thread exits (success, timeout,
 * CoDel drop, or interruption). The CoDel state is reset only when both {@code activeCalls}
 * and {@code acquireThreads} reach zero — meaning no permits are held AND no thread is
 * anywhere inside the acquire flow.
 *
 * <h2>FIX #3a (sojourn time precision)</h2>
 * <p>The original sojourn time measurement used a timestamp captured <em>before</em>
 * {@code lock.lockInterruptibly()}, so the measured "queue wait" included lock contention
 * time. Under high thread contention, requests could be falsely rejected by CoDel due to
 * time spent fighting for the Java monitor rather than actual queue congestion.
 *
 * <p>The fix introduces a separate {@code codelEnqueueNanos} timestamp captured <em>after</em>
 * lock acquisition but before entering the condition wait loop. This cleanly measures only
 * the time spent waiting on the {@code permitAvailable} condition — the true queue sojourn
 * time. The pre-lock {@code waitStartNanos} is retained for general telemetry
 * ({@code publishWaitTrace}, {@code handleAcquireFailure/Success}), which correctly reports
 * the total user-visible wait including lock contention.
 *
 * @since 0.2.0
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
  // Protected by the lock.
  private long firstAboveTargetNanos = 0L;

  private int activeCalls = 0;

  /**
   * FIX #3: Counts threads currently inside the tryAcquire critical section.
   *
   * <p>Incremented immediately after lock acquisition, decremented in a finally block
   * before unlock. This provides an exact count of threads that are either waiting on
   * the condition, evaluating CoDel, or anywhere else between lock entry and exit.
   *
   * <p>Unlike {@code lock.hasWaiters(permitAvailable)}, this counter captures ALL threads
   * in the acquire flow — including those between lock acquisition and the first
   * {@code await()} call, and those that have woken from {@code await()} but haven't
   * returned yet (e.g., executing the CoDel evaluation or publishing trace events).
   */
  private int acquireThreads = 0;

  public CoDelImperativeStateMachine(String name, BulkheadConfig config, Duration targetDelay, Duration interval) {
    super(name, config);
    this.targetDelayNanos = targetDelay.toNanos();
    this.intervalNanos = interval.toNanos();
    this.nanoTimeSource = config.getNanoTimeSource();
  }

  @Override
  public boolean tryAcquire(String callId, Duration timeout) throws InterruptedException {
    long remainingNanos = timeout.toNanos();

    // Captured BEFORE lock acquisition — includes lock contention time.
    // Used for general telemetry (publishWaitTrace, handleAcquireFailure/Success)
    // which reports the total user-visible wait.
    long waitStartNanos = nanoTimeSource.getAsLong();

    lock.lockInterruptibly();
    try {
      acquireThreads++;

      // FIX #3a: Captured AFTER lock acquisition — excludes lock contention.
      // Used exclusively for the CoDel sojourn time decision. This ensures CoDel
      // evaluates only the time spent waiting in the permit queue, not time spent
      // fighting for the Java monitor under high thread contention.
      long codelEnqueueNanos = nanoTimeSource.getAsLong();

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

        // FIX #3a: Use codelEnqueueNanos for CoDel decision — pure queue wait time
        long sojournNanos = now - codelEnqueueNanos;

        if (sojournNanos > targetDelayNanos) {
          if (firstAboveTargetNanos == 0L) {
            // Start the interval stopwatch
            firstAboveTargetNanos = now;
          } else if (now - firstAboveTargetNanos > intervalNanos) {
            // Enter dropping state — sustained congestion confirmed
            permitAvailable.signal();

            eventPublisher.publishTrace(() -> new BulkheadCodelRejectedTraceEvent(
                callId,
                name,
                sojournNanos,
                targetDelayNanos,
                config.getClock().instant()
            ));

            handleAcquireFailure(callId, waitStartNanos);
            return false;
          }
        } else {
          // Sojourn time is below target — system is healthy, reset CoDel state
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
        acquireThreads--;

        // FIX #3: Reset CoDel state when the system is truly idle.
        // "Truly idle" means: no permits held AND no threads anywhere inside tryAcquire.
        //
        // This fires at the exact moment the last thread exits the acquire flow after a
        // chain-drain: all queued threads have been rejected, no permits are held, and no
        // thread is between lock acquisition and return. Any stale firstAboveTargetNanos
        // from the concluded congestion episode is cleared so new traffic starts fresh.
        //
        // During an active chain-drain, acquireThreads > 0 prevents premature reset:
        // dropped threads decrement one-by-one, but the counter stays positive until the
        // very last thread exits.
        if (activeCalls == 0 && acquireThreads == 0) {
          firstAboveTargetNanos = 0L;
        }
      }
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

        // FIX #3: Also check for idle state on permit release.
        // This handles the case where the last active call completes and no threads
        // are waiting — the system has naturally drained without a CoDel chain-drain.
        if (activeCalls == 0 && acquireThreads == 0) {
          firstAboveTargetNanos = 0L;
        }

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
