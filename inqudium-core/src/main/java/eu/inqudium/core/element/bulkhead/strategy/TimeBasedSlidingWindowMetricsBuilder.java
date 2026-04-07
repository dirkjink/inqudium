package eu.inqudium.core.element.bulkhead.strategy;

public class TimeBasedSlidingWindowMetricsBuilder {
  private int windowSizeInSeconds = 60;

  public TimeBasedSlidingWindowMetricsBuilder windowSizeInSeconds(int seconds) {
    this.windowSizeInSeconds = seconds;
    return this;
  }
}