package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.circuitbreaker.event.CircuitBreakerOnErrorEvent;
import eu.inqudium.core.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import eu.inqudium.core.circuitbreaker.event.CircuitBreakerOnSuccessEvent;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;

import java.time.Duration;
import java.time.Instant;

/**
 * Base implementation for all circuit breaker paradigms (imperative, Reactor, Kotlin, RxJava).
 *
 * <p>Contains the complete circuit breaker logic — state machine, sliding window,
 * event publishing, exception handling, and decoration. Paradigm modules only
 * provide the mutual exclusion mechanism: {@link #lock()} and {@link #unlock()}.
 *
 * <p>This separation ensures that the state machine, event publishing, and
 * error codes are implemented <strong>once</strong> in the core, not duplicated
 * across every paradigm module.
 *
 * <h2>Subclass contract</h2>
 * <ul>
 *   <li>{@link #lock()} — acquire exclusive access to the circuit breaker state.
 *       Must be reentrant-safe (the implementation calls lock inside lock).</li>
 *   <li>{@link #unlock()} — release exclusive access. Called in {@code finally} blocks.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public abstract class AbstractCircuitBreaker implements InqDecorator {

  private final String name;
  private final CircuitBreakerConfig config;
  private final CircuitBreakerBehavior behavior;
  private final InqEventPublisher eventPublisher;

  private volatile CircuitBreakerState state;
  private SlidingWindow slidingWindow;
  private int halfOpenCallCount;
  private Instant openedAt;

  protected AbstractCircuitBreaker(String name, CircuitBreakerConfig config) {
    this.name = name;
    this.config = config;
    this.behavior = CircuitBreakerBehavior.defaultBehavior();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.CIRCUIT_BREAKER);
    this.state = CircuitBreakerState.CLOSED;
    this.slidingWindow = config.createSlidingWindow();
    this.halfOpenCallCount = 0;
    this.openedAt = null;
  }

  // ── InqDecorator / InqElement ──

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqElementType getElementType() {
    return InqElementType.CIRCUIT_BREAKER;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  public CircuitBreakerConfig getConfig() {
    return config;
  }

  // ── State queries ──

  public CircuitBreakerState getState() {
    if (state == CircuitBreakerState.OPEN) {
      lock();
      try {
        checkOpenToHalfOpen();
      } finally {
        unlock();
      }
    }
    return state;
  }

  public WindowSnapshot getSnapshot() {
    lock();
    try {
      return slidingWindow.snapshot();
    } finally {
      unlock();
    }
  }

  // ── Decoration — template method ──

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

  // ── Manual state transitions ──

  public void transitionToClosedState() {
    lock();
    try {
      transitionTo(CircuitBreakerState.CLOSED, "manual");
      slidingWindow.reset();
    } finally {
      unlock();
    }
  }

  public void transitionToOpenState() {
    lock();
    try {
      transitionTo(CircuitBreakerState.OPEN, "manual");
      openedAt = config.getClock().instant();
    } finally {
      unlock();
    }
  }

  public void transitionToHalfOpenState() {
    lock();
    try {
      transitionTo(CircuitBreakerState.HALF_OPEN, "manual");
      halfOpenCallCount = 0;
      slidingWindow.reset();
    } finally {
      unlock();
    }
  }

  public void reset() {
    lock();
    try {
      state = CircuitBreakerState.CLOSED;
      slidingWindow = config.createSlidingWindow();
      halfOpenCallCount = 0;
      openedAt = null;
    } finally {
      unlock();
    }
  }

  // ── Internal state machine logic ──

  private void acquirePermission(String callId) {
    lock();
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
      unlock();
    }
  }

  private void onSuccess(String callId, Instant start) {
    lock();
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
      unlock();
    }
  }

  private void onError(String callId, Instant start, Throwable throwable) {
    lock();
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
      unlock();
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

  // ── Abstract — paradigm-specific lock mechanism ──

  /**
   * Acquires exclusive access to the circuit breaker state.
   *
   * <p>Must be reentrant-safe — the circuit breaker calls lock inside lock
   * (e.g. {@code onSuccess} → {@code transitionTo} → event publish).
   *
   * <p>Imperative: {@link java.util.concurrent.locks.ReentrantLock#lock()}.
   * Kotlin: coroutine Mutex. Reactor: may use a different strategy.
   */
  protected abstract void lock();

  /**
   * Releases exclusive access. Called in {@code finally} blocks.
   */
  protected abstract void unlock();
}
