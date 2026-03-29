package eu.inqudium.core.circuitbreaker;

/**
 * Behavioral contract for circuit breaker state transitions.
 *
 * <p>Decides whether a call is permitted, processes outcomes, and determines
 * state transitions. Pure — no threading, no timing. The paradigm module
 * provides synchronization and wait-duration enforcement (ADR-005).
 *
 * @since 0.1.0
 */
public interface CircuitBreakerBehavior {

  /**
   * Returns the default behavior implementation.
   *
   * @return the default behavior
   */
  static CircuitBreakerBehavior defaultBehavior() {
    return DefaultCircuitBreakerBehavior.INSTANCE;
  }

  /**
   * Determines whether a call is permitted in the given state.
   *
   * @param currentState the current circuit breaker state
   * @param config       the circuit breaker configuration
   * @return true if the call should proceed
   */
  boolean isCallPermitted(CircuitBreakerState currentState, CircuitBreakerConfig config);

  /**
   * Processes a successful call outcome and determines the next state.
   *
   * @param currentState the current state
   * @param snapshot     the sliding window snapshot after recording the outcome
   * @param config       the circuit breaker configuration
   * @return the next state (may be the same as current)
   */
  CircuitBreakerState onSuccess(CircuitBreakerState currentState, WindowSnapshot snapshot, CircuitBreakerConfig config);

  /**
   * Processes a failed call outcome and determines the next state.
   *
   * @param currentState the current state
   * @param snapshot     the sliding window snapshot after recording the outcome
   * @param config       the circuit breaker configuration
   * @return the next state (may be the same as current)
   */
  CircuitBreakerState onError(CircuitBreakerState currentState, WindowSnapshot snapshot, CircuitBreakerConfig config);
}

/**
 * Default circuit breaker state transition logic.
 */
final class DefaultCircuitBreakerBehavior implements CircuitBreakerBehavior {

  static final DefaultCircuitBreakerBehavior INSTANCE = new DefaultCircuitBreakerBehavior();

  private DefaultCircuitBreakerBehavior() {
  }

  @Override
  public boolean isCallPermitted(CircuitBreakerState currentState, CircuitBreakerConfig config) {
    return switch (currentState) {
      case CLOSED -> true;
      case OPEN -> false;
      case HALF_OPEN -> true; // limited by permittedNumberOfCallsInHalfOpenState (enforced by paradigm module)
    };
  }

  @Override
  public CircuitBreakerState onSuccess(CircuitBreakerState currentState, WindowSnapshot snapshot, CircuitBreakerConfig config) {
    return switch (currentState) {
      case CLOSED -> evaluateThresholds(snapshot, config);
      case HALF_OPEN -> evaluateHalfOpenThresholds(snapshot, config);
      case OPEN -> CircuitBreakerState.OPEN;
    };
  }

  @Override
  public CircuitBreakerState onError(CircuitBreakerState currentState, WindowSnapshot snapshot, CircuitBreakerConfig config) {
    return switch (currentState) {
      case CLOSED -> evaluateThresholds(snapshot, config);
      case HALF_OPEN -> evaluateHalfOpenThresholds(snapshot, config);
      case OPEN -> CircuitBreakerState.OPEN;
    };
  }

  private CircuitBreakerState evaluateHalfOpenThresholds(WindowSnapshot snapshot, CircuitBreakerConfig config) {
    // Wait until all permitted probes have completed before deciding
    if (!snapshot.hasMinimumCalls(config.getPermittedNumberOfCallsInHalfOpenState())) {
      return CircuitBreakerState.HALF_OPEN;
    }
    if (snapshot.failureRate() >= config.getFailureRateThreshold()) {
      return CircuitBreakerState.OPEN;
    }
    if (snapshot.slowCallRate() >= config.getSlowCallRateThreshold()) {
      return CircuitBreakerState.OPEN;
    }
    return CircuitBreakerState.CLOSED;
  }

  private CircuitBreakerState evaluateThresholds(WindowSnapshot snapshot, CircuitBreakerConfig config) {
    if (!snapshot.hasMinimumCalls(config.getMinimumNumberOfCalls())) {
      return CircuitBreakerState.CLOSED; // not enough data
    }
    if (snapshot.failureRate() >= config.getFailureRateThreshold()) {
      return CircuitBreakerState.OPEN;
    }
    if (snapshot.slowCallRate() >= config.getSlowCallRateThreshold()) {
      return CircuitBreakerState.OPEN;
    }
    return CircuitBreakerState.CLOSED;
  }
}
