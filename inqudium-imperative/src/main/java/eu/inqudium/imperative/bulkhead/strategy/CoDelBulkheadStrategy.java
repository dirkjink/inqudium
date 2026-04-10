package eu.inqudium.imperative.bulkhead.strategy;

import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.log.Logger;

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

    public CoDelBulkheadStrategy(InqBulkheadConfig config,
                                 int maxConcurrentCalls,
                                 Duration targetDelay,
                                 Duration interval,
                                 LongSupplier nanoTimeSource) {
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
        this.logger = config.general().loggerFactory().getLogger(getClass());
        this.maxConcurrent = maxConcurrentCalls;
        this.targetDelayNanos = targetDelay.toNanos();
        this.intervalNanos = interval.toNanos();
        this.nanoTimeSource = nanoTimeSource != null ? nanoTimeSource : System::nanoTime;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Preset Factory Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>Protective</b> preset — prioritizes downstream safety over throughput.
     *
     * <p>Designed for critical downstream services where queue buildup is an early
     * warning of cascading failure (e.g., payment gateways, auth services, databases
     * with strict connection limits).
     *
     * <h3>Characteristics</h3>
     * <ul>
     *   <li><b>Low concurrency (20):</b> Tight permit budget limits the blast radius
     *       if the downstream service degrades.</li>
     *   <li><b>Short target delay (50 ms):</b> Detects queuing early. Any request that
     *       waits more than 50 ms for a permit is considered "above target" and starts
     *       the CoDel congestion stopwatch.</li>
     *   <li><b>Short interval (500 ms):</b> If sojourn times remain above target for
     *       500 ms, the first drop occurs. Combined with the stopwatch reset after each
     *       drop, this produces a steady one-drop-per-500ms cadence under sustained
     *       congestion — shedding load quickly without flushing the entire queue.</li>
     * </ul>
     *
     * <h3>Defaults</h3>
     * <table>
     *   <tr><td>Max concurrent calls</td><td>20</td></tr>
     *   <tr><td>Target delay</td><td>50 ms</td></tr>
     *   <tr><td>Interval</td><td>500 ms</td></tr>
     * </table>
     *
     * @param config the bulkhead configuration (provides the logger factory)
     * @return a protectively tuned CoDel strategy
     */
    public static CoDelBulkheadStrategy protective(InqBulkheadConfig config) {
        return new CoDelBulkheadStrategy(
                config,
                20,                         // maxConcurrentCalls: tight permit budget
                Duration.ofMillis(50),      // targetDelay: detect queuing early
                Duration.ofMillis(500),     // interval: start dropping after 500 ms sustained congestion
                System::nanoTime
        );
    }

    /**
     * <b>Balanced</b> preset — the recommended production default.
     *
     * <p>Suitable for most backend-to-backend communication where the downstream
     * service has moderate capacity and occasional latency spikes are normal
     * (e.g., internal microservices, managed databases, message brokers).
     *
     * <h3>Characteristics</h3>
     * <ul>
     *   <li><b>Moderate concurrency (50):</b> Provides reasonable throughput while
     *       limiting the number of threads that can be parked simultaneously.</li>
     *   <li><b>Moderate target delay (100 ms):</b> Tolerates brief wait times caused by
     *       bursty traffic without triggering the congestion stopwatch. Only sustained
     *       queuing beyond 100 ms is considered problematic.</li>
     *   <li><b>Moderate interval (1 s):</b> Gives the downstream service a full second
     *       to recover from a transient spike before the first drop. Long enough to
     *       absorb GC pauses and brief load spikes, short enough to react within
     *       a few seconds to genuine congestion.</li>
     * </ul>
     *
     * <h3>Defaults</h3>
     * <table>
     *   <tr><td>Max concurrent calls</td><td>50</td></tr>
     *   <tr><td>Target delay</td><td>100 ms</td></tr>
     *   <tr><td>Interval</td><td>1 s</td></tr>
     * </table>
     *
     * @param config the bulkhead configuration (provides the logger factory)
     * @return a balanced CoDel strategy suitable for general production use
     */
    public static CoDelBulkheadStrategy balanced(InqBulkheadConfig config) {
        return new CoDelBulkheadStrategy(
                config,
                50,                         // maxConcurrentCalls: moderate permit budget
                Duration.ofMillis(100),     // targetDelay: tolerates brief spikes
                Duration.ofSeconds(1),      // interval: absorbs transient congestion
                System::nanoTime
        );
    }

    /**
     * <b>Performant</b> preset — prioritizes throughput over caution.
     *
     * <p>Designed for downstream services with high, elastic capacity where
     * brief queue buildup is acceptable and aggressive dropping would waste
     * capacity (e.g., autoscaling compute clusters, CDN origins, horizontally
     * scaled stateless services).
     *
     * <h3>Characteristics</h3>
     * <ul>
     *   <li><b>High concurrency (100):</b> Allows many requests in flight simultaneously,
     *       maximizing utilization of elastic backends.</li>
     *   <li><b>Tolerant target delay (250 ms):</b> Accepts longer wait times before
     *       considering the system congested. Elastic backends may have variable latency
     *       during scale-up; a higher target prevents premature drops during these
     *       transient periods.</li>
     *   <li><b>Long interval (2 s):</b> Gives the downstream service ample time to
     *       absorb load or scale up before any drops occur. Only truly sustained
     *       congestion (beyond 2 seconds) triggers load shedding. This minimizes
     *       unnecessary request rejection at the cost of slightly slower reaction
     *       to genuine overload.</li>
     * </ul>
     *
     * <h3>Defaults</h3>
     * <table>
     *   <tr><td>Max concurrent calls</td><td>100</td></tr>
     *   <tr><td>Target delay</td><td>250 ms</td></tr>
     *   <tr><td>Interval</td><td>2 s</td></tr>
     * </table>
     *
     * @param config the bulkhead configuration (provides the logger factory)
     * @return a throughput-optimized CoDel strategy
     */
    public static CoDelBulkheadStrategy performant(InqBulkheadConfig config) {
        return new CoDelBulkheadStrategy(
                config,
                100,                        // maxConcurrentCalls: generous permit budget
                Duration.ofMillis(250),     // targetDelay: tolerant of queue buildup
                Duration.ofSeconds(2),      // interval: only drop under sustained congestion
                System::nanoTime
        );
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
