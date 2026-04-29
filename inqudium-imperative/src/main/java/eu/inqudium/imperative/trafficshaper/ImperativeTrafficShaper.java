package eu.inqudium.imperative.trafficshaper;

import eu.inqudium.core.element.trafficshaper.ThrottlePermission;
import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;
import eu.inqudium.core.element.trafficshaper.TrafficShaperEvent;
import eu.inqudium.core.element.trafficshaper.TrafficShaperException;
import eu.inqudium.core.element.trafficshaper.strategy.SchedulingState;
import eu.inqudium.core.element.trafficshaper.strategy.SchedulingStrategy;

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
 * Thread-safe, imperative traffic shaper implementation with a pluggable
 * {@link SchedulingStrategy}.
 *
 * <p>The strategy is always obtained from {@link TrafficShaperConfig#strategy()},
 * ensuring type-safe consistency between configuration and algorithm.
 *
 * <p>Uses lock-free CAS operations for scheduling and
 * {@link LockSupport#parkNanos} for the throttle delay (virtual-thread-friendly).
 *
 * <h2>Reset behavior</h2>
 * <p>{@link #reset()} increments the state's epoch and unparks all waiting
 * threads. Parked threads detect the epoch change, skip their stale slot,
 * and re-acquire a fresh slot from the reset state.
 *
 * @param <S> the strategy-specific state type
 */
public class ImperativeTrafficShaper<S extends SchedulingState> {

    private static final Logger LOG = Logger.getLogger(ImperativeTrafficShaper.class.getName());
    private static final int MAX_CAS_RETRIES_BEFORE_YIELD = 64;

    private final TrafficShaperConfig<S> config;
    private final SchedulingStrategy<S> strategy;
    private final AtomicReference<S> stateRef;
    private final Clock clock;
    private final List<Consumer<TrafficShaperEvent>> eventListeners;
    private final String instanceId;
    private final Set<Thread> parkedThreads;

    public ImperativeTrafficShaper(TrafficShaperConfig<S> config) {
        this(config, Clock.systemUTC());
    }

    public ImperativeTrafficShaper(TrafficShaperConfig<S> config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.strategy = config.strategy();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stateRef = new AtomicReference<>(strategy.initial(config, clock.instant()));
        this.eventListeners = new CopyOnWriteArrayList<>();
        this.instanceId = UUID.randomUUID().toString();
        this.parkedThreads = ConcurrentHashMap.newKeySet();
    }

    // ======================== Execution ========================

    private static int yieldIfExcessiveRetries(int retries) {
        if (retries > MAX_CAS_RETRIES_BEFORE_YIELD) {
            Thread.yield();
            return 0;
        }
        return retries + 1;
    }

    public <T> T execute(Callable<T> callable) throws Exception {
        Objects.requireNonNull(callable, "callable must not be null");
        waitForSlot();
        return callable.call();
    }

    public void execute(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        try {
            execute(() -> {
                runnable.run();
                return null;
            });
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ======================== Slot acquisition ========================

    public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
        try {
            return execute(callable);
        } catch (TrafficShaperException e) {
            if (Objects.equals(e.getInstanceId(), this.instanceId)) {
                return fallback.get();
            }
            throw e;
        }
    }

    private void waitForSlot() throws InterruptedException {
        ThrottlePermission<S> permission = acquireSlot();

        if (!permission.requiresWait()) {
            return;
        }

        long epochAtSchedule = permission.state().epoch();

        try {
            parkedThreads.add(Thread.currentThread());
            parkUntil(permission.scheduledSlot(), epochAtSchedule);
        } catch (InterruptedException e) {
            recordExecution();
            throw e;
        } finally {
            parkedThreads.remove(Thread.currentThread());
        }

        // Check if a reset occurred while parked
        S currentAfterWake = stateRef.get();
        if (currentAfterWake.epoch() != epochAtSchedule) {
            recordExecution();
            waitForSlot(); // Retry with fresh state
            return;
        }

        recordExecution();
    }

    // ======================== Internal — Parking ========================

    private ThrottlePermission<S> acquireSlot() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            Instant now = clock.instant();
            S current = stateRef.get();

            ThrottlePermission<S> permission = strategy.schedule(current, config, now);

            if (!permission.admitted()) {
                // Best-effort commit of the rejection counter
                stateRef.compareAndSet(current, permission.state());

                emitEvent(TrafficShaperEvent.rejected(
                        config.name(), permission.waitDuration(),
                        strategy.queueDepth(permission.state()), now));
                throw new TrafficShaperException(
                        config.name(), instanceId, permission.waitDuration(),
                        strategy.queueDepth(current));
            }

            if (stateRef.compareAndSet(current, permission.state())) {
                if (permission.requiresWait()) {
                    emitEvent(TrafficShaperEvent.admittedDelayed(
                            config.name(), permission.waitDuration(),
                            strategy.queueDepth(permission.state()), now));

                    if (strategy.isUnboundedQueueWarning(permission.state(), config, now)) {
                        emitEvent(TrafficShaperEvent.unboundedQueueWarning(
                                config.name(),
                                permission.state().projectedTailWait(now),
                                strategy.queueDepth(permission.state()),
                                now));
                    }
                } else {
                    emitEvent(TrafficShaperEvent.admittedImmediate(
                            config.name(), strategy.queueDepth(permission.state()), now));
                }
                return permission;
            }
        }
    }

    private void parkUntil(Instant targetWakeup, long expectedEpoch) throws InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(
                        "Thread interrupted while waiting for traffic shaper '%s' slot"
                                .formatted(config.name()));
            }

            if (stateRef.get().epoch() != expectedEpoch) {
                break;
            }

            Duration remaining = Duration.between(clock.instant(), targetWakeup);
            if (remaining.isNegative() || remaining.isZero()) {
                break;
            }
            LockSupport.parkNanos(remaining.toNanos());
        }
    }

    private void recordExecution() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            Instant now = clock.instant();
            S current = stateRef.get();
            S dequeued = strategy.recordExecution(current);

            if (stateRef.compareAndSet(current, dequeued)) {
                emitEvent(TrafficShaperEvent.executing(
                        config.name(), strategy.queueDepth(dequeued), now));
                return;
            }
        }
    }

    private void unparkAll() {
        for (Thread thread : parkedThreads) {
            LockSupport.unpark(thread);
        }
    }

    // ======================== Listeners ========================

    public Runnable onEvent(Consumer<TrafficShaperEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        eventListeners.add(listener);
        return () -> eventListeners.remove(listener);
    }

    private void emitEvent(TrafficShaperEvent event) {
        for (Consumer<TrafficShaperEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "Event listener threw exception for traffic shaper '%s' (event: %s): %s"
                                .formatted(config.name(), event.type(), t.getMessage()), t);
            }
        }
    }

    // ======================== Introspection & Maintenance ========================

    public int getQueueDepth() {
        return strategy.queueDepth(stateRef.get());
    }

    public Duration getEstimatedWait() {
        return strategy.estimateWait(stateRef.get(), config, clock.instant());
    }

    public S getState() {
        return stateRef.get();
    }

    public TrafficShaperConfig<S> getConfig() {
        return config;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void reset() {
        Instant now = clock.instant();
        while (true) {
            S current = stateRef.get();
            S fresh = strategy.reset(current, config, now);
            if (stateRef.compareAndSet(current, fresh)) {
                emitEvent(TrafficShaperEvent.reset(config.name(), now));
                unparkAll();
                return;
            }
        }
    }
}
