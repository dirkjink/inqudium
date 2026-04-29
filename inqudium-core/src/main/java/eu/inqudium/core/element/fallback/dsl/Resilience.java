package eu.inqudium.core.element.fallback.dsl;

public final class Resilience {

    private Resilience() {
    }

    public static <T> FallbackProtection<T> degradeWithFallback() {
        return new DefaultFallbackProtection<>();
    }
}