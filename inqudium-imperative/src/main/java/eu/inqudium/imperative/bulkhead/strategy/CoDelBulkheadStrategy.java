package eu.inqudium.imperative.bulkhead.strategy;

import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
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
 * <p>Monitors how long requests wait for a permit (sojourn time) and rejects
 * requests that have been waiting too long during sustained congestion.
 *
 * <h2>Fair lock requirement</h2>
 * <p>The lock <strong>must</strong> be fair. An unfair lock allows "barging" where
 * a newly arrived thread measures near-zero sojourn time and resets the congestion
 * stopwatch — permanently preventing CoDel from detecting sustained congestion.
 *
 * @since 0.3.0
 */
public final class CoDelBulkheadStrategy implements BlockingBulkheadStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(CoDelBulkheadStrategy.class);

  private final int maxConcurrent;
  private final long targetDelayNanos;
  private final long intervalNanos;
  private final LongSupplier nanoTimeSource;

  private final ReentrantLock lock = new ReentrantLock(true); // fair — critical for CoDel
  private final Condition permitAvailable = lock.newCondition();

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
    this.maxConcurrent = maxConcurrentCalls;
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
        while (activeCalls >= maxConcurrent) {
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
            // Start congestion stopwatch — request proceeds normally
            firstAboveTargetNanos = now;
          } else if (now - firstAboveTargetNanos > intervalNanos) {
            // Sustained congestion — reject (CoDel drop)
            permitAvailable.signal(); // chain-drain: wake next waiter
            LOG.debug("CoDel drop: sojourn={}ns target={}ns interval={}ns",
                sojournNanos, targetDelayNanos, intervalNanos);
            return false;
          }
        } else {
          // Sojourn time acceptable — reset congestion stopwatch
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
        if (activeCalls == 0 && acquireThreads == 0) {
          firstAboveTargetNanos = 0L; // idle reset
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
      return Math.max(0, maxConcurrent - activeCalls);
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
    return maxConcurrent;
  }
}
