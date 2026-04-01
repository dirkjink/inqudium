package eu.inqudium.core.element.ratelimiter;

import java.time.Duration;

public record ReservationResult<S extends RateLimiterState>(
    S state,
    Duration waitDuration,
    boolean timedOut
) {
  public static <S extends RateLimiterState> ReservationResult<S> immediate(S state) {
    return new ReservationResult<>(state, Duration.ZERO, false);
  }

  public static <S extends RateLimiterState> ReservationResult<S> delayed(S state, Duration waitDuration) {
    return new ReservationResult<>(state, waitDuration, false);
  }

  public static <S extends RateLimiterState> ReservationResult<S> timedOut(S state, Duration wouldHaveWaited) {
    return new ReservationResult<>(state, wouldHaveWaited, true);
  }
}