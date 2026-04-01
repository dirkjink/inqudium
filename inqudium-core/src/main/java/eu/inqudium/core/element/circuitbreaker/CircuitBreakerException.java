package eu.inqudium.core.element.circuitbreaker;

/**
 * Exception thrown when a call is rejected because the circuit breaker is open.
 */
public class CircuitBreakerException extends RuntimeException {

  private final String circuitBreakerName;
  private final CircuitState state;

  public CircuitBreakerException(String circuitBreakerName, CircuitState state) {
    super("CircuitBreaker '%s' is %s — call not permitted".formatted(circuitBreakerName, state));
    this.circuitBreakerName = circuitBreakerName;
    this.state = state;
  }

  public String getCircuitBreakerName() {
    return circuitBreakerName;
  }

  public CircuitState getState() {
    return state;
  }
}
