package eu.inqudium.core.element.ratelimiter.dsl;

public interface RateLimiterNaming {
    /**
     * Assigns a mandatory unique identifier to this Circuit Breaker.
     */
    RateLimiterProtection named(String name);
}
