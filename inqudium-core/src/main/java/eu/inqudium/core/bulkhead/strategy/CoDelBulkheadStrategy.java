package eu.inqudium.core.bulkhead.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * CoDel (Controlled Delay) bulkhead strategy for queue-based congestion management.
 *
 * <p>Unlike adaptive strategies that adjust the concurrency <em>limit</em>, CoDel
 * operates on the <em>queue</em>: it monitors how long requests wait for a permit
 * (the "sojourn time") and rejects requests that have been waiting too long during
 * sustained congestion.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><strong>Measure:</strong> When a permit becomes available, measure the thread's
 *       sojourn time (time spent in the condition queue — excludes lock contention).</li>
 *   <li><strong>Track:</strong> If sojourn time exceeds {@code targetDelay}, record the
 *       timestamp (start the "congestion stopwatch").</li>
 *   <li><strong>Detect:</strong> If the stopwatch has been running for longer than
 *       {@code interval}, sustained congestion is confirmed.</li>
 *   <li><strong>Drop:</strong> The request is rejected and the next waiter is signaled,
 *       creating a chain-drain that rapidly clears the queue.</li>
 *   <li><strong>Recover:</strong> When a request arrives with acceptable sojourn time,
 *       the stopwatch resets.</li>
 * </ol>
 *
 * <h2>Fair lock requirement</h2>
 * <p>The lock <strong>must</strong> be fair. An unfair lock allows "barging" where a
 * newly arrived thread acquires the lock before older threads, measures near-zero
 * sojourn time, and resets the congestion stopwatch — permanently preventing CoDel
 * from detecting sustained congestion.
 *
 * @since 0.3.0
 */
public final class CoDelBulkheadStrategy implements BulkheadStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(CoDelBulkheadStrategy.class);

  private final int maxConcurrentCalls;
  private final long targetDelayNanos;
  private final long intervalNanos;
  private final LongSupplier nanoTimeSource;

  // Fair lock — critical for CoDel correctness (see class Javadoc)
  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition permitAvailable = lock.newCondition();

  // Mutable state — all protected by lock
  private long firstAboveTargetNanos = 0L;
  private int activeCalls = 0;
  private int acquireThreads = 0;

  public CoDelBulkheadStrategy(int maxConcurrentCalls, Duration targetDelay,
                               Duration interval, LongSupplier nanoTimeSource) {
    if (maxConcurrentCalls < 0) {
      throw new IllegalArgumentException("maxConcurrentCalls must be >= 0, got " + maxConcurrentCalls);
    }
    Objects.requireNonNull(targetDelay, "targetDelay must not be null");
    Objects.requireNonNull(interval, "interval must not be null");
    if (targetDelay.isNegative() || targetDelay.isZero()) {
      throw new IllegalArgumentException("targetDelay must be positive");
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive");
    }
    this.maxConcurrentCalls = maxConcurrentCalls;
    this.targetDelayNanos = targetDelay.toNanos();
    this.intervalNanos = interval.toNanos();
    this.nanoTimeSource = nanoTimeSource != null ? nanoTimeSource : System::nanoTime;
  }

  @Override
  public boolean tryAcquire(Duration timeout) throws InterruptedException {
    long remainingNanos = timeout.toNanos();

    try {
      lock.lockInterruptibly();
      acquireThreads++;

      // CoDel enqueue time — post-lock, excludes lock contention
      long codelEnqueueNanos = nanoTimeSource.getAsLong();

      try {
        // Phase 1: Wait for capacity
        while (activeCalls >= maxConcurrentCalls) {
          if (remainingNanos <= 0L) {
            permitAvailable.signal(); // pass the baton
            return false;
          }
          remainingNanos = permitAvailable.awaitNanos(remainingNanos);
        }

        // Phase 2: CoDel sojourn time evaluation
        long now = nanoTimeSource.getAsLong();
        long sojournNanos = now - codelEnqueueNanos;

        if (sojournNanos > targetDelayNanos) {
          if (firstAboveTargetNanos == 0L) {
            // Start the congestion stopwatch — this request proceeds normally
            firstAboveTargetNanos = now;
          } else if (now - firstAboveTargetNanos > intervalNanos) {
            // Sustained congestion confirmed — reject (CoDel drop)
            permitAvailable.signal(); // chain-drain: wake next waiter
            LOG.debug("CoDel drop: sojourn={}ns target={}ns interval={}ns",
                sojournNanos, targetDelayNanos, intervalNanos);
            return false;
          }
        } else {
          // Sojourn time acceptable — reset the congestion stopwatch
          firstAboveTargetNanos = 0L;
        }

        // Phase 3: Grant the permit
        activeCalls++;
        return true;

      } catch (InterruptedException e) {
        permitAvailable.signal(); // pass the baton
        Thread.currentThread().interrupt();
        throw e;
      } finally {
        acquireThreads--;
        // Idle detection: reset CoDel state when truly idle
        if (activeCalls == 0 && acquireThreads == 0) {
          firstAboveTargetNanos = 0L;
        }
      }
    } finally {
      if (lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    }
  }

  @Override
  public void release() {
    lock.lock();
    try {
      if (activeCalls > 0) {
        activeCalls--;
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
  public void rollback() {
    release();
  }

  @Override
  public int availablePermits() {
    lock.lock();
    try {
      return Math.max(0, maxConcurrentCalls - activeCalls);
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
    return maxConcurrentCalls;
  }
}
