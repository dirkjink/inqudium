package eu.inqudium.core.element.circuitbreaker.dsl;

public final class Resilience {

    private Resilience() {
        // Prevent instantiation
    }

    public static CircuitBreakerNaming stabilizeWithCircuitBreaker() {
        return new DefaultCircuitBreakerProtection(null);
    }
}