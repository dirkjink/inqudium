package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Locale;
import java.util.function.LongFunction;

/**
 * Immutable implementation of a consecutive-failures circuit breaker strategy.
 *
 * <h2>Algorithm Overview</h2>
 * <p>This is the simplest failure detection algorithm: it counts uninterrupted,
 * back-to-back failures and trips the circuit once that count reaches a configured
 * maximum. A <em>single</em> successful call is enough to reset the counter to zero,
 * making this strategy very forgiving for intermittent errors but highly responsive
 * to sustained outages.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>The downstream service either works or is completely down (binary health).</li>
 *   <li>Sporadic errors are acceptable and should not accumulate over time.</li>
 *   <li>Fast detection of total outages is more important than nuanced error-rate tracking.</li>
 * </ul>
 *
 * <h2>Time Independence</h2>
 * <p>This algorithm is entirely time-agnostic. The {@code nowNanos} parameter required
 * by the {@link FailureMetrics} interface is accepted but ignored in every method.
 *
 * <h2>Record Fields</h2>
 * <ul>
 *   <li>{@code maxConsecutiveFailures} — the configured threshold; once the consecutive
 *       failure count reaches this value, {@link #isThresholdReached(long)} returns {@code true}.</li>
 *   <li>{@code consecutiveFailures} — the current number of uninterrupted failures since
 *       the last success or reset.</li>
 * </ul>
 *
 * @param maxConsecutiveFailures the maximum number of consecutive failures before the circuit opens
 * @param consecutiveFailures    the running count of consecutive failures (0 after a success or reset)
 */
public record ConsecutiveFailuresMetrics(
    int maxConsecutiveFailures,
    int consecutiveFailures
) implements FailureMetrics {

  /**
   * Creates a fresh instance with zero consecutive failures.
   *
   * @param maxConsecutiveFailures the threshold at which the circuit should trip
   * @return a new {@code ConsecutiveFailuresMetrics} with {@code consecutiveFailures == 0}
   */
  public static ConsecutiveFailuresMetrics initial(int maxConsecutiveFailures,
                                                   int initialConsecutiveFailures) {
    return new ConsecutiveFailuresMetrics(maxConsecutiveFailures, initialConsecutiveFailures);
  }

  /**
   * Returns a factory function that produces a fresh instance of this metrics strategy.
   *
   * @return a {@link LongFunction} that accepts a nanosecond timestamp and produces a
   *         fresh {@link FailureMetrics} instance with identical configuration
   */
  @Override
  public LongFunction<FailureMetrics> metricsFactory() {
    return (long nowNanos)-> ConsecutiveFailuresMetrics.initial(maxConsecutiveFailures, consecutiveFailures);
  }

  /**
   * Records a successful call. Because a single success breaks the failure streak,
   * the consecutive failure counter is unconditionally reset to zero.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return a new instance with {@code consecutiveFailures == 0}
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return new ConsecutiveFailuresMetrics(maxConsecutiveFailures, 0);
  }

  /**
   * Records a failed call by incrementing the consecutive failure counter by one.
   *
   * <p>Note: the counter is not capped at {@code maxConsecutiveFailures}. It may exceed
   * the threshold, which is harmless — {@link #isThresholdReached(long)} uses {@code >=}.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return a new instance with {@code consecutiveFailures} incremented by one
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return new ConsecutiveFailuresMetrics(maxConsecutiveFailures, consecutiveFailures + 1);
  }

  /**
   * Returns {@code true} when the number of consecutive failures has reached or
   * exceeded the configured maximum.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return {@code true} if the circuit should open
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    return consecutiveFailures >= maxConsecutiveFailures;
  }

  /**
   * Resets the metrics to their initial state (zero consecutive failures).
   * Equivalent to calling {@link #initial(int, int)} with the same threshold.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return a fresh instance with {@code consecutiveFailures == 0}
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return initial(maxConsecutiveFailures, 0);
  }

  /**
   * Produces a diagnostic message showing the current streak length versus the threshold.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return a formatted string such as
   * "Consecutive failure threshold reached: Received 5 failures in a row (Threshold: 5)."
   */
  @Override
  public String getTripReason(long nowNanos) {
    return String.format(
        Locale.ROOT,"Consecutive failure threshold reached: " +
            "Received %d failures in a row (Threshold: %d).",consecutiveFailures, maxConsecutiveFailures);
  }
}
