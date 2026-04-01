package eu.inqudium.core.ratelimiter;

import java.time.Duration;

public record RateLimitPermission<S extends RateLimiterState>(
    S state,
    boolean permitted,
    Duration waitDuration
) {
  public static <S extends RateLimiterState> RateLimitPermission<S> permitted(S state) {
    return new RateLimitPermission<>(state, true, Duration.ZERO);
  }

  public static <S extends RateLimiterState> RateLimitPermission<S> rejected(S state, Duration waitDuration) {
    return new RateLimitPermission<>(state, false, waitDuration);
  }
}