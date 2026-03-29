package eu.inqudium.core.ratelimiter;

import java.time.Duration;

/**
 * Result of a rate limiter permit acquisition attempt.
 *
 * @param permitted    whether the call is allowed
 * @param waitDuration estimated wait until the next permit (zero if permitted)
 * @param updatedState the token bucket state after this attempt
 * @since 0.1.0
 */
public record PermitResult(boolean permitted, Duration waitDuration, TokenBucketState updatedState) {

  public static PermitResult permitted(TokenBucketState state) {
    return new PermitResult(true, Duration.ZERO, state);
  }

  public static PermitResult denied(Duration estimatedWait, TokenBucketState state) {
    return new PermitResult(false, estimatedWait, state);
  }
}
