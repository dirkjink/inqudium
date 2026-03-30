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
 * <h2>Algorithm Explanation</h2>
 * <p>Unlike AIMD or Vegas, which try to calculate a dynamic concurrency limit, CoDel is an
 * Active Queue Management (AQM) algorithm designed to defeat "bufferbloat". It focuses
 * entirely on the <b>sojourn time</b> — the time a request spends waiting in the queue
 * before it gets a permit.
 * <ul>
 * <li><b>Target Delay:</b> The acceptable maximum wait time for a request (e.g., 5ms).</li>
 * <li><b>Interval Window:</b> A sliding time window (e.g., 100ms) used to determine if
 * the system is experiencing a temporary burst or sustained congestion.</li>
 * </ul>
 * <p><b>How it works:</b>
 * As long as the wait time is below the target, the system is healthy. If a request
 * waits longer than the target, CoDel starts a stopwatch. If subsequent requests
 * <i>continue</i> to wait longer than the target for the entire duration of the interval,
 * CoDel enters a <b>dropping state</b>. It actively rejects incoming requests to quickly
 * drain the queue and restore low latency. Once a request is processed within the target
 * delay, the stopwatch resets.
 *
 * <h2>Advantages</h2>
 * <ul>
 * <li><b>Burst Tolerance:</b> It perfectly absorbs temporary load spikes. If 100 requests
 * arrive at once, they might wait a bit, but as long as the queue drains before the interval
 * expires, no requests are dropped.</li>
 * <li><b>Bufferbloat Prevention:</b> It guarantees that the queue will never become a
 * "standing queue" where every request inherently suffers a massive latency penalty just
 * for waiting in line.</li>
 * <li><b>No Magic Numbers:</b> You do not need to guess the "optimal" maximum concurrency
 * limit of your backend. You only define the acceptable latency constraints.</li>
 * </ul>
 *
 * <h2>Disadvantages</h2>
 * <ul>
 * <li><b>Parameter Sensitivity:</b> It requires careful tuning of the `target` and
 * `interval` parameters. If the interval is too short, it drops requests prematurely.
 * If it is too long, the queue grows too large before CoDel intervenes.</li>
 * <li><b>Reactive Queue Draining:</b> It does not proactively slow down traffic before the
 * queue forms (unlike Vegas). It allows the queue to form and then mercilessly slaughters
 * requests to clean it up.</li>
 * <li><b>Lock Contention:</b> Because it relies on precise time measurements under a
 * reentrant lock, extreme contention can slightly skew the wait time calculations.</li>
 * </ul>
 *
 * @since 0.2.0
 */
public final class CoDelImperativeStateMachine extends AbstractBulkheadStateMachine {

  private final long targetDelayNanos;
  private final long intervalNanos;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition permitAvailable = lock.newCondition();

  private int activeCalls = 0;

  // Tracks the exact timestamp when the system first exceeded the target delay.
  // A value of 0 means the system is currently healthy (below target).
  private final AtomicLong firstAboveTargetNanos = new AtomicLong(0);

  /**
   * Creates a new CoDel-based state machine.
   *
   * @param name        The name of the bulkhead instance.
   * @param config      The core bulkhead configuration.
   * @param targetDelay The ideal maximum time a thread should spend waiting in the queue.
   * @param interval    The sliding window duration. If wait times exceed the target for
   * this entire duration, the algorithm will start dropping requests.
   */
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
    // A non-blocking acquire fails instantly if the limit is reached,
    // so it bypasses the CoDel delay evaluation entirely.
    handleAcquireFailure(callId);
    return false;
  }

  @Override
  public boolean tryAcquireBlocking(String callId, Duration timeout) throws InterruptedException {
    long remainingNanos = timeout.toNanos();

    // Record the exact moment this request entered the queue
    long waitStartNanos = System.nanoTime();

    lock.lockInterruptibly();
    try {
      // 1. Wait for a permit to become available
      while (activeCalls >= maxConcurrentCalls) {
        if (remainingNanos <= 0L) {
          handleAcquireFailure(callId);
          return false;
        }
        remainingNanos = permitAvailable.awaitNanos(remainingNanos);
      }

      // 2. CoDel Wait Time Evaluation (Sojourn Time)
      // Calculate how long this thread actually spent sleeping/waiting for the permit.
      long waitTimeNanos = System.nanoTime() - waitStartNanos;
      long now = System.nanoTime();

      if (waitTimeNanos > targetDelayNanos) {
        // The wait time was unacceptable.
        long firstAbove = firstAboveTargetNanos.get();

        if (firstAbove == 0) {
          // This is the first request to breach the target delay.
          // We start the interval stopwatch, but we GRANT the permit to allow bursts.
          firstAboveTargetNanos.compareAndSet(0, now);
        } else if (now - firstAbove > intervalNanos) {
          // We have been consistently above the target delay for longer than the interval.
          // The system is suffering from bufferbloat.
          // Enter dropping state: reject this request immediately to shed load and drain the queue.
          handleAcquireFailure(callId);
          return false;
        }
      } else {
        // The system is healthy. The request was processed quickly.
        // We reset the CoDel stopwatch, stopping any potential dropping state.
        firstAboveTargetNanos.set(0);
      }

      // 3. Grant the permit
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
        // Wake up the next waiting thread in the queue
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