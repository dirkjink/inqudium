package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.ReservationResult;

import java.time.Duration;
import java.time.Instant;

public class SlidingWindowCounterStrategy implements RateLimiterStrategy<SlidingWindowCounterState> {

  @Override
  public SlidingWindowCounterState initial(RateLimiterConfig<SlidingWindowCounterState> config, Instant now) {
    return new SlidingWindowCounterState(alignToWindow(now, config.refillPeriod()), 0, 0, 0L);
  }

  private SlidingWindowCounterState refresh(SlidingWindowCounterState state, RateLimiterConfig<?> config, Instant now) {
    Instant alignedNow = alignToWindow(now, config.refillPeriod());
    long windowMs = config.refillPeriod().toMillis();
    long elapsedWindows = (alignedNow.toEpochMilli() - state.currentWindowStart().toEpochMilli()) / windowMs;

    if (elapsedWindows == 0) {
      return state;
    } else if (elapsedWindows == 1) {
      // Genau ein Fenster weiter: Das aktuelle wird zum vorherigen
      return new SlidingWindowCounterState(alignedNow, state.currentCount(), 0, state.epoch());
    } else if (elapsedWindows > 1) {
      // Mehr als ein Fenster weiter: Beide Zähler verfallen
      return new SlidingWindowCounterState(alignedNow, 0, 0, state.epoch());
    }
    return state; // Zeitverschiebung rückwärts (NTP), ignoriere
  }

  private int estimateUsage(SlidingWindowCounterState state, RateLimiterConfig<?> config, Instant now) {
    long windowMs = config.refillPeriod().toMillis();
    long elapsedMs = now.toEpochMilli() - state.currentWindowStart().toEpochMilli();

    // Gewichtung des alten Fensters nimmt linear ab, je weiter wir im aktuellen Fenster sind
    double previousWeight = 1.0 - ((double) elapsedMs / windowMs);
    // Verhindere negative Gewichtung bei asynchronen Race-Conditions
    previousWeight = Math.max(0.0, Math.min(1.0, previousWeight));

    return state.currentCount() + (int) (state.previousCount() * previousWeight);
  }

  @Override
  public RateLimitPermission<SlidingWindowCounterState> tryAcquirePermissions(
      SlidingWindowCounterState state, RateLimiterConfig<SlidingWindowCounterState> config, Instant now, int permits) {

    validatePermits(permits, config);
    SlidingWindowCounterState refreshed = refresh(state, config, now);
    int estimatedUsage = estimateUsage(refreshed, config, now);

    if (estimatedUsage + permits <= config.capacity()) {
      return RateLimitPermission.permitted(refreshed.withCurrentCount(refreshed.currentCount() + permits));
    }

    return RateLimitPermission.rejected(refreshed, estimateWaitDuration(refreshed, config, now, permits));
  }

  @Override
  public ReservationResult<SlidingWindowCounterState> reservePermissions(
      SlidingWindowCounterState state, RateLimiterConfig<SlidingWindowCounterState> config, Instant now, int permits, Duration timeout) {

    validatePermits(permits, config);
    SlidingWindowCounterState refreshed = refresh(state, config, now);
    int estimatedUsage = estimateUsage(refreshed, config, now);

    if (estimatedUsage + permits <= config.capacity()) {
      return ReservationResult.immediate(refreshed.withCurrentCount(refreshed.currentCount() + permits));
    }

    Duration waitDuration = estimateWaitDuration(refreshed, config, now, permits);
    if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
      return ReservationResult.timedOut(refreshed, waitDuration);
    }

    // Debt Floor: Gleitendes Fenster deckelt Schulden strikt auf 2x Kapazität
    if (refreshed.currentCount() + permits > config.capacity() * 2) {
      return ReservationResult.timedOut(refreshed, waitDuration);
    }

    return ReservationResult.delayed(refreshed.withCurrentCount(refreshed.currentCount() + permits), waitDuration);
  }

  @Override
  public SlidingWindowCounterState drain(SlidingWindowCounterState state, RateLimiterConfig<SlidingWindowCounterState> config, Instant now) {
    Instant windowStart = alignToWindow(now, config.refillPeriod());
    return state.withNextEpoch(windowStart, state.currentCount(), config.capacity());
  }

  @Override
  public SlidingWindowCounterState reset(SlidingWindowCounterState state, RateLimiterConfig<SlidingWindowCounterState> config, Instant now) {
    Instant windowStart = alignToWindow(now, config.refillPeriod());
    return state.withNextEpoch(windowStart, 0, 0);
  }

  @Override
  public SlidingWindowCounterState refund(SlidingWindowCounterState state, RateLimiterConfig<SlidingWindowCounterState> config, int permits) {
    if (permits < 1) return state;
    int newCurrent = Math.max(0, state.currentCount() - permits);
    return state.withCurrentCount(newCurrent);
  }

  @Override
  public int availablePermits(SlidingWindowCounterState state, RateLimiterConfig<SlidingWindowCounterState> config, Instant now) {
    SlidingWindowCounterState refreshed = refresh(state, config, now);
    int estimatedUsage = estimateUsage(refreshed, config, now);
    return Math.max(0, config.capacity() - estimatedUsage);
  }

  private Duration estimateWaitDuration(SlidingWindowCounterState state, RateLimiterConfig<?> config, Instant now, int permits) {
    long windowMs = config.refillPeriod().toMillis();
    long elapsedMs = now.toEpochMilli() - state.currentWindowStart().toEpochMilli();
    int current = state.currentCount();
    int prev = state.previousCount();

    // Fall 1: Warten innerhalb des aktuellen Fensters reicht aus, da das Gewicht von 'prev' weiter sinkt
    if (current + permits <= config.capacity() && prev > 0) {
      int allowedFromPrev = config.capacity() - permits - current;
      long targetElapsedMs = windowMs - (long) ((double) allowedFromPrev * windowMs / prev);
      long waitMs = targetElapsedMs - elapsedMs;
      if (waitMs > 0) return Duration.ofMillis(waitMs);
    }

    // Fall 2: Der aktuelle Count ist schon zu hoch. Warten auf das nächste Fenster.
    // Im nächsten Fenster wird 'current' zu 'prev' und fadet aus.
    long timeToNextWindow = windowMs - elapsedMs;
    int allowedNextFromPrev = config.capacity() - permits;

    if (current == 0 || allowedNextFromPrev >= current) {
      return Duration.ofMillis(timeToNextWindow);
    }

    long targetElapsedNextMs = windowMs - (long) ((double) allowedNextFromPrev * windowMs / current);
    return Duration.ofMillis(timeToNextWindow + targetElapsedNextMs);
  }

  private Instant alignToWindow(Instant time, Duration windowSize) {
    long windowMs = windowSize.toMillis();
    return Instant.ofEpochMilli((time.toEpochMilli() / windowMs) * windowMs);
  }

  private void validatePermits(int permits, RateLimiterConfig<?> config) {
    if (permits < 1) throw new IllegalArgumentException("permits must be >= 1");
    if (permits > config.capacity()) throw new IllegalArgumentException("permits exceeds capacity");
  }
}
