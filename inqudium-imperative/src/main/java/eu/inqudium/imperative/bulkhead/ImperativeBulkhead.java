package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadWaitTraceEvent;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.log.Logger;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfig;
import eu.inqudium.imperative.core.InqAsyncExecutor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Composition-based imperative bulkhead implementing the {@link InqDecorator} interface.
 *
 * <p>The bulkhead logic is expressed as around-advice in {@link #execute}: it acquires a
 * permit, delegates to the next step in the chain via {@code next.execute(...)}, measures
 * RTT, and releases the permit in a {@code finally} block. This replaces the previous
 * {@code decorate(InqCall)} approach with the unified pipeline execution model.</p>
 *
 * <h2>Usage via Decorator factory methods</h2>
 * <pre>{@code
 * ImperativeBulkhead<Void, String> bulkhead = new ImperativeBulkhead<>(config, strategy);
 *
 * // Wrap any functional interface — fully type-safe
 * Supplier<String> protected = bulkhead.decorateSupplier(() -> callApi());
 * Callable<String> protected = bulkhead.decorateCallable(() -> readFile());
 *
 * // Compose with other decorators
 * Supplier<String> resilient = retry.decorateSupplier(
 *     bulkhead.decorateSupplier(() -> callApi())
 * );
 * }</pre>
 *
 * <h2>Execution modes</h2>
 * <ul>
 *   <li><b>Synchronous</b> (via {@link InqDecorator} factory methods): Acquire and release
 *       both happen on the calling thread.</li>
 *   <li><b>Asynchronous</b> ({@link #executeAsync}, {@link #executeFutureAsync},
 *       {@link #executeCompletionStageAsync}): Acquire is synchronous, release is
 *       asynchronous via the {@link CompletableFuture} pipeline.</li>
 * </ul>
 *
 * <h2>Observability model</h2>
 * <p><b>Metrics</b> (always on) are delivered via polling-based gauges.
 * <b>Events</b> (off by default) provide per-call tracing controlled by
 * {@link BulkheadEventConfig}.</p>
 *
 * @since 0.4.0
 */
public final class ImperativeBulkhead<A, R> implements Bulkhead<A, R>, InqAsyncExecutor, BulkheadContext {

  private final Logger logger;
  private final String name;
  private final InqImperativeBulkheadConfig config;
  private final BlockingBulkheadStrategy strategy;
  private final InqEventPublisher eventPublisher;
  private final BulkheadEventConfig eventConfig;
  private final Duration maxWaitDuration;
  private final InqNanoTimeSource nanoTimeSource;
  private final InqClock clock;
  private final CompletableFutureAsyncExecutor asyncExecutor;
  private final boolean enableExceptionOptimization;

  public ImperativeBulkhead(InqImperativeBulkheadConfig config, BlockingBulkheadStrategy strategy) {
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(strategy, "strategy must not be null");
    this.logger = config.general().loggerFactory().getLogger(getClass());
    this.name = config.name();
    this.config = config;
    this.strategy = strategy;
    this.eventConfig = config.eventConfig();
    this.maxWaitDuration = config.maxWaitDuration();
    this.nanoTimeSource = config.general().nanoTimesource();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
    this.clock = config.general().clock();
    this.asyncExecutor = new CompletableFutureAsyncExecutor(this);
    this.enableExceptionOptimization = config.enableExceptionOptimization();
  }

  // ======================== Decorator (around-advice) ========================

  /**
   * Core bulkhead logic as around-advice for the wrapper pipeline.
   *
   * <p>This method replaces the previous {@code decorate(InqCall)} approach. The execution
   * flow is:</p>
   * <ol>
   *   <li>Acquire a permit from the {@link BlockingBulkheadStrategy} (with configurable wait)</li>
   *   <li>On success: publish diagnostic acquire event, then delegate to {@code next}</li>
   *   <li>On rejection or interrupt: publish failure event, throw appropriate exception</li>
   *   <li>Measure RTT and release the permit in a {@code finally} block</li>
   * </ol>
   *
   * <p>The {@code chainId} and {@code callId} are converted to {@code String} for event
   * correlation and exception context, preserving compatibility with the existing
   * observability infrastructure.</p>
   *
   * @param chainId  the chain identifier (converted to String for event correlation)
   * @param callId   the call identifier (converted to String for exception context)
   * @param argument the argument flowing through the chain (passed through unchanged)
   * @param next     the next step in the chain — the actual business logic
   * @return the result of the downstream chain execution
   */
  @Override
  public R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next) {
    String callIdStr = Long.toString(callId);

    // ── Acquire permit ──
    // startWait is only needed for trace events (wait duration measurement).
    // In standard mode (trace disabled), this nanoTime call is skipped entirely.
    long startWait = eventConfig.isTraceEnabled() ? nanoTimeSource.now() : 0L;

    RejectionContext rejection;
    try {
      rejection = strategy.tryAcquire(maxWaitDuration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleAcquireFailure(chainId, callId, startWait, null);
      throw new InqBulkheadInterruptedException(callIdStr, name, enableExceptionOptimization);
    }

    if (rejection != null) {
      handleAcquireFailure(chainId, callId, startWait, rejection);
      throw new InqBulkheadFullException(callIdStr, name, rejection, enableExceptionOptimization);
    }

    // Diagnostic events (acquire) — no-op in standard mode
    handleAcquireSuccess(chainId, callId, startWait);

    // ── Execute downstream chain with RTT measurement ──
    long startNanos = nanoTimeSource.now();
    Throwable businessError = null;

    try {
      return next.execute(chainId, callId, argument);
    } catch (Throwable t) {
      businessError = t;
      throw t;
    } finally {
      long rttNanos = nanoTimeSource.now() - startNanos;
      releaseAndReport(chainId, callId, rttNanos, businessError);
    }
  }

  // ======================== InqElement (via Bulkhead → Decorator) ========================

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  // ======================== Bulkhead facade ========================

  @Override
  public InqImperativeBulkheadConfig getConfig() {
    return config;
  }

  @Override
  public boolean isEnableExceptionOptimization() {
    return enableExceptionOptimization;
  }

  @Override
  public int getConcurrentCalls() {
    return strategy.concurrentCalls();
  }

  @Override
  public int getAvailablePermits() {
    return strategy.availablePermits();
  }

  public int getMaxConcurrentCalls() {
    return strategy.maxConcurrentCalls();
  }

  public BlockingBulkheadStrategy getStrategy() {
    return strategy;
  }

  // ======================== BulkheadContext (package-private SPI) ========================

  @Override
  public String bulkheadName() {
    return name;
  }

  @Override
  public BlockingBulkheadStrategy strategy() {
    return strategy;
  }

  @Override
  public Duration maxWaitDuration() {
    return maxWaitDuration;
  }

  @Override
  public InqNanoTimeSource nanoTimeSource() {
    return nanoTimeSource;
  }

  @Override
  public BulkheadEventConfig eventConfig() {
    return eventConfig;
  }

  @Override
  public InqEventPublisher eventPublisher() {
    return eventPublisher;
  }

  @Override
  public InqClock clock() {
    return clock;
  }

  @Override
  public Logger logger() {
    return logger;
  }

  // ======================== Async execution (InqAsyncExecutor) ========================

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> CompletableFuture<T> executeAsync(Callable<T> callable) {
    return asyncExecutor.executeAsync(callable);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> CompletableFuture<T> executeFutureAsync(Supplier<Future<T>> futureSupplier) {
    return asyncExecutor.executeFutureAsync(futureSupplier);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> CompletableFuture<T> executeCompletionStageAsync(
      Supplier<CompletionStage<T>> stageSupplier) {
    return asyncExecutor.executeCompletionStageAsync(stageSupplier);
  }

  // ======================== Diagnostic events — acquire ========================

  /**
   * Publishes diagnostic acquire events. In standard mode, this is a complete no-op.
   */
  private void handleAcquireSuccess(long chainId, long callId, long startWait) {
    if (eventConfig.isLifecycleEnabled()) {
      try {
        eventPublisher.publish(new BulkheadOnAcquireEvent(chainId,
            callId,
            name,
            strategy.concurrentCalls(),
            clock.instant()));
      } catch (RuntimeException e) {
        strategy.rollback();
        if (eventConfig.isTraceEnabled()) {
          try {
            eventPublisher.publishTrace(() -> new BulkheadRollbackTraceEvent(chainId,
                callId,
                name,
                e.getClass().getSimpleName(),
                clock.instant()));
          } catch (RuntimeException traceError) {
            logger.error().log("Failed to publish rollback trace for bulkhead '{}', callId='{}'. "
                + "Permit rolled back.", name, callId, traceError);
          }
        }
        throw e;
      }
    }

    if (eventConfig.isTraceEnabled()) {
      try {
        publishWaitTrace(chainId, callId, startWait, true);
      } catch (RuntimeException e) {
        logger.error().log("Failed to publish wait trace for acquired call on bulkhead '{}', "
            + "callId='{}'. Diagnostic-only failure.", name, callId, e);
      }
    }
  }

  /**
   * Publishes diagnostic events for a rejected or interrupted acquire attempt.
   */
  private void handleAcquireFailure(long chainId, long callId, long startWait, RejectionContext rejection) {
    if (eventConfig.isTraceEnabled()) {
      try {
        publishWaitTrace(chainId, callId, startWait, false);
      } catch (RuntimeException e) {
        logger.error().log("Failed to publish wait trace for rejected call on bulkhead '{}', "
            + "callId='{}'. Diagnostic-only failure.", name, callId, e);
      }
    }
    if (eventConfig.isRejectionEnabled()) {
      try {
        eventPublisher.publish(new BulkheadOnRejectEvent(chainId,
            callId,
            name,
            rejection,
            clock.instant()));
      } catch (RuntimeException e) {
        logger.error().log("Failed to publish reject event for bulkhead '{}', callId='{}'. "
            + "Diagnostic-only failure.", name, callId, e);
      }
    }
  }

  // ======================== Release + diagnostic events ========================

  /**
   * Releases the permit, feeds the adaptive algorithm, and publishes diagnostic events.
   */
  private void releaseAndReport(long chainId, long callId, long rttNanos, Throwable businessError) {
    RuntimeException releaseError = null;

    try {
      strategy.onCallComplete(rttNanos, businessError == null);
    } catch (RuntimeException algorithmError) {
      logger.error().log("Adaptive algorithm hook failed for bulkhead '{}', callId='{}'. "
          + "Permit will still be released.", name, callId, algorithmError);
    } finally {
      try {
        strategy.release();
      } catch (RuntimeException e) {
        releaseError = e;
        logger.error().log("Strategy release failed for bulkhead '{}', callId='{}'. "
            + "Events will still be published.", name, callId, e);
      }
    }

    if (eventConfig.isLifecycleEnabled()) {
      try {
        eventPublisher.publish(new BulkheadOnReleaseEvent(chainId,
            callId,
            name,
            strategy.concurrentCalls(),
            clock.instant()));
      } catch (RuntimeException publisherError) {
        logger.error().log("Failed to publish release event for bulkhead '{}', callId='{}'. "
            + "Diagnostic-only failure.", name, callId, publisherError);
      }
    }

    if (releaseError != null) {
      if (businessError != null) {
        businessError.addSuppressed(releaseError);
      } else {
        throw releaseError;
      }
    }
  }

  // ======================== Internal ========================

  private void publishWaitTrace(long chainId, long callId, long startWait, boolean acquired) {
    if (eventPublisher.isTraceEnabled()) {
      long waitDurationNanos = nanoTimeSource.now() - startWait;
      if (waitDurationNanos > 0) {
        eventPublisher.publishTrace(() -> new BulkheadWaitTraceEvent(chainId,
            callId,
            name,
            waitDurationNanos,
            acquired,
            clock.instant()));
      }
    }
  }
}
