package eu.inqudium.core.element.circuitbreaker.config;

public class GradualDecayBuilderConfig {
  private Double failureRateThreshold;
  private Integer initialFailureCount;

  public GradualDecayBuilderConfig failureRateThreshold(double failureRateThreshold) {
    this.failureRateThreshold = failureRateThreshold;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 5-10.
   * Since every success only heals one failure, a low threshold
   * ensures high sensitivity to recurring issues.
   */
  public GradualDecayBuilderConfig protective() {
    this.failureRateThreshold = 7.0;
    this.initialFailureCount = 0;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 25-50.
   * Balanced approach for services with occasional "noise" that
   * eventually recover, requiring a steady stream of successes to heal.
   */
  public GradualDecayBuilderConfig balanced() {
    this.failureRateThreshold = 37.0;
    this.initialFailureCount = 0;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 100+.
   * High capacity for errors; intended for systems where intermittent
   * failures are common and slow recovery is acceptable.
   */
  public GradualDecayBuilderConfig permissive() {
    this.failureRateThreshold = 100.0;
    this.initialFailureCount = 0;
    return this;
  }

  public GradualDecayConfig build() {
    if (failureRateThreshold == null || initialFailureCount == null) {
      balanced();
    }
    return new GradualDecayConfig(failureRateThreshold, initialFailureCount);
  }
}
