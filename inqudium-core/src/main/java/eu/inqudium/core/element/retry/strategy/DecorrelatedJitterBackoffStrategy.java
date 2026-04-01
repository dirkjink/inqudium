package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decorrelated Jitter — the AWS-recommended backoff strategy for distributed systems.
 *
 * <p>Unlike full jitter which randomises within a deterministic exponential
 * envelope, decorrelated jitter computes:
 * <pre>
 *   delay = random_between(initialDelay, previousDelay * 3)
 *   delay = min(delay, maxDelay)
 * </pre>
 *
 * <p>The key insight is that each client's delay sequence diverges rapidly
 * because it depends on the <em>previous delay</em> (which was itself random),
 * not on a shared exponential curve. This produces better spread between
 * competing clients than full jitter.
 *
 * <p>This is a <strong>stateful</strong> strategy — it overrides
 * {@link #computeDelay(int, Duration)} to receive the previous delay.
 * On the first retry ({@code previousDelay == ZERO}), it uses
 * {@code initialDelay} as the baseline.
 *
 * @param initialDelay the minimum delay and baseline for the first retry
 * @param maxDelay     ceiling for the computed delay
 * @see <a href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">
 * AWS Architecture Blog: Exponential Backoff and Jitter</a>
 */
public record DecorrelatedJitterBackoffStrategy(Duration initialDelay, Duration maxDelay) implements BackoffStrategy {

  public DecorrelatedJitterBackoffStrategy {
    Objects.requireNonNull(initialDelay, "initialDelay must not be null");
    Objects.requireNonNull(maxDelay, "maxDelay must not be null");
    if (initialDelay.isNegative() || initialDelay.isZero()) {
      throw new IllegalArgumentException("initialDelay must be positive");
    }
    if (maxDelay.isNegative() || maxDelay.isZero()) {
      throw new IllegalArgumentException("maxDelay must be positive");
    }
  }

  /**
   * Stateless fallback — uses initialDelay as the previous delay baseline.
   * The retry core calls the two-argument form; this exists for direct usage.
   */
  @Override
  public Duration computeDelay(int attemptIndex) {
    return computeDelay(attemptIndex, Duration.ZERO);
  }

  /**
   * Computes the decorrelated jitter delay based on the previous delay.
   *
   * @param attemptIndex  ignored (delay depends on previousDelay, not index)
   * @param previousDelay the delay from the previous retry cycle
   * @return a random duration in {@code [initialDelay, min(maxDelay, previousDelay * 3)]}
   */
  @Override
  public Duration computeDelay(int attemptIndex, Duration previousDelay) {
    long initialNanos = initialDelay.toNanos();
    long maxNanos = maxDelay.toNanos();

    // On the first retry or if previous delay was zero, use initialDelay as baseline
    long baseNanos = (previousDelay == null || previousDelay.isZero())
        ? initialNanos
        : previousDelay.toNanos();

    // Upper bound: previousDelay * 3, capped at maxDelay
    long upperBound = Math.min(maxNanos, baseNanos * 3);

    // Guard against overflow: baseNanos * 3 can overflow for very large delays
    if (upperBound < 0 || baseNanos < 0) {
      upperBound = maxNanos;
    }

    // Ensure lower bound does not exceed upper bound
    long lowerBound = Math.min(initialNanos, upperBound);

    if (lowerBound >= upperBound) {
      return Duration.ofNanos(lowerBound);
    }

    // Random in [lowerBound, upperBound]
    long jitteredNanos = ThreadLocalRandom.current().nextLong(lowerBound, upperBound + 1);
    return Duration.ofNanos(jitteredNanos);
  }
}
