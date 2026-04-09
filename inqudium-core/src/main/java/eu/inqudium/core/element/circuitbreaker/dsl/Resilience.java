package eu.inqudium.core.element.circuitbreaker.dsl;

public final class Resilience {

  private Resilience() {
    // Prevent instantiation
  }

  public static CircuitBreakerProtection stabilizeWithCircuitBreaker() {
    return new DefaultCircuitBreakerProtection();
  }

  public static FailureTrackingStrategy errorTracking() {
    return new DefaultErrorTrackingStrategy();
  }
}