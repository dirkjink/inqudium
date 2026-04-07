package eu.inqudium.core.element.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;

import java.util.Optional;

/**
 * Pure functional core of the circuit breaker state machine.
 *
 * <p>All methods are static and side-effect-free. They accept the current
 * immutable {@link CircuitBreakerSnapshot} and return a new snapshot
 * reflecting the state transition. No synchronization, no I/O, no mutation.
 *
 * <p>All time parameters are expressed as nanoseconds obtained from
 * {@link eu.inqudium.core.time.InqNanoTimeSource#now()}, ensuring
 * deterministic testability without {@code Thread.sleep()} (ADR-016).
 */
public final class CircuitBreakerCore {

  private CircuitBreakerCore() {
    // Utility class — not instantiable
  }

  /**
   * Evaluates whether a call is permitted given the current state.
   *
   * @param snapshot  the current state snapshot
   * @param config    the circuit breaker configuration
   * @param nowNanos  the current nanosecond timestamp
   * @return a {@link PermissionResult} indicating whether the call is permitted
   */
  public static PermissionResult tryAcquirePermission(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      long nowNanos) {

    return switch (snapshot.state()) {
      case CLOSED -> PermissionResult.permitted(snapshot);

      case OPEN -> {
        if (isWaitDurationExpired(snapshot, config, nowNanos)) {
          String reason = "Wait duration of %s expired".formatted(config.waitDurationInOpenState());

          CircuitBreakerSnapshot halfOpen = snapshot
              .withState(CircuitState.HALF_OPEN, nowNanos, reason)
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

  public static CircuitBreakerSnapshot recordSuccess(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      long nowNanos) {

    return switch (snapshot.state()) {
      case CLOSED -> {
        FailureMetrics updatedMetrics = snapshot.failureMetrics().recordSuccess(nowNanos);
        if (updatedMetrics.isThresholdReached(nowNanos)) {
          String reason = updatedMetrics.getTripReason(nowNanos);
          yield snapshot.withState(CircuitState.OPEN, nowNanos, reason);
        }
        yield snapshot.withUpdatedFailureMetrics(updatedMetrics);
      }

      case HALF_OPEN -> {
        int newSuccessCount = snapshot.successCount() + 1;
        if (newSuccessCount >= config.successThresholdInHalfOpen()) {
          String reason = "Success threshold (%d) met in HALF_OPEN state".formatted(config.successThresholdInHalfOpen());
          yield snapshot.withState(CircuitState.CLOSED, nowNanos, reason);
        }
        yield snapshot.withIncrementedSuccessCount();
      }

      case OPEN -> snapshot;
    };
  }

  public static CircuitBreakerSnapshot recordFailure(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      long nowNanos) {

    return switch (snapshot.state()) {
      case CLOSED -> {
        FailureMetrics updatedMetrics = snapshot.failureMetrics().recordFailure(nowNanos);
        if (updatedMetrics.isThresholdReached(nowNanos)) {
          String reason = updatedMetrics.getTripReason(nowNanos);
          yield snapshot.withState(CircuitState.OPEN, nowNanos, reason);
        }
        yield snapshot.withUpdatedFailureMetrics(updatedMetrics);
      }

      case HALF_OPEN -> {
        String reason = "Probe call failed in HALF_OPEN state";
        yield snapshot.withState(CircuitState.OPEN, nowNanos, reason);
      }

      case OPEN -> snapshot;
    };
  }

  public static CircuitBreakerSnapshot recordIgnored(CircuitBreakerSnapshot snapshot) {
    return switch (snapshot.state()) {
      case HALF_OPEN -> snapshot.withDecrementedHalfOpenAttempts();
      case CLOSED, OPEN -> snapshot;
    };
  }

  // ======================== Query helpers ========================

  public static CircuitState currentState(CircuitBreakerSnapshot snapshot) {
    return snapshot.state();
  }

  public static boolean isWaitDurationExpired(
      CircuitBreakerSnapshot snapshot,
      CircuitBreakerConfig config,
      long nowNanos) {

    long elapsedNanos = nowNanos - snapshot.stateChangedAtNanos();
    return elapsedNanos >= config.waitDurationNanos();
  }

  public static Optional<StateTransition> detectTransition(
      String name,
      CircuitBreakerSnapshot before,
      CircuitBreakerSnapshot after,
      long nowNanos) {

    if (before.state() != after.state()) {
      return Optional.of(new StateTransition(
          name,
          before.state(),
          after.state(),
          nowNanos,
          after.transitionReason()
      ));
    }
    return Optional.empty();
  }
}
