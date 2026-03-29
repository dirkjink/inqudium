package eu.inqudium.core.retry;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;

import java.util.Locale;

/**
 * Thrown when a retry element has exhausted all configured attempts.
 *
 * <p>This is the only {@link InqException} that wraps the original cause — the
 * last exception from the final retry attempt. The application needs to know both
 * that retries were attempted and what the final failure was (ADR-009).
 *
 * @since 0.1.0
 */
public class InqRetryExhaustedException extends InqException {

  /**
   * All retry attempts exhausted.
   */
  public static final String CODE = InqElementType.RETRY.errorCode(1);

  private final int attempts;

  /**
   * Creates a new exception indicating that all retry attempts have failed.
   *
   * <p>The cause is {@linkplain InqFailure#unwrap(Throwable) unwrapped} to strip
   * common wrapper exceptions before storing.
   *
   * @param callId      the unique call identifier
   * @param elementName the retry instance name
   * @param attempts    total number of attempts made (including the initial call)
   * @param lastCause   the exception from the final attempt (unwrapped automatically)
   */
  public InqRetryExhaustedException(String callId, String elementName, int attempts, Throwable lastCause) {
    super(callId, CODE, elementName, InqElementType.RETRY,
        String.format(Locale.ROOT, "Retry '%s' exhausted after %d attempts", elementName, attempts),
        InqFailure.unwrap(lastCause));
    this.attempts = attempts;
  }

  /**
   * Returns the total number of attempts made, including the initial call.
   *
   * @return the attempt count (e.g. 3 means initial call + 2 retries)
   */
  public int getAttempts() {
    return attempts;
  }

  /**
   * Returns the exception from the final attempt.
   * Equivalent to {@link #getCause()}.
   *
   * @return the last cause
   */
  public Throwable getLastCause() {
    return getCause();
  }
}
