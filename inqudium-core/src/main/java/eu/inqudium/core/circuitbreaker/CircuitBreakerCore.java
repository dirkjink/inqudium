package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.circuitbreaker.metrics.FailureMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Pure functional core of the circuit breaker state machine.
 *
 * <p>All methods are static and side-effect-free. They accept the current
 * immutable {@link CircuitBreakerSnapshot} and return a new snapshot
 * reflecting the state transition. No synchronization, no I/O, no mutation.
 *
 * <p>This design allows the same core logic to be shared between an
 * imperative (virtual-thread) wrapper and a reactive (Project Reactor) wrapper,
 * each providing its own concurrency strategy.
 *
 * <h2>State Machine</h2>
 * <pre>
 * [failures >= threshold]
 * CLOSED ──────────────────────────────► OPEN
 * ▲                                     │
 * │ [successes >= successThreshold]     │ [waitDuration expired]
 * │                                     ▼
 * └──────────────────────────────── HALF_OPEN
 * │
 * │ [any failure]
 * └──► OPEN
 * </pre>
 */
public final class CircuitBreakerCore {

  private CircuitBreakerCore() {
    // Utility class — not instantiable
  }

  // ======================== Permission ========================

  /**
   * Evaluates whether a call is permitted given the current state.
   *
   * <p>May trigger a state transition from OPEN → HALF_OPEN when the
   * wait duration has expired. The returned {@link PermissionResult}
   * contains the (possibly updated) snapshot.
   *
   * @param snapshot the current state snapshot
   * @param config   the circuit breaker configuration
   * @param now      the current timestamp
   * @return a {@link PermissionResult} indicating whether the call is permitted
   */
  /**
   * Evaluates whether a call is permitted given the current state.
   *
   * <p>May trigger a state transition from OPEN → HALF_OPEN when the
   * wait duration has expired. The returned {@link PermissionResult}
   * contains the (possibly updated) snapshot.
   *
   * @param snapshot the current state snapshot
   * @param config   the circuit breaker configuration
   * @param now      the current timestamp
   * @return a {@link PermissionResult} indicating whether the call is permitted
   */
  public static PermissionResult tryAcquirePermission(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      Instant now) {

    return switch (snapshot.state()) {
      case CLOSED -> PermissionResult.permitted(snapshot);

      case OPEN -> {
        if (isWaitDurationExpired(snapshot, config, now)) {
          // DevOps Logging: Reason hinzufügen!
          String reason = "Wait duration of %s expired".formatted(config.waitDurationInOpenState());

          CircuitBreakerSnapshot halfOpen = snapshot
              .withState(CircuitState.HALF_OPEN, now, reason)
              .withIncrementedHalfOpenAttempts();

          yield PermissionResult.permitted(halfOpen);
        }
        yield PermissionResult.rejected(snapshot);
      }

      case HALF_OPEN -> {
        if (snapshot.halfOpenAttempts() < config.permittedCallsInHalfOpen()) {
          yield PermissionResult.permitted(snapshot.withIncrementedHalfOpenAttempts());
        }
        yield PermissionResult.rejected(snapshot);
      }
    };
  }

  // ======================== Recording outcomes ========================

  /**
   * Records a successful call and returns the updated snapshot.
   *
   * <p>In CLOSED state, the success recording is delegated to the configured
   * {@link FailureMetrics} strategy. It also evaluates the threshold, because
   * in sliding window algorithms, adding a success might reach the
   * 'minimumNumberOfCalls' limit, thereby activating a previously hidden
   * high failure rate.
   * * <p>In HALF_OPEN state the success counter is incremented; if it reaches the
   * configured threshold the circuit transitions back to CLOSED.
   */
  public static CircuitBreakerSnapshot recordSuccess(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      Instant now) {

    return switch (snapshot.state()) {
      case CLOSED -> {
        FailureMetrics updatedMetrics = snapshot.failureMetrics().recordSuccess(now);
        if (updatedMetrics.isThresholdReached(config, now)) {
          // METRIK WIRD GEFRAGT WARUM:
          String reason = updatedMetrics.getTripReason(config, now);
          yield snapshot.withState(CircuitState.OPEN, now, reason);
        }
        yield snapshot.withUpdatedFailureMetrics(updatedMetrics);
      }

      case HALF_OPEN -> {
        int newSuccessCount = snapshot.successCount() + 1;
        if (newSuccessCount >= config.successThresholdInHalfOpen()) {
          // Reason hinzufügen!
          String reason = "Success threshold (%d) met in HALF_OPEN state".formatted(config.successThresholdInHalfOpen());
          yield snapshot.withState(CircuitState.CLOSED, now, reason);
        }
        yield snapshot.withIncrementedSuccessCount();
      }

      case OPEN -> snapshot;
    };
  }

  /**
   * Records a failed call and returns the updated snapshot.
   *
   * <p>In CLOSED state, the failure recording and threshold evaluation are
   * delegated to the configured {@link FailureMetrics} strategy. If the threshold
   * is reached, the circuit transitions to OPEN.
   * In HALF_OPEN state any failure immediately transitions back to OPEN.
   *
   * @param snapshot the current state snapshot
   * @param config   the circuit breaker configuration
   * @param now      the current timestamp
   * @return the updated snapshot
   */
  public static CircuitBreakerSnapshot recordFailure(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      Instant now) {

    return switch (snapshot.state()) {
      case CLOSED -> {
        FailureMetrics updatedMetrics = snapshot.failureMetrics().recordFailure(now);
        if (updatedMetrics.isThresholdReached(config, now)) {
          // METRIK WIRD GEFRAGT WARUM:
          String reason = updatedMetrics.getTripReason(config, now);
          yield snapshot.withState(CircuitState.OPEN, now, reason);
        }
        yield snapshot.withUpdatedFailureMetrics(updatedMetrics);
      }

      case HALF_OPEN -> {
        // Reason hinzufügen!
        String reason = "Probe call failed in HALF_OPEN state";
        yield snapshot.withState(CircuitState.OPEN, now, reason);
      }

      case OPEN -> snapshot;
    };
  }

  /**
   * Records that a call outcome was ignored (neither success nor failure).
   *
   * <p>In HALF_OPEN state, an ignored exception must release the attempt slot
   * that was consumed during permission acquisition. Without this, ignored exceptions
   * would permanently consume HALF_OPEN slots.
   *
   * <p>In CLOSED and OPEN states, this is a no-op.
   *
   * @param snapshot the current state snapshot
   * @return the updated snapshot with the attempt slot released (in HALF_OPEN)
   */
  public static CircuitBreakerSnapshot recordIgnored(CircuitBreakerSnapshot snapshot) {
    return switch (snapshot.state()) {
      case HALF_OPEN -> snapshot.withDecrementedHalfOpenAttempts();
      case CLOSED, OPEN -> snapshot;
    };
  }

  // ======================== Query helpers ========================

  /**
   * Returns the current {@link CircuitState} of the snapshot.
   */
  public static CircuitState currentState(CircuitBreakerSnapshot snapshot) {
    return snapshot.state();
  }

  /**
   * Checks whether the wait duration in OPEN state has expired.
   */
  public static boolean isWaitDurationExpired(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      Instant now) {

    Duration elapsed = Duration.between(snapshot.stateChangedAt(), now);
    return elapsed.compareTo(config.waitDurationInOpenState()) >= 0;
  }

  /**
   * Detects whether a state transition occurred between two snapshots.
   *
   * @return an Optional containing the transition with the captured reason,
   * or empty if no transition occurred
   */
  public static Optional<StateTransition> detectTransition(
      String name,
      CircuitBreakerSnapshot before,
      CircuitBreakerSnapshot after,
      Instant now) {

    if (before.state() != after.state()) {
      // Wir nehmen den Grund aus dem neuen Snapshot ('after'),
      // der beim Zustandswechsel im Core gesetzt wurde.
      return Optional.of(new StateTransition(
          name,
          before.state(),
          after.state(),
          now,
          after.transitionReason()
      ));
    }
    return Optional.empty();
  }
}