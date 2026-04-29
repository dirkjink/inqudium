package eu.inqudium.core.element.timelimiter.dsl;

public interface TimeLimiterNaming {
    /**
     * Assigns a mandatory unique identifier to this Circuit Breaker.
     */
    TimeLimiterProtection named(String name);
}
