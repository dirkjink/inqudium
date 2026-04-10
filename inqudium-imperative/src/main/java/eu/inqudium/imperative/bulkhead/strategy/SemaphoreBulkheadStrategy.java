package eu.inqudium.imperative.bulkhead.strategy;

import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static (fixed-limit) bulkhead strategy using a fair {@link Semaphore}.
 *
 * <p>The simplest strategy — a fixed concurrency limit set once at construction.
 * Suitable for downstream services with known, stable capacity.
 *
 * <h2>Over-release guard</h2>
 * <p>{@link Semaphore#release()} does NOT check whether the caller holds a permit —
 * it unconditionally increments the count. The {@link #acquiredPermits} counter
 * shadows the semaphore's state and prevents inflation from double-release bugs.
 *
 * <h2>Fairness</h2>
 * <p>The semaphore is created with {@code fair=true}, guaranteeing FIFO ordering.
 * All acquire calls use {@code tryAcquire(timeout, TimeUnit)} — even for
 * {@link Duration#ZERO} — because the parameterless {@code tryAcquire()} is
 * inherently unfair and would bypass the queue.
 *
 * @since 0.3.0
 */
public final class SemaphoreBulkheadStrategy implements BlockingBulkheadStrategy {

    private final Semaphore semaphore;
    private final AtomicInteger acquiredPermits;
    private final int maxConcurrent;

    public SemaphoreBulkheadStrategy(int maxConcurrentCalls) {
        if (maxConcurrentCalls < 0) {
            throw new IllegalArgumentException(
                    "maxConcurrentCalls must be >= 0, got " + maxConcurrentCalls);
        }
        this.maxConcurrent = maxConcurrentCalls;
        this.semaphore = new Semaphore(maxConcurrentCalls, true);
        this.acquiredPermits = new AtomicInteger(0);
    }

    /**
     * Attempts to acquire a permit, waiting up to the specified timeout.
     *
     * <p><b>Fairness note for {@code Duration.ZERO}:</b> Unlike
     * {@link NonBlockingBulkheadStrategy#tryAcquire()}, which succeeds whenever capacity
     * is available, this method respects the fair semaphore's FIFO queue even for zero-timeout
     * attempts. If other threads are already queued, a zero-timeout call may return a
     * rejection despite available permits. This prevents starvation of waiting threads
     * but means {@code tryAcquire(Duration.ZERO)} is not semantically identical to a
     * non-blocking strategy's {@code tryAcquire()}.
     */
    @Override
    public RejectionContext tryAcquire(Duration timeout) throws InterruptedException {
        boolean acquired = semaphore.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (acquired) {
            acquiredPermits.incrementAndGet();
            return null; // permit acquired — no allocation, no nanoTime call
        }

        // Rejection path only — all allocation and measurement happens here
        int currentActive = acquiredPermits.get();
        if (timeout.isZero()) {
            return RejectionContext.capacityReached(maxConcurrent, currentActive);
        }
        // The semaphore waited approximately the full timeout before returning false.
        // Using the configured timeout as the waited time avoids a System.nanoTime()
        // call on every happy-path acquire — a critical optimization under contention,
        // where nanoTime's native call becomes a secondary contention point.
        return RejectionContext.timeoutExpired(maxConcurrent, currentActive, timeout.toNanos());
    }

    @Override
    public void release() {
        releaseInternal();
    }

    @Override
    public void rollback() {
        releaseInternal();
    }

    private void releaseInternal() {
        if (acquiredPermits.getAndUpdate(c -> c > 0 ? c - 1 : 0) > 0) {
            semaphore.release();
        }
    }

    @Override
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    @Override
    public int concurrentCalls() {
        return acquiredPermits.get();
    }

    @Override
    public int maxConcurrentCalls() {
        return maxConcurrent;
    }
}
