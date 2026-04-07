package eu.inqudium.core.element.circuitbreaker.metrics;

/**
 * Immutable implementation of the gradual decay algorithm.
 * One success heals exactly one failure.
 */
public record GradualDecayMetrics(
    int maxFailureCount,
    int failureCount
) implements FailureMetrics {

  public static GradualDecayMetrics initial(int maxFailureCount) {
    return new GradualDecayMetrics(maxFailureCount, 0);
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return new GradualDecayMetrics(maxFailureCount, Math.max(0, failureCount - 1));
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return new GradualDecayMetrics(maxFailureCount, failureCount + 1);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    return failureCount >= maxFailureCount;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new GradualDecayMetrics(maxFailureCount, 0);
  }

  @Override
  public String getTripReason(long nowNanos) {
    return "Failure threshold reached: Current failure count is %d (Threshold: %d)."
        .formatted(failureCount, maxFailureCount);
  }
}
