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
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe, imperative time limiter implementation.
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

  public <T> T execute(Callable<T> callable) throws Exception {
    return execute(callable, config.timeout());
  }

  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    // Fix 1A & 1B: Wir nutzen FutureTask, da dessen `cancel(true)` den Thread garantiert unterbricht.
    // Wir übergeben den Task an einen direkten Virtual Thread, wodurch wir keinen
    // ExecutorService benötigen, dessen `close()` den Aufrufer blockieren würde.
    FutureTask<T> task = new FutureTask<>(callable);
    Thread.ofVirtual().name("timelimiter-" + config.name()).start(task);

    return awaitFuture(task, snapshot, timeout);
  }

  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      // Fix 3A: Disjunkte Typen korrigiert. Fängt TimeLimiterException, RuntimeExceptions und JVM-Errors.
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TimeLimiterException e) {
      // Fix 2B: Verschachtelungs-Check. Springe nur in den Fallback,
      // wenn dieser TimeLimiter den Timeout verursacht hat.
      if (Objects.equals(e.getTimeLimiterName(), config.name())) {
        return fallback.get();
      }
      throw e;
    }
  }

  // ======================== Future Execution ========================

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier) throws Exception {
    return executeFuture(futureSupplier, config.timeout());
  }

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier, Duration timeout) throws Exception {
    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    Future<T> future = futureSupplier.get();
    return awaitFuture(future, snapshot, timeout);
  }

  public <T> T executeCompletionStage(Supplier<CompletionStage<T>> stageSupplier) throws Exception {
    // Hinweis: CompletableFuture.cancel(true) unterbricht physikalisch keine Threads.
    // Diese Methode greift primär als Timeout-Absicherung für reaktive Ketten, nicht zur echten Thread-Unterbrechung.
    return executeFuture(() -> stageSupplier.get().toCompletableFuture());
  }

  // ======================== Internal ========================

  private <T> T awaitFuture(Future<T> future, ExecutionSnapshot snapshot, Duration timeout) throws Exception {
    try {
      T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

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

      if (cause instanceof Exception ex) throw ex;
      if (cause instanceof Error err) throw err;
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

  // ======================== Listeners & Introspection ========================

  public void onEvent(Consumer<TimeLimiterEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(TimeLimiterEvent event) {
    for (Consumer<TimeLimiterEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  public TimeLimiterConfig getConfig() {
    return config;
  }
}