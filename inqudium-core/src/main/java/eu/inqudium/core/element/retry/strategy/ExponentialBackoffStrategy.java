package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.Objects;

/**
 * Exponentially increasing wait duration: {@code initialDelay * multiplier^attemptIndex},
 * capped at {@code maxDelay}.
 *
 * @param initialDelay the delay before the first retry
 * @param multiplier   the factor by which delay grows each attempt
 * @param maxDelay     ceiling for the computed delay
 */
public record ExponentialBackoffStrategy(Duration initialDelay, double multiplier,
                                         Duration maxDelay) implements BackoffStrategy {

  public ExponentialBackoffStrategy {
    Objects.requireNonNull(initialDelay, "initialDelay must not be null");
    Objects.requireNonNull(maxDelay, "maxDelay must not be null");
    if (initialDelay.isNegative() || initialDelay.isZero()) {
      throw new IllegalArgumentException("initialDelay must be positive");
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be >= 1.0, got " + multiplier);
    }
    if (maxDelay.isNegative() || maxDelay.isZero()) {
      throw new IllegalArgumentException("maxDelay must be positive");
    }
  }

  @Override
  public Duration computeDelay(int attemptIndex) {
    double delayNanos = initialDelay.toNanos() * Math.pow(multiplier, attemptIndex);
    long maxNanos = maxDelay.toNanos();

    if (Double.isInfinite(delayNanos) || Double.isNaN(delayNanos) || delayNanos >= maxNanos) {
      return maxDelay;
    }

    return Duration.ofNanos((long) delayNanos);
  }
}
