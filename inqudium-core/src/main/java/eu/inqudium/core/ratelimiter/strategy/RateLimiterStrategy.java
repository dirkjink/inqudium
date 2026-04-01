package eu.inqudium.core.ratelimiter.strategy;

import eu.inqudium.core.ratelimiter.RateLimitPermission;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterState;
import eu.inqudium.core.ratelimiter.ReservationResult;

import java.time.Duration;
import java.time.Instant;

public interface RateLimiterStrategy<S extends RateLimiterState> {

  S initial(RateLimiterConfig config, Instant now);

  RateLimitPermission<S> tryAcquirePermissions(S state, RateLimiterConfig config, Instant now, int permits);

  ReservationResult<S> reservePermissions(S state, RateLimiterConfig config, Instant now, int permits, Duration timeout);

  S drain(S state, RateLimiterConfig config, Instant now); // Korrigiert

  S reset(S state, RateLimiterConfig config, Instant now);

  S refund(S state, RateLimiterConfig config, int permits);

  int availablePermits(S state, RateLimiterConfig config, Instant now);
}