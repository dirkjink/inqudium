package eu.inqudium.core.element.timelimiter;

import java.time.Duration;

/**
 * Exception thrown when an operation exceeds its configured time limit.
 */
public class TimeLimiterException extends RuntimeException {

  private final String timeLimiterName;
  private final String instanceId;
  private final Duration timeout;

  // Fix 10: Primary constructor — all fields required.
  // All other constructors delegate here to ensure instanceId is always available.

  /**
   * Creates a new TimeLimiterException with full identity information.
   *
   * @param timeLimiterName human-readable name
   * @param instanceId      unique instance identifier (UUID-based); may be {@code null}
   *                        for exceptions created outside of an ImperativeTimeLimiter context
   * @param timeout         the timeout duration that was exceeded
   */
  public TimeLimiterException(String timeLimiterName, String instanceId, Duration timeout) {
    super("TimeLimiter '%s' — operation timed out after %s ms"
        .formatted(timeLimiterName, timeout.toMillis()));
    this.timeLimiterName = timeLimiterName;
    this.instanceId = instanceId;
    this.timeout = timeout;
  }

  /**
   * Convenience constructor without instanceId.
   * Used by the default exception factory in {@link TimeLimiterConfig}.
   */
  public TimeLimiterException(String timeLimiterName, Duration timeout) {
    this(timeLimiterName, null, timeout);
  }

  /**
   * Fix 10: Constructor with cause and instanceId.
   *
   * @param timeLimiterName human-readable name
   * @param instanceId      unique instance identifier; may be {@code null}
   * @param timeout         the timeout duration that was exceeded
   * @param cause           the underlying cause
   */
  public TimeLimiterException(String timeLimiterName, String instanceId, Duration timeout, Throwable cause) {
    super("TimeLimiter '%s' — operation timed out after %s ms"
        .formatted(timeLimiterName, timeout.toMillis()), cause);
    this.timeLimiterName = timeLimiterName;
    this.instanceId = instanceId;
    this.timeout = timeout;
  }

  /**
   * Convenience constructor with cause but without instanceId.
   */
  public TimeLimiterException(String timeLimiterName, Duration timeout, Throwable cause) {
    this(timeLimiterName, null, timeout, cause);
  }

  public String getTimeLimiterName() {
    return timeLimiterName;
  }

  /**
   * Returns the unique instance identifier of the time limiter that produced
   * this exception, or {@code null} if not set.
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

  /**
   * Fix 2: Returns a copy of this exception with the given instanceId injected.
   * Preserves the exception class, message, cause, and suppressed exceptions.
   *
   * <p>This is used by the imperative wrapper to stamp the instanceId onto
   * exceptions produced by the configured exception factory, without discarding
   * any factory customizations (subclass type, extra fields, cause chain).
   *
   * @param newInstanceId the instance identifier to inject
   * @return a new exception with the instanceId set; same type if possible
   */
  public TimeLimiterException withInstanceId(String newInstanceId) {
    TimeLimiterException copy = new TimeLimiterException(
        this.timeLimiterName, newInstanceId, this.timeout, this.getCause());
    for (Throwable suppressed : this.getSuppressed()) {
      copy.addSuppressed(suppressed);
    }
    // Preserve the original stack trace — the timeout location didn't change
    copy.setStackTrace(this.getStackTrace());
    return copy;
  }
}
