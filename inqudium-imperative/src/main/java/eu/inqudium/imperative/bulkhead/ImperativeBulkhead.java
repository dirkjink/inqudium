package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.bulkhead.event.BulkheadWaitTraceEvent;
import eu.inqudium.core.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.bulkhead.strategy.NonBlockingBulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

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
  private final BulkheadConfig config;
  private final BlockingBulkheadStrategy strategy;
  private final InqEventPublisher eventPublisher;
  private final Duration maxWaitDuration;
  private final LongSupplier nanoTimeSource;

  public ImperativeBulkhead(String name, BulkheadConfig config, BulkheadStrategy strategy) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(strategy, "strategy must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Bulkhead name must not be blank");
    }
    if (!(strategy instanceof BlockingBulkheadStrategy blocking)) {
      throw new IllegalArgumentException(
          "ImperativeBulkhead requires a BlockingBulkheadStrategy, but received: "
              + strategy.getClass().getName()
              + ". Use the reactive bulkhead facade for NonBlockingBulkheadStrategy.");
    }
    this.name = name;
    this.config = config;
    this.strategy = blocking;
    this.maxWaitDuration = config.getMaxWaitDuration();
    this.nanoTimeSource = config.getNanoTimeSource();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
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
  public BulkheadConfig getConfig() {
    return config;
  }

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    return call.withCallable(() -> {

      // Duty 1: Acquire
      long startWait = nanoTimeSource.getAsLong();
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
      long startNanos = nanoTimeSource.getAsLong();
      Throwable businessError = null;

      try {
        return call.callable().call();
      } catch (Throwable t) {
        businessError = t;
        throw t;
      } finally {
        // Duty 3 (end): Measurement
        Duration rtt = Duration.ofNanos(nanoTimeSource.getAsLong() - startNanos);

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
          callId, name, strategy.concurrentCalls(), config.getClock().instant()));
    } catch (RuntimeException e) {
      strategy.rollback();
      try {
        eventPublisher.publishTrace(() -> new BulkheadRollbackTraceEvent(
            callId, name, e.getClass().getSimpleName(), config.getClock().instant()));
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
          callId, name, strategy.concurrentCalls(), config.getClock().instant()));
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
          callId, name, strategy.concurrentCalls(), config.getClock().instant()));
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
      long waitDurationNanos = nanoTimeSource.getAsLong() - startWait;
      if (waitDurationNanos > 0) {
        eventPublisher.publishTrace(() -> new BulkheadWaitTraceEvent(
            callId, name, waitDurationNanos, acquired, config.getClock().instant()));
      }
    }
  }
}
