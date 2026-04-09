package eu.inqudium.core.element.circuitbreaker.dsl;

class DefaultErrorTrackingStrategy implements FailureTrackingStrategy {

  @Override
  public CountBasedStrategy byCountingCalls() {
    return new CountBased();
  }

  @Override
  public TimeBasedStrategy byTimeWindow() {
    return new TimeBased();
  }

  static class CountBased implements CountBasedStrategy {
    private int windowSize = 50; // Default fallback
    private int minimumCalls = 20; // Default fallback

    @Override
    public CountBasedStrategy keepingHistoryOf(int numberOfCalls) {
      this.windowSize = numberOfCalls;
      return this;
    }

    @Override
    public CountBasedStrategy requiringAtLeast(int minimumCalls) {
      this.minimumCalls = minimumCalls;
      return this;
    }

    @Override
    public SlidingWindowConfig applyProtectiveProfile() {
      return new SlidingWindowConfig(10, 5);
    }

    @Override
    public SlidingWindowConfig applyBalancedProfile() {
      return new SlidingWindowConfig(50, 20);
    }

    @Override
    public SlidingWindowConfig applyPermissiveProfile() {
      return new SlidingWindowConfig(200, 100);
    }

    @Override
    public SlidingWindowConfig apply() {
      return new SlidingWindowConfig(windowSize, minimumCalls);
    }
  }

  static class TimeBased implements TimeBasedStrategy {
    private int seconds = 60; // Default fallback

    @Override
    public TimeBasedStrategy lookingAtTheLast(int seconds) {
      this.seconds = seconds;
      return this;
    }

    @Override
    public TimeBasedSlidingWindowConfig applyProtectiveProfile() {
      return new TimeBasedSlidingWindowConfig(5);
    }

    @Override
    public TimeBasedSlidingWindowConfig applyBalancedProfile() {
      return new TimeBasedSlidingWindowConfig(60);
    }

    @Override
    public TimeBasedSlidingWindowConfig applyPermissiveProfile() {
      return new TimeBasedSlidingWindowConfig(300);
    }

    @Override
    public TimeBasedSlidingWindowConfig apply() {
      return new TimeBasedSlidingWindowConfig(seconds);
    }
  }
}