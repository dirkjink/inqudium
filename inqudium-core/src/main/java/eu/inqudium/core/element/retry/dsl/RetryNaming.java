package eu.inqudium.core.element.retry.dsl;

public interface RetryNaming {
    /**
     * Assigns a mandatory unique identifier to this Circuit Breaker.
     */
    RetryProtection named(String name);
}
