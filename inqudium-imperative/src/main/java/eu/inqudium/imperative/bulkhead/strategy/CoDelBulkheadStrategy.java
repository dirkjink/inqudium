package eu.inqudium.imperative.bulkhead.strategy;

import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.log.Logger;
import eu.inqudium.core.log.LoggerFactory;

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

    private final Logger logger;
    private final int maxConcurrent;
    private final long targetDelayNanos;
    private final long intervalNanos;
    private final LongSupplier nanoTimeSource;

    private final ReentrantLock lock = new ReentrantLock(true); // fair — critical for CoDel
    private final Condition permitAvailable = lock.newCondition();

    private long firstAboveTargetNanos = 0L;
    private int activeCalls = 0;
    private int acquireThreads = 0;

    /**
     * Direct-dependency constructor.
     *
     * @param loggerFactory     the factory to obtain this strategy's logger from; non-null.
     * @param maxConcurrentCalls fixed concurrency limit; non-negative.
     * @param targetDelay       latency budget above which sojourn samples count as
     *                          "above target"; non-null and strictly positive.
     * @param interval          consecutive-overshoot window before the first drop; non-null
     *                          and strictly positive.
     * @param nanoTimeSource    monotonic time source; falls back to {@link System#nanoTime}
     *                          when {@code null}.
     */
    public CoDelBulkheadStrategy(LoggerFactory loggerFactory,
                                 int maxConcurrentCalls,
                                 Duration targetDelay,
                                 Duration interval,
                                 LongSupplier nanoTimeSource) {
        Objects.requireNonNull(loggerFactory, "loggerFactory must not be null");
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
        this.logger = loggerFactory.getLogger(getClass());
        this.maxConcurrent = maxConcurrentCalls;
        this.targetDelayNanos = targetDelay.toNanos();
        this.intervalNanos = interval.toNanos();
        this.nanoTimeSource = nanoTimeSource != null ? nanoTimeSource : System::nanoTime;
    }

    @Override
    public RejectionContext tryAcquire(Duration timeout) throws InterruptedException {
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
                        int active = activeCalls;
                        permitAvailable.signal(); // pass the baton

                        if (timeout.isZero()) {
                            return RejectionContext.capacityReached(maxConcurrent, active);
                        }
                        // Approximation: the semaphore waited approximately the full timeout.
                        // Avoids a nanoTimeSource call on every happy-path acquire.
                        return RejectionContext.timeoutExpired(maxConcurrent, active, timeout.toNanos());
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
                        // Sustained congestion — reject (CoDel drop).
                        //
                        // Reset the congestion stopwatch after each drop. Without this reset,
                        // every subsequent waiter would also be immediately dropped (chain-drain
                        // cascade), because their sojourn time is even higher and the interval
                        // has already been exceeded. Resetting to 0 gives the next waiter a
                        // fresh interval window — if congestion persists, it will be dropped
                        // after another full interval, producing a controlled one-drop-per-interval
                        // cadence instead of a catastrophic queue flush.
                        //
                        // This also fixes a liveness issue: without the reset, the system could
                        // never recover from sustained overload because all new arrivals would be
                        // dropped indefinitely (the fair lock ensures they always queue behind
                        // older waiters with longer sojourn times). With the reset, successful
                        // grants can occur between drops, allowing the system to detect when
                        // downstream has recovered (sojourn times drop below target → full reset).
                        firstAboveTargetNanos = 0L;
                        int active = activeCalls;
                        // waitedNanos ≈ sojournNanos: the sojourn time (post-lock queue wait) is the
                        // dominant component. The difference (lock acquisition time) is typically
                        // sub-microsecond and not worth a nanoTimeSource call on every happy-path acquire.
                        permitAvailable.signal(); // wake next waiter for fresh evaluation
                        logger.debug().log("CoDel drop: sojourn={}ns target={}ns interval={}ns",
                                sojournNanos, targetDelayNanos, intervalNanos);
                        return RejectionContext.codelDrop(maxConcurrent, active, sojournNanos, sojournNanos);
                    }
                } else {
                    // Sojourn time acceptable — reset congestion stopwatch
                    firstAboveTargetNanos = 0L;
                }

                // Phase 3: Grant the permit
                activeCalls++;
                return null; // permit acquired — no allocation

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
