package eu.inqudium.core.timelimiter;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.exception.InqException;

import java.time.Duration;
import java.util.Locale;

/**
 * Thrown when a time limiter fires because the caller's maximum wait time
 * has been exceeded.
 *
 * <p>The underlying operation may still be running — the TimeLimiter bounds
 * the caller's wait time, not the operation's execution time. Orphaned operations
 * are handled via the {@code OrphanedCallHandler} callback (ADR-010).
 *
 * @since 0.1.0
 */
public class InqTimeLimitExceededException extends InqException {

  /**
   * Caller wait time exceeded configured timeout.
   */
  public static final String CODE = InqElementType.TIME_LIMITER.errorCode(1);

  private final Duration configuredDuration;
  private final Duration actualDuration;

  /**
   * Creates a new exception indicating that the time limit was exceeded.
   *
   * @param elementName        the time limiter instance name
   * @param configuredDuration the configured timeout
   * @param actualDuration     how long the caller actually waited
   */
  public InqTimeLimitExceededException(String callId, String elementName, Duration configuredDuration, Duration actualDuration) {
    super(callId, CODE, elementName, InqElementType.TIME_LIMITER,
        String.format(Locale.ROOT, "TimeLimiter '%s' timed out after %dms (configured: %dms)",
            elementName, actualDuration.toMillis(), configuredDuration.toMillis()));
    this.configuredDuration = configuredDuration;
    this.actualDuration = actualDuration;
  }

  /**
   * Returns the configured timeout duration.
   *
   * @return the configured timeout
   */
  public Duration getConfiguredDuration() {
    return configuredDuration;
  }

  /**
   * Returns how long the caller actually waited before the timeout fired.
   *
   * @return the actual wait duration (may be slightly longer than configured due to scheduling)
   */
  public Duration getActualDuration() {
    return actualDuration;
  }
}
