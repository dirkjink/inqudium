package eu.inqudium.core.circuitbreaker;

import java.time.Instant;

/**
 * Immutable snapshot of the circuit breaker's internal state.
 *
 * <p>This is the central data structure of the functional core.
 * All state transitions produce a new snapshot rather than mutating in place.
 *
 * @param state            the current circuit state
 * @param failureCount     accumulated failure count (reset on state transition)
 * @param successCount     accumulated success count in HALF_OPEN (reset on state transition)
 * @param halfOpenAttempts number of probe calls issued in HALF_OPEN
 * @param stateChangedAt   timestamp of the last state transition
 */
public record CircuitBreakerSnapshot(
    CircuitState state,
    int failureCount,
    int successCount,
    int halfOpenAttempts,
    Instant stateChangedAt
) {

  /**
   * Creates the initial snapshot in CLOSED state with all counters at zero.
   */
  public static CircuitBreakerSnapshot initial(Instant now) {
    return new CircuitBreakerSnapshot(CircuitState.CLOSED, 0, 0, 0, now);
  }

  // --- Wither methods for immutable updates ---

  public CircuitBreakerSnapshot withState(CircuitState newState, Instant now) {
    return new CircuitBreakerSnapshot(newState, 0, 0, 0, now);
  }

  public CircuitBreakerSnapshot withIncrementedFailureCount() {
    return new CircuitBreakerSnapshot(state, failureCount + 1, successCount, halfOpenAttempts, stateChangedAt);
  }

  public CircuitBreakerSnapshot withIncrementedSuccessCount() {
    return new CircuitBreakerSnapshot(state, failureCount, successCount + 1, halfOpenAttempts, stateChangedAt);
  }

  public CircuitBreakerSnapshot withIncrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureCount, successCount, halfOpenAttempts + 1, stateChangedAt);
  }

  public CircuitBreakerSnapshot withResetFailureCount() {
    return new CircuitBreakerSnapshot(state, 0, successCount, halfOpenAttempts, stateChangedAt);
  }

  // Fix 2: Allow releasing a HALF_OPEN attempt slot when an ignored exception occurs
  public CircuitBreakerSnapshot withDecrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureCount, successCount, Math.max(0, halfOpenAttempts - 1), stateChangedAt);
  }

  // Fix 4: Gradual failure decay instead of full reset — one success heals one failure
  public CircuitBreakerSnapshot withDecrementedFailureCount() {
    return new CircuitBreakerSnapshot(state, Math.max(0, failureCount - 1), successCount, halfOpenAttempts, stateChangedAt);
  }
}
