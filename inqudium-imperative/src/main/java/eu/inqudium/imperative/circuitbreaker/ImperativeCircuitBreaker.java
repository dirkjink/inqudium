package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerCore;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerException;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerSnapshot;
import eu.inqudium.core.element.circuitbreaker.CircuitState;
import eu.inqudium.core.element.circuitbreaker.PermissionResult;
import eu.inqudium.core.element.circuitbreaker.StateTransition;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
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
 * <p>Implements {@link InqDecorator} to participate in the pipeline as a
 * self-describing, pluggable decorator with around-semantics.
 */
public class ImperativeCircuitBreaker implements InqDecorator<Void, Object> {

  private static final Logger LOG = Logger.getLogger(ImperativeCircuitBreaker.class.getName());

  // Fix 6: Maximum CAS retries before yielding to prevent CPU spin under extreme contention
  private static final int MAX_CAS_RETRIES_BEFORE_YIELD = 64;

  private final CircuitBreakerConfig config;
  private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<StateTransition>> transitionListeners;
  private final InqEventPublisher eventPublisher;

  // Lock exclusively used to serialize state transitions (NOT for event emissions)
  private final ReentrantLock transitionLock = new ReentrantLock();

  public ImperativeCircuitBreaker(CircuitBreakerConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
    this(config, clock, InqEventPublisher.create(config.name(), InqElementType.CIRCUIT_BREAKER));
  }

  public ImperativeCircuitBreaker(CircuitBreakerConfig config, Clock clock, InqEventPublisher eventPublisher) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");

    Instant now = clock.instant();
    FailureMetrics initialMetrics = config.metricsFactory().apply(now);

    this.snapshotRef = new AtomicReference<>(CircuitBreakerSnapshot.initial(now, initialMetrics));
    this.transitionListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== InqElement ========================

  @Override
  public String getName() {
    return config.name();
  }

  @Override
  public InqElementType getElementType() {
    return InqElementType.CIRCUIT_BREAKER;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  // ======================== LayerAction ========================

  /**
   * Around-advice implementation for the pipeline chain.
   *
   * <p>Acquires permission from the circuit breaker, delegates to the next
   * step in the chain, and records the outcome (success, failure, or ignored).
   */
  @Override
  @SuppressWarnings("unchecked")
  public Object execute(long chainId, long callId, Void argument, InternalExecutor<Void, Object> next) {
    acquirePermissionOrThrow();
    boolean outcomeRecorded = false;
    try {
      Object result = next.execute(chainId, callId, argument);
      recordSuccess();
      outcomeRecorded = true;
      return result;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      handleThrowable(e);
      outcomeRecorded = true;
      throw e instanceof RuntimeException re ? re : new RuntimeException(e);
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

  /**
   * Yields the current thread if the CAS retry count exceeds the threshold.
   * Prevents CPU spin under extreme contention. Resets the counter after yielding.
   *
   * @param retries current retry count
   * @return the (possibly reset) retry count
   */
  private static int yieldIfExcessiveRetries(int retries) {
    if (retries > MAX_CAS_RETRIES_BEFORE_YIELD) {
      Thread.yield();
      return 0;
    }
    return retries + 1;
  }

  /**
   * Executes the callable, recording the outcome with the circuit breaker.
   *
   * <p><strong>Fix 2b:</strong> Errors (OOM, StackOverflow) are never recorded as
   * downstream failures — they are treated as ignored to release HALF_OPEN slots.
   *
   * <p><strong>Fix 3:</strong> A {@code finally} safety net ensures that every
   * acquired permission slot is released, even in unexpected scenarios.
   */
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

  /**
   * Executes the callable with a fallback that activates only when the circuit breaker
   * rejects the call (i.e., circuit is OPEN or HALF_OPEN with no remaining slots).
   *
   * <p>Business exceptions thrown by the callable are NOT caught — they propagate normally.
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (CircuitBreakerException e) {
      return fallback.get();
    }
  }

  /**
   * Executes the callable with a fallback that activates on ANY exception,
   * including business exceptions thrown by the callable and circuit breaker rejections.
   *
   * <p><strong>Bonus fix:</strong> If the fallback itself throws, the original exception
   * is attached as a suppressed exception for debugging.
   */
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

  /**
   * Executes the runnable, recording the outcome with the circuit breaker.
   *
   * <p><strong>Fix 4:</strong> Implemented directly instead of delegating to the
   * Callable variant — eliminates the unreachable catch block and wrapping overhead.
   */
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

      Instant now = clock.instant();
      CircuitBreakerSnapshot current = snapshotRef.get();
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

      if (!result.permitted()) {
        throw new CircuitBreakerException(config.name(), result.snapshot().state());
      }

      if (result.snapshot().state() != current.state()) {
        StateTransition transition = null;
        transitionLock.lock();
        try {
          now = clock.instant();
          current = snapshotRef.get();
          result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

          if (!result.permitted()) {
            throw new CircuitBreakerException(config.name(), result.snapshot().state());
          }

          if (result.snapshot().state() == current.state()) {
            continue;
          }

          if (snapshotRef.compareAndSet(current, result.snapshot())) {
            transition = CircuitBreakerCore.detectTransition(
                config.name(), current, result.snapshot(), now).orElse(null);
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

      Instant now = clock.instant();
      CircuitBreakerSnapshot current = snapshotRef.get();
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordSuccess(current, config, now);

      if (updated.state() != current.state()) {
        StateTransition transition = null;
        transitionLock.lock();
        try {
          now = clock.instant();
          current = snapshotRef.get();
          updated = CircuitBreakerCore.recordSuccess(current, config, now);

          if (updated.state() == current.state()) {
            continue;
          }

          if (snapshotRef.compareAndSet(current, updated)) {
            transition = CircuitBreakerCore.detectTransition(
                config.name(), current, updated, now).orElse(null);
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
    if (!config.shouldRecordAsFailure(throwable)) {
      recordIgnored();
      return;
    }

    int retries = 0;
    while (true) {
      retries = yieldIfExcessiveRetries(retries);

      Instant now = clock.instant();
      CircuitBreakerSnapshot current = snapshotRef.get();
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordFailure(current, config, now);

      if (updated.state() != current.state()) {
        StateTransition transition = null;
        transitionLock.lock();
        try {
          now = clock.instant();
          current = snapshotRef.get();
          updated = CircuitBreakerCore.recordFailure(current, config, now);

          if (updated.state() == current.state()) {
            continue;
          }

          if (snapshotRef.compareAndSet(current, updated)) {
            transition = CircuitBreakerCore.detectTransition(
                config.name(), current, updated, now).orElse(null);
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

  /**
   * Registers a state transition listener.
   *
   * @param listener the listener to be notified on state transitions
   * @return a Runnable that, when executed, unregisters this listener to prevent memory leaks
   */
  public Runnable onStateTransition(Consumer<StateTransition> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    transitionListeners.add(listener);
    return () -> transitionListeners.remove(listener);
  }

  /**
   * Notifies all listeners outside of any lock.
   * Each listener is invoked in its own try-catch to ensure that a failing listener
   * does not prevent subsequent listeners from being notified.
   */
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
                .formatted(config.name(), e.getMessage()),
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

  public CircuitBreakerConfig getConfig() {
    return config;
  }

  /**
   * Resets the circuit breaker to its initial CLOSED state.
   *
   * <p><strong>Fix 10:</strong> When already in a clean CLOSED state, the metrics
   * are refreshed but no transition event is fired, avoiding unnecessary CAS
   * contention in other threads.
   */
  public void reset() {
    StateTransition transition = null;
    transitionLock.lock();
    try {
      Instant now = clock.instant();
      CircuitBreakerSnapshot current = snapshotRef.get();

      if (current.state() == CircuitState.CLOSED
          && current.successCount() == 0
          && current.halfOpenAttempts() == 0) {
        FailureMetrics freshMetrics = config.metricsFactory().apply(now);
        CircuitBreakerSnapshot refreshed = CircuitBreakerSnapshot.initial(now, freshMetrics);
        snapshotRef.set(refreshed);
        return;
      }

      FailureMetrics initialMetrics = config.metricsFactory().apply(now);
      CircuitBreakerSnapshot initial = CircuitBreakerSnapshot.initial(now, initialMetrics);
      CircuitBreakerSnapshot before = snapshotRef.getAndSet(initial);
      transition = CircuitBreakerCore.detectTransition(
          config.name(), before, initial, now).orElse(null);
    } finally {
      transitionLock.unlock();
    }
    notifyListeners(transition);
  }
}
