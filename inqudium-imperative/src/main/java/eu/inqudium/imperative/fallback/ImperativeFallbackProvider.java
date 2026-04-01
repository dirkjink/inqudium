package eu.inqudium.imperative.fallback;

import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.fallback.FallbackConfig;
import eu.inqudium.core.fallback.FallbackCore;
import eu.inqudium.core.fallback.FallbackEvent;
import eu.inqudium.core.fallback.FallbackException;
import eu.inqudium.core.fallback.FallbackExceptionHandler;
import eu.inqudium.core.fallback.FallbackResultHandler;
import eu.inqudium.core.fallback.FallbackSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative fallback provider implementation optimized for low GC overhead.
 */
public class ImperativeFallbackProvider<T> {

  private static final Logger LOG = Logger.getLogger(ImperativeFallbackProvider.class.getName());

  private final FallbackConfig<T> config;
  private final Clock clock;
  private final List<Consumer<FallbackEvent>> eventListeners;

  public ImperativeFallbackProvider(FallbackConfig<T> config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeFallbackProvider(FallbackConfig<T> config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  public T execute(Callable<T> callable) throws Exception {
    Objects.requireNonNull(callable, "callable must not be null");

    long startNanos = System.nanoTime();
    FallbackSnapshot snapshot = FallbackCore.start(startNanos);
    emitEventIfListening(() -> FallbackEvent.primaryStarted(config.name(), clock.instant()));

    T result;
    try {
      result = callable.call();
    } catch (Throwable primary) {
      InqException.rethrowIfFatal(primary); // 3. Fail-Fast for JVM Errors
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw (InterruptedException) primary;
      }
      return handlePrimaryFailure(snapshot, primary);
    }

    return handleResult(snapshot, result);
  }

  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      executeWithoutResultCheck(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private T executeWithoutResultCheck(Callable<T> callable) throws Exception {
    long startNanos = System.nanoTime();
    FallbackSnapshot snapshot = FallbackCore.start(startNanos);
    emitEventIfListening(() -> FallbackEvent.primaryStarted(config.name(), clock.instant()));

    try {
      T result = callable.call();

      if (!eventListeners.isEmpty()) {
        long endNanos = System.nanoTime();
        Duration elapsed = Duration.ofNanos(endNanos - snapshot.startNanos());
        emitEvent(FallbackEvent.primarySucceeded(config.name(), elapsed, clock.instant()));
      }
      return result;

    } catch (Throwable primary) {
      InqException.rethrowIfFatal(primary); // 3. Fail-Fast for JVM Errors
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw (InterruptedException) primary;
      }
      return handlePrimaryFailure(snapshot, primary);
    }
  }

  private T handleResult(FallbackSnapshot snapshot, T result) throws Exception {
    // Zero Iterator Allocation (due to array iteration inside config)
    FallbackResultHandler<T> handler = config.findHandlerForResult(result);

    if (handler == null) {
      if (!eventListeners.isEmpty()) {
        long endNanos = System.nanoTime();
        Duration elapsed = Duration.ofNanos(endNanos - snapshot.startNanos());
        emitEvent(FallbackEvent.primarySucceeded(config.name(), elapsed, clock.instant()));
      }
      return result;
    }

    long fallbackStartNanos = System.nanoTime();
    snapshot = snapshot.withFallingBack(null, handler.name(), fallbackStartNanos);
    emitEventIfListening(() -> FallbackEvent.resultFallbackInvoked(
        config.name(), handler.name(), Duration.ZERO, clock.instant()));

    try {
      T fallbackResult = FallbackCore.invokeResultHandler(handler, result);
      long recoveredNanos = System.nanoTime();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.resultFallbackRecovered(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(recoveredNanos), clock.instant()));
      }
      return fallbackResult;

    } catch (Throwable fallbackEx) {
      InqException.rethrowIfFatal(fallbackEx); // Fail-fast before handling the failure
      long fbFailedNanos = System.nanoTime();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackFailed(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(fbFailedNanos), fallbackEx, clock.instant()));
      }

      if (fallbackEx instanceof Exception e) throw e;
      if (fallbackEx instanceof Error err) throw err;
      throw new RuntimeException(fallbackEx);
    }
  }

  private T handlePrimaryFailure(FallbackSnapshot snapshot, Throwable primary) throws Exception {
    long failedNanos = System.nanoTime();

    if (!eventListeners.isEmpty()) {
      Duration elapsed = Duration.ofNanos(failedNanos - snapshot.startNanos());
      emitEvent(FallbackEvent.primaryFailed(config.name(), elapsed, primary, clock.instant()));
    }

    // Zero Iterator Allocation
    FallbackExceptionHandler<T> handler = config.findHandlerForException(primary);

    if (handler == null) {
      if (!eventListeners.isEmpty()) {
        Duration elapsed = Duration.ofNanos(failedNanos - snapshot.startNanos());
        emitEvent(FallbackEvent.noHandlerMatched(config.name(), elapsed, primary, clock.instant()));
      }
      if (primary instanceof Exception e) throw e;
      if (primary instanceof Error err) throw err;
      throw new RuntimeException(primary);
    }

    snapshot = snapshot.withFallingBack(primary, handler.name(), failedNanos);
    emitEventIfListening(() -> FallbackEvent.fallbackInvoked(
        config.name(), handler.name(), Duration.ZERO, clock.instant()));

    try {
      T fallbackValue = FallbackCore.invokeExceptionHandler(handler, primary);
      long recoveredNanos = System.nanoTime();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackRecovered(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(recoveredNanos), clock.instant()));
      }
      return fallbackValue;

    } catch (Throwable fallbackEx) {
      InqException.rethrowIfFatal(fallbackEx); // Fail-fast for handler issues
      long fbFailedNanos = System.nanoTime();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackFailed(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(fbFailedNanos), fallbackEx, clock.instant()));
      }

      throw new FallbackException(config.name(), primary, fallbackEx);
    }
  }

  public void onEvent(Consumer<FallbackEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEventIfListening(java.util.function.Supplier<FallbackEvent> eventSupplier) {
    if (!eventListeners.isEmpty()) {
      emitEvent(eventSupplier.get());
    }
  }

  private void emitEvent(FallbackEvent event) {
    for (Consumer<FallbackEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for fallback '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()),
            t);
      }
    }
  }

  public FallbackConfig<T> getConfig() {
    return config;
  }
}