package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEventPublisher;

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
  @Override
  public int getMaxConcurrentCalls() {
    return maxConcurrentCalls;
  }

  @Override
  public final void releaseAndReport(String callId, Duration rtt, Throwable error) {
    // 0. Feed the adaptive telemetry hook
    onCallComplete(rtt, error);

    // 1. Paradigm-specific release logic
    releasePermitInternal();

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