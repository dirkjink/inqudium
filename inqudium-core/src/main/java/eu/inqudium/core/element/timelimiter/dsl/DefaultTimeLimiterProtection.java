package eu.inqudium.core.element.timelimiter.dsl;

import java.time.Duration;

class DefaultTimeLimiterProtection implements TimeLimiterNaming, TimeLimiterProtection {

    private Duration timeoutDuration = Duration.ofSeconds(1); // Fallback
    private boolean cancelRunningTask = true; // Fallback: Free up resources
    private String name;

    @Override
    public TimeLimiterProtection named(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Bulkhead name must not be blank");
        }
        this.name = name;
        return this;
    }

    @Override
    public TimeLimiterProtection timingOutAfter(Duration timeout) {
        this.timeoutDuration = timeout;
        return this;
    }

    @Override
    public TimeLimiterProtection cancelingRunningTasks(boolean cancel) {
        this.cancelRunningTask = cancel;
        return this;
    }

    @Override
    public TimeLimiterConfig applyStrictProfile() {
        // Strict: Very short timeout, aggressively kills the thread
        return new TimeLimiterConfig(name, Duration.ofMillis(500), true);
    }

    @Override
    public TimeLimiterConfig applyBalancedProfile() {
        // Balanced: Standard timeout, safely cancels task
        return new TimeLimiterConfig(name, Duration.ofSeconds(3), true);
    }

    @Override
    public TimeLimiterConfig applyPermissiveProfile() {
        // Permissive: Long waiting allowed, lets the thread finish in background
        return new TimeLimiterConfig(name, Duration.ofSeconds(10), false);
    }

    @Override
    public TimeLimiterConfig apply() {
        return new TimeLimiterConfig(name, timeoutDuration, cancelRunningTask);
    }
}
