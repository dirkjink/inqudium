package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.ReservationResult;

import java.time.Duration;
import java.time.Instant;

public class FixedWindowStrategy implements RateLimiterStrategy<FixedWindowState> {

  @Override
  public FixedWindowState initial(RateLimiterConfig<FixedWindowState> config, Instant now) {
    return new FixedWindowState(0, alignToWindow(now, config.refillPeriod()), 0L);
  }

  /**
   * Berechnet den aktuellen Fenster-Start und reduziert die konsumierten Permits,
   * falls vergangene Fenster überschritten wurden (Debt-Abbau).
   */
  private FixedWindowState refreshWindow(FixedWindowState state, RateLimiterConfig<FixedWindowState> config, Instant now) {
    Instant currentWindowStart = alignToWindow(now, config.refillPeriod());

    if (currentWindowStart.equals(state.windowStart())) {
      return state;
    }

    if (currentWindowStart.isBefore(state.windowStart())) {
      return state; // Zeit läuft rückwärts (NTP Sync), ignoriere
    }

    long windowMs = config.refillPeriod().toMillis();
    long windowsElapsed = (currentWindowStart.toEpochMilli() - state.windowStart().toEpochMilli()) / windowMs;

    // Reduziere genutzte Permits um die Kapazität pro vergangenem Fenster.
    // Dies ermöglicht korrekten Abbau von Warteschlangen/Debt bei Reservierungen.
    long capacityRecovered = windowsElapsed * config.capacity();
    int newUsed = (int) Math.max(0, state.usedPermits() - capacityRecovered);

    return new FixedWindowState(newUsed, currentWindowStart, state.epoch());
  }

  @Override
  public RateLimitPermission<FixedWindowState> tryAcquirePermissions(
      FixedWindowState state, RateLimiterConfig<FixedWindowState> config, Instant now, int permits) {
    validatePermits(permits, config);
    FixedWindowState refreshed = refreshWindow(state, config, now);

    if (config.capacity() - refreshed.usedPermits() >= permits) {
      return RateLimitPermission.permitted(
          refreshed.withUsedPermits(refreshed.usedPermits() + permits));
    }

    return RateLimitPermission.rejected(refreshed, estimateWaitDuration(refreshed, config, now, permits));
  }

  @Override
  public ReservationResult<FixedWindowState> reservePermissions(
      FixedWindowState state, RateLimiterConfig<FixedWindowState> config, Instant now, int permits, Duration timeout) {
    validatePermits(permits, config);
    FixedWindowState refreshed = refreshWindow(state, config, now);

    if (config.capacity() - refreshed.usedPermits() >= permits) {
      return ReservationResult.immediate(
          refreshed.withUsedPermits(refreshed.usedPermits() + permits));
    }

    Duration waitDuration = estimateWaitDuration(refreshed, config, now, permits);
    if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
      return ReservationResult.timedOut(refreshed, waitDuration);
    }

    // Debt Floor: Ein Fixed Window lässt nicht unendlich viele Reservierungen für die Zukunft zu.
    // Wir deckeln das Warten auf ein maximales komplettes Zukunfts-Fenster.
    if (refreshed.usedPermits() + permits > config.capacity() * 2) {
      return ReservationResult.timedOut(refreshed, waitDuration);
    }

    return ReservationResult.delayed(
        refreshed.withUsedPermits(refreshed.usedPermits() + permits), waitDuration);
  }

  @Override
  public FixedWindowState drain(FixedWindowState state, RateLimiterConfig<FixedWindowState> config, Instant now) {
    // Entleeren bedeutet, dass das aktuelle Fenster vollständig verbraucht ist.
    Instant currentWindowStart = alignToWindow(now, config.refillPeriod());
    return state.withNextEpoch(config.capacity(), currentWindowStart);
  }

  @Override
  public FixedWindowState reset(FixedWindowState state, RateLimiterConfig<FixedWindowState> config, Instant now) {
    Instant currentWindowStart = alignToWindow(now, config.refillPeriod());
    return state.withNextEpoch(0, currentWindowStart);
  }

  @Override
  public FixedWindowState refund(FixedWindowState state, RateLimiterConfig<FixedWindowState> config, int permits) {
    if (permits < 1) return state;
    int newUsed = Math.max(0, state.usedPermits() - permits);
    return state.withUsedPermits(newUsed);
  }

  @Override
  public int availablePermits(FixedWindowState state, RateLimiterConfig<FixedWindowState> config, Instant now) {
    FixedWindowState refreshed = refreshWindow(state, config, now);
    return Math.max(0, config.capacity() - refreshed.usedPermits());
  }

  private Duration estimateWaitDuration(FixedWindowState state, RateLimiterConfig<FixedWindowState> config, Instant now, int requiredPermits) {
    int predictedUsed = state.usedPermits() + requiredPermits;
    if (predictedUsed <= config.capacity()) {
      return Duration.ZERO;
    }

    int deficit = predictedUsed - config.capacity();
    long windowsNeeded = (deficit + config.capacity() - 1) / config.capacity();
    Instant targetTime = state.windowStart().plus(config.refillPeriod().multipliedBy(windowsNeeded));

    Duration wait = Duration.between(now, targetTime);
    return wait.isNegative() ? Duration.ZERO : wait;
  }

  private Instant alignToWindow(Instant time, Duration windowSize) {
    long windowMs = windowSize.toMillis();
    long alignedMs = (time.toEpochMilli() / windowMs) * windowMs;
    return Instant.ofEpochMilli(alignedMs);
  }

  private void validatePermits(int permits, RateLimiterConfig<FixedWindowState> config) {
    if (permits < 1) throw new IllegalArgumentException("permits must be >= 1");
    if (permits > config.capacity()) throw new IllegalArgumentException("permits exceeds capacity");
  }
}
