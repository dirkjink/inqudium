package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadWaitTraceEvent;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.NonBlockingBulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.invoke.InqCall;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * Composition-based imperative bulkhead facade.
 *
 * <p>Delegates permit management to a {@link BlockingBulkheadStrategy} and
 * owns the entire telemetry lifecycle (events, traces, two-tier error handling).
 *
 * <p>Requires a {@link BlockingBulkheadStrategy} — non-blocking strategies
 * are rejected at construction time. For reactive paradigms, use the reactive
 * bulkhead facade (which accepts a {@link NonBlockingBulkheadStrategy}).
 *
 * @since 0.3.0
 */
public final class ImperativeBulkhead implements Bulkhead {

  private static final Logger LOG = LoggerFactory.getLogger(ImperativeBulkhead.class);

  private final String name;
  private final InqBulkheadConfig config;
  private final BlockingBulkheadStrategy strategy;
  private final InqEventPublisher eventPublisher;
  private final Duration maxWaitDuration;
  private final InqNanoTimeSource nanoTimeSource;
  private final InqClock clock;

  public ImperativeBulkhead(InqBulkheadConfig config, BulkheadStrategy strategy) {
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(strategy, "strategy must not be null");
    if (!(strategy instanceof BlockingBulkheadStrategy blocking)) {
      throw new IllegalArgumentException(
          "ImperativeBulkhead requires a BlockingBulkheadStrategy, but received: "
              + strategy.getClass().getName()
              + ". Use the reactive bulkhead facade for NonBlockingBulkheadStrategy.");
    }
    this.name = config.name();
    this.config = config;
    this.strategy = blocking;
    this.maxWaitDuration = config.maxWaitDuration();
    this.nanoTimeSource = config.general().nanoTimesource();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
    this.clock = config.general().clock();
  }

  // ======================== Bulkhead facade ========================

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  @Override
  public InqBulkheadConfig getConfig() {
    return config;
  }

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    return call.withCallable(() -> {

      // Duty 1: Acquire
      long startWait = nanoTimeSource.now();
      boolean acquired;
      try {
        acquired = strategy.tryAcquire(maxWaitDuration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        handleAcquireFailure(call.callId(), startWait);
        throw new InqBulkheadInterruptedException(
            call.callId(), name,
            strategy.concurrentCalls(), strategy.maxConcurrentCalls());
      }

      if (!acquired) {
        handleAcquireFailure(call.callId(), startWait);
        throw new InqBulkheadFullException(
            call.callId(), name,
            strategy.concurrentCalls(), strategy.maxConcurrentCalls());
      }

      // Two-tier acquire telemetry
      handleAcquireSuccess(call.callId(), startWait);

      // Duty 3 (start): RTT measurement
      long startNanos = nanoTimeSource.now();
      Throwable businessError = null;

      try {
        return call.callable().call();
      } catch (Throwable t) {
        businessError = t;
        throw t;
      } finally {
        // Duty 3 (end): Measurement
        Duration rtt = Duration.ofNanos(nanoTimeSource.now() - startNanos);

        // Duty 2: Guaranteed release
        releaseAndReport(call.callId(), rtt, businessError);
      }
    });
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

  // ======================== Telemetry — acquire ========================

  private void handleAcquireSuccess(String callId, long startWait) {
    // Tier 1: Acquire event — rollback on failure
    try {
      eventPublisher.publish(new BulkheadOnAcquireEvent(
          callId, name, strategy.concurrentCalls(), clock.instant()));
    } catch (RuntimeException e) {
      strategy.rollback();
      try {
        eventPublisher.publishTrace(() -> new BulkheadRollbackTraceEvent(
            callId, name, e.getClass().getSimpleName(), clock.instant()));
      } catch (RuntimeException traceError) {
        LOG.error("Failed to publish rollback trace for bulkhead '{}', callId='{}'. "
            + "Permit rolled back.", name, callId, traceError);
      }
      throw e;
    }

    // Tier 2: Wait trace — best-effort
    try {
      publishWaitTrace(callId, startWait, true);
    } catch (RuntimeException e) {
      LOG.error("Failed to publish wait trace for acquired call on bulkhead '{}', "
          + "callId='{}'. Telemetry-only failure.", name, callId, e);
    }
  }

  private void handleAcquireFailure(String callId, long startWait) {
    try {
      publishWaitTrace(callId, startWait, false);
    } catch (RuntimeException e) {
      LOG.error("Failed to publish wait trace for rejected call on bulkhead '{}', "
          + "callId='{}'. Telemetry-only failure.", name, callId, e);
    }
    try {
      eventPublisher.publish(new BulkheadOnRejectEvent(
          callId, name, strategy.concurrentCalls(), clock.instant()));
    } catch (RuntimeException e) {
      LOG.error("Failed to publish reject event for bulkhead '{}', callId='{}'. "
          + "Telemetry-only failure.", name, callId, e);
    }
  }

  // ======================== Telemetry — release ========================

  private void releaseAndReport(String callId, Duration rtt, Throwable businessError) {
    RuntimeException releaseError = null;

    try {
      strategy.onCallComplete(rtt, businessError == null);
    } catch (RuntimeException algorithmError) {
      LOG.error("Adaptive algorithm hook failed for bulkhead '{}', callId='{}'. "
          + "Permit will still be released.", name, callId, algorithmError);
    } finally {
      try {
        strategy.release();
      } catch (RuntimeException e) {
        releaseError = e;
        LOG.error("Strategy release failed for bulkhead '{}', callId='{}'. "
            + "Telemetry will still be published.", name, callId, e);
      }
    }

    try {
      eventPublisher.publish(new BulkheadOnReleaseEvent(
          callId, name, strategy.concurrentCalls(), clock.instant()));
    } catch (RuntimeException publisherError) {
      LOG.error("Failed to publish release event for bulkhead '{}', callId='{}'. "
          + "Telemetry-only failure.", name, callId, publisherError);
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

  private void publishWaitTrace(String callId, long startWait, boolean acquired) {
    if (eventPublisher.isTraceEnabled()) {
      long waitDurationNanos = nanoTimeSource.now() - startWait;
      if (waitDurationNanos > 0) {
        eventPublisher.publishTrace(() -> new BulkheadWaitTraceEvent(
            callId, name, waitDurationNanos, acquired, clock.instant()));
      }
    }
  }
}
