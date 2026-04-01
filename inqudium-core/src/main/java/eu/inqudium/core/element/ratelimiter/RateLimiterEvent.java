package eu.inqudium.core.element.ratelimiter;

import java.time.Duration;
import java.time.Instant;

/**
 * Event emitted by the rate limiter for observability.
 *
 * @param name             the rate limiter name
 * @param type             the event type
 * @param availablePermits permits available after the event
 * @param waitDuration     wait duration (relevant for WAITING and REJECTED events)
 * @param timestamp        when the event occurred
 */
public record RateLimiterEvent(
    String name,
    Type type,
    int availablePermits,
    Duration waitDuration,
    Instant timestamp
) {

  public static RateLimiterEvent permitted(String name, int availablePermits, Instant now) {
    return new RateLimiterEvent(name, Type.PERMITTED, availablePermits, Duration.ZERO, now);
  }

  public static RateLimiterEvent waiting(String name, int availablePermits, Duration wait, Instant now) {
    return new RateLimiterEvent(name, Type.WAITING, availablePermits, wait, now);
  }

  public static RateLimiterEvent rejected(String name, int availablePermits, Duration wait, Instant now) {
    return new RateLimiterEvent(name, Type.REJECTED, availablePermits, wait, now);
  }

  public static RateLimiterEvent drained(String name, Instant now) {
    return new RateLimiterEvent(name, Type.DRAINED, 0, Duration.ZERO, now);
  }

  public static RateLimiterEvent reset(String name, int availablePermits, Instant now) {
    return new RateLimiterEvent(name, Type.RESET, availablePermits, Duration.ZERO, now);
  }

  @Override
  public String toString() {
    return "RateLimiter '%s': %s — %d permits available at %s"
        .formatted(name, type, availablePermits, timestamp);
  }

  public enum Type {
    /**
     * A permit was acquired immediately.
     */
    PERMITTED,
    /**
     * A permit was acquired after waiting.
     */
    WAITING,
    /**
     * The request was rejected — no permit available within timeout.
     */
    REJECTED,
    /**
     * The rate limiter was manually drained (all permits removed).
     */
    DRAINED,
    /**
     * The rate limiter was manually reset.
     */
    RESET
  }
}
