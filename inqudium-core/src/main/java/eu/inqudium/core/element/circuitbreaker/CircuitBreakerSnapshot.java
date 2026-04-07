package eu.inqudium.core.element.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;

/**
 * @param stateChangedAtNanos nanosecond timestamp (from {@link eu.inqudium.core.time.InqNanoTimeSource}) when the state was entered
 * @param transitionReason    a human-readable reason why this state was entered
 */
public record CircuitBreakerSnapshot(
    CircuitState state,
    FailureMetrics failureMetrics,
    int successCount,
    int halfOpenAttempts,
    long stateChangedAtNanos,
    String transitionReason
) {

  public static CircuitBreakerSnapshot initial(long nowNanos, FailureMetrics initialMetrics) {
    return new CircuitBreakerSnapshot(
        CircuitState.CLOSED, initialMetrics, 0, 0, nowNanos, "Initial configuration applied"
    );
  }

  // --- Wither methods ---

  public CircuitBreakerSnapshot withState(CircuitState newState, long nowNanos, String reason) {
    return new CircuitBreakerSnapshot(newState, failureMetrics.reset(nowNanos), 0, 0, nowNanos, reason);
  }

  public CircuitBreakerSnapshot withUpdatedFailureMetrics(FailureMetrics newMetrics) {
    return new CircuitBreakerSnapshot(state, newMetrics, successCount, halfOpenAttempts, stateChangedAtNanos, transitionReason);
  }

  public CircuitBreakerSnapshot withIncrementedSuccessCount() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount + 1, halfOpenAttempts, stateChangedAtNanos, transitionReason);
  }

  public CircuitBreakerSnapshot withIncrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount, halfOpenAttempts + 1, stateChangedAtNanos, transitionReason);
  }

  public CircuitBreakerSnapshot withDecrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount, Math.max(0, halfOpenAttempts - 1), stateChangedAtNanos, transitionReason);
  }
}
