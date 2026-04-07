package eu.inqudium.core.element.circuitbreaker.config;

public class GradualDecayConfigBuilder {
  private Integer maxFailureCount;
  private Integer initialFailureCount;

  public GradualDecayConfigBuilder maxFailureCount(int maxFailureCount) {
    this.maxFailureCount = maxFailureCount;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 5-10.
   * Since every success only heals one failure, a low threshold
   * ensures high sensitivity to recurring issues.
   */
  public GradualDecayConfigBuilder protective() {
    this.maxFailureCount = 7;
    this.initialFailureCount = 0;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 25-50.
   * Balanced approach for services with occasional "noise" that
   * eventually recover, requiring a steady stream of successes to heal.
   */
  public GradualDecayConfigBuilder balanced() {
    this.maxFailureCount = 37;
    this.initialFailureCount = 0;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 100+.
   * High capacity for errors; intended for systems where intermittent
   * failures are common and slow recovery is acceptable.
   */
  public GradualDecayConfigBuilder permissive() {
    this.maxFailureCount = 100;
    this.initialFailureCount = 0;
    return this;
  }

  public GradualDecayConfig build() {
    if (maxFailureCount == null || initialFailureCount == null) {
      balanced();
    }
    return new GradualDecayConfig(maxFailureCount, initialFailureCount);
  }
}
