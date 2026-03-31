package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.bulkhead.event.BulkheadWaitTraceEvent;
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
 *   <li><b>Post-acquire telemetry errors are never propagated.</b> Once a permit has been
 *       irrevocably granted (acquire event published) or a rejection has been decided, a
 *       crashing event publisher must never disrupt the business call flow. All such
 *       telemetry exceptions — wait traces on both success and failure paths, reject events,
 *       and release events — are logged at ERROR level and swallowed.</li>
 *   <li><b>Acquire event failures trigger a rollback.</b> The acquire event is the sole
 *       exception to the suppression rule. If the primary acquire event cannot be published,
 *       the permit is rolled back and the exception is propagated. This is safe because the
 *       business call has not started yet — a clean abort is possible and preferable to an
 *       orphaned release event with no matching acquire. See
 *       {@link #handleAcquireSuccess} for the full two-tier rationale.</li>
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
   * <p>Added defensive validation for constructor parameters. While
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
   * <h3>Exception Propagation</h3>
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
    // Publisher errors are ALWAYS logged and NEVER propagated, regardless
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
   * Publishes telemetry for a successful permit acquisition.
   *
   * <h3>Two-Tier Error Handling</h3>
   * <p>This method distinguishes between two categories of telemetry:
   * <ul>
   *   <li><b>Acquire event (critical):</b> If the primary acquire event cannot be published,
   *       the permit is rolled back and the exception is propagated. The rationale is that
   *       the acquire path is the only point where we can still abort cleanly — the business
   *       call has not started yet. Allowing a call to proceed without any acquire telemetry
   *       would create an orphaned release event with no matching acquire, which is worse for
   *       observability than a clean rollback.</li>
   *   <li><b>Wait trace (best-effort):</b> If the wait trace fails after the acquire event
   *       has already been published, the error is logged and swallowed. Rolling back the
   *       permit at this point would be incorrect: the acquire event is already in the stream,
   *       and a rollback would produce contradictory telemetry (acquire → rollback, but no
   *       release). The permit must remain granted so the business call proceeds normally and
   *       eventually produces a matching release event.</li>
   * </ul>
   *
   * <p>This two-tier approach aligns with the contract established in
   * {@link #releaseAndReport}: telemetry failures must not disrupt the business call flow
   * once the permit is irrevocably granted.
   */
  protected final boolean handleAcquireSuccess(String callId, long startWait) {
    // ── Tier 1: Acquire event — critical, rollback on failure ──
    try {
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnAcquireEvent(
          callId, name, snap.concurrentCalls(), snap.timestamp()));
    } catch (RuntimeException e) {
      // The acquire event failed. No telemetry has been emitted for this call yet,
      // so rolling back the permit produces a clean state: no acquire, no release,
      // just a rollback trace for diagnostics.
      rollbackPermit();

      // The rollback trace itself is best-effort — if it also fails, we must not
      // mask the original exception or leave the caller without any signal.
      try {
        eventPublisher.publishTrace(() -> new BulkheadRollbackTraceEvent(
            callId,
            name,
            e.getClass().getSimpleName(),
            config.getClock().instant()
        ));
      } catch (RuntimeException traceError) {
        LOG.error("Failed to publish rollback trace for bulkhead '{}', callId='{}'. "
                + "The permit has been rolled back; this is a telemetry-only failure.",
            name, callId, traceError);
      }

      throw e;
    }

    // ── Tier 2: Wait trace — best-effort, swallow on failure ──
    //
    // At this point the acquire event has been published successfully. The permit
    // is irrevocably granted. A wait trace failure must NOT roll back the permit,
    // because that would leave a published acquire event without a matching release.
    try {
      publishWaitTrace(callId, startWait, true);
    } catch (RuntimeException e) {
      LOG.error("Failed to publish wait trace for acquired call on bulkhead '{}', "
          + "callId='{}'. The permit has been acquired; this is a telemetry-only "
          + "failure.", name, callId, e);
    }

    return true;
  }

  /**
   * Publishes telemetry for a failed permit acquisition (timeout, CoDel rejection,
   * or interruption).
   *
   * <h3>Exception Suppression Contract</h3>
   * <p>This method follows the same telemetry-safety contract as
   * {@link #releaseAndReport}: publisher errors are <b>always logged and never
   * propagated</b>. The callers of this method (timeout, CoDel drop, and interruption
   * paths in the state machines) must be able to return {@code false} or throw their
   * domain exception ({@link eu.inqudium.core.bulkhead.InqBulkheadFullException},
   * {@link eu.inqudium.core.bulkhead.InqBulkheadInterruptedException}) without risk
   * of a telemetry crash masking the intended outcome.
   *
   * <p>Each telemetry operation is independently guarded so that a failure in the
   * wait trace does not prevent the reject event from being published, and vice versa.
   */
  protected final void handleAcquireFailure(String callId, long startWait) {
    try {
      publishWaitTrace(callId, startWait, false);
    } catch (RuntimeException e) {
      LOG.error("Failed to publish wait trace for rejected call on bulkhead '{}', "
          + "callId='{}'. This is a telemetry-only failure.", name, callId, e);
    }

    try {
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnRejectEvent(
          callId, name, snap.concurrentCalls(), snap.timestamp()));
    } catch (RuntimeException e) {
      LOG.error("Failed to publish reject event for bulkhead '{}', callId='{}'. "
          + "This is a telemetry-only failure.", name, callId, e);
    }
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
