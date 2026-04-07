package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.RequestBasedEwmaMetrics;

public class RequestBasedEwmaMetricsBuilder {
  private double smoothingFactor = 0.1;
  private int minimumNumberOfCalls = 10;

  public RequestBasedEwmaMetricsBuilder smoothingFactor(double alpha) {
    this.smoothingFactor = alpha;
    return this;
  }

  public RequestBasedEwmaMetricsBuilder minimumNumberOfCalls(int calls) {
    this.minimumNumberOfCalls = calls;
    return this;
  }

  /**
   * High alpha value ensures new failures immediately dominate the average.
   */
  public RequestBasedEwmaMetrics protective() {
    return RequestBasedEwmaMetrics.initial(0.5, 5);
  }

  /**
   * Balanced smoothing that filters out noise while still tracking recent trends.
   */
  public RequestBasedEwmaMetrics balanced() {
    return RequestBasedEwmaMetrics.initial(0.2, 10);
  }

  /**
   * Low alpha value makes the metric very resilient to short-term changes.
   */
  public RequestBasedEwmaMetrics permissive() {
    return RequestBasedEwmaMetrics.initial(0.05, 50);
  }

  public RequestBasedEwmaMetrics build() {
    return RequestBasedEwmaMetrics.initial(smoothingFactor, minimumNumberOfCalls);
  }
}