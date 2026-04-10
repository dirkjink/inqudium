package eu.inqudium.core.element.circuitbreaker.dsl;

public interface CircuitBreakerNaming {
    /**
     * Assigns a mandatory unique identifier to this Circuit Breaker.
     */
    CircuitBreakerProtection named(String name);
}
