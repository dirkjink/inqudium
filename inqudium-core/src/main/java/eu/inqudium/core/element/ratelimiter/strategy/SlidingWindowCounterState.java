package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimiterState;

import java.time.Instant;

public record SlidingWindowCounterState(
    Instant currentWindowStart,
    int previousCount,
    int currentCount,
    long epoch
) implements RateLimiterState {

  @Override
  public SlidingWindowCounterState withNextEpoch(Instant now) {
    return new SlidingWindowCounterState(currentWindowStart, previousCount, currentCount, epoch + 1);
  }

  public SlidingWindowCounterState withNextEpoch(Instant newWindowStart, int newPrevious, int newCurrent) {
    return new SlidingWindowCounterState(newWindowStart, newPrevious, newCurrent, epoch + 1);
  }

  public SlidingWindowCounterState withCurrentCount(int newCurrentCount) {
    return new SlidingWindowCounterState(currentWindowStart, previousCount, newCurrentCount, epoch);
  }
}