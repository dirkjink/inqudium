package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;

/**
 * Immutable implementation of a Leaky Bucket algorithm.
 *
 * <p>Failures add water (1.0) to the bucket. The bucket leaks continuously at a
 * constant rate over time. Successes do not add water, but they do trigger a leak
 * calculation based on elapsed time.
 *
 * <p>The {@link CircuitBreakerConfig#failureThreshold()} is used as the absolute capacity
 * of the bucket (e.g., a threshold of 5 means the circuit trips if the bucket holds >= 5.0).
 */
public record LeakyBucketMetrics(
    long failureThreshold,
    double leakRatePerSecond,
    double currentLevel,
    long lastUpdateNanos
) implements FailureMetrics {

  public static LeakyBucketMetrics initial(double failureThreshold, double leakRatePerSecond, Instant now) {
    if (leakRatePerSecond < 0) {
      throw new IllegalArgumentException("leakRatePerSecond cannot be negative");
    }
    return new LeakyBucketMetrics(
        Math.round(failureThreshold),
        leakRatePerSecond,
        0.0,
        toNanos(now)
    );
  }

  private static long toNanos(Instant instant) {
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    // Successes don't add to the failure level, but we must process the time-based leak
    return leak(toNanos(now));
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    long nowNanos = toNanos(now);

    // First leak the water based on elapsed time
    LeakyBucketMetrics leakedState = leak(nowNanos);

    // Then add a new failure (1.0) to the bucket
    double newLevel = leakedState.currentLevel() + 1.0;

    return new LeakyBucketMetrics(
        failureThreshold,
        leakRatePerSecond,
        newLevel,
        nowNanos
    );
  }

  @Override
  public boolean isThresholdReached(Instant now) {
    // We must apply the continuous leak up to the exact 'now' timestamp before evaluating
    LeakyBucketMetrics evaluatedState = leak(toNanos(now));

    return evaluatedState.currentLevel() >= failureThreshold;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    return new LeakyBucketMetrics(
        failureThreshold,
        leakRatePerSecond,
        0.0,
        toNanos(now)
    );
  }

  /**
   * Helper method to calculate the new level after leaking water over the elapsed time.
   */
  private LeakyBucketMetrics leak(long nowNanos) {
    long deltaNanos = Math.max(0, nowNanos - lastUpdateNanos);

    // Convert nanos back to seconds for the rate calculation
    double deltaSeconds = deltaNanos / 1_000_000_000.0;
    double leakAmount = deltaSeconds * leakRatePerSecond;

    double newLevel = Math.max(0.0, currentLevel - leakAmount);
    long newLastUpdateNanos = Math.max(nowNanos, lastUpdateNanos);

    return new LeakyBucketMetrics(
        failureThreshold,
        leakRatePerSecond,
        newLevel,
        newLastUpdateNanos
    );
  }

  @Override
  public String getTripReason(Instant now) {
    // We apply the leak to get the absolutely current level for the log
    LeakyBucketMetrics evaluatedState = leak(toNanos(now));
    return "Leaky bucket overflow: Current failure level is %.2f (Capacity: %d, Leak Rate: %.1f/sec)."
        .formatted(evaluatedState.currentLevel(), failureThreshold, leakRatePerSecond);
  }
}
