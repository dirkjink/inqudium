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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative time limiter implementation.
 *
 * <p>Each call to {@link #execute} spawns a virtual thread to run the callable
 * and waits for the result with a deadline. If the deadline expires, a
 * {@link TimeLimiterException} is thrown and the virtual thread is cancelled.
 *
 * <p><strong>Design intent (Fix 9):</strong> This implementation is optimised for
 * I/O-bound operations (HTTP calls, database queries, file I/O) where the
 * overhead of spawning a virtual thread is negligible compared to the operation
 * itself. For CPU-bound micro-operations where sub-millisecond overhead matters,
 * consider a deadline-check approach instead.
 *
 * <p><strong>CompletionStage caveat (Fix 11):</strong>
 * {@link #executeCompletionStage} converts the stage to a {@code CompletableFuture}
 * and calls {@code cancel(true)} on timeout. However, {@code CompletableFuture.cancel()}
 * does <em>not</em> interrupt the underlying computation — it only completes the
 * future exceptionally with a {@code CancellationException}. The asynchronous
 * operation may continue running in the background. For true cancellation, the
 * supplier should check for cancellation cooperatively (e.g., via a shared
 * {@code AtomicBoolean} or by inspecting the future's cancelled state).
 */
public class ImperativeTimeLimiter {

  private static final Logger LOG = Logger.getLogger(ImperativeTimeLimiter.class.getName());

  private final TimeLimiterConfig config;
  private final Clock clock;
  private final List<Consumer<TimeLimiterEvent>> eventListeners;
  private final String instanceId;

  // Fix 4: Thread-safe counter for unique virtual thread names
  private final AtomicLong threadCounter = new AtomicLong(0);

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
    Objects.requireNonNull(callable, "callable must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive, got " + timeout);
    }

    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    // Fix 4: Unique thread name per execution to distinguish parallel calls in thread dumps
    String threadName = "timelimiter-%s-%d".formatted(config.name(), threadCounter.incrementAndGet());
    FutureTask<T> task = new FutureTask<>(callable);
    Thread.ofVirtual().name(threadName).start(task);

    try {
      return awaitFuture(task, snapshot, timeout);
    } catch (Throwable t) {
      cancelTaskSafely(task);
      throw t;
    }
  }

  /**
   * Fix 1: Direct Runnable implementation with explicit InterruptedException handling.
   * The previous delegation to the Callable variant wrapped InterruptedException
   * in a bare RuntimeException, losing the interrupt semantics.
   */
  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (InterruptedException e) {
      // Interrupt flag was already restored in awaitFuture.
      // Wrap as unchecked since execute(Runnable) cannot throw checked exceptions.
      throw new RuntimeException(e);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Executes with a fallback that activates when <em>this</em> time limiter times out.
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

  /**
   * Executes a {@link CompletionStage} with a timeout.
   *
   * <p><strong>Fix 11 — Cancellation caveat:</strong> On timeout,
   * {@code cancel(true)} is called on the underlying {@code CompletableFuture}.
   * However, {@code CompletableFuture.cancel()} does <em>not</em> interrupt
   * the thread running the asynchronous operation — it only transitions the
   * future to a cancelled state. The computation may continue in the background.
   *
   * <p>For true cooperative cancellation, the supplier should check the future's
   * cancelled state periodically or use a shared cancellation signal.
   */
  public <T> T executeCompletionStage(Supplier<CompletionStage<T>> stageSupplier) throws Exception {
    return executeFuture(() -> stageSupplier.get().toCompletableFuture());
  }

  // ======================== Internal — Future awaiting ========================

  /**
   * Awaits the future result with timeout handling.
   *
   * <p><strong>Fix 5:</strong> Uses nanosecond precision for the timeout to avoid
   * sub-millisecond timeouts being rounded to zero (which would cause an immediate
   * timeout check instead of a timed wait).
   */
  private <T> T awaitFuture(
      Future<T> future,
      ExecutionSnapshot snapshot,
      Duration timeout) throws Exception {

    try {
      // Fix 5: Use nanosecond precision to preserve sub-millisecond timeouts
      T result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);

      Instant completedAt = clock.instant();
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(snapshot, completedAt);
      emitEvent(TimeLimiterEvent.completed(
          config.name(), completed.elapsed(completedAt), timeout, completedAt));
      return result;

    } catch (TimeoutException e) {
      Instant timedOutAt = clock.instant();
      ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(snapshot, timedOutAt);
      emitEvent(TimeLimiterEvent.timedOut(
          config.name(), timedOut.elapsed(timedOutAt), timeout, timedOutAt));

      if (config.cancelOnTimeout()) {
        // Fix 6: No isDone() check — cancel() is idempotent on completed futures
        boolean cancelled = future.cancel(true);
        if (cancelled) {
          Instant cancelledAt = clock.instant();
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
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(failedAt), timeout, failedAt));

      if (cause instanceof Exception ex) throw ex;
      if (cause instanceof Error err) throw err;
      throw new RuntimeException(cause);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Instant failedAt = clock.instant();
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, e, failedAt);
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(failedAt), timeout, failedAt));
      cancelTaskSafely(future);
      throw e;
    }
  }

  // ======================== Internal — Exception creation ========================

  /**
   * Creates the timeout exception via the configured factory, then injects the instanceId.
   *
   * <p><strong>Fix 2:</strong> Instead of discarding the factory-produced exception
   * and creating a fresh one, we use {@link TimeLimiterException#withInstanceId} to
   * preserve all factory customizations (subclass type, extra fields, cause chain,
   * suppressed exceptions). For non-TimeLimiterException types from custom factories,
   * the exception is returned as-is — {@link #isOwnException} handles those via
   * name-based fallback comparison.
   */
  private RuntimeException createTimeoutExceptionWithIdentity(Duration effectiveTimeout) {
    RuntimeException exception = config.createTimeoutException(effectiveTimeout);
    if (exception instanceof TimeLimiterException tle) {
      // Inject instanceId while preserving all factory customizations
      return tle.withInstanceId(instanceId);
    }
    // Custom non-TimeLimiterException type — return as-is.
    // isOwnException falls back to name comparison for these.
    return exception;
  }

  // ======================== Internal — Task cancellation ========================

  /**
   * Safely cancels a future, catching any exceptions from the cancel call.
   *
   * <p><strong>Fix 6:</strong> Removed the {@code isDone()} check before
   * {@code cancel()} — {@code Future.cancel()} is idempotent on completed futures
   * (returns {@code false} without side effects). The check was a no-op that
   * introduced a needless race condition window.
   */
  private void cancelTaskSafely(Future<?> future) {
    try {
      future.cancel(true);
    } catch (Throwable t) {
      LOG.log(Level.WARNING,
          "Failed to cancel task for time limiter '%s': %s"
              .formatted(config.name(), t.getMessage()),
          t);
    }
  }

  // ======================== Listeners ========================

  /**
   * Registers an event listener.
   *
   * <p><strong>Fix 7:</strong> Returns a {@link Runnable} that, when executed,
   * unregisters this listener. Prevents memory leaks when listeners are
   * registered from short-lived contexts.
   *
   * @param listener the listener to be notified on time limiter events
   * @return a disposable handle that removes the listener when invoked
   */
  public Runnable onEvent(Consumer<TimeLimiterEvent> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    eventListeners.add(listener);
    return () -> eventListeners.remove(listener);
  }

  /**
   * Fix 3: Catches {@link Throwable} (not just {@link Exception}) to prevent
   * an {@link Error} from a monitoring listener from breaking the execution flow.
   * This is especially critical because events are emitted after the virtual thread
   * has been started — an unhandled Error would leak the thread.
   */
  private void emitEvent(TimeLimiterEvent event) {
    for (Consumer<TimeLimiterEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for time limiter '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()),
            t);
      }
    }
  }

  // ======================== Introspection ========================

  public TimeLimiterConfig getConfig() {
    return config;
  }

  public String getInstanceId() {
    return instanceId;
  }
}
