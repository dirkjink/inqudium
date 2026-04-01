package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.Objects;

/**
 * Constant wait duration between every retry attempt.
 *
 * @param delay the fixed delay between attempts
 */
public record FixedBackoffStrategy(Duration delay) implements BackoffStrategy {

  public FixedBackoffStrategy {
    Objects.requireNonNull(delay, "delay must not be null");
    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative");
    }
  }

  @Override
  public Duration computeDelay(int attemptIndex) {
    return delay;
  }
}
