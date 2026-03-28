package eu.inqudium.core.retry.backoff;

import java.time.Duration;

/**
 * Fixed backoff: every retry waits the same duration.
 *
 * <p>{@code delay = initialInterval} (constant, regardless of attempt number).
 *
 * <p>Use case: retrying against a rate-limited API where the rate limit resets
 * at fixed intervals.
 *
 * @since 0.1.0
 */
public final class FixedBackoff implements BackoffStrategy {

  @Override
  public Duration computeDelay(int attemptNumber, Duration initialInterval) {
    return initialInterval;
  }

  @Override
  public String toString() {
    return "FixedBackoff";
  }
}
