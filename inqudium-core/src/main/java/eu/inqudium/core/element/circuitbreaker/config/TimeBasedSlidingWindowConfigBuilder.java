package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

public class TimeBasedSlidingWindowConfigBuilder extends ExtensionBuilder<TimeBasedSlidingWindowConfig> {
  private Integer windowSizeInSeconds;

  public TimeBasedSlidingWindowConfigBuilder windowSizeInSeconds(int seconds) {
    this.windowSizeInSeconds = seconds;
    return this;
  }

  /**
   * Very strict window that only considers the last 5 seconds.
   * Useful for detecting immediate, hard outages.
   */
  public TimeBasedSlidingWindowConfigBuilder protective() {
    this.windowSizeInSeconds = 5;
    return this;
  }

  /**
   * Standard 1-minute window, providing a good balance between
   * responsiveness and history.
   */
  public TimeBasedSlidingWindowConfigBuilder balanced() {
    this.windowSizeInSeconds = 60;
    return this;
  }

  /**
   * Long 5-minute window for systems where high recovery times
   * or intermittent blips are expected and should not trip easily.
   */
  public TimeBasedSlidingWindowConfigBuilder permissive() {
    this.windowSizeInSeconds = 300;
    return this;
  }

  public TimeBasedSlidingWindowConfig build() {
    if (windowSizeInSeconds == null) {
      balanced();
    }
    return new TimeBasedSlidingWindowConfig(windowSizeInSeconds);
  }
}
