package eu.inqudium.core.element.ratelimiter.dsl;

import java.time.Duration;

public interface RateLimiterProtection {

    // Modifiers
    RateLimiterProtection allowingCalls(int limit);

    RateLimiterProtection refreshingLimitEvery(Duration period);

    RateLimiterProtection waitingForPermissionAtMost(Duration maxWait);

    // Terminal Operations (Profiles)
    RateLimiterConfig applyStrictProfile();

    RateLimiterConfig applyBalancedProfile();

    RateLimiterConfig applyPermissiveProfile();

    // Terminal Operation for custom configuration
    RateLimiterConfig apply();
}