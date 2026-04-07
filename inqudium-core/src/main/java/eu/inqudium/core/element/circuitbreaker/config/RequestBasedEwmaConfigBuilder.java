package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.element.circuitbreaker.metrics.RequestBasedEwmaMetrics;

public class RequestBasedEwmaConfigBuilder  extends ExtensionBuilder<RequestBasedEwmaConfig> {
  private Double smoothingFactor;
  private Integer minimumNumberOfCalls;

  public RequestBasedEwmaConfigBuilder smoothingFactor(double alpha) {
    this.smoothingFactor = alpha;
    return this;
  }

  public RequestBasedEwmaConfigBuilder minimumNumberOfCalls(int calls) {
    this.minimumNumberOfCalls = calls;
    return this;
  }

  /**
   * High alpha value ensures new failures immediately dominate the average.
   */
  public RequestBasedEwmaConfigBuilder protective() {
    this.smoothingFactor = 0.5;
    this.minimumNumberOfCalls = 5;
    return this;
  }

  /**
   * Balanced smoothing that filters out noise while still tracking recent trends.
   */
  public RequestBasedEwmaConfigBuilder balanced() {
    this.smoothingFactor = 0.2;
    this.minimumNumberOfCalls = 10;
    return this;
  }

  /**
   * Low alpha value makes the metric very resilient to short-term changes.
   */
  public RequestBasedEwmaConfigBuilder permissive() {
    this.smoothingFactor = 0.05;
    this.minimumNumberOfCalls = 50;
    return this;
  }

  public RequestBasedEwmaConfig build() {
    if (minimumNumberOfCalls == null || smoothingFactor == null) {
      balanced();
    }
    return new RequestBasedEwmaConfig(smoothingFactor, minimumNumberOfCalls);
  }
}