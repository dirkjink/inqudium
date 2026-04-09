package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Locale;
import java.util.function.LongFunction;

/**
 * Immutable implementation of a Leaky Bucket failure tracking strategy.
 *
 * <h2>Algorithm Overview</h2>
 * <p>The leaky bucket is a classic rate-limiting metaphor: failures pour "water" into
 * a bucket that continuously leaks at a constant rate. The circuit trips when the water
 * level reaches or exceeds the bucket's capacity. Because the bucket leaks over time,
 * infrequent failures drain away naturally, while a burst of failures fills the bucket
 * and triggers the breaker.
 *
 * <h2>Mechanics</h2>
 * <ul>
 *   <li><strong>Failure:</strong> adds exactly 1.0 to the current level (after first
 *       applying any accumulated leak since the last update).</li>
 *   <li><strong>Success:</strong> does not add water, but still triggers a leak
 *       calculation so the level stays current.</li>
 *   <li><strong>Leak:</strong> computed as {@code elapsedSeconds × leakRatePerSecond},
 *       subtracted from the current level, floored at 0.0.</li>
 *   <li><strong>Threshold check:</strong> leaks the bucket to the query time, then
 *       compares the resulting level against the capacity.</li>
 * </ul>
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>You want failures to "heal" passively over time even without successes.</li>
 *   <li>Burst tolerance is important — a few rapid failures should not trip the circuit
 *       unless they exceed the bucket's capacity.</li>
 *   <li>A simple, intuitive model is preferred over statistical approaches like EWMA.</li>
 * </ul>
 *
 * <h2>Time Dependence</h2>
 * <p>This algorithm is time-aware. The {@code nowNanos} parameter drives the leak
 * calculation. All timestamps are compared against {@code lastUpdateNanos} to compute
 * elapsed time. If {@code nowNanos} is less than or equal to {@code lastUpdateNanos}
 * (e.g., due to clock skew), no leak is applied (delta is floored at zero).
 *
 * <h2>Note on Level Cap</h2>
 * <p>The water level is <em>not</em> capped at the bucket capacity. It can grow beyond
 * {@code bucketCapacity}, which means it takes proportionally longer to leak back below
 * the threshold after a large burst.
 *
 * @param bucketCapacity    the maximum level before the circuit trips (acts as the threshold)
 * @param leakRatePerSecond how many units of water drain per second (must be >= 0)
 * @param currentLevel      the current water level in the bucket
 * @param lastUpdateNanos   the timestamp (in nanoseconds) of the most recent update
 */
public record LeakyBucketMetrics(
    int bucketCapacity,
    double leakRatePerSecond,
    double currentLevel,
    long lastUpdateNanos
) implements FailureMetrics {

  /**
   * Creates an empty bucket with the given configuration.
   *
   * @param bucketCapacity    the capacity (threshold) of the bucket
   * @param leakRatePerSecond how fast the bucket drains (units per second); must be non-negative
   * @param nowNanos          the initial timestamp anchor
   * @return a new instance with {@code currentLevel == 0.0}
   * @throws IllegalArgumentException if {@code leakRatePerSecond} is negative
   */
  public static LeakyBucketMetrics initial(int bucketCapacity,
                                           double leakRatePerSecond,
                                           long nowNanos) {
    if (leakRatePerSecond < 0) {
      throw new IllegalArgumentException("leakRatePerSecond cannot be negative");
    }
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, 0.0, nowNanos);
  }

  /**
   * Returns a factory function that produces a fresh instance of this metrics strategy.
   *
   * @return a {@link LongFunction} that accepts a nanosecond timestamp and produces a
   * fresh {@link FailureMetrics} instance with identical configuration
   */
  @Override
  public LongFunction<FailureMetrics> metricsFactory() {
    return (long nowNanos) -> LeakyBucketMetrics.initial(
        bucketCapacity,
        leakRatePerSecond,
        nowNanos
    );
  }

  /**
   * Records a successful call. No water is added, but the bucket is leaked up to
   * the current time so that the level reflects passive draining.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return a new instance with the level reduced by the amount leaked since the last update
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return leak(nowNanos);
  }

  /**
   * Records a failed call. First leaks the bucket to the current time, then adds
   * exactly 1.0 to the resulting level.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return a new instance with the level increased by 1.0 (after leaking)
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    LeakyBucketMetrics leakedState = leak(nowNanos);
    double newLevel = leakedState.currentLevel() + 1.0;
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, newLevel, nowNanos);
  }

  /**
   * Evaluates whether the bucket has overflowed at the given time.
   * The bucket is leaked to {@code nowNanos} before the comparison.
   *
   * @param nowNanos the current timestamp in nanoseconds
   * @return {@code true} if the leaked level >= bucket capacity
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    LeakyBucketMetrics evaluatedState = leak(nowNanos);
    return evaluatedState.currentLevel() >= bucketCapacity;
  }

  /**
   * Resets the bucket to empty (level 0.0) and re-anchors the timestamp.
   *
   * @param nowNanos the new timestamp anchor
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, 0.0, nowNanos);
  }

  /**
   * Internal helper that computes the leaked state at the given timestamp.
   *
   * <p>Calculates the elapsed time since the last update, converts it to seconds,
   * multiplies by the leak rate, and subtracts from the current level (floored at 0).
   * If the given timestamp is not ahead of the last update, no leak is applied —
   * this guards against negative deltas from clock anomalies.
   *
   * @param nowNanos the point in time to leak to
   * @return a new instance reflecting the post-leak state
   */
  private LeakyBucketMetrics leak(long nowNanos) {
    long deltaNanos = Math.max(0, nowNanos - lastUpdateNanos);
    double deltaSeconds = deltaNanos / 1_000_000_000.0;
    double leakAmount = deltaSeconds * leakRatePerSecond;
    double newLevel = Math.max(0.0, currentLevel - leakAmount);
    long newLastUpdateNanos = Math.max(nowNanos, lastUpdateNanos);
    return new LeakyBucketMetrics(bucketCapacity, leakRatePerSecond, newLevel, newLastUpdateNanos);
  }

  /**
   * Produces a diagnostic message showing the current bucket level, capacity, and leak rate.
   * The level is computed after leaking to {@code nowNanos}.
   *
   * @param nowNanos the current timestamp in nanoseconds
   */
  @Override
  public String getTripReason(long nowNanos) {
    LeakyBucketMetrics evaluatedState = leak(nowNanos);
    return String.format(
        Locale.ROOT, "Leaky bucket overflow: " +
            "Current failure level is %.2f (Capacity: %d, Leak Rate: %.1f/sec).",
        evaluatedState.currentLevel(), bucketCapacity, leakRatePerSecond);
  }
}
