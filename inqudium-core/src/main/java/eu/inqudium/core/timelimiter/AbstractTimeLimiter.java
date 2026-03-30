package eu.inqudium.core.timelimiter;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.timelimiter.event.TimeLimiterOnErrorEvent;
import eu.inqudium.core.timelimiter.event.TimeLimiterOnSuccessEvent;
import eu.inqudium.core.timelimiter.event.TimeLimiterOnTimeoutEvent;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Base implementation for all time limiter paradigms (imperative, Reactor, Kotlin, RxJava).
 *
 * <p>Contains the complete time limiter logic — timing, event publishing, exception
 * translation, and decoration. Paradigm modules only provide the timeout execution
 * mechanism: {@link #executeWithTimeout(String, Callable, Duration)} and optionally
 * the future-supplier decoration via {@link #decorateFutureSupplier(Supplier)}.
 *
 * <p>This separation ensures that event publishing, error codes, and the
 * timeout-to-exception translation are implemented <strong>once</strong> in the core.
 *
 * <h2>Subclass contract</h2>
 * <ul>
 *   <li>{@link #executeWithTimeout(String, Callable, Duration)} — execute the callable
 *       with a timeout. Throw {@link TimeoutException} on timeout,
 *       {@link InterruptedException} on interrupt. Unwrap execution wrappers.
 *       Install orphaned handlers if configured.</li>
 *   <li>{@link #decorateFutureSupplier(Supplier)} — decorate a future supplier
 *       for standalone use. Paradigm-specific (CompletionStage is imperative/async).</li>
 * </ul>
 *
 * @since 0.1.0
 */
public abstract class AbstractTimeLimiter implements InqDecorator {

  private final String name;
  private final TimeLimiterConfig config;
  private final InqEventPublisher eventPublisher;

  protected AbstractTimeLimiter(String name, TimeLimiterConfig config) {
    this.name = name;
    this.config = config;
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.TIME_LIMITER);
  }

  // ── InqDecorator / InqElement ──

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqElementType getElementType() {
    return InqElementType.TIME_LIMITER;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  public TimeLimiterConfig getConfig() {
    return config;
  }

  // ── Decoration — template method ──

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    return call.withCallable(() -> timedExecution(call.callId(),
        () -> executeWithTimeout(call.callId(), call.callable(),
            config.getTimeoutDuration())));
  }

  /**
   * Wraps any callable with timing, event publishing, and exception translation.
   *
   * <p>The callable is expected to throw {@link TimeoutException} on timeout and
   * {@link InterruptedException} on interrupt — this method translates those to
   * {@link InqTimeLimitExceededException} and publishes the appropriate events.
   *
   * <p>Subclasses may call this from {@link #decorateFutureSupplier} to reuse
   * the event/exception logic for paradigm-specific async paths.
   *
   * @param callId   the call identifier
   * @param callable the operation to execute (may wrap a future-get or executeWithTimeout)
   * @param <T>      the result type
   * @return the result
   * @throws Exception if the callable throws or timeout/interrupt occurs
   */
  protected <T> T timedExecution(String callId, Callable<T> callable) throws Exception {
    var start = config.getClock().instant();
    var timeout = config.getTimeoutDuration();

    try {
      T result = callable.call();
      // Success — publish event
      var duration = Duration.between(start, config.getClock().instant());
      eventPublisher.publish(new TimeLimiterOnSuccessEvent(
          callId, name, duration, config.getClock().instant()));
      return result;
    } catch (TimeoutException te) {
      // Timeout — publish event, throw typed exception
      var actualDuration = Duration.between(start, config.getClock().instant());
      eventPublisher.publish(new TimeLimiterOnTimeoutEvent(
          callId, name, timeout, config.getClock().instant()));
      throw new InqTimeLimitExceededException(callId, name, timeout, actualDuration);
    } catch (InterruptedException ie) {
      // Interrupt — restore flag, treat as timeout
      Thread.currentThread().interrupt();
      var actualDuration = Duration.between(start, config.getClock().instant());
      eventPublisher.publish(new TimeLimiterOnTimeoutEvent(
          callId, name, timeout, config.getClock().instant()));
      throw new InqTimeLimitExceededException(callId, name, timeout, actualDuration);
    } catch (InqException inqEx) {
      // Inqudium exceptions pass through without wrapping
      throw inqEx;
    } catch (Exception e) {
      // Error — publish event, rethrow
      var duration = Duration.between(start, config.getClock().instant());
      eventPublisher.publish(new TimeLimiterOnErrorEvent(
          callId, name, duration, e, config.getClock().instant()));
      throw e;
    }
  }

  /**
   * Decorates and immediately executes a future supplier with timeout protection.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link eu.inqudium.core.pipeline.InqPipeline} to compose elements.
   *
   * @param futureSupplier a supplier that returns a CompletionStage
   * @param <T>            the result type
   * @return the result
   */
  public <T> T executeFutureSupplier(Supplier<CompletionStage<T>> futureSupplier) {
    return decorateFutureSupplier(futureSupplier).get();
  }

  // ── Abstract — paradigm-specific timeout mechanism ──

  /**
   * Executes the callable with a timeout.
   *
   * <p>Implementations must:
   * <ul>
   *   <li>Throw {@link TimeoutException} if the callable does not complete within
   *       the given timeout.</li>
   *   <li>Throw {@link InterruptedException} if the waiting thread is interrupted.</li>
   *   <li>Unwrap execution wrappers (e.g. {@link java.util.concurrent.ExecutionException})
   *       to expose the original exception.</li>
   *   <li>Install orphaned handlers (from {@link TimeLimiterConfig}) if the timeout
   *       fires and the callable continues running in the background.</li>
   * </ul>
   *
   * @param callId   the call identifier (for orphaned handler context)
   * @param callable the operation to execute
   * @param timeout  the maximum time to wait
   * @param <T>      the result type
   * @return the result
   * @throws TimeoutException     if the timeout fires
   * @throws InterruptedException if the thread is interrupted
   * @throws Exception            if the callable throws
   */
  protected abstract <T> T executeWithTimeout(String callId, Callable<T> callable,
                                              Duration timeout) throws Exception;

  /**
   * Decorates a future supplier with a timeout for standalone use.
   *
   * <p>Paradigm-specific — the concept of a {@link CompletionStage} is tied to
   * the imperative/async model. Other paradigms (Reactor, Kotlin) have their own
   * async primitives.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link eu.inqudium.core.pipeline.InqPipeline} to compose elements.
   *
   * @param futureSupplier a supplier that returns a CompletionStage
   * @param <T>            the result type
   * @return a supplier that applies the timeout
   */
  public abstract <T> Supplier<T> decorateFutureSupplier(Supplier<CompletionStage<T>> futureSupplier);
}
