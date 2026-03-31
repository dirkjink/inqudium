package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.circuitbreaker.*;

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

/**
 * Thread-safe, imperative circuit breaker implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom) or traditional
 * platform threads. Uses lock-free CAS operations for high-throughput fast-paths
 * and a {@link ReentrantLock} exclusively to serialize state transitions.
 */
public class ImperativeCircuitBreaker {

  private final CircuitBreakerConfig config;
  private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<StateTransition>> transitionListeners;

  // Lock exclusively used to serialize state transitions and event emissions (Fix 1A)
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
      // Fix 2B: Catch Throwable to ensure Errors (e.g., OutOfMemoryError) are evaluated
    } catch (Throwable t) {
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

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (CircuitBreakerException e) {
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
      throw e; // Rethrow directly
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Permission ========================

  private void acquirePermissionOrThrow() {
    Instant now = clock.instant();
    while (true) {
      CircuitBreakerSnapshot current = snapshotRef.get();
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

      if (!result.permitted()) {
        throw new CircuitBreakerException(config.name(), current.state());
      }

      // Fix 1A: Fast-path (no transition) vs Slow-path (transition)
      if (result.snapshot().state() != current.state()) {
        transitionLock.lock();
        try {
          // Re-evaluate inside lock to guarantee strict event order
          current = snapshotRef.get();
          result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

          if (!result.permitted()) {
            throw new CircuitBreakerException(config.name(), current.state());
          }

          if (snapshotRef.compareAndSet(current, result.snapshot())) {
            emitTransitionIfChanged(current, result.snapshot(), now);
            return;
          }
        } finally {
          transitionLock.unlock();
        }
      } else {
        // Fast-path: simple state increment
        if (snapshotRef.compareAndSet(current, result.snapshot())) {
          return;
        }
      }
    }
  }

  // ======================== Recording ========================

  private void recordSuccess() {
    Instant now = clock.instant();
    while (true) {
      CircuitBreakerSnapshot current = snapshotRef.get();
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordSuccess(current, config, now);

      if (updated.state() != current.state()) {
        transitionLock.lock();
        try {
          current = snapshotRef.get();
          updated = CircuitBreakerCore.recordSuccess(current, config, now);
          if (snapshotRef.compareAndSet(current, updated)) {
            emitTransitionIfChanged(current, updated, now);
            return;
          }
        } finally {
          transitionLock.unlock();
        }
      } else {
        if (snapshotRef.compareAndSet(current, updated)) {
          return;
        }
      }
    }
  }

  private void handleThrowable(Throwable throwable) {
    if (!config.shouldRecordAsFailure(throwable)) {
      // Fix 2A: Ignored exceptions are simply bypassed.
      // They do NOT count as a success, preventing premature state switching.
      return;
    }

    Instant now = clock.instant();
    while (true) {
      CircuitBreakerSnapshot current = snapshotRef.get();
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordFailure(current, config, now);

      if (updated.state() != current.state()) {
        transitionLock.lock();
        try {
          current = snapshotRef.get();
          updated = CircuitBreakerCore.recordFailure(current, config, now);
          if (snapshotRef.compareAndSet(current, updated)) {
            emitTransitionIfChanged(current, updated, now);
            return;
          }
        } finally {
          transitionLock.unlock();
        }
      } else {
        if (snapshotRef.compareAndSet(current, updated)) {
          return;
        }
      }
    }
  }

  // ======================== Listeners ========================

  public void onStateTransition(Consumer<StateTransition> listener) {
    transitionListeners.add(Objects.requireNonNull(listener));
  }

  private void emitTransitionIfChanged(CircuitBreakerSnapshot before, CircuitBreakerSnapshot after, Instant now) {
    StateTransition transition = CircuitBreakerCore.detectTransition(config.name(), before, after, now);
    if (transition != null) {
      for (Consumer<StateTransition> listener : transitionListeners) {
        listener.accept(transition);
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
    // Fix 1B: Atomically pre-construct the snapshot and lock to ensure clean event emission
    transitionLock.lock();
    try {
      Instant now = clock.instant();
      CircuitBreakerSnapshot initial = CircuitBreakerSnapshot.initial(now);
      CircuitBreakerSnapshot before = snapshotRef.getAndSet(initial);
      emitTransitionIfChanged(before, initial, now);
    } finally {
      transitionLock.unlock();
    }
  }
}