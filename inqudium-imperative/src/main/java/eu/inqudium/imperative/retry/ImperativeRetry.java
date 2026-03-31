package eu.inqudium.imperative.retry;

import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.core.retry.RetryCore;
import eu.inqudium.core.retry.RetryDecision;
import eu.inqudium.core.retry.RetryEvent;
import eu.inqudium.core.retry.RetryException;
import eu.inqudium.core.retry.RetrySnapshot;
import eu.inqudium.core.retry.RetryState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe, imperative retry implementation.
 */
public class ImperativeRetry {

  private final RetryConfig config;
  private final Clock clock;
  private final List<Consumer<RetryEvent>> eventListeners;

  public ImperativeRetry(RetryConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRetry(RetryConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Callable Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    Instant now = clock.instant();
    RetrySnapshot snapshot = RetryCore.startFirstAttempt(now);
    emitEvent(RetryEvent.attemptStarted(config.name(), 1, snapshot.totalElapsed(now), now));

    while (true) {
      boolean success = false;
      T result = null;
      Throwable attemptFailure = null;

      // Fix 2A & 2D: Try-Catch ist jetzt isoliert und fängt Throwable.
      try {
        result = callable.call();
        success = true;
      } catch (Throwable e) {
        attemptFailure = e;
      }

      if (success) {
        RetryDecision resultDecision = RetryCore.evaluateResult(snapshot, config, result);
        if (resultDecision == null) {
          snapshot = RetryCore.recordSuccess(snapshot);
          Instant completedAt = clock.instant();
          emitEvent(RetryEvent.attemptSucceeded(
              config.name(), snapshot.attemptNumber(),
              snapshot.totalElapsed(completedAt), completedAt));
          return result;
        }

        snapshot = handleDecision(resultDecision, snapshot, null);

        if (snapshot.state() == RetryState.EXHAUSTED) {
          throw new RetryException(
              config.name(), snapshot.totalAttempts(),
              snapshot.lastFailure(), snapshot.failures());
        }
      } else {
        RetryDecision decision = RetryCore.evaluateFailure(snapshot, config, attemptFailure);
        snapshot = handleDecision(decision, snapshot, attemptFailure);

        if (snapshot.state() == RetryState.FAILED) {
          if (attemptFailure instanceof Exception ex) throw ex;
          if (attemptFailure instanceof Error err) throw err;
          throw new RuntimeException(attemptFailure);
        }

        if (snapshot.state() == RetryState.EXHAUSTED) {
          throw new RetryException(
              config.name(), snapshot.totalAttempts(),
              attemptFailure, snapshot.failures());
        }
      }

      // Wait and start next attempt
      if (snapshot.state() == RetryState.WAITING_FOR_RETRY) {
        Duration delay = snapshot.nextRetryDelay();
        if (delay != null && delay.isPositive()) {
          parkUntil(clock.instant().plus(delay));
        }

        Instant retryNow = clock.instant();
        snapshot = RetryCore.startNextAttempt(snapshot, retryNow);
        emitEvent(RetryEvent.attemptStarted(
            config.name(), snapshot.attemptNumber(),
            snapshot.totalElapsed(retryNow), retryNow));
      }
    }
  }

  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) { // Fix: Disjunkte Typen korrigiert
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (RetryException e) {
      // Fix 2C: Verhindere Maskierung von RetryExceptions aus tieferen Schichten
      if (Objects.equals(e.getRetryName(), config.name())) {
        return fallback.get();
      }
      throw e;
    }
  }

  // ======================== Internal ========================

  /**
   * Parks the thread safely, preventing spurious wakeups and handling interrupts.
   */
  private void parkUntil(Instant targetWakeup) {
    while (true) {
      // Fix 1A: Wenn der Thread während des Backoffs abgebrochen wird,
      // sofort mit einer Exception abbrechen, statt unendlich zu parken.
      if (Thread.currentThread().isInterrupted()) {
        throw new RuntimeException(new InterruptedException("Thread interrupted during retry backoff"));
      }

      Duration remaining = Duration.between(clock.instant(), targetWakeup);
      if (remaining.isNegative() || remaining.isZero()) {
        break; // Wartezeit ist physisch vorüber
      }
      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  private RetrySnapshot handleDecision(RetryDecision decision, RetrySnapshot snapshot, Throwable failure) {
    Instant now = clock.instant();
    Duration elapsed = snapshot.totalElapsed(now);

    return switch (decision) {
      case RetryDecision.DoRetry doRetry -> {
        emitEvent(RetryEvent.retryScheduled(
            config.name(), snapshot.attemptNumber(), doRetry.delay(),
            elapsed, failure, now));
        yield doRetry.snapshot();
      }
      case RetryDecision.DoNotRetry doNotRetry -> {
        emitEvent(RetryEvent.failedNonRetryable(
            config.name(), snapshot.attemptNumber(), elapsed,
            doNotRetry.failure(), now));
        yield doNotRetry.snapshot();
      }
      case RetryDecision.RetriesExhausted exhausted -> {
        emitEvent(RetryEvent.retriesExhausted(
            config.name(), snapshot.attemptNumber(), elapsed,
            exhausted.failure(), now));
        yield exhausted.snapshot();
      }
    };
  }

  // ======================== Listeners & Introspection ========================

  public void onEvent(Consumer<RetryEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(RetryEvent event) {
    for (Consumer<RetryEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  public RetryConfig getConfig() {
    return config;
  }
}