package eu.inqudium.core.element.fallback.dsl;

public interface FallbackNaming<T> {
    /**
     * Assigns a mandatory unique identifier to this Circuit Breaker.
     */
    FallbackProtection<T> named(String name);
}
