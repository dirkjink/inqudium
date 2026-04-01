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
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative fallback provider implementation optimized for low GC overhead.
 *
 * <p>Each {@link #execute} call is stateless — no shared mutable state exists between
 * invocations. Thread safety comes from the immutability of {@link FallbackConfig}
 * and {@link FallbackSnapshot}.
 */
public class ImperativeFallbackProvider<T> {

  private static final Logger LOG = Logger.getLogger(ImperativeFallbackProvider.class.getName());

  private final FallbackConfig<T> config;
  private final Clock clock;
  private final List<Consumer<FallbackEvent>> eventListeners;

  // Fix 9: Injectable nano time supplier for testability.
  // In production this is System::nanoTime; in tests it can be replaced
  // with a controllable supplier so that durations in events are deterministic.
  private final LongSupplier nanoTimeSupplier;

  public ImperativeFallbackProvider(FallbackConfig<T> config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeFallbackProvider(FallbackConfig<T> config, Clock clock) {
    this(config, clock, System::nanoTime);
  }

  /**
   * Full constructor with injectable clock and nano time supplier.
   *
   * <p><strong>Fix 9:</strong> The {@code nanoTimeSupplier} decouples duration
   * measurement from {@code System.nanoTime()}, allowing tests to control both
   * wall-clock timestamps (via {@code clock}) and elapsed durations consistently.
   *
   * @param config           the fallback configuration
   * @param clock            the clock for wall-clock timestamps in events
   * @param nanoTimeSupplier the supplier for monotonic nanosecond timestamps
   */
  public ImperativeFallbackProvider(FallbackConfig<T> config, Clock clock, LongSupplier nanoTimeSupplier) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Execution ========================

  /**
   * Fix 4: Restores the thread's interrupt status if the throwable is an
   * {@link InterruptedException}. Called in fallback handler catch blocks
   * where we want to preserve the interrupt signal but continue processing
   * the fallback failure.
   */
  private static void restoreInterruptIfNeeded(Throwable t) {
    if (t instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Restores the thread's interrupt status and immediately rethrows if the
   * throwable is an {@link InterruptedException}.
   *
   * <p>Thread interrupts must bypass the fallback chain entirely — they signal
   * a cooperative cancellation request from the calling thread, not a downstream
   * failure that a fallback could recover from. Routing them through the handler
   * chain would silently swallow the interrupt and return a fallback value,
   * violating the caller's cancellation contract.
   *
   * @throws InterruptedException always, if {@code t} is an InterruptedException
   */
  private static void rethrowIfInterrupted(Throwable t) throws InterruptedException {
    if (t instanceof InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw ie;
    }
  }

  // ======================== Result handling ========================

  /**
   * Fix 10: Converts a {@link Throwable} to an {@link Exception} for rethrowing.
   * Eliminates the unreachable third branch ({@code throw new RuntimeException(t)})
   * that existed in the original code.
   *
   * <p>In Java, {@link Throwable} has exactly two direct subclasses:
   * {@link Exception} and {@link Error}. This method handles both and uses
   * an unchecked cast for the (theoretically impossible) third case.
   */
  private static Exception asException(Throwable t) throws Error {
    if (t instanceof Error err) throw err;
    if (t instanceof Exception e) return e;
    // Unreachable in standard Java — Throwable subtypes are always Exception or Error.
    // Defensive fallback for hypothetical non-standard Throwable subclasses.
    return new RuntimeException("Unexpected Throwable type: " + t.getClass().getName(), t);
  }

  // ======================== Primary failure handling ========================

  /**
   * Rethrows a {@link Throwable} as an unchecked exception.
   * Used by the Runnable variant which cannot declare checked exceptions.
   */
  private static void throwUnchecked(Throwable t) throws Error {
    if (t instanceof Error err) throw err;
    if (t instanceof RuntimeException re) throw re;
    // Checked exception from a Runnable context — should not happen in normal usage,
    // but defensive wrapping preserves the cause.
    throw new RuntimeException(t);
  }

  public T execute(Callable<T> callable) throws Exception {
    Objects.requireNonNull(callable, "callable must not be null");

    long startNanos = nanoTimeSupplier.getAsLong();
    FallbackSnapshot snapshot = FallbackCore.start(startNanos);
    emitEventIfListening(() -> FallbackEvent.primaryStarted(config.name(), clock.instant()));

    T result;
    try {
      result = callable.call();
    } catch (Throwable primary) {
      InqException.rethrowIfFatal(primary);
      rethrowIfInterrupted(primary);
      return handlePrimaryFailure(snapshot, primary);
    }

    return handleResult(snapshot, result);
  }

  // ======================== Listeners ========================

  /**
   * Executes the runnable with fallback support.
   *
   * <p><strong>Fix 3:</strong> Directly implements the execution lifecycle instead
   * of delegating to the Callable variant. Checked exceptions originating from
   * fallback handlers are wrapped in {@link FallbackException} to preserve context,
   * rather than being silently wrapped in a bare {@code RuntimeException}.
   */
  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");

    long startNanos = nanoTimeSupplier.getAsLong();
    FallbackSnapshot snapshot = FallbackCore.start(startNanos);
    emitEventIfListening(() -> FallbackEvent.primaryStarted(config.name(), clock.instant()));

    try {
      runnable.run();
    } catch (Throwable primary) {
      InqException.rethrowIfFatal(primary);
      // Note: Runnable.run() cannot throw InterruptedException (checked),
      // so no rethrowIfInterrupted() is needed here. InterruptedException
      // can only appear in the Callable variant.
      handlePrimaryFailureForRunnable(snapshot, primary);
      return;
    }

    // Runnable has no return value — emit success event and return
    if (!eventListeners.isEmpty()) {
      long endNanos = nanoTimeSupplier.getAsLong();
      Duration elapsed = Duration.ofNanos(endNanos - snapshot.startNanos());
      emitEvent(FallbackEvent.primarySucceeded(config.name(), elapsed, clock.instant()));
    }
  }

  private T handleResult(FallbackSnapshot snapshot, T result) throws Exception {
    FallbackResultHandler<T> handler = config.findHandlerForResult(result);

    if (handler == null) {
      if (!eventListeners.isEmpty()) {
        long endNanos = nanoTimeSupplier.getAsLong();
        Duration elapsed = Duration.ofNanos(endNanos - snapshot.startNanos());
        emitEvent(FallbackEvent.primarySucceeded(config.name(), elapsed, clock.instant()));
      }
      return result;
    }

    long fallbackStartNanos = nanoTimeSupplier.getAsLong();
    snapshot = snapshot.withFallingBack(null, handler.name(), fallbackStartNanos);
    emitEventIfListening(() -> FallbackEvent.resultFallbackInvoked(
        config.name(), handler.name(), Duration.ZERO, clock.instant()));

    try {
      T fallbackResult = FallbackCore.invokeResultHandler(handler, result);
      long recoveredNanos = nanoTimeSupplier.getAsLong();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.resultFallbackRecovered(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(recoveredNanos), clock.instant()));
      }
      return fallbackResult;

    } catch (Throwable fallbackEx) {
      InqException.rethrowIfFatal(fallbackEx);
      // Fix 4: Restore interrupt status if the result-fallback handler was interrupted
      restoreInterruptIfNeeded(fallbackEx);

      long fbFailedNanos = nanoTimeSupplier.getAsLong();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackFailed(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(fbFailedNanos), fallbackEx, clock.instant()));
      }

      // Fix 10: Unified rethrow — no unreachable branch
      throw asException(fallbackEx);
    }
  }

  private T handlePrimaryFailure(FallbackSnapshot snapshot, Throwable primary) throws Exception {
    long failedNanos = nanoTimeSupplier.getAsLong();

    if (!eventListeners.isEmpty()) {
      Duration elapsed = Duration.ofNanos(failedNanos - snapshot.startNanos());
      emitEvent(FallbackEvent.primaryFailed(config.name(), elapsed, primary, clock.instant()));
    }

    FallbackExceptionHandler<T> handler = config.findHandlerForException(primary);

    if (handler == null) {
      if (!eventListeners.isEmpty()) {
        Duration elapsed = Duration.ofNanos(failedNanos - snapshot.startNanos());
        emitEvent(FallbackEvent.noHandlerMatched(config.name(), elapsed, primary, clock.instant()));
      }
      // Fix 10: Unified rethrow
      throw asException(primary);
    }

    snapshot = snapshot.withFallingBack(primary, handler.name(), failedNanos);
    emitEventIfListening(() -> FallbackEvent.fallbackInvoked(
        config.name(), handler.name(), Duration.ZERO, clock.instant()));

    try {
      T fallbackValue = FallbackCore.invokeExceptionHandler(handler, primary);
      long recoveredNanos = nanoTimeSupplier.getAsLong();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackRecovered(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(recoveredNanos), clock.instant()));
      }
      return fallbackValue;

    } catch (Throwable fallbackEx) {
      InqException.rethrowIfFatal(fallbackEx);
      // Fix 4: Restore interrupt status if the fallback handler was interrupted
      restoreInterruptIfNeeded(fallbackEx);

      long fbFailedNanos = nanoTimeSupplier.getAsLong();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackFailed(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(fbFailedNanos), fallbackEx, clock.instant()));
      }

      throw new FallbackException(config.name(), primary, fallbackEx);
    }
  }

  // ======================== Introspection ========================

  /**
   * Fix 3: Handles primary failure for the Runnable variant.
   *
   * <p>Unlike the Callable variant which declares {@code throws Exception}, the
   * Runnable variant can only throw unchecked exceptions. Checked exceptions from
   * fallback handlers are wrapped in {@link FallbackException} to preserve the
   * full failure context instead of wrapping in a bare {@code RuntimeException}.
   */
  private void handlePrimaryFailureForRunnable(FallbackSnapshot snapshot, Throwable primary) {
    long failedNanos = nanoTimeSupplier.getAsLong();

    if (!eventListeners.isEmpty()) {
      Duration elapsed = Duration.ofNanos(failedNanos - snapshot.startNanos());
      emitEvent(FallbackEvent.primaryFailed(config.name(), elapsed, primary, clock.instant()));
    }

    FallbackExceptionHandler<T> handler = config.findHandlerForException(primary);

    if (handler == null) {
      if (!eventListeners.isEmpty()) {
        Duration elapsed = Duration.ofNanos(failedNanos - snapshot.startNanos());
        emitEvent(FallbackEvent.noHandlerMatched(config.name(), elapsed, primary, clock.instant()));
      }
      throwUnchecked(primary);
      return; // unreachable, but makes compiler happy
    }

    snapshot = snapshot.withFallingBack(primary, handler.name(), failedNanos);
    emitEventIfListening(() -> FallbackEvent.fallbackInvoked(
        config.name(), handler.name(), Duration.ZERO, clock.instant()));

    try {
      // Invoke the handler — we discard the result since Runnable returns void
      FallbackCore.invokeExceptionHandler(handler, primary);
      long recoveredNanos = nanoTimeSupplier.getAsLong();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackRecovered(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(recoveredNanos), clock.instant()));
      }
      // Recovered — return normally

    } catch (Throwable fallbackEx) {
      InqException.rethrowIfFatal(fallbackEx);
      restoreInterruptIfNeeded(fallbackEx);

      long fbFailedNanos = nanoTimeSupplier.getAsLong();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedNanos);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackFailed(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(fbFailedNanos), fallbackEx, clock.instant()));
      }

      // Fix 3: Wrap in FallbackException to preserve both primary and fallback context
      throw new FallbackException(config.name(), primary, fallbackEx);
    }
  }

  // ======================== Fix 4 + Fix 10: Shared utilities ========================

  /**
   * Registers an event listener.
   *
   * <p><strong>Fix 8:</strong> Returns a {@link Runnable} that, when executed,
   * unregisters this listener. This prevents memory leaks when listeners are
   * registered from short-lived contexts (e.g., per-request scopes).
   *
   * @param listener the listener to be notified on fallback events
   * @return a disposable handle that removes the listener when invoked
   */
  public Runnable onEvent(Consumer<FallbackEvent> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    eventListeners.add(listener);
    return () -> eventListeners.remove(listener);
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
