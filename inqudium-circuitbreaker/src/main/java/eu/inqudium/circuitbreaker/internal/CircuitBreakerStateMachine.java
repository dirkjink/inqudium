package eu.inqudium.circuitbreaker.internal;

import eu.inqudium.circuitbreaker.CircuitBreaker;
import eu.inqudium.circuitbreaker.event.CircuitBreakerOnErrorEvent;
import eu.inqudium.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import eu.inqudium.circuitbreaker.event.CircuitBreakerOnSuccessEvent;
import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.circuitbreaker.*;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Imperative circuit breaker implementation using {@link ReentrantLock}.
 *
 * <p>Virtual-thread safe — no {@code synchronized} blocks, no carrier-thread
 * pinning (ADR-008). All mutable state is protected by a single lock.
 *
 * @since 0.1.0
 */
public final class CircuitBreakerStateMachine implements CircuitBreaker {

    private final String name;
    private final CircuitBreakerConfig config;
    private final CircuitBreakerBehavior behavior;
    private final InqEventPublisher eventPublisher;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile CircuitBreakerState state;
    private SlidingWindow slidingWindow;
    private int halfOpenCallCount;
    private Instant openedAt;

    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig config) {
        this.name = name;
        this.config = config;
        this.behavior = CircuitBreakerBehavior.defaultBehavior();
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.CIRCUIT_BREAKER);
        this.state = CircuitBreakerState.CLOSED;
        this.slidingWindow = config.createSlidingWindow();
        this.halfOpenCallCount = 0;
        this.openedAt = null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public InqEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    @Override
    public CircuitBreakerState getState() {
        if (state == CircuitBreakerState.OPEN) {
            lock.lock();
            try {
                checkOpenToHalfOpen();
            } finally {
                lock.unlock();
            }
        }
        return state;
    }

    @Override
    public WindowSnapshot getSnapshot() {
        lock.lock();
        try {
            return slidingWindow.snapshot();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CircuitBreakerConfig getConfig() {
        return config;
    }

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        return call.withCallable(() -> executeCall(call));
    }

    private <T> T executeCall(InqCall<T> call) throws Exception {
        var callId = call.callId();
        acquirePermission(callId);
        var start = config.getClock().instant();
        try {
            T result = call.callable().call();
            onSuccess(callId, start);
            return result;
        } catch (Exception e) {
            onError(callId, start, e);
            throw e;
        }
    }

    @Override
    public void transitionToClosedState() {
        lock.lock();
        try {
            transitionTo(CircuitBreakerState.CLOSED, "manual");
            slidingWindow.reset();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void transitionToOpenState() {
        lock.lock();
        try {
            transitionTo(CircuitBreakerState.OPEN, "manual");
            openedAt = config.getClock().instant();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void transitionToHalfOpenState() {
        lock.lock();
        try {
            transitionTo(CircuitBreakerState.HALF_OPEN, "manual");
            halfOpenCallCount = 0;
            slidingWindow.reset();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            state = CircuitBreakerState.CLOSED;
            slidingWindow = config.createSlidingWindow();
            halfOpenCallCount = 0;
            openedAt = null;
        } finally {
            lock.unlock();
        }
    }

    private void acquirePermission(String callId) {
        lock.lock();
        try {
            checkOpenToHalfOpen();

            if (state == CircuitBreakerState.OPEN) {
                var snapshot = slidingWindow.snapshot();
                throw new InqCallNotPermittedException(
                        callId, name, CircuitBreakerState.OPEN, snapshot.failureRate());
            }

            if (state == CircuitBreakerState.HALF_OPEN) {
                if (halfOpenCallCount >= config.getPermittedNumberOfCallsInHalfOpenState()) {
                    throw new InqCallNotPermittedException(
                            callId, name, CircuitBreakerState.HALF_OPEN, slidingWindow.snapshot().failureRate());
                }
                halfOpenCallCount++;
            }
        } finally {
            lock.unlock();
        }
    }

    private void onSuccess(String callId, Instant start) {
        lock.lock();
        try {
            var now = config.getClock().instant();
            var durationNanos = Duration.between(start, now).toNanos();
            var outcome = CallOutcome.success(durationNanos, now);
            var snapshot = slidingWindow.record(outcome);
            var newState = behavior.onSuccess(state, snapshot, config);

            if (newState != state) {
                transitionTo(newState, callId);
                if (newState == CircuitBreakerState.CLOSED) {
                    slidingWindow.reset();
                }
            }

            eventPublisher.publish(new CircuitBreakerOnSuccessEvent(
                    callId, name, Duration.between(start, now), now));
        } finally {
            lock.unlock();
        }
    }

    private void onError(String callId, Instant start, Throwable throwable) {
        lock.lock();
        try {
            var now = config.getClock().instant();
            var durationNanos = Duration.between(start, now).toNanos();
            var outcome = CallOutcome.failure(durationNanos, now);
            var snapshot = slidingWindow.record(outcome);
            var newState = behavior.onError(state, snapshot, config);

            if (newState != state) {
                transitionTo(newState, callId);
                if (newState == CircuitBreakerState.OPEN) {
                    openedAt = now;
                }
            }

            eventPublisher.publish(new CircuitBreakerOnErrorEvent(
                    callId, name, Duration.between(start, now), throwable, now));
        } finally {
            lock.unlock();
        }
    }

    private void checkOpenToHalfOpen() {
        if (state == CircuitBreakerState.OPEN && openedAt != null) {
            var now = config.getClock().instant();
            var elapsed = Duration.between(openedAt, now);
            if (elapsed.compareTo(config.getWaitDurationInOpenState()) >= 0) {
                transitionTo(CircuitBreakerState.HALF_OPEN, "timer");
                halfOpenCallCount = 0;
                slidingWindow.reset();
            }
        }
    }

    private void transitionTo(CircuitBreakerState newState, String callId) {
        var oldState = state;
        if (oldState == newState) return;
        state = newState;
        eventPublisher.publish(new CircuitBreakerOnStateTransitionEvent(
                callId, name, oldState, newState, config.getClock().instant()));
    }
}
