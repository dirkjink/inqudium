package eu.inqudium.core.element.retry.dsl;

import java.time.Duration;

public interface RetryProtection {

    // Modifiers
    RetryProtection attemptingUpTo(int maxAttempts);

    RetryProtection waitingBetweenAttempts(Duration waitDuration);

    RetryProtection backingOffExponentially(double multiplier);

    // Terminal Operations (Profiles)
    RetryConfig applyStrictProfile();

    RetryConfig applyBalancedProfile();

    RetryConfig applyPermissiveProfile();

    // Terminal Operation for custom configuration
    RetryConfig apply();
}