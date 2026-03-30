package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Abstract base state machine handling the paradigm-agnostic telemetry lifecycle.
 *
 * <p>It strictly manages event publishing, consistent snapshots, and safe error
 * suppression. Paradigm modules must extend this to implement the actual
 * permit acquisition and release mechanics.
 *
 * @since 0.2.0
 */
public abstract class AbstractBulkheadStateMachine implements BulkheadStateMachine {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractBulkheadStateMachine.class);

  protected final String name;
  protected final BulkheadConfig config;
  protected final InqEventPublisher eventPublisher;
  protected final int maxConcurrentCalls;

  protected AbstractBulkheadStateMachine(String name, BulkheadConfig config) {
    this.name = name;
    this.config = config;
    this.maxConcurrentCalls = config.getMaxConcurrentCalls();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
  }

  // ── Abstract methods forced by BulkheadStateMachine interface ──

  @Override
  public abstract boolean tryAcquireNonBlocking(String callId);

  @Override
  public abstract boolean tryAcquireBlocking(String callId, Duration timeout) throws InterruptedException;

  @Override
  public abstract int getAvailablePermits();

  @Override
  public abstract int getConcurrentCalls();

  // ── Implemented Telemetry & State Logic ──

  /**
   * Returns the effective maximum concurrent calls.
   *
   * <p>Subclasses with adaptive limits (e.g., AIMD, Vegas) should override this
   * to return the current dynamic limit instead of the static config value.
   */
  @Override
  public int getMaxConcurrentCalls() {
    return maxConcurrentCalls;
  }

  /**
   * FIX #1: Wrapped onCallComplete + releasePermitInternal in try-finally to guarantee
   * permit release even when the adaptive algorithm hook throws an exception.
   * Without this, a failing onCallComplete() would permanently leak the permit.
   */
  @Override
  public final void releaseAndReport(String callId, Duration rtt, Throwable error) {
    try {
      // 0. Feed the adaptive telemetry hook (may throw)
      onCallComplete(rtt, error);
    } catch (RuntimeException algorithmError) {
      // Log but do NOT propagate — the permit MUST be released below
      LOG.error("Adaptive algorithm hook failed for bulkhead '{}', callId='{}'. "
          + "Permit will still be released.", name, callId, algorithmError);
    } finally {
      // 1. Paradigm-specific release logic — ALWAYS executes
      releasePermitInternal();
    }

    // 2. Telemetry and safe exception handling
    try {
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnReleaseEvent(
          callId, name, snap.concurrentCalls(), snap.timestamp()));
    } catch (RuntimeException publisherError) {
      if (error != null) {
        if (error != publisherError) {
          error.addSuppressed(publisherError);
        }
      } else {
        throw publisherError;
      }
    }
  }

  /**
   * Hook for adaptive implementations to analyze call latency and success.
   */
  protected void onCallComplete(Duration rtt, Throwable error) {
    // No-op by default
  }

  /**
   * Publishes an acquire event and returns true. If event publishing crashes,
   * the permit is rolled back to prevent leaks and the exception is propagated.
   *
   * <p>This fail-safe rollback is critical: without it, a crashing event publisher
   * would leave a permit permanently acquired with no corresponding release call,
   * since the caller never receives the decorated callable.
   */
  protected final boolean handleAcquireSuccess(String callId) {
    try {
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnAcquireEvent(
          callId, name, snap.concurrentCalls(), snap.timestamp()));
      return true;
    } catch (RuntimeException e) {
      // CRITICAL: Rollback the permit if event publishing crashes
      rollbackPermit();
      throw e;
    }
  }

  protected final void handleAcquireFailure(String callId) {
    var snap = snapshot();
    eventPublisher.publish(new BulkheadOnRejectEvent(
        callId, name, snap.concurrentCalls(), snap.timestamp()));
  }

  // ── Paradigm-specific internal hooks ──

  protected abstract void releasePermitInternal();

  protected abstract void rollbackPermit();

  private Snapshot snapshot() {
    return new Snapshot(getConcurrentCalls(), config.getClock().instant());
  }

  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  private record Snapshot(int concurrentCalls, Instant timestamp) {
  }
}
