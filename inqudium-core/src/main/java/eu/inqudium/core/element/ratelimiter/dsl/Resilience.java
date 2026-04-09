package eu.inqudium.core.element.ratelimiter.dsl;

public final class Resilience {

  private Resilience() {
  }

  // --- Rate Limiter (NEU) ---
  public static RateLimiterProtection throttleWithRateLimiter() {
    return new DefaultRateLimiterProtection();
  }
}
