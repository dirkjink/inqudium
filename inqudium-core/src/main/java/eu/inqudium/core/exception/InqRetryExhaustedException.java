package eu.inqudium.core.exception;

import eu.inqudium.core.InqElementType;

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

  private final int attempts;

  /**
   * Creates a new exception indicating that all retry attempts have failed.
   *
   * @param elementName the retry instance name
   * @param attempts    total number of attempts made (including the initial call)
   * @param lastCause   the exception from the final attempt
   */
  public InqRetryExhaustedException(String elementName, int attempts, Throwable lastCause) {
    super(elementName, InqElementType.RETRY,
        String.format("Retry '%s' exhausted after %d attempts", elementName, attempts),
        lastCause);
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
