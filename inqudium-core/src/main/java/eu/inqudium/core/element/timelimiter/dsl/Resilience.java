package eu.inqudium.core.element.timelimiter.dsl;

public final class Resilience {

    private Resilience() {
    }

    // --- Time Limiter (NEU) ---
    public static TimeLimiterProtection constrainWithTimeLimiter() {
        return new DefaultTimeLimiterProtection();
    }
}
