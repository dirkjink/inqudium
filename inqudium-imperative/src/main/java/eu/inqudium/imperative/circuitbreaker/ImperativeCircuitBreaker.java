package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.circuitbreaker.CircuitBreakerCore;
import eu.inqudium.core.circuitbreaker.CircuitBreakerException;
import eu.inqudium.core.circuitbreaker.CircuitBreakerSnapshot;
import eu.inqudium.core.circuitbreaker.CircuitState;
import eu.inqudium.core.circuitbreaker.PermissionResult;
import eu.inqudium.core.circuitbreaker.StateTransition;

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

  private final CircuitBreakerConfig config;
  private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<StateTransition>> transitionListeners;

  // Lock exclusively used to serialize state transitions (Fix 3: NOT for event emissions)
  private final ReentrantLock transitionLock = new ReentrantLock();

  public ImperativeCircuitBreaker(CircuitBreakerConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(CircuitBreakerSnapshot.initial(clock.instant()));
    this.transitionListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    acquirePermissionOrThrow();
    try {
      T result = callable.call();
      recordSuccess();
      return result;
    } catch (Throwable t) {
      // Fix 7: Preserve interrupt status before any further processing
      if (t instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      handleThrowable(t);
      if (t instanceof Exception e) {
        throw e;
      } else if (t instanceof Error err) {
        throw err;
      } else {
        throw new RuntimeException(t);
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
   * Fix 6: Executes the callable with a fallback that activates on ANY exception,
   * including business exceptions thrown by the callable and circuit breaker rejections.
   *
   * <p>This matches the common developer expectation that "fallback" means
   * "alternative result when the primary path fails for any reason".
   */
  public <T> T executeWithFallbackOnAny(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (Exception e) {
      return fallback.get();
    }
  }

  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (CircuitBreakerException e) {
      throw e;
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Permission ========================

  private void acquirePermissionOrThrow() {
    while (true) {
      // Fix 5: Refresh timestamp on every CAS retry to avoid stale time comparisons
      Instant now = clock.instant();
      CircuitBreakerSnapshot current = snapshotRef.get();
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

      if (!result.permitted()) {
        throw new CircuitBreakerException(config.name(), current.state());
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
            throw new CircuitBreakerException(config.name(), current.state());
          }

          if (snapshotRef.compareAndSet(current, result.snapshot())) {
            // Fix 3: Detect transition inside lock, but emit OUTSIDE
            transition = CircuitBreakerCore.detectTransition(
                config.name(), current, result.snapshot(), now).orElse(null);
          } else {
            continue; // CAS failed, retry
          }
        } finally {
          transitionLock.unlock();
        }
        // Fix 3: Listener notification outside the lock to prevent deadlocks
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

  // ======================== Recording ========================

  private void recordSuccess() {
    while (true) {
      // Fix 5: Fresh timestamp per retry
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
          if (snapshotRef.compareAndSet(current, updated)) {
            transition = CircuitBreakerCore.detectTransition(
                config.name(), current, updated, now).orElse(null);
          } else {
            continue;
          }
        } finally {
          transitionLock.unlock();
        }
        // Fix 3: Emit outside lock
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
      // Fix 2: Ignored exceptions in HALF_OPEN must release their attempt slot,
      // otherwise the circuit can get stuck when all slots are consumed by ignored exceptions.
      recordIgnored();
      return;
    }

    while (true) {
      // Fix 5: Fresh timestamp per retry
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
          if (snapshotRef.compareAndSet(current, updated)) {
            transition = CircuitBreakerCore.detectTransition(
                config.name(), current, updated, now).orElse(null);
          } else {
            continue;
          }
        } finally {
          transitionLock.unlock();
        }
        // Fix 3: Emit outside lock
        notifyListeners(transition);
        return;
      } else {
        if (snapshotRef.compareAndSet(current, updated)) {
          return;
        }
      }
    }
  }

  /**
   * Fix 2: Records an ignored call outcome.
   * In HALF_OPEN, releases the attempt slot so it can be reused.
   */
  private void recordIgnored() {
    while (true) {
      CircuitBreakerSnapshot current = snapshotRef.get();
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordIgnored(current);
      if (snapshotRef.compareAndSet(current, updated)) {
        return;
      }
    }
  }

  // ======================== Listeners ========================

  public void onStateTransition(Consumer<StateTransition> listener) {
    transitionListeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Fix 3: Notifies all listeners outside of any lock.
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
        // Fix 3: Log but do not propagate — one bad listener must not break others
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

  public void reset() {
    StateTransition transition = null;
    transitionLock.lock();
    try {
      Instant now = clock.instant();
      CircuitBreakerSnapshot initial = CircuitBreakerSnapshot.initial(now);
      CircuitBreakerSnapshot before = snapshotRef.getAndSet(initial);
      transition = CircuitBreakerCore.detectTransition(
          config.name(), before, initial, now).orElse(null);
    } finally {
      transitionLock.unlock();
    }
    // Fix 3: Emit outside lock
    notifyListeners(transition);
  }
}
