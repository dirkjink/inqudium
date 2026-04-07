package eu.inqudium.core.element.circuitbreaker.config;

public class ConsecutiveFailuresConfigBuilder {
  private Integer maxConsecutiveFailures;
  private Integer initialConsecutiveFailures;

  public ConsecutiveFailuresConfigBuilder initialConsecutiveFailures(int count) {
    this.initialConsecutiveFailures = count;
    return this;
  }

  public ConsecutiveFailuresConfigBuilder maxConsecutiveFailures(int maxConsecutiveFailures) {
    this.maxConsecutiveFailures = maxConsecutiveFailures;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 3-5.
   * Best for critical low-latency dependencies where a short burst of errors
   * indicates a complete service outage.
   */
  public ConsecutiveFailuresConfigBuilder protective() {
    this.maxConsecutiveFailures = 4;
    this.initialConsecutiveFailures = 0;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 10-20.
   * Standard setting for distributed systems to tolerate small network
   * hiccups while catching sustained connectivity issues.
   */
  public ConsecutiveFailuresConfigBuilder balanced() {
    this.maxConsecutiveFailures = 15;
    this.initialConsecutiveFailures = 0;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 50+.
   * Use this for highly unstable legacy systems or non-critical
   * background tasks that should almost never trigger the breaker.
   */
  public ConsecutiveFailuresConfigBuilder permissive() {
    this.maxConsecutiveFailures = 50;
    this.initialConsecutiveFailures = 0;
    return this;
  }

  public ConsecutiveFailuresConfig build() {
    if (maxConsecutiveFailures == null || initialConsecutiveFailures == null) {
      balanced();
    }
    return new ConsecutiveFailuresConfig(maxConsecutiveFailures, initialConsecutiveFailures);
  }
}