package eu.inqudium.core.element.circuitbreaker.dsl;

public interface FailureTrackingStrategy {

  CountBasedStrategy byCountingCalls();

  TimeBasedStrategy byTimeWindow();

  interface Builder {
    FailureMetricsConfig apply();
  }

  static FailureTrackingStrategy errorTracking() {
    return new DefaultErrorTrackingStrategy();
  }

  interface CountBasedStrategy extends Builder {
    CountBasedStrategy keepingHistoryOf(int numberOfCalls);

    CountBasedStrategy requiringAtLeast(int minimumCalls);

    SlidingWindowConfig applyProtectiveProfile();

    SlidingWindowConfig applyBalancedProfile();

    SlidingWindowConfig applyPermissiveProfile();

    @Override
    SlidingWindowConfig apply();
  }

  interface TimeBasedStrategy extends Builder {
    TimeBasedStrategy lookingAtTheLast(int seconds);

    TimeBasedSlidingWindowConfig applyProtectiveProfile();

    TimeBasedSlidingWindowConfig applyBalancedProfile();

    TimeBasedSlidingWindowConfig applyPermissiveProfile();

    @Override
    TimeBasedSlidingWindowConfig apply();
  }
}
