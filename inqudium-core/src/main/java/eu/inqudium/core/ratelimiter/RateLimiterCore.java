package eu.inqudium.core.ratelimiter;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure functional core of the token-bucket rate limiter.
 */
public final class RateLimiterCore {

  private RateLimiterCore() {
    // Utility class — not instantiable
  }

  // ======================== Refill ========================

  public static RateLimiterSnapshot refill(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    long elapsedNanos = Duration.between(snapshot.lastRefillTime(), now).toNanos();
    if (elapsedNanos <= 0) {
      return snapshot;
    }

    long periodNanos = config.refillPeriod().toNanos();
    long completePeriods = elapsedNanos / periodNanos;

    if (completePeriods <= 0) {
      return snapshot;
    }

    Instant newRefillTime = snapshot.lastRefillTime()
        .plusNanos(completePeriods * periodNanos);

    long tokensToAdd = completePeriods * config.refillPermits();
    if (tokensToAdd >= (long) config.capacity() - Math.min(snapshot.availablePermits(), 0)) {
      return snapshot.withRefill(config.capacity(), newRefillTime);
    }

    int newPermits = (int) Math.min(
        (long) snapshot.availablePermits() + tokensToAdd,
        config.capacity()
    );

    return snapshot.withRefill(newPermits, newRefillTime);
  }

  // ======================== Permission (fail-fast) ========================

  public static RateLimitPermission tryAcquirePermission(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {
    return tryAcquirePermissions(snapshot, config, now, 1);
  }

  public static RateLimitPermission tryAcquirePermissions(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now,
      int permits) {

    if (permits < 1) {
      throw new IllegalArgumentException("permits must be >= 1, got " + permits);
    }
    if (permits > config.capacity()) {
      throw new IllegalArgumentException(
          "permits (%d) exceeds capacity (%d)".formatted(permits, config.capacity()));
    }

    RateLimiterSnapshot refilled = refill(snapshot, config, now);

    if (refilled.availablePermits() >= permits) {
      return RateLimitPermission.permitted(
          refilled.withAvailablePermits(refilled.availablePermits() - permits));
    }

    Duration waitDuration = estimateWaitDuration(refilled, config, now, permits);
    return RateLimitPermission.rejected(refilled, waitDuration);
  }

  // ======================== Reservation (wait-capable) ========================

  // Fix 4: Ermöglicht das Reservieren mehrerer Permits in einer blockierenden Anfrage
  public static ReservationResult reservePermissions(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now,
      int permits,
      Duration timeout) {

    if (permits < 1) {
      throw new IllegalArgumentException("permits must be >= 1, got " + permits);
    }
    if (permits > config.capacity()) {
      throw new IllegalArgumentException(
          "permits (%d) exceeds capacity (%d)".formatted(permits, config.capacity()));
    }

    RateLimiterSnapshot refilled = refill(snapshot, config, now);

    if (refilled.availablePermits() >= permits) {
      return ReservationResult.immediate(
          refilled.withAvailablePermits(refilled.availablePermits() - permits));
    }

    Duration waitDuration = estimateWaitDuration(refilled, config, now, permits);

    if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
      return ReservationResult.timedOut(refilled, waitDuration);
    }

    int debtFloor = -config.capacity();
    if (refilled.availablePermits() - permits < debtFloor) {
      return ReservationResult.timedOut(refilled, waitDuration);
    }

    RateLimiterSnapshot consumed = refilled.withAvailablePermits(
        refilled.availablePermits() - permits);
    return ReservationResult.delayed(consumed, waitDuration);
  }

  // ======================== Drain, Reset & Refund ========================

  /**
   * Fix 3: drain ändert jetzt die Epoche, damit schlafende Threads invalidiert werden
   * und beim Aufwachen neu evaluieren (und so sofort den entleerten Zustand erkennen).
   */
  public static RateLimiterSnapshot drain(RateLimiterSnapshot snapshot, Instant now) {
    return snapshot.withNextEpoch(0, now);
  }

  public static RateLimiterSnapshot reset(
      RateLimiterSnapshot current,
      RateLimiterConfig config,
      Instant now) {
    return current.withNextEpoch(config.capacity(), now);
  }

  /**
   * Fix 1: Gibt Token sicher an den Bucket zurück (gedeckelt durch die Kapazität).
   */
  public static RateLimiterSnapshot refund(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      int permits) {
    if (permits < 1) return snapshot;
    int newPermits = Math.min(snapshot.availablePermits() + permits, config.capacity());
    return snapshot.withAvailablePermits(newPermits);
  }

  // ======================== Query helpers ========================

  public static int availablePermits(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {
    return refill(snapshot, config, now).availablePermits();
  }

  // Fix 4: Kalkuliert die Wartezeit auf Basis der angeforderten Permits
  public static Duration estimateWaitDuration(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now,
      int requiredPermits) {

    if (snapshot.availablePermits() >= requiredPermits) {
      return Duration.ZERO;
    }

    int deficit = requiredPermits - snapshot.availablePermits();
    return estimateWaitForPermits(config, deficit);
  }

  static Duration estimateWaitForPermits(RateLimiterConfig config, int permits) {
    if (permits <= 0) {
      return Duration.ZERO;
    }
    long cyclesNeeded = ((long) permits + config.refillPermits() - 1) / config.refillPermits();
    return config.refillPeriod().multipliedBy(cyclesNeeded);
  }
}