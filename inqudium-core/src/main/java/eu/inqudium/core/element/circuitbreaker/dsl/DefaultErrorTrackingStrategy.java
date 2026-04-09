package eu.inqudium.core.element.circuitbreaker.dsl;

import eu.inqudium.core.element.circuitbreaker.config.SlidingWindowConfigBuilder;
import eu.inqudium.core.element.circuitbreaker.config.TimeBasedSlidingWindowConfigBuilder;

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
      var cfg = new SlidingWindowConfigBuilder().protective().build();
      return new SlidingWindowConfig(cfg.maxFailuresInWindow(),
          cfg.windowSize(),
          cfg.minimumNumberOfCalls());
    }

    @Override
    public SlidingWindowConfig applyBalancedProfile() {
      var cfg = new SlidingWindowConfigBuilder().balanced().build();
      return new SlidingWindowConfig(cfg.maxFailuresInWindow(),
          cfg.windowSize(),
          cfg.minimumNumberOfCalls());
    }

    @Override
    public SlidingWindowConfig applyPermissiveProfile() {
      var cfg = new SlidingWindowConfigBuilder().permissive().build();
      return new SlidingWindowConfig(cfg.maxFailuresInWindow(),
          cfg.windowSize(),
          cfg.minimumNumberOfCalls());
    }

    @Override
    public SlidingWindowConfig apply() {
      return new SlidingWindowConfig(-1, windowSize, minimumCalls);
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
      var cfg = new TimeBasedSlidingWindowConfigBuilder().protective().build();
      return new TimeBasedSlidingWindowConfig(cfg.maxFailuresInWindow(), cfg.windowSizeInSeconds());
    }

    @Override
    public TimeBasedSlidingWindowConfig applyBalancedProfile() {
      var cfg = new TimeBasedSlidingWindowConfigBuilder().balanced().build();
      return new TimeBasedSlidingWindowConfig(cfg.maxFailuresInWindow(), cfg.windowSizeInSeconds());
    }

    @Override
    public TimeBasedSlidingWindowConfig applyPermissiveProfile() {
      var cfg = new TimeBasedSlidingWindowConfigBuilder().protective().build();
      return new TimeBasedSlidingWindowConfig(cfg.maxFailuresInWindow(), cfg.windowSizeInSeconds());
    }

    @Override
    public TimeBasedSlidingWindowConfig apply() {
      return new TimeBasedSlidingWindowConfig(-1, seconds);
    }
  }
}