package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.element.ratelimiter.RateLimiterException;
import eu.inqudium.core.element.ratelimiter.RateLimiterState;
import eu.inqudium.core.element.ratelimiter.ReservationResult;
import eu.inqudium.core.element.ratelimiter.strategy.RateLimiterStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative rate limiter implementation using a pluggable strategy.
 *
 * <p>The strategy is always obtained from {@link RateLimiterConfig#strategy()},
 * ensuring type-safe consistency between configuration and algorithm.
 *
 * <h2>Drain vs. Reset behavior</h2>
 * <ul>
 *   <li>{@link #drain()} — Removes all available permits. Existing reservations
 *       (parked threads) are honored and will proceed when their wait expires.
 *       No threads are woken prematurely.</li>
 *   <li>{@link #reset()} — Restores full capacity and increments the epoch.
 *       All parked threads are woken and must re-acquire permits. Previously
 *       consumed permits from invalidated reservations are not refunded —
 *       they are absorbed into the fresh bucket state.</li>
 * </ul>
 */
public class ImperativeRateLimiter<S extends RateLimiterState> {

    private static final Logger LOG = Logger.getLogger(ImperativeRateLimiter.class.getName());

    private final RateLimiterConfig<S> config;
    private final RateLimiterStrategy<S> strategy;
    private final AtomicReference<S> stateRef;
    private final Clock clock;
    private final List<Consumer<RateLimiterEvent>> eventListeners;
    private final Set<Thread> parkedThreads;
    private final String instanceId;

    // ======================== Constructors ========================

    // Fix 1 + 2: Removed raw-type static factory methods and redundant
    // constructors that accepted a separate strategy parameter.
    // The strategy is always extracted from the config, ensuring type safety.

    public ImperativeRateLimiter(RateLimiterConfig<S> config) {
        this(config, Clock.systemUTC());
    }

    public ImperativeRateLimiter(RateLimiterConfig<S> config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.strategy = config.strategy();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stateRef = new AtomicReference<>(strategy.initial(config, clock.instant()));
        this.eventListeners = new CopyOnWriteArrayList<>();
        this.parkedThreads = ConcurrentHashMap.newKeySet();
        this.instanceId = UUID.randomUUID().toString();
    }

    // ======================== Execution (Callable) ========================

    public <T> T execute(Callable<T> callable) throws Exception {
        return execute(callable, 1, config.defaultTimeout());
    }

    public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
        return execute(callable, 1, timeout);
    }

    public <T> T execute(Callable<T> callable, int permits) throws Exception {
        return execute(callable, permits, config.defaultTimeout());
    }

    public <T> T execute(Callable<T> callable, int permits, Duration timeout) throws Exception {
        Objects.requireNonNull(callable, "callable must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative, got " + timeout);
        }

        acquirePermissionsOrThrow(permits, timeout);
        return callable.call();
    }

    public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
        return executeWithFallback(callable, 1, config.defaultTimeout(), fallback);
    }

    public <T> T executeWithFallback(
            Callable<T> callable, int permits, Duration timeout, Supplier<T> fallback) throws Exception {
        try {
            return execute(callable, permits, timeout);
        } catch (RateLimiterException e) {
            // Only activate fallback for rejections from THIS rate limiter instance.
            // If the callable itself throws a RateLimiterException from an inner
            // rate limiter, it must propagate unmodified.
            if (Objects.equals(e.getInstanceId(), this.instanceId)) {
                return fallback.get();
            }
            throw e;
        }
    }

    // ======================== Execution (Runnable) ========================

    /**
     * Fix 7: Direct Runnable implementation instead of delegating to the
     * Callable variant. Eliminates the unreachable catch block and wrapping overhead.
     *
     * @throws InterruptedException if the thread is interrupted while waiting for permits
     * @throws RateLimiterException if no permit is available within the timeout
     */
    public void execute(Runnable runnable) throws InterruptedException {
        execute(runnable, 1, config.defaultTimeout());
    }

    public void execute(Runnable runnable, Duration timeout) throws InterruptedException {
        execute(runnable, 1, timeout);
    }

    public void execute(Runnable runnable, int permits, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(runnable, "runnable must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative, got " + timeout);
        }

        acquirePermissionsOrThrow(permits, timeout);
        runnable.run();
    }

    // ======================== Direct permission API ========================

    public boolean tryAcquirePermission() {
        return tryAcquirePermissions(1);
    }

    public boolean tryAcquirePermissions(int permits) {
        Instant now = clock.instant();
        while (true) {
            S current = stateRef.get();
            RateLimitPermission<S> result = strategy.tryAcquirePermissions(current, config, now, permits);

            if (stateRef.compareAndSet(current, result.state())) {
                if (result.permitted()) {
                    emitEvent(RateLimiterEvent.permitted(
                            config.name(), strategy.availablePermits(result.state(), config, now), now));
                } else {
                    emitEvent(RateLimiterEvent.rejected(
                            config.name(), strategy.availablePermits(result.state(), config, now),
                            result.waitDuration(), now));
                }
                return result.permitted();
            }
        }
    }

    public void acquirePermission() throws InterruptedException {
        acquirePermissionsOrThrow(1, config.defaultTimeout());
    }

    // ======================== Internal — Permit acquisition ========================

    private void acquirePermissionsOrThrow(int permits, Duration timeout) throws InterruptedException {
        Instant start = clock.instant();
        Instant deadline = timeout.isZero() ? start : start.plus(timeout);

        while (true) {
            S current = stateRef.get();
            Instant now = clock.instant();

            Duration remainingTimeout = timeout.isZero()
                    ? Duration.ZERO
                    : Duration.between(now, deadline);
            if (remainingTimeout.isNegative()) {
                remainingTimeout = Duration.ZERO;
            }

            ReservationResult<S> reservation = strategy.reservePermissions(
                    current, config, now, permits, remainingTimeout);

            if (reservation.timedOut()) {
                int available = strategy.availablePermits(current, config, now);
                emitEvent(RateLimiterEvent.rejected(
                        config.name(), available, reservation.waitDuration(), now));
                throw new RateLimiterException(
                        config.name(), instanceId, reservation.waitDuration(), available);
            }

            if (stateRef.compareAndSet(current, reservation.state())) {
                int newAvailable = strategy.availablePermits(reservation.state(), config, now);

                // Immediate grant — no waiting needed
                if (reservation.waitDuration().isZero()) {
                    emitEvent(RateLimiterEvent.permitted(config.name(), newAvailable, now));
                    return;
                }

                // Deferred grant — must wait for permits to refill
                emitEvent(RateLimiterEvent.waiting(
                        config.name(), newAvailable, reservation.waitDuration(), now));

                long epochBeforePark = reservation.state().epoch();
                Instant targetWakeup = now.plus(reservation.waitDuration());

                try {
                    parkedThreads.add(Thread.currentThread());
                    parkUntil(targetWakeup, epochBeforePark);
                } catch (InterruptedException e) {
                    // Thread was interrupted — refund the reserved permits to prevent leaks
                    refundPermits(permits);
                    throw e;
                } finally {
                    parkedThreads.remove(Thread.currentThread());
                }

                // Fix 6: Check if the epoch changed while we were parked.
                // A reset() increments the epoch and invalidates all pending reservations.
                // In that case the permits we consumed are "lost" — they belonged to the
                // old state which has been replaced by a fresh full bucket. We retry from
                // scratch against the new state. This is correct because:
                // (a) the new state has full capacity, so the retry will likely succeed immediately
                // (b) refunding into the new state would exceed capacity since it was already reset
                S currentAfterWake = stateRef.get();
                if (currentAfterWake.epoch() != epochBeforePark) {
                    continue; // Epoch changed (reset occurred), retry with fresh state
                }
                return;
            }
        }
    }

    private void refundPermits(int permits) {
        while (true) {
            S current = stateRef.get();
            S refunded = strategy.refund(current, config, permits);
            if (stateRef.compareAndSet(current, refunded)) {
                return;
            }
        }
    }

    /**
     * Parks the current thread until the target wakeup time or until the epoch changes.
     *
     * <p>Fix 5 — Implementation notes:
     * <ul>
     *   <li>{@link LockSupport#parkNanos} does NOT consume the thread's interrupt
     *       status (unlike {@link Thread#sleep}). We check {@code isInterrupted()}
     *       explicitly at the top of each iteration.</li>
     *   <li>Spurious wakeups are handled by the loop — the thread re-checks the
     *       remaining time and parks again if needed.</li>
     *   <li>Epoch changes (from {@link #reset()}) cause an early exit so the
     *       caller can retry against the new state.</li>
     * </ul>
     *
     * @param targetWakeupTime when the reservation's wait duration expires
     * @param expectedEpoch    the epoch at the time of reservation; if it changes, exit early
     * @throws InterruptedException if the thread is interrupted while parked
     */
    private void parkUntil(Instant targetWakeupTime, long expectedEpoch) throws InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(
                        "Thread was interrupted while waiting for rate limiter permits");
            }

            // Epoch changed → reset occurred, caller must retry
            if (stateRef.get().epoch() != expectedEpoch) {
                break;
            }

            Instant now = clock.instant();
            Duration remaining = Duration.between(now, targetWakeupTime);

            if (remaining.isNegative() || remaining.isZero()) {
                break;
            }

            LockSupport.parkNanos(remaining.toNanos());
        }
    }

    private void unparkAll() {
        for (Thread thread : parkedThreads) {
            LockSupport.unpark(thread);
        }
    }

    // ======================== Listeners ========================

    /**
     * Registers an event listener.
     *
     * <p><strong>Fix 4:</strong> Returns a {@link Runnable} that, when executed,
     * unregisters this listener. Prevents memory leaks when listeners are
     * registered from short-lived contexts (e.g., per-request scopes).
     *
     * @param listener the listener to be notified on rate limiter events
     * @return a disposable handle that removes the listener when invoked
     */
    public Runnable onEvent(Consumer<RateLimiterEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        eventListeners.add(listener);
        return () -> eventListeners.remove(listener);
    }

    private void emitEvent(RateLimiterEvent event) {
        for (Consumer<RateLimiterEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "Event listener threw exception for rate limiter '%s' (event: %s): %s"
                                .formatted(config.name(), event.type(), t.getMessage()),
                        t);
            }
        }
    }

    // ======================== Introspection & Maintenance ========================

    public int getAvailablePermits() {
        return strategy.availablePermits(stateRef.get(), config, clock.instant());
    }

    public S getState() {
        return stateRef.get();
    }

    // Fix 3: Return properly typed config instead of raw type
    public RateLimiterConfig<S> getConfig() {
        return config;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Removes all available permits from the bucket.
     *
     * <p><strong>Fix 8:</strong> Drain honors existing reservations — parked threads
     * are not woken and will proceed normally when their wait duration expires.
     * Only future callers are affected by the empty bucket. Permits will refill
     * naturally over time.
     *
     * <p>Use {@link #reset()} to invalidate pending reservations.
     */
    public void drain() {
        Instant now = clock.instant();
        while (true) {
            S current = stateRef.get();
            S drained = strategy.drain(current, config, now);
            if (stateRef.compareAndSet(current, drained)) {
                emitEvent(RateLimiterEvent.drained(config.name(), now));
                // Fix 8: No unparkAll() — existing reservations are honored.
                // Parked threads continue waiting for their reserved permits.
                return;
            }
        }
    }

    /**
     * Resets the rate limiter to full capacity, invalidating all pending reservations.
     *
     * <p>The epoch is incremented so parked threads detect the state change and
     * retry permit acquisition against the fresh bucket.
     */
    public void reset() {
        Instant now = clock.instant();
        while (true) {
            S current = stateRef.get();
            S fresh = strategy.reset(current, config, now);
            if (stateRef.compareAndSet(current, fresh)) {
                emitEvent(RateLimiterEvent.reset(
                        config.name(), strategy.availablePermits(fresh, config, now), now));
                unparkAll();
                return;
            }
        }
    }
}
