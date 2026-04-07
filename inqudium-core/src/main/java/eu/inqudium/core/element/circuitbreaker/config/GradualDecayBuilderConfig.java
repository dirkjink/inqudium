package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.GradualDecayMetrics;

public class GradualDecayBuilderConfig {
  private Double failureRateThreshold;
  private int initialFailureCount = 0;

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 5-10.
   * Since every success only heals one failure, a low threshold
   * ensures high sensitivity to recurring issues.
   */
  public GradualDecayConfig buildProtective() {
    return new GradualDecayConfig(initialFailureCount);
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 25-50.
   * Balanced approach for services with occasional "noise" that
   * eventually recover, requiring a steady stream of successes to heal.
   */
  public GradualDecayConfig buildBalanced() {
    return new GradualDecayConfig(initialFailureCount);
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 100+.
   * High capacity for errors; intended for systems where intermittent
   * failures are common and slow recovery is acceptable.
   */
  public GradualDecayConfig buildPermissive() {
    return new GradualDecayConfig(initialFailureCount);
  }

  public GradualDecayConfig build() {
    return new GradualDecayConfig(initialFailureCount);
  }
}
