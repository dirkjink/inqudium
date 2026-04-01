package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.Objects;

/**
 * Linearly increasing wait duration: {@code initialDelay + attemptIndex * increment},
 * capped at {@code maxDelay}.
 *
 * <p>Growth is additive rather than multiplicative — suitable for scenarios
 * where exponential backoff is too aggressive, e.g., database connections
 * that recover quickly after a brief outage.
 *
 * <p>Example with initialDelay=1s, increment=500ms:
 * 1.0s → 1.5s → 2.0s → 2.5s → 3.0s → ...
 *
 * @param initialDelay the delay before the first retry
 * @param increment    added to the delay for each subsequent retry
 * @param maxDelay     ceiling for the computed delay
 */
public record LinearBackoffStrategy(Duration initialDelay, Duration increment,
                                    Duration maxDelay) implements BackoffStrategy {

  public LinearBackoffStrategy {
    Objects.requireNonNull(initialDelay, "initialDelay must not be null");
    Objects.requireNonNull(increment, "increment must not be null");
    Objects.requireNonNull(maxDelay, "maxDelay must not be null");
    if (initialDelay.isNegative()) {
      throw new IllegalArgumentException("initialDelay must not be negative");
    }
    if (increment.isNegative()) {
      throw new IllegalArgumentException("increment must not be negative");
    }
    if (maxDelay.isNegative() || maxDelay.isZero()) {
      throw new IllegalArgumentException("maxDelay must be positive");
    }
  }

  @Override
  public Duration computeDelay(int attemptIndex) {
    // Use nanoseconds for sub-millisecond precision
    long delayNanos = initialDelay.toNanos() + (long) attemptIndex * increment.toNanos();

    // Guard against overflow: if the addition wrapped around or exceeds max
    if (delayNanos < 0 || delayNanos > maxDelay.toNanos()) {
      return maxDelay;
    }

    return Duration.ofNanos(delayNanos);
  }
}
