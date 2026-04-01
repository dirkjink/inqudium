package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.circuitbreaker.metrics.FailureMetrics;
import java.time.Instant;

/**
 * @param transitionReason a human-readable reason why this state was entered
 */
public record CircuitBreakerSnapshot(
    CircuitState state,
    FailureMetrics failureMetrics,
    int successCount,
    int halfOpenAttempts,
    Instant stateChangedAt,
    String transitionReason
) {

  public static CircuitBreakerSnapshot initial(Instant now, FailureMetrics initialMetrics) {
    return new CircuitBreakerSnapshot(
        CircuitState.CLOSED, initialMetrics, 0, 0, now, "Initial configuration applied"
    );
  }

  // --- Wither methods ---

  public CircuitBreakerSnapshot withState(CircuitState newState, Instant now, String reason) {
    return new CircuitBreakerSnapshot(newState, failureMetrics.reset(now), 0, 0, now, reason);
  }

  public CircuitBreakerSnapshot withUpdatedFailureMetrics(FailureMetrics newMetrics) {
    return new CircuitBreakerSnapshot(state, newMetrics, successCount, halfOpenAttempts, stateChangedAt, transitionReason);
  }

  public CircuitBreakerSnapshot withIncrementedSuccessCount() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount + 1, halfOpenAttempts, stateChangedAt, transitionReason);
  }

  public CircuitBreakerSnapshot withIncrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount, halfOpenAttempts + 1, stateChangedAt, transitionReason);
  }

  public CircuitBreakerSnapshot withDecrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount, Math.max(0, halfOpenAttempts - 1), stateChangedAt, transitionReason);
  }
}