package eu.inqudium.core.element.ratelimiter.dsl;

import java.time.Duration;

class DefaultRateLimiterProtection implements RateLimiterNaming, RateLimiterProtection {

    private int limitForPeriod = 50; // Fallback
    private Duration limitRefreshPeriod = Duration.ofSeconds(1); // Fallback: 50 calls per second
    private Duration timeoutDuration = Duration.ofSeconds(5); // Fallback: Default wait time
    private String name;

    @Override
    public RateLimiterProtection named(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Bulkhead name must not be blank");
        }
        this.name = name;
        return this;
    }

    @Override
    public RateLimiterProtection allowingCalls(int limit) {
        this.limitForPeriod = limit;
        return this;
    }

    @Override
    public RateLimiterProtection refreshingLimitEvery(Duration period) {
        this.limitRefreshPeriod = period;
        return this;
    }

    @Override
    public RateLimiterProtection waitingForPermissionAtMost(Duration maxWait) {
        this.timeoutDuration = maxWait;
        return this;
    }

    @Override
    public RateLimiterConfig applyStrictProfile() {
        // Strict: Very low limits, fails fast if the limit is exceeded (no waiting)
        return new RateLimiterConfig(name, 10, Duration.ofSeconds(1), Duration.ZERO);
    }

    @Override
    public RateLimiterConfig applyBalancedProfile() {
        // Balanced: Standard rate, short wait allowed to fetch a token in the next cycle
        return new RateLimiterConfig(name, 100, Duration.ofSeconds(1), Duration.ofMillis(500));
    }

    @Override
    public RateLimiterConfig applyPermissiveProfile() {
        // Permissive: High burst capacity, long wait allowed
        return new RateLimiterConfig(name, 1000, Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    @Override
    public RateLimiterConfig apply() {
        return new RateLimiterConfig(name, limitForPeriod, limitRefreshPeriod, timeoutDuration);
    }
}
