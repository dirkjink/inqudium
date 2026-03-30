package eu.inqudium.ratelimiter.internal;

import eu.inqudium.core.ratelimiter.AbstractRateLimiter;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.ratelimiter.RateLimiter;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Imperative rate limiter using {@link LockSupport#parkNanos} for blocking waits (ADR-019).
 *
 * <p>All token bucket logic, event publishing, and exception handling live in
 * {@link AbstractRateLimiter}. This class only provides the blocking wait mechanism.
 *
 * <p>Virtual-thread safe — {@link LockSupport#parkNanos} does not pin carrier threads.
 *
 * @since 0.1.0
 */
public final class TokenBucketRateLimiter extends AbstractRateLimiter implements RateLimiter {

  public TokenBucketRateLimiter(String name, RateLimiterConfig config) {
    super(name, config);
  }

  @Override
  protected void waitForPermit(Duration duration) {
    LockSupport.parkNanos(duration.toNanos());
  }
}
