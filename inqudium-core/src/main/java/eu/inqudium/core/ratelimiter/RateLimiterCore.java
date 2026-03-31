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

    long tokensToAdd = completePeriods * config.refillPermits();
    int newPermits = (int) Math.min(
        (long) snapshot.availablePermits() + tokensToAdd,
        config.capacity()
    );

    Instant newRefillTime = snapshot.lastRefillTime()
        .plusNanos(completePeriods * periodNanos);

    return snapshot.withRefill(newPermits, newRefillTime);
  }

  // ======================== Permission (fail-fast) ========================

  public static RateLimitPermission tryAcquirePermission(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    RateLimiterSnapshot refilled = refill(snapshot, config, now);

    if (refilled.availablePermits() > 0) {
      return RateLimitPermission.permitted(refilled.withPermitConsumed());
    }

    Duration waitDuration = estimateWaitDuration(refilled, config, now);
    return RateLimitPermission.rejected(refilled, waitDuration);
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

    int deficit = permits - refilled.availablePermits();
    Duration waitDuration = estimateWaitForPermits(config, deficit);
    return RateLimitPermission.rejected(refilled, waitDuration);
  }

  // ======================== Reservation (wait-capable) ========================

  public static ReservationResult reservePermission(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now,
      Duration timeout) {

    RateLimiterSnapshot refilled = refill(snapshot, config, now);

    if (refilled.availablePermits() > 0) {
      return ReservationResult.immediate(refilled.withPermitConsumed());
    }

    Duration waitDuration = estimateWaitDuration(refilled, config, now);

    if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
      return ReservationResult.timedOut(refilled, waitDuration);
    }

    RateLimiterSnapshot consumed = refilled.withAvailablePermits(
        refilled.availablePermits() - 1);
    return ReservationResult.delayed(consumed, waitDuration);
  }

  // ======================== Drain & Reset ========================

  public static RateLimiterSnapshot drain(RateLimiterSnapshot snapshot) {
    return snapshot.withAvailablePermits(0);
  }

  public static RateLimiterSnapshot reset(RateLimiterConfig config, Instant now) {
    return RateLimiterSnapshot.initial(config, now);
  }

  // ======================== Query helpers ========================

  public static int availablePermits(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    return refill(snapshot, config, now).availablePermits();
  }

  public static Duration estimateWaitDuration(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    if (snapshot.availablePermits() > 0) {
      return Duration.ZERO;
    }

    // Fix 1A: Bucket-Schulden einberechnen.
    // Wenn permits bei -5 steht, benötigen wir 6 nachgefüllte Permits, um auf +1 zu kommen.
    int deficit = 1 - snapshot.availablePermits();
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