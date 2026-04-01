package eu.inqudium.core.ratelimiter;

import java.time.Instant;

/**
 * Marker- und Basis-Interface für den Zustand eines Rate Limiters.
 */
public interface RateLimiterState {

  long epoch();

  RateLimiterState withNextEpoch(Instant now);
}
