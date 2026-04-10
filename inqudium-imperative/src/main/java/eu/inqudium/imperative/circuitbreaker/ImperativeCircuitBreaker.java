package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.*;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative circuit breaker implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom) or traditional
 * platform threads. Uses lock-free CAS operations for high-throughput fast-paths
 * and a {@link ReentrantLock} exclusively to serialize state transitions.
 *
 * <p>All configuration values are extracted into final fields in the constructor.
 */
public class ImperativeCircuitBreaker<A, R> implements CircuitBreaker<A, R> {

    private static final Logger LOG = Logger.getLogger(ImperativeCircuitBreaker.class.getName());

    // Fix 6: Maximum CAS retries before yielding to prevent CPU spin under extreme contention
    private static final int MAX_CAS_RETRIES_BEFORE_YIELD = 64;

    private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;
    private final InqNanoTimeSource timeSource;
    private final List<Consumer<StateTransition>> transitionListeners;
    private final InqEventPublisher eventPublisher;

    // All config values extracted into final fields
    private final String name;
    private final long waitDurationNanos;
    private final int permittedCallsInHalfOpen;
    private final int successThresholdInHalfOpen;
    private final Predicate<Throwable> recordFailurePredicate;
    private final LongFunction<FailureMetrics> metricsFactory;

    // Lock exclusively used to serialize state transitions (NOT for event emissions)
    private final ReentrantLock transitionLock = new ReentrantLock();

    public ImperativeCircuitBreaker(InqCircuitBreakerConfig config,
                                    LongFunction<FailureMetrics> metricsFactory,
                                    Predicate<Throwable> recordFailurePredicate) {
        Objects.requireNonNull(config, "config must not be null");
        this.timeSource = config.general().nanoTimesource();
        this.eventPublisher = config.eventPublisher();

        // Extract all config values into fields
        this.name = config.name();
        this.waitDurationNanos = config.waitDurationNanos();
        this.permittedCallsInHalfOpen = config.permittedCallsInHalfOpen();
        this.successThresholdInHalfOpen = config.successThresholdInHalfOpen();
        this.recordFailurePredicate = recordFailurePredicate;
        this.metricsFactory = metricsFactory;

        long nowNanos = timeSource.now();
        FailureMetrics initialMetrics = metricsFactory.apply(nowNanos);

        this.snapshotRef = new AtomicReference<>(CircuitBreakerSnapshot.initial(nowNanos, initialMetrics));
        this.transitionListeners = new CopyOnWriteArrayList<>();
    }

    // ======================== InqElement ========================

    private static int yieldIfExcessiveRetries(int retries) {
        if (retries > MAX_CAS_RETRIES_BEFORE_YIELD) {
            Thread.yield();
            return 0;
        }
        return retries + 1;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public InqElementType getElementType() {
        return InqElementType.CIRCUIT_BREAKER;
    }

    // ======================== LayerAction ========================

    @Override
    public InqEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    // ======================== AsyncLayerAction ========================

    @Override
    public R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        acquirePermissionOrThrow();
        boolean outcomeRecorded = false;
        try {
            R result = next.execute(chainId, callId, argument);
            recordSuccess();
            outcomeRecorded = true;
            return result;
        } catch (Exception e) {
            handleThrowable(e);
            outcomeRecorded = true;
            throw (RuntimeException) e;
        } catch (Error e) {
            recordIgnored();
            outcomeRecorded = true;
            throw e;
        } finally {
            if (!outcomeRecorded) {
                recordIgnored();
            }
        }
    }

    // ======================== Execution ========================

    @Override
    public CompletionStage<R> executeAsync(long chainId, long callId, A argument,
                                           InternalAsyncExecutor<A, R> next) {
        acquirePermissionOrThrow();
        CompletionStage<R> stage;
        try {
            stage = next.executeAsync(chainId, callId, argument);
        } catch (Throwable t) {
            if (t instanceof Exception e) {
                handleThrowable(e);
            } else {
                recordIgnored();
            }
            throw t instanceof RuntimeException re ? re : new RuntimeException(t);
        }
        stage.whenComplete((result, error) -> {
            if (error != null) {
                if (error instanceof Exception e) {
                    handleThrowable(e);
                } else {
                    recordIgnored();
                }
            } else {
                recordSuccess();
            }
        });
        return stage;
    }

    public <T> T execute(Callable<T> callable) throws Exception {
        acquirePermissionOrThrow();
        boolean outcomeRecorded = false;
        try {
            T result = callable.call();
            recordSuccess();
            outcomeRecorded = true;
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleThrowable(e);
            outcomeRecorded = true;
            throw e;
        } catch (Error e) {
            recordIgnored();
            outcomeRecorded = true;
            throw e;
        } finally {
            if (!outcomeRecorded) {
                recordIgnored();
            }
        }
    }

    public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
        try {
            return execute(callable);
        } catch (CircuitBreakerException e) {
            return fallback.get();
        }
    }

    public <T> T executeWithFallbackOnAny(Callable<T> callable, Supplier<T> fallback) throws Exception {
        try {
            return execute(callable);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            try {
                return fallback.get();
            } catch (Exception fallbackException) {
                fallbackException.addSuppressed(e);
                throw fallbackException;
            }
        }
    }

    public void execute(Runnable runnable) {
        acquirePermissionOrThrow();
        boolean outcomeRecorded = false;
        try {
            runnable.run();
            recordSuccess();
            outcomeRecorded = true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleThrowable(e);
            outcomeRecorded = true;
            throw e;
        } catch (Error e) {
            recordIgnored();
            outcomeRecorded = true;
            throw e;
        } finally {
            if (!outcomeRecorded) {
                recordIgnored();
            }
        }
    }

    // ======================== Permission ========================

    private void acquirePermissionOrThrow() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();
            PermissionResult result = CircuitBreakerCore.tryAcquirePermission(
                    current, waitDurationNanos, permittedCallsInHalfOpen, nowNanos);

            if (!result.permitted()) {
                throw new CircuitBreakerException(name, result.snapshot().state());
            }

            if (result.snapshot().state() != current.state()) {
                StateTransition transition = null;
                transitionLock.lock();
                try {
                    nowNanos = timeSource.now();
                    current = snapshotRef.get();
                    result = CircuitBreakerCore.tryAcquirePermission(
                            current, waitDurationNanos, permittedCallsInHalfOpen, nowNanos);

                    if (!result.permitted()) {
                        throw new CircuitBreakerException(name, result.snapshot().state());
                    }

                    if (result.snapshot().state() == current.state()) {
                        continue;
                    }

                    if (snapshotRef.compareAndSet(current, result.snapshot())) {
                        transition = CircuitBreakerCore.detectTransition(
                                name, current, result.snapshot(), nowNanos).orElse(null);
                    } else {
                        continue;
                    }
                } finally {
                    transitionLock.unlock();
                }
                notifyListeners(transition);
                return;
            } else {
                if (snapshotRef.compareAndSet(current, result.snapshot())) {
                    return;
                }
            }
        }
    }

    // ======================== Recording ========================

    private void recordSuccess() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordSuccess(
                    current, successThresholdInHalfOpen, nowNanos);

            if (updated.state() != current.state()) {
                StateTransition transition = null;
                transitionLock.lock();
                try {
                    nowNanos = timeSource.now();
                    current = snapshotRef.get();
                    updated = CircuitBreakerCore.recordSuccess(
                            current, successThresholdInHalfOpen, nowNanos);

                    if (updated.state() == current.state()) {
                        continue;
                    }

                    if (snapshotRef.compareAndSet(current, updated)) {
                        transition = CircuitBreakerCore.detectTransition(
                                name, current, updated, nowNanos).orElse(null);
                    } else {
                        continue;
                    }
                } finally {
                    transitionLock.unlock();
                }
                notifyListeners(transition);
                return;
            } else {
                if (snapshotRef.compareAndSet(current, updated)) {
                    return;
                }
            }
        }
    }

    private void handleThrowable(Throwable throwable) {
        if (!recordFailurePredicate.test(throwable)) {
            recordIgnored();
            return;
        }

        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordFailure(current, nowNanos);

            if (updated.state() != current.state()) {
                StateTransition transition = null;
                transitionLock.lock();
                try {
                    nowNanos = timeSource.now();
                    current = snapshotRef.get();
                    updated = CircuitBreakerCore.recordFailure(current, nowNanos);

                    if (updated.state() == current.state()) {
                        continue;
                    }

                    if (snapshotRef.compareAndSet(current, updated)) {
                        transition = CircuitBreakerCore.detectTransition(
                                name, current, updated, nowNanos).orElse(null);
                    } else {
                        continue;
                    }
                } finally {
                    transitionLock.unlock();
                }
                notifyListeners(transition);
                return;
            } else {
                if (snapshotRef.compareAndSet(current, updated)) {
                    return;
                }
            }
        }
    }

    private void recordIgnored() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordIgnored(current);
            if (snapshotRef.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    // ======================== Listeners ========================

    public Runnable onStateTransition(Consumer<StateTransition> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        transitionListeners.add(listener);
        return () -> transitionListeners.remove(listener);
    }

    private void notifyListeners(StateTransition transition) {
        if (transition == null) {
            return;
        }
        for (Consumer<StateTransition> listener : transitionListeners) {
            try {
                listener.accept(transition);
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                        "State transition listener threw exception for circuit breaker '%s': %s"
                                .formatted(name, e.getMessage()),
                        e);
            }
        }
    }

    // ======================== Introspection ========================

    public CircuitState getState() {
        return snapshotRef.get().state();
    }

    public CircuitBreakerSnapshot getSnapshot() {
        return snapshotRef.get();
    }

    public void reset() {
        StateTransition transition = null;
        transitionLock.lock();
        try {
            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();

            if (current.state() == CircuitState.CLOSED
                    && current.successCount() == 0
                    && current.halfOpenAttempts() == 0) {
                FailureMetrics freshMetrics = metricsFactory.apply(nowNanos);
                CircuitBreakerSnapshot refreshed = CircuitBreakerSnapshot.initial(nowNanos, freshMetrics);
                snapshotRef.set(refreshed);
                return;
            }

            FailureMetrics initialMetrics = metricsFactory.apply(nowNanos);
            CircuitBreakerSnapshot initial = CircuitBreakerSnapshot.initial(nowNanos, initialMetrics);
            CircuitBreakerSnapshot before = snapshotRef.getAndSet(initial);
            transition = CircuitBreakerCore.detectTransition(
                    name, before, initial, nowNanos).orElse(null);
        } finally {
            transitionLock.unlock();
        }
        notifyListeners(transition);
    }
}
