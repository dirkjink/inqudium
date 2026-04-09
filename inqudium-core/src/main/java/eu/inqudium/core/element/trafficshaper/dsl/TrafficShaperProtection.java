package eu.inqudium.core.element.trafficshaper.dsl;

import java.time.Duration;

public interface TrafficShaperProtection {

  // Modifiers
  TrafficShaperProtection permittingCalls(int limit);

  TrafficShaperProtection withinPeriod(Duration period);

  TrafficShaperProtection queueingForAtMost(Duration maxWait);

  // Terminal Operations (Profiles)
  TrafficShaperConfig applyStrictProfile();

  TrafficShaperConfig applyBalancedProfile();

  TrafficShaperConfig applyPermissiveProfile();

  // Terminal Operation for custom configuration
  TrafficShaperConfig apply();
}