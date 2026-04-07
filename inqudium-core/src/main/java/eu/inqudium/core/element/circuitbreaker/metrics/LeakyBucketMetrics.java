package eu.inqudium.core.element.circuitbreaker.metrics;

/**
 * Immutable implementation of a Leaky Bucket algorithm.
 *
 * <p>Failures add water (1.0) to the bucket. The bucket leaks continuously at a
 * constant rate over time. Successes do not add water, but they do trigger a leak
 * calculation based on elapsed time.
 *
 * <p>The failure threshold is used as the absolute capacity of the bucket.
 */
public record LeakyBucketMetrics(
    int bucketCapacity,
    double leakRatePerSecond,
    double currentLevel,
    long lastUpdateNanos
) implements FailureMetrics {

  public static LeakyBucketMetrics initial(int bucketCapacity, double leakRatePerSecond, long nowNanos) {
    if (leakRatePerSecond < 0) {
      throw new IllegalArgumentException("leakRatePerSecond cannot be negative");
    }
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, 0.0, nowNanos);
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return leak(nowNanos);
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    LeakyBucketMetrics leakedState = leak(nowNanos);
    double newLevel = leakedState.currentLevel() + 1.0;
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, newLevel, nowNanos);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    LeakyBucketMetrics evaluatedState = leak(nowNanos);
    return evaluatedState.currentLevel() >= bucketCapacity;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, 0.0, nowNanos);
  }

  private LeakyBucketMetrics leak(long nowNanos) {
    long deltaNanos = Math.max(0, nowNanos - lastUpdateNanos);
    double deltaSeconds = deltaNanos / 1_000_000_000.0;
    double leakAmount = deltaSeconds * leakRatePerSecond;
    double newLevel = Math.max(0.0, currentLevel - leakAmount);
    long newLastUpdateNanos = Math.max(nowNanos, lastUpdateNanos);
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, newLevel, newLastUpdateNanos);
  }

  @Override
  public String getTripReason(long nowNanos) {
    LeakyBucketMetrics evaluatedState = leak(nowNanos);
    return "Leaky bucket overflow: Current failure level is %.2f (Capacity: %d, Leak Rate: %.1f/sec)."
        .formatted(evaluatedState.currentLevel(), bucketCapacity, leakRatePerSecond);
  }
}
