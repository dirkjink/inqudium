package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;


public class SlidingWindowConfigBuilder extends ExtensionBuilder<SlidingWindowConfig> {
  private Integer maxFailuresInWindow;
  private Integer windowSize;
  private Integer minimumNumberOfCalls;

  public SlidingWindowConfigBuilder maxFailuresInWindow(int maxFailuresInWindow) {
    this.maxFailuresInWindow = maxFailuresInWindow;
    return this;
  }

  public SlidingWindowConfigBuilder windowSize(int windowSize) {
    this.windowSize = windowSize;
    return this;
  }

  public SlidingWindowConfigBuilder minimumNumberOfCalls(int minimumNumberOfCalls) {
    this.minimumNumberOfCalls = minimumNumberOfCalls;
    return this;
  }

  /**
   * Highly sensitive configuration for critical systems where even
   * a few failures in a small window should trip the circuit.
   */
  public SlidingWindowConfigBuilder protective() {
    this.windowSize = 10;
    this.minimumNumberOfCalls = 5;
    return this;
  }

  /**
   * Standard configuration for most microservices, balancing
   * responsiveness with tolerance for isolated spikes.
   */
  public SlidingWindowConfigBuilder balanced() {
    this.windowSize = 50;
    this.minimumNumberOfCalls = 20;
    return this;
  }

  /**
   * High-volume configuration that requires a significant history
   * of calls before making an opening decision.
   */
  public SlidingWindowConfigBuilder permissive() {
    this.windowSize = 200;
    this.minimumNumberOfCalls = 50;
    return this;
  }


  public SlidingWindowConfig build() {
    if (minimumNumberOfCalls == null || windowSize == null || maxFailuresInWindow == null) {
      balanced();
    }
    return new SlidingWindowConfig(maxFailuresInWindow, windowSize, minimumNumberOfCalls);
  }
}
