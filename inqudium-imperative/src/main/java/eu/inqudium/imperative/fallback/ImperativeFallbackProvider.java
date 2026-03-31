package eu.inqudium.imperative.fallback;

import eu.inqudium.core.fallback.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe, imperative fallback provider implementation.
 */
public class ImperativeFallbackProvider<T> {

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
    Instant now = clock.instant();
    FallbackSnapshot snapshot = FallbackCore.start(now);
    emitEvent(FallbackEvent.primaryStarted(config.name(), now));

    try {
      T result = callable.call();

      Instant resultTime = clock.instant();
      FallbackCore.ResultResolution<T> resultResolution =
          FallbackCore.resolveResultHandler(snapshot, config, result, resultTime);

      if (resultResolution == null) {
        snapshot = FallbackCore.recordPrimarySuccess(snapshot, resultTime);
        emitEvent(FallbackEvent.primarySucceeded(
            config.name(), snapshot.elapsed(resultTime), resultTime));
        return result;
      }

      // Update snapshot tracking
      snapshot = resultResolution.snapshot();
      // Duration is 0 as the fallback execution just starts now
      emitEvent(FallbackEvent.resultFallbackInvoked(
          config.name(), resultResolution.handler().name(), Duration.ZERO, resultTime));

      T fallbackResult = FallbackCore.invokeResultHandler(resultResolution.handler());
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);

      // Pass the specific fallback execution time
      emitEvent(FallbackEvent.resultFallbackRecovered(
          config.name(), resultResolution.handler().name(),
          snapshot.fallbackElapsed(recoveredTime), recoveredTime));
      return fallbackResult;

      // Catch Throwable to handle Error properly
    } catch (Throwable primary) {
      return handleThrowable(snapshot, primary);
    }
  }

  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      throw e; // Fängt RuntimeException (inkl. FallbackException) und Errors, wirft sie direkt weiter
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private T handleThrowable(FallbackSnapshot snapshot, Throwable primary) throws Exception {
    Instant failedTime = clock.instant();
    emitEvent(FallbackEvent.primaryFailed(
        config.name(), snapshot.elapsed(failedTime), primary, failedTime));

    FallbackCore.ExceptionResolution<T> resolution =
        FallbackCore.resolveExceptionHandler(snapshot, config, primary, failedTime);

    if (!resolution.matched()) {
      snapshot = resolution.snapshot();
      emitEvent(FallbackEvent.noHandlerMatched(
          config.name(), snapshot.elapsed(failedTime), primary, failedTime));

      // Transparent propagation
      if (primary instanceof Exception e) throw e;
      if (primary instanceof Error err) throw err;
      throw new RuntimeException(primary);
    }

    snapshot = resolution.snapshot();
    emitEvent(FallbackEvent.fallbackInvoked(
        config.name(), resolution.handler().name(), Duration.ZERO, failedTime));

    try {
      T fallbackValue = FallbackCore.invokeExceptionHandler(resolution.handler(), primary);
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);

      emitEvent(FallbackEvent.fallbackRecovered(
          config.name(), resolution.handler().name(),
          snapshot.fallbackElapsed(recoveredTime), recoveredTime));
      return fallbackValue;

    } catch (Throwable fallbackEx) {
      Instant fbFailedTime = clock.instant();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedTime);

      emitEvent(FallbackEvent.fallbackFailed(
          config.name(), resolution.handler().name(),
          snapshot.fallbackElapsed(fbFailedTime), fallbackEx, fbFailedTime));

      throw new FallbackException(config.name(), primary, fallbackEx);
    }
  }

  public void onEvent(Consumer<FallbackEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(FallbackEvent event) {
    for (Consumer<FallbackEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  public FallbackConfig<T> getConfig() {
    return config;
  }
}