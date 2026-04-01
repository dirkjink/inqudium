package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerCore;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerException;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerSnapshot;
import eu.inqudium.core.element.circuitbreaker.CircuitState;
import eu.inqudium.core.element.circuitbreaker.PermissionResult;
import eu.inqudium.core.element.circuitbreaker.StateTransition;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;

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
 */
public class ImperativeCircuitBreaker {

  private static final Logger LOG = Logger.getLogger(ImperativeCircuitBreaker.class.getName());

  // Fix 6: Maximum CAS retries before yielding to prevent CPU spin under extreme contention
  private static final int MAX_CAS_RETRIES_BEFORE_YIELD = 64;

  private final CircuitBreakerConfig config;
  private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<StateTransition>> transitionListeners;

  // Lock exclusively used to serialize state transitions (NOT for event emissions)
  private final ReentrantLock transitionLock = new ReentrantLock();

  public ImperativeCircuitBreaker(CircuitBreakerConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");

    Instant now = clock.instant();
    FailureMetrics initialMetrics = config.metricsFactory().apply(now);

    this.snapshotRef = new AtomicReference<>(CircuitBreakerSnapshot.initial(now, initialMetrics));
    this.transitionListeners = new CopyOnWriteArrayList<>();
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
      // Fix 2b: Only Exceptions reach handleThrowable — the predicate decides
      // whether to record as failure or ignore.
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      handleThrowable(e);
      outcomeRecorded = true;
      throw e;
    } catch (Error e) {
      // Fix 2b: JVM-level errors are never downstream failures.
      // Release the HALF_OPEN slot if applicable.
      recordIgnored();
      outcomeRecorded = true;
      throw e;
    } finally {
      // Fix 3: Safety net — if no outcome was recorded (e.g., due to an unexpected
      // Throwable subclass or a bug in handleThrowable), release the slot to prevent
      // permanent HALF_OPEN starvation.
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
   * Use {@link #executeWithFallbackOnAny(Callable, Supplier)} if you want the fallback
   * to also cover business exceptions.
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
   * <p>This matches the common developer expectation that "fallback" means
   * "alternative result when the primary path fails for any reason".
   *
   * <p><strong>Bonus fix:</strong> If the fallback itself throws, the original exception
   * is attached as a suppressed exception for debugging.
   */
  public <T> T executeWithFallbackOnAny(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (InterruptedException e) {
      // Do not mask thread interrupts by executing a fallback.
      // The interrupt status was already restored in execute(), but we must re-throw
      // to ensure the calling context can abort properly.
      throw e;
    } catch (Exception e) {
      // Bonus: Preserve the original cause if the fallback also fails
      try {
        return fallback.get();
      } catch (Exception fallbackException) {
        fallbackException.addSuppressed(e);
        throw fallbackException;
      }
    }
  }

  // ======================== Permission ========================

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
      // Runnable.run() cannot throw checked exceptions,
      // so anything caught here is an unchecked exception.
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

  // ======================== Recording ========================

  private void acquirePermissionOrThrow() {
    int retries = 0;
    while (true) {
      // Fix 6: Yield after too many CAS retries to prevent CPU spin
      retries = yieldIfExcessiveRetries(retries);

      // Fix 5: Refresh timestamp on every CAS retry to avoid stale time comparisons
      Instant now = clock.instant();
      CircuitBreakerSnapshot current = snapshotRef.get();
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

      if (!result.permitted()) {
        // Fix 5: Use state from result, not from the potentially stale 'current'
        throw new CircuitBreakerException(config.name(), result.snapshot().state());
      }

      if (result.snapshot().state() != current.state()) {
        // Slow-path: state transition requires lock
        StateTransition transition = null;
        transitionLock.lock();
        try {
          now = clock.instant();
          current = snapshotRef.get();
          result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

          if (!result.permitted()) {
            // Fix 5: Use state from result
            throw new CircuitBreakerException(config.name(), result.snapshot().state());
          }

          // Bail out early if another thread already transitioned
          if (result.snapshot().state() == current.state()) {
            continue;
          }

          if (snapshotRef.compareAndSet(current, result.snapshot())) {
            transition = CircuitBreakerCore.detectTransition(
                config.name(), current, result.snapshot(), now).orElse(null);
          } else {
            continue; // CAS failed, retry
          }
        } finally {
          transitionLock.unlock();
        }
        // Listener notification outside the lock to prevent deadlocks
        notifyListeners(transition);
        return;
      } else {
        // Fast-path: simple state increment, no lock needed
        if (snapshotRef.compareAndSet(current, result.snapshot())) {
          return;
        }
      }
      // CAS failed, retry loop
    }
  }

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

          // Bail out early if another thread already transitioned
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

          // Bail out early if another thread already completed the state transition
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

  // ======================== Fix 6: CAS starvation guard ========================

  /**
   * Records an ignored call outcome.
   * In HALF_OPEN, releases the attempt slot so it can be reused.
   */
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

    // Return a disposable handle to allow removing the listener
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

      // Fix 10: Skip transition event if already in a clean initial state.
      // The metrics are still refreshed to clear the sliding window.
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
