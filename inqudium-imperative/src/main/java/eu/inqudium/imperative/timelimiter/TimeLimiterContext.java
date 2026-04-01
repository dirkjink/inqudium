package eu.inqudium.imperative.timelimiter;

import eu.inqudium.core.element.timelimiter.TimeLimiterConfig;
import eu.inqudium.core.element.timelimiter.TimeLimiterEvent;

import java.time.Clock;
import java.util.concurrent.Future;

/**
 * Shared infrastructure provided to {@link TimeLimiterSyncExecutor} and
 * {@link TimeLimiterAsyncExecutor} implementations.
 *
 * <p>This context bundles the cross-cutting concerns that every execution
 * strategy needs: configuration access, wall-clock time, event emission,
 * exception creation, task cancellation, and thread naming. By injecting
 * the context at construction time, executor implementations remain
 * decoupled from the {@link ImperativeTimeLimiter} facade and can be
 * tested or replaced independently.
 *
 * <p>The {@link ImperativeTimeLimiter} itself implements this interface
 * and passes {@code this} to the executor constructors.
 */
public interface TimeLimiterContext {

  /**
   * Returns the time limiter configuration.
   */
  TimeLimiterConfig config();

  /**
   * Returns the clock used for timestamps in events and snapshots.
   */
  Clock clock();

  /**
   * Returns the unique instance identifier for exception identity matching.
   */
  String instanceId();

  /**
   * Emits an event to all registered listeners.
   * Listener exceptions are caught and logged — they never propagate to the caller.
   */
  void emitEvent(TimeLimiterEvent event);

  /**
   * Creates the timeout exception via the configured factory with the instanceId injected.
   *
   * @param effectiveTimeout the actual timeout that was exceeded
   * @return the timeout exception ready to throw
   */
  RuntimeException createTimeoutException(java.time.Duration effectiveTimeout);

  /**
   * Safely cancels a future, catching and logging any exceptions from the cancel call.
   */
  void cancelSafely(Future<?> future);

  /**
   * Returns a unique thread name for a worker virtual thread.
   */
  String nextThreadName();

  /**
   * Returns a unique thread name for a Future-to-CF bridge virtual thread.
   */
  String nextBridgeThreadName();
}
