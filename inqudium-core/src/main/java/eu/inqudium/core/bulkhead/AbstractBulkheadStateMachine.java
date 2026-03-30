package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.event.*;
import eu.inqudium.core.event.InqEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Abstract base state machine handling the paradigm-agnostic telemetry lifecycle.
 *
 * <p>It strictly manages event publishing, consistent snapshots, and safe error
 * suppression. Paradigm modules must extend this to implement the actual
 * permit acquisition and release mechanics.
 *
 * <h2>Exception Propagation Contract</h2>
 * <p>This class distinguishes between <b>infrastructure errors</b> (permit release, adaptive
 * algorithm) and <b>telemetry errors</b> (event publishing). The contract is:
 * <ul>
 *   <li><b>Telemetry errors are never propagated.</b> A crashing event publisher must never
 *       disrupt the business call flow. All telemetry exceptions are logged at ERROR level
 *       and swallowed. This makes the behavior predictable: callers never see telemetry
 *       exceptions regardless of whether the business call succeeded or failed.</li>
 *   <li><b>Infrastructure errors are always propagated.</b> A failing {@code releasePermitInternal()}
 *       indicates a state machine defect that must surface. If a business error already exists,
 *       the infrastructure error is attached as suppressed; otherwise it is thrown directly.</li>
 * </ul>
 *
 * @since 0.2.0
 */
public abstract class AbstractBulkheadStateMachine implements BulkheadStateMachine {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractBulkheadStateMachine.class);

  protected final String name;
  protected final BulkheadConfig config;
  protected final InqEventPublisher eventPublisher;
  protected final int maxConcurrentCalls;
  private final LongSupplier nanoTimeSource;
  private final InqClock clock;

  /**
   * Creates a new state machine instance.
   *
   * <p>FIX: Added defensive validation for constructor parameters. While
   * {@link BulkheadConfig.Builder} validates {@code maxConcurrentCalls >= 0},
   * this class cannot assume it is always constructed from a validated config.
   * Subclasses or test code could pass arbitrary config instances. The validation
   * here provides a fail-fast safety net at the point of use.
   *
   * @param name   the bulkhead instance name, must not be null or blank
   * @param config the configuration, must not be null
   * @throws NullPointerException     if name or config is null
   * @throws IllegalArgumentException if name is blank or maxConcurrentCalls is negative
   */
  protected AbstractBulkheadStateMachine(String name, BulkheadConfig config) {
    Objects.requireNonNull(name, "Bulkhead name must not be null");
    Objects.requireNonNull(config, "BulkheadConfig must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Bulkhead name must not be blank");
    }

    int maxCalls = config.getMaxConcurrentCalls();
    if (maxCalls < 0) {
      throw new IllegalArgumentException(
          "maxConcurrentCalls must be >= 0, but was: " + maxCalls
              + ". A value of 0 creates a closed bulkhead that rejects all requests.");
    }

    this.name = name;
    this.config = config;
    this.maxConcurrentCalls = maxCalls;
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
    this.nanoTimeSource = config.getNanoTimeSource();
    this.clock = config.getClock();
  }

  // ── Abstract methods forced by BulkheadStateMachine interface ──

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
   * Releases a previously acquired permit and reports the execution metrics.
   *
   * <h3>Exception Propagation (FIX)</h3>
   * <p>The original implementation had inconsistent exception handling: a telemetry
   * (event publisher) error was thrown to the caller if — and only if — no business
   * error and no release error existed. This meant monitoring code that caught
   * publisher exceptions would only trigger when everything else happened to succeed,
   * making failure modes unpredictable and hard to test.
   *
   * <p>The fix applies a strict, predictable rule:
   * <ul>
   *   <li><b>Telemetry errors (event publishing):</b> Always logged at ERROR, never thrown.
   *       A broken event publisher must never disrupt the business call path.</li>
   *   <li><b>Algorithm hook errors ({@code onCallComplete}):</b> Always logged at ERROR,
   *       never thrown. The permit must still be released.</li>
   *   <li><b>Release errors ({@code releasePermitInternal}):</b> Always propagated.
   *       If a business error exists, the release error is attached as suppressed.
   *       Otherwise, it is thrown directly. This indicates a state machine defect.</li>
   * </ul>
   */
  @Override
  public final void releaseAndReport(String callId, Duration rtt, Throwable error) {
    RuntimeException releaseError = null;

    try {
      // 0. Feed the adaptive telemetry hook (may throw)
      onCallComplete(callId, rtt, error);
    } catch (RuntimeException algorithmError) {
      // Log but do NOT propagate — the permit MUST be released below
      LOG.error("Adaptive algorithm hook failed for bulkhead '{}', callId='{}'. "
          + "Permit will still be released.", name, callId, algorithmError);
    } finally {
      // 1. Paradigm-specific release logic — ALWAYS executes
      try {
        releasePermitInternal();
      } catch (RuntimeException e) {
        // Capture but don't propagate yet — telemetry must still run
        releaseError = e;
        LOG.error("releasePermitInternal() failed for bulkhead '{}', callId='{}'. "
            + "Telemetry will still be published.", name, callId, e);
      }
    }

    // 2. Telemetry — runs even if releasePermitInternal() failed.
    // FIX: Publisher errors are ALWAYS logged and NEVER propagated, regardless
    // of whether a business error or release error exists. This eliminates the
    // inconsistency where a publisher crash only surfaced to callers when no
    // other error was present.
    try {
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnReleaseEvent(
          callId, name, snap.concurrentCalls(), snap.timestamp()));
    } catch (RuntimeException publisherError) {
      LOG.error("Failed to publish release event for bulkhead '{}', callId='{}'. "
          + "The permit has been released; this is a telemetry-only failure.",
          name, callId, publisherError);
    }

    // 3. Propagate the release error — this indicates a state machine defect
    // and must always surface to the caller.
    if (releaseError != null) {
      if (error != null) {
        error.addSuppressed(releaseError);
      } else {
        throw releaseError;
      }
    }
  }

  protected void publishWaitTrace(String callId, long startWait, boolean acquired) {
    if (eventPublisher.isTraceEnabled()) {
      long waitDurationNanos = nanoTimeSource.getAsLong() - startWait;
      if (waitDurationNanos > 0) {
        eventPublisher.publishTrace(() -> new BulkheadWaitTraceEvent(
            callId, name, waitDurationNanos, acquired, clock.instant()
        ));
      }
    }
  }

  /**
   * Hook for adaptive implementations to analyze call latency and success.
   */
  protected void onCallComplete(String callId, Duration rtt, Throwable error) {
    // No-op by default
  }

  /**
   * FIX #6: Restructured to avoid contradictory telemetry. The wait trace is now
   * published AFTER the acquire event succeeds (not before). On rollback, we no
   * longer have a "wait acquired=true" trace followed by a "rollback" trace —
   * instead, only the rollback trace is emitted, which is consistent.
   */
  protected final boolean handleAcquireSuccess(String callId, long startWait) {
    try {
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnAcquireEvent(
          callId, name, snap.concurrentCalls(), snap.timestamp()));
      // Publish wait trace only AFTER the acquire event succeeded
      publishWaitTrace(callId, startWait, true);
      return true;
    } catch (RuntimeException e) {
      // CRITICAL: Rollback the permit if event publishing crashes
      rollbackPermit();
      eventPublisher.publishTrace(() -> new BulkheadRollbackTraceEvent(
          callId,
          name,
          e.getClass().getSimpleName(),
          config.getClock().instant()
      ));
      throw e;
    }
  }

  protected final void handleAcquireFailure(String callId, long startWait) {
    publishWaitTrace(callId, startWait, false);
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
