package eu.inqudium.core.element.timelimiter.dsl;

import java.time.Duration;

public interface TimeLimiterProtection {

  // Modifiers
  TimeLimiterProtection timingOutAfter(Duration timeout);

  TimeLimiterProtection cancelingRunningTasks(boolean cancel);

  // Terminal Operations (Profiles)
  TimeLimiterConfig applyStrictProfile();

  TimeLimiterConfig applyBalancedProfile();

  TimeLimiterConfig applyPermissiveProfile();

  // Terminal Operation for custom configuration
  TimeLimiterConfig apply();
}
