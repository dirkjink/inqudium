package eu.inqudium.core.timelimiter;

import java.time.Duration;

/**
 * Exception thrown when an operation exceeds its configured time limit.
 */
public class TimeLimiterException extends RuntimeException {

  private final String timeLimiterName;
  private final String instanceId;
  private final Duration timeout;

  public TimeLimiterException(String timeLimiterName, Duration timeout) {
    super("TimeLimiter '%s' — operation timed out after %s ms"
        .formatted(timeLimiterName, timeout.toMillis()));
    this.timeLimiterName = timeLimiterName;
    this.instanceId = null;
    this.timeout = timeout;
  }

  /**
   * Fix 3: Constructor that includes the instance identifier for identity-based
   * comparison in fallback wrappers.
   *
   * @param timeLimiterName human-readable name
   * @param instanceId      unique instance identifier (UUID-based)
   * @param timeout         the timeout duration that was exceeded
   */
  public TimeLimiterException(String timeLimiterName, String instanceId, Duration timeout) {
    super("TimeLimiter '%s' — operation timed out after %s ms"
        .formatted(timeLimiterName, timeout.toMillis()));
    this.timeLimiterName = timeLimiterName;
    this.instanceId = instanceId;
    this.timeout = timeout;
  }

  public TimeLimiterException(String timeLimiterName, Duration timeout, Throwable cause) {
    super("TimeLimiter '%s' — operation timed out after %s ms"
        .formatted(timeLimiterName, timeout.toMillis()), cause);
    this.timeLimiterName = timeLimiterName;
    this.instanceId = null;
    this.timeout = timeout;
  }

  public String getTimeLimiterName() {
    return timeLimiterName;
  }

  /**
   * Fix 3: Returns the unique instance identifier of the time limiter
   * that produced this exception, or {@code null} if not set.
   */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Returns the configured timeout that was exceeded.
   */
  public Duration getTimeout() {
    return timeout;
  }
}
