package eu.inqudium.core.element.circuitbreaker.metrics;

/**
 * Immutable implementation of the gradual decay algorithm.
 * One success heals exactly one failure.
 */
public record GradualDecayMetrics(long failureThreshold, int failureCount) implements FailureMetrics {

  public static GradualDecayMetrics initial(double failureThreshold) {
    return new GradualDecayMetrics(Math.round(failureThreshold), 0);
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return new GradualDecayMetrics(failureThreshold, Math.max(0, failureCount - 1));
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return new GradualDecayMetrics(failureThreshold, failureCount + 1);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    return failureCount >= failureThreshold;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new GradualDecayMetrics(failureThreshold, 0);
  }

  @Override
  public String getTripReason(long nowNanos) {
    return "Failure threshold reached: Current failure count is %d (Threshold: %d)."
        .formatted(failureCount, failureThreshold);
  }
}
