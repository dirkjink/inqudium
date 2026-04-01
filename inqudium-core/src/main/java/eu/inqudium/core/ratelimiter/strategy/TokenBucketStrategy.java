package eu.inqudium.core.ratelimiter.strategy;

import eu.inqudium.core.ratelimiter.RateLimitPermission;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.ReservationResult;

import java.time.Duration;
import java.time.Instant;

public class TokenBucketStrategy implements RateLimiterStrategy<TokenBucketState> {

  @Override
  public TokenBucketState initial(RateLimiterConfig config, Instant now) {
    return new TokenBucketState(config.capacity(), now, 0L);
  }

  private TokenBucketState refill(TokenBucketState state, RateLimiterConfig config, Instant now) {
    long elapsedNanos = Duration.between(state.lastRefillTime(), now).toNanos();
    if (elapsedNanos <= 0) return state;

    long periodNanos = config.refillPeriod().toNanos();
    long completePeriods = elapsedNanos / periodNanos;

    if (completePeriods <= 0) return state;

    Instant newRefillTime = state.lastRefillTime().plusNanos(completePeriods * periodNanos);
    long tokensToAdd = completePeriods * config.refillPermits();

    if (tokensToAdd >= (long) config.capacity() - Math.min(state.availablePermits(), 0)) {
      return state.withRefill(config.capacity(), newRefillTime);
    }

    int newPermits = (int) Math.min((long) state.availablePermits() + tokensToAdd, config.capacity());
    return state.withRefill(newPermits, newRefillTime);
  }

  @Override
  public RateLimitPermission<TokenBucketState> tryAcquirePermissions(
      TokenBucketState state, RateLimiterConfig config, Instant now, int permits) {
    validatePermits(permits, config);
    TokenBucketState refilled = refill(state, config, now);

    if (refilled.availablePermits() >= permits) {
      return RateLimitPermission.permitted(
          refilled.withAvailablePermits(refilled.availablePermits() - permits));
    }
    return RateLimitPermission.rejected(refilled, estimateWaitDuration(refilled, config, now, permits));
  }

  @Override
  public ReservationResult<TokenBucketState> reservePermissions(
      TokenBucketState state, RateLimiterConfig config, Instant now, int permits, Duration timeout) {
    validatePermits(permits, config);
    TokenBucketState refilled = refill(state, config, now);

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

    return ReservationResult.delayed(
        refilled.withAvailablePermits(refilled.availablePermits() - permits), waitDuration);
  }

  @Override
  public TokenBucketState drain(TokenBucketState state, RateLimiterConfig config, Instant now) {
    return state.withAvailablePermits(0).withNextEpoch(now);
  }

  @Override
  public TokenBucketState reset(TokenBucketState state, RateLimiterConfig config, Instant now) {
    return state.withNextEpoch(config.capacity(), now);
  }

  @Override
  public TokenBucketState refund(TokenBucketState state, RateLimiterConfig config, int permits) {
    if (permits < 1) return state;
    int newPermits = Math.min(state.availablePermits() + permits, config.capacity());
    return state.withAvailablePermits(newPermits);
  }

  @Override
  public int availablePermits(TokenBucketState state, RateLimiterConfig config, Instant now) {
    return refill(state, config, now).availablePermits();
  }

  private Duration estimateWaitDuration(TokenBucketState state, RateLimiterConfig config, Instant now, int requiredPermits) {
    if (state.availablePermits() >= requiredPermits) return Duration.ZERO;
    int deficit = requiredPermits - state.availablePermits();
    long cyclesNeeded = ((long) deficit + config.refillPermits() - 1) / config.refillPermits();
    return config.refillPeriod().multipliedBy(cyclesNeeded);
  }

  private void validatePermits(int permits, RateLimiterConfig config) {
    if (permits < 1) throw new IllegalArgumentException("permits must be >= 1");
    if (permits > config.capacity()) throw new IllegalArgumentException("permits exceeds capacity");
  }
}
