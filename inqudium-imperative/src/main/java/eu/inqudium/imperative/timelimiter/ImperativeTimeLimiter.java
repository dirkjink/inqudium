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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe, imperative time limiter implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom). Executes the
 * protected operation on a virtual thread and enforces the timeout via
 * {@link Future#get(long, TimeUnit)}. On timeout, the running task is
 * optionally cancelled (interrupting the virtual thread).
 *
 * <p>Delegates all lifecycle logic to the functional
 * {@link TimeLimiterCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = TimeLimiterConfig.builder("downstream-call")
 *     .timeout(Duration.ofSeconds(3))
 *     .cancelOnTimeout(true)
 *     .build();
 *
 * var limiter = new ImperativeTimeLimiter(config);
 *
 * // Basic usage
 * String result = limiter.execute(() -> httpClient.call());
 *
 * // With fallback
 * String result = limiter.executeWithFallback(
 *     () -> httpClient.call(),
 *     () -> "timeout-fallback"
 * );
 *
 * // With custom timeout override
 * String result = limiter.execute(() -> slowOperation(), Duration.ofSeconds(10));
 *
 * // Wrapping a Future
 * Future<String> future = executor.submit(() -> longRunning());
 * String result = limiter.executeFuture(() -> future);
 * }</pre>
 */
public class ImperativeTimeLimiter {

  private final TimeLimiterConfig config;
  private final Clock clock;
  private final List<Consumer<TimeLimiterEvent>> eventListeners;

  public ImperativeTimeLimiter(TimeLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeTimeLimiter(TimeLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Callable Execution ========================

  /**
   * Executes the given callable with the configured timeout.
   *
   * <p>The callable runs on a new virtual thread. If it does not complete
   * within the timeout, a {@link TimeLimiterException} is thrown and the
   * task is optionally cancelled.
   *
   * @param callable the operation to protect
   * @param <T>      the return type
   * @return the result of the callable
   * @throws TimeLimiterException if the operation times out
   * @throws Exception            if the callable itself throws
   */
  public <T> T execute(Callable<T> callable) throws Exception {
    return execute(callable, config.timeout());
  }

  /**
   * Executes with a custom timeout override.
   */
  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<T> future = executor.submit(callable);
      return awaitFuture(future, snapshot, timeout);
    }
  }

  /**
   * Executes a {@link Runnable} with the configured timeout.
   *
   * @param runnable the operation to protect
   * @throws TimeLimiterException if the operation times out
   */
  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (TimeLimiterException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Executes with a fallback on timeout.
   *
   * @param callable the primary operation
   * @param fallback the fallback supplier invoked when the operation times out
   * @param <T>      the return type
   * @return the result of the callable or the fallback
   * @throws Exception if the callable throws a non-timeout exception
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TimeLimiterException e) {
      return fallback.get();
    }
  }

  // ======================== Future Execution ========================

  /**
   * Applies the time limit to an already-submitted {@link Future}.
   *
   * <p>Useful when the operation is already running on a thread pool
   * and only the timeout enforcement is needed.
   *
   * @param futureSupplier supplier that returns the Future to time-limit
   * @param <T>            the return type
   * @return the result of the Future
   * @throws TimeLimiterException if the Future does not complete within the timeout
   * @throws Exception            if the Future's computation throws
   */
  public <T> T executeFuture(Supplier<Future<T>> futureSupplier) throws Exception {
    return executeFuture(futureSupplier, config.timeout());
  }

  /**
   * Applies the time limit to a Future with a custom timeout.
   */
  public <T> T executeFuture(Supplier<Future<T>> futureSupplier, Duration timeout) throws Exception {
    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    Future<T> future = futureSupplier.get();
    return awaitFuture(future, snapshot, timeout);
  }

  /**
   * Applies the time limit to a {@link CompletionStage}.
   *
   * @param stageSupplier supplier that returns the CompletionStage to time-limit
   * @param <T>           the return type
   * @return the result of the CompletionStage
   * @throws TimeLimiterException if it does not complete within the timeout
   * @throws Exception            if the stage completes exceptionally
   */
  public <T> T executeCompletionStage(Supplier<CompletionStage<T>> stageSupplier) throws Exception {
    return executeFuture(() -> stageSupplier.get().toCompletableFuture());
  }

  // ======================== Internal ========================

  private <T> T awaitFuture(Future<T> future, ExecutionSnapshot snapshot, Duration timeout) throws Exception {
    try {
      T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

      // Completed within timeout
      Instant completedAt = clock.instant();
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(snapshot, completedAt);
      emitEvent(TimeLimiterEvent.completed(
          config.name(), completed.elapsed(completedAt), config.timeout(), completedAt));
      return result;

    } catch (TimeoutException e) {
      Instant timedOutAt = clock.instant();
      ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(snapshot, timedOutAt);
      emitEvent(TimeLimiterEvent.timedOut(
          config.name(), timedOut.elapsed(timedOutAt), config.timeout(), timedOutAt));

      if (config.cancelOnTimeout()) {
        boolean cancelled = future.cancel(true);
        if (cancelled) {
          Instant cancelledAt = clock.instant();
          emitEvent(TimeLimiterEvent.cancelled(
              config.name(), timedOut.elapsed(cancelledAt), config.timeout(), cancelledAt));
        }
      }

      throw config.createTimeoutException();

    } catch (ExecutionException e) {
      Instant failedAt = clock.instant();
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, cause, failedAt);
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(failedAt), config.timeout(), failedAt));

      // Unwrap and rethrow the original exception
      if (cause instanceof Exception ex) {
        throw ex;
      }
      throw new RuntimeException(cause);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Instant failedAt = clock.instant();
      TimeLimiterCore.recordFailure(snapshot, e, failedAt);
      emitEvent(TimeLimiterEvent.failed(
          config.name(), snapshot.elapsed(failedAt), config.timeout(), failedAt));
      throw e;
    }
  }

  // ======================== Listeners ========================

  /**
   * Registers a listener that is called on every time limiter event.
   */
  public void onEvent(Consumer<TimeLimiterEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(TimeLimiterEvent event) {
    for (Consumer<TimeLimiterEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  // ======================== Introspection ========================

  /**
   * Returns the configuration.
   */
  public TimeLimiterConfig getConfig() {
    return config;
  }
}
