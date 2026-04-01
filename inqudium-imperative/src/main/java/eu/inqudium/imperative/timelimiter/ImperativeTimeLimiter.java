package eu.inqudium.imperative.timelimiter;

import eu.inqudium.core.timelimiter.ExecutionSnapshot;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import eu.inqudium.core.timelimiter.TimeLimiterCore;
import eu.inqudium.core.timelimiter.TimeLimiterEvent;
import eu.inqudium.core.timelimiter.TimeLimiterException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative time limiter implementation.
 */
public class ImperativeTimeLimiter {

  private static final Logger LOG = Logger.getLogger(ImperativeTimeLimiter.class.getName());

  private final TimeLimiterConfig config;
  private final Clock clock;
  private final List<Consumer<TimeLimiterEvent>> eventListeners;

  // Fix 3: Unique instance identifier for identity-based comparison in executeWithFallback
  private final String instanceId;

  public ImperativeTimeLimiter(TimeLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeTimeLimiter(TimeLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.instanceId = UUID.randomUUID().toString();
  }

  // ======================== Callable Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    return execute(callable, config.timeout());
  }

  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    // Fix 8: Fail fast on null
    Objects.requireNonNull(callable, "callable must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive, got " + timeout);
    }

    Instant now = clock.instant();
    // Fix 4: Use the effective timeout (the parameter) instead of config.timeout()
    // so the snapshot deadline matches the actual wait duration
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    FutureTask<T> task = new FutureTask<>(callable);
    Thread.ofVirtual().name("timelimiter-" + config.name()).start(task);

    // Fix 1: Wrap awaitFuture in try-finally to guarantee task cancellation
    // if any unexpected exception occurs (listener errors, CancellationException, etc.)
    try {
      return awaitFuture(task, snapshot, timeout);
    } catch (Throwable t) {
      // Fix 1: Ensure the task is cancelled on any unhandled exception
      // to prevent thread leaks from orphaned virtual threads
      cancelTaskSafely(task);
      throw t;
    }
  }

  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Executes with a fallback that activates when <em>this</em> time limiter times out.
   *
   * <p>Fix 3: Uses {@code instanceId} to determine whether the exception originated
   * from this time limiter instance. Falls back to name comparison for exceptions
   * created by custom exception factories that don't carry an instanceId.
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TimeLimiterException e) {
      if (isOwnException(e)) {
        return fallback.get();
      }
      throw e;
    }
  }

  /**
   * Fix 3: Checks if the exception was produced by this instance.
   * Prefers instanceId match; falls back to name match if instanceId is null
   * (e.g. from a custom exception factory that uses the 2-arg constructor).
   */
  private boolean isOwnException(TimeLimiterException e) {
    if (e.getInstanceId() != null) {
      return Objects.equals(e.getInstanceId(), this.instanceId);
    }
    return Objects.equals(e.getTimeLimiterName(), config.name());
  }

  // ======================== Future Execution ========================

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier) throws Exception {
    return executeFuture(futureSupplier, config.timeout());
  }

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier, Duration timeout) throws Exception {
    Objects.requireNonNull(futureSupplier, "futureSupplier must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    Instant now = clock.instant();
    // Fix 4: Use effective timeout for snapshot deadline
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    Future<T> future = futureSupplier.get();
    try {
      return awaitFuture(future, snapshot, timeout);
    } catch (Throwable t) {
      cancelTaskSafely(future);
      throw t;
    }
  }

  public <T> T executeCompletionStage(Supplier<CompletionStage<T>> stageSupplier) throws Exception {
    return executeFuture(() -> stageSupplier.get().toCompletableFuture());
  }

  // ======================== Internal ========================

  /**
   * Awaits the future result with timeout handling.
   *
   * <p>Fix 6: All events use the effective {@code timeout} parameter instead of
   * {@code config.timeout()} so logged/observed timeouts match the actual behavior.
   *
   * <p>Fix 9: The interrupt path now correctly cancels the future and uses
   * the updated snapshot for event emission.
   */
  private <T> T awaitFuture(
      Future<T> future,
      ExecutionSnapshot snapshot,
      Duration timeout) throws Exception {

    try {
      T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

      Instant completedAt = clock.instant();
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(snapshot, completedAt);
      // Fix 6: Use effective timeout instead of config.timeout()
      emitEvent(TimeLimiterEvent.completed(
          config.name(), completed.elapsed(completedAt), timeout, completedAt));
      return result;

    } catch (TimeoutException e) {
      Instant timedOutAt = clock.instant();
      ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(snapshot, timedOutAt);
      // Fix 6: Use effective timeout
      emitEvent(TimeLimiterEvent.timedOut(
          config.name(), timedOut.elapsed(timedOutAt), timeout, timedOutAt));

      if (config.cancelOnTimeout()) {
        boolean cancelled = future.cancel(true);
        if (cancelled) {
          Instant cancelledAt = clock.instant();
          // Fix 7: Record the cancellation transition from TIMED_OUT → CANCELLED
          ExecutionSnapshot cancelledSnapshot = TimeLimiterCore.recordCancellation(timedOut, cancelledAt);
          emitEvent(TimeLimiterEvent.cancelled(
              config.name(), cancelledSnapshot.elapsed(cancelledAt), timeout, cancelledAt));
        }
      }

      throw createTimeoutExceptionWithIdentity(timeout);
    } catch (ExecutionException e) {
      Instant failedAt = clock.instant();
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, cause, failedAt);
      // Fix 6: Use effective timeout
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(failedAt), timeout, failedAt));

      if (cause instanceof Exception ex) throw ex;
      if (cause instanceof Error err) throw err;
      throw new RuntimeException(cause);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Instant failedAt = clock.instant();
      // Fix 9: Use the updated snapshot for the event and cancel the future
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, e, failedAt);
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(failedAt), timeout, failedAt));
      // Fix 9: Cancel the task so the virtual thread doesn't keep running
      cancelTaskSafely(future);
      throw e;
    }
  }

  /**
   * Creates the timeout exception via the configured factory.
   * If the factory produces a TimeLimiterException, enriches it with the instanceId.
   * Custom exception types are returned as-is — the fallback in isOwnException()
   * handles identity matching via name comparison for those cases.
   */
  private RuntimeException createTimeoutExceptionWithIdentity(Duration effectiveTimeout) {
    RuntimeException exception = config.createTimeoutException(effectiveTimeout);
    if (exception instanceof TimeLimiterException) {
      // Re-create with instanceId for identity-based matching
      return new TimeLimiterException(config.name(), instanceId, effectiveTimeout);
    }
    return exception;
  }

  /**
   * Fix 1: Safely cancels a future, catching any exceptions from the cancel call.
   * This prevents secondary failures from masking the original exception.
   */
  private void cancelTaskSafely(Future<?> future) {
    try {
      if (!future.isDone()) {
        future.cancel(true);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING,
          "Failed to cancel task for time limiter '%s': %s"
              .formatted(config.name(), e.getMessage()),
          e);
    }
  }

  // ======================== Listeners & Introspection ========================

  public void onEvent(Consumer<TimeLimiterEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Fix 2: Each listener is invoked in its own try-catch to prevent a failing listener
   * from breaking the execution flow. Without this, a listener exception after
   * the virtual thread has been started would leak the thread.
   */
  private void emitEvent(TimeLimiterEvent event) {
    for (Consumer<TimeLimiterEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for time limiter '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), e.getMessage()),
            e);
      }
    }
  }

  public TimeLimiterConfig getConfig() {
    return config;
  }

  /**
   * Fix 3: Returns the unique instance identifier.
   */
  public String getInstanceId() {
    return instanceId;
  }
}
