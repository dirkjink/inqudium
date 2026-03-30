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
 * permit acquisition and release mechanics (e.g., Thread Semaphore vs. Coroutine Mutex).
 *
 * @since 0.2.0
 */
public abstract class AbstractBulkheadStateMachine {

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

  /**
   * Called by subclasses to securely publish an acquire event.
   * If publishing fails, this method handles the rollback to prevent permit leaks.
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

  /**
   * Called by subclasses to publish a rejection event.
   */
  protected final void handleAcquireFailure(String callId) {
    var snap = snapshot();
    eventPublisher.publish(new BulkheadOnRejectEvent(
        callId, name, snap.concurrentCalls(), snap.timestamp()));
  }

  /**
   * Safely releases the permit and publishes the metrics.
   * Ensures that publisher exceptions do not mask business exceptions.
   */
  public final void releaseAndReport(String callId, Duration rtt, Throwable error) {
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

  public abstract int getAvailablePermits();

  public abstract int getConcurrentCalls();

  // ── Paradigm-specific internal hooks ──

  protected abstract void releasePermitInternal();

  protected abstract void rollbackPermit();

  private Snapshot snapshot() {
    return new Snapshot(getConcurrentCalls(), config.getClock().instant());
  }

  private record Snapshot(int concurrentCalls, Instant timestamp) {
  }
}
