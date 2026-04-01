package eu.inqudium.core.element.timelimiter;

import java.time.Duration;
import java.time.Instant;

/**
 * Event emitted by the time limiter for observability.
 *
 * @param name      the time limiter name
 * @param type      the event type
 * @param elapsed   elapsed execution duration at the time of the event
 * @param timeout   the configured timeout
 * @param timestamp when the event occurred
 */
public record TimeLimiterEvent(
    String name,
    Type type,
    Duration elapsed,
    Duration timeout,
    Instant timestamp
) {

  public static TimeLimiterEvent started(String name, Duration timeout, Instant now) {
    return new TimeLimiterEvent(name, Type.STARTED, Duration.ZERO, timeout, now);
  }

  public static TimeLimiterEvent completed(String name, Duration elapsed, Duration timeout, Instant now) {
    return new TimeLimiterEvent(name, Type.COMPLETED, elapsed, timeout, now);
  }

  public static TimeLimiterEvent timedOut(String name, Duration elapsed, Duration timeout, Instant now) {
    return new TimeLimiterEvent(name, Type.TIMED_OUT, elapsed, timeout, now);
  }

  public static TimeLimiterEvent failed(String name, Duration elapsed, Duration timeout, Instant now) {
    return new TimeLimiterEvent(name, Type.FAILED, elapsed, timeout, now);
  }

  public static TimeLimiterEvent cancelled(String name, Duration elapsed, Duration timeout, Instant now) {
    return new TimeLimiterEvent(name, Type.CANCELLED, elapsed, timeout, now);
  }

  @Override
  public String toString() {
    return "TimeLimiter '%s': %s — elapsed %s ms (timeout %s ms) at %s"
        .formatted(name, type, elapsed.toMillis(), timeout.toMillis(), timestamp);
  }

  public enum Type {
    /**
     * An execution started.
     */
    STARTED,
    /**
     * An execution completed successfully within the time limit.
     */
    COMPLETED,
    /**
     * An execution exceeded the time limit.
     */
    TIMED_OUT,
    /**
     * An execution failed with an exception within the time limit.
     */
    FAILED,
    /**
     * A running execution was cancelled (typically after timeout).
     */
    CANCELLED
  }
}
