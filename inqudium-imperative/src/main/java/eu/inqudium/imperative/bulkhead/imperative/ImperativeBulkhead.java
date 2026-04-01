package eu.inqudium.imperative.bulkhead.imperative;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.bulkhead.event.BulkheadWaitTraceEvent;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.imperative.bulkhead.Bulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Composition-based imperative bulkhead facade.
 *
 * <p>Delegates all permit management to a pluggable {@link BulkheadStrategy}
 * and owns the entire telemetry lifecycle (events, traces, two-tier error handling).
 * This replaces the previous inheritance-based design where
 * {@code AbstractBulkheadStateMachine} mixed telemetry with permit logic.
 *
 * <h2>Separation of concerns</h2>
 * <table>
 *   <tr><th>Responsibility</th><th>Owner</th></tr>
 *   <tr><td>Permit acquire/release</td><td>{@link BulkheadStrategy}</td></tr>
 *   <tr><td>Adaptive feedback</td><td>{@link BulkheadStrategy#onCallComplete}</td></tr>
 *   <tr><td>Event publishing</td><td>This facade</td></tr>
 *   <tr><td>Two-tier error handling</td><td>This facade</td></tr>
 *   <tr><td>RTT measurement</td><td>This facade (via {@code nanoTimeSource})</td></tr>
 * </table>
 *
 * <h2>Exception propagation contract</h2>
 * <ul>
 *   <li><strong>Acquire event failure:</strong> The permit is rolled back and the
 *       exception is propagated. The business call has not started yet — clean abort.</li>
 *   <li><strong>Post-acquire telemetry failures:</strong> Logged and swallowed. Once the
 *       permit is irrevocably granted, telemetry must not disrupt the business call.</li>
 *   <li><strong>Release errors from the strategy:</strong> Always propagated (state machine
 *       defect). If a business error exists, the release error is added as suppressed.</li>
 * </ul>
 *
 * @since 0.3.0
 */
public final class ImperativeBulkhead implements Bulkhead {

  private static final Logger LOG = LoggerFactory.getLogger(ImperativeBulkhead.class);

  private final String name;
  private final BulkheadConfig config;
  private final BulkheadStrategy strategy;
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
    this.name = name;
    this.config = config;
    this.strategy = strategy;
    this.maxWaitDuration = config.getMaxWaitDuration();
    this.nanoTimeSource = config.getNanoTimeSource();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
  }

  // ======================== Bulkhead facade interface ========================

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

      // ── Duty 1: Acquire ──
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

      // ── Two-tier acquire telemetry ──
      if (!handleAcquireSuccess(call.callId(), startWait)) {
        // handleAcquireSuccess rolled back the permit and rethrew — unreachable,
        // but the compiler needs this for the return type.
        throw new IllegalStateException("unreachable");
      }

      // ── Duty 3 (start): RTT measurement ──
      long startNanos = nanoTimeSource.getAsLong();
      Throwable businessError = null;

      try {
        return call.callable().call();
      } catch (Throwable t) {
        businessError = t;
        throw t;
      } finally {
        // ── Duty 3 (end): Measurement ──
        Duration rtt = Duration.ofNanos(nanoTimeSource.getAsLong() - startNanos);

        // ── Duty 2: Guaranteed release ──
        releaseAndReport(call.callId(), rtt, businessError);
      }
    });
  }

  public int getConcurrentCalls() {
    return strategy.concurrentCalls();
  }

  public int getAvailablePermits() {
    return strategy.availablePermits();
  }

  public int getMaxConcurrentCalls() {
    return strategy.maxConcurrentCalls();
  }

  /**
   * Returns the underlying strategy for introspection or testing.
   */
  public BulkheadStrategy getStrategy() {
    return strategy;
  }

  // ======================== Telemetry — acquire ========================

  /**
   * Two-tier acquire telemetry.
   *
   * <p><strong>Tier 1 (critical):</strong> If the acquire event cannot be published,
   * the permit is rolled back (via {@link BulkheadStrategy#rollback()}) and the
   * exception is propagated. The business call has not started — clean abort.
   *
   * <p><strong>Tier 2 (best-effort):</strong> If the wait trace fails after the
   * acquire event was published, the error is logged and swallowed. The permit is
   * irrevocably granted.
   *
   * @return always {@code true} on success; on failure, throws after rollback
   */
  private boolean handleAcquireSuccess(String callId, long startWait) {
    // Tier 1: Acquire event — rollback on failure
    try {
      eventPublisher.publish(new BulkheadOnAcquireEvent(
          callId, name,
          strategy.concurrentCalls(),
          config.getClock().instant()));
    } catch (RuntimeException e) {
      strategy.rollback();
      try {
        eventPublisher.publishTrace(() -> new BulkheadRollbackTraceEvent(
            callId, name, e.getClass().getSimpleName(), config.getClock().instant()));
      } catch (RuntimeException traceError) {
        LOG.error("Failed to publish rollback trace for bulkhead '{}', callId='{}'. "
            + "The permit has been rolled back.", name, callId, traceError);
      }
      throw e;
    }

    // Tier 2: Wait trace — best-effort
    try {
      publishWaitTrace(callId, startWait, true);
    } catch (RuntimeException e) {
      LOG.error("Failed to publish wait trace for acquired call on bulkhead '{}', "
          + "callId='{}'. Permit acquired; telemetry-only failure.", name, callId, e);
    }
    return true;
  }

  /**
   * Publishes telemetry for a failed permit acquisition.
   * Publisher errors are always logged and never propagated.
   */
  private void handleAcquireFailure(String callId, long startWait) {
    try {
      publishWaitTrace(callId, startWait, false);
    } catch (RuntimeException e) {
      LOG.error("Failed to publish wait trace for rejected call on bulkhead '{}', "
          + "callId='{}'. Telemetry-only failure.", name, callId, e);
    }
    try {
      eventPublisher.publish(new BulkheadOnRejectEvent(
          callId, name,
          strategy.concurrentCalls(),
          config.getClock().instant()));
    } catch (RuntimeException e) {
      LOG.error("Failed to publish reject event for bulkhead '{}', callId='{}'. "
          + "Telemetry-only failure.", name, callId, e);
    }
  }

  // ======================== Telemetry — release ========================

  /**
   * Releases the permit and publishes release telemetry.
   *
   * <p>Follows the strict error hierarchy:
   * <ul>
   *   <li>Algorithm hook errors: logged, never thrown</li>
   *   <li>Release errors: always propagated (state machine defect)</li>
   *   <li>Publisher errors: logged, never thrown</li>
   * </ul>
   */
  private void releaseAndReport(String callId, Duration rtt, Throwable businessError) {
    RuntimeException releaseError = null;

    // 0. Feed the adaptive hook (may throw)
    try {
      strategy.onCallComplete(rtt, businessError == null);
    } catch (RuntimeException algorithmError) {
      LOG.error("Adaptive algorithm hook failed for bulkhead '{}', callId='{}'. "
          + "Permit will still be released.", name, callId, algorithmError);
    } finally {
      // 1. Release the permit — ALWAYS executes
      try {
        strategy.release();
      } catch (RuntimeException e) {
        releaseError = e;
        LOG.error("Strategy release failed for bulkhead '{}', callId='{}'. "
            + "Telemetry will still be published.", name, callId, e);
      }
    }

    // 2. Publish release event — best-effort
    try {
      eventPublisher.publish(new BulkheadOnReleaseEvent(
          callId, name,
          strategy.concurrentCalls(),
          config.getClock().instant()));
    } catch (RuntimeException publisherError) {
      LOG.error("Failed to publish release event for bulkhead '{}', callId='{}'. "
          + "Permit released; telemetry-only failure.", name, callId, publisherError);
    }

    // 3. Propagate release error (state machine defect)
    if (releaseError != null) {
      if (businessError != null) {
        businessError.addSuppressed(releaseError);
      } else {
        throw releaseError;
      }
    }
  }

  // ======================== Internal — wait trace ========================

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
