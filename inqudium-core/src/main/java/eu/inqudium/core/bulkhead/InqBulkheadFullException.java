package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.exception.InqException;

import java.util.Locale;

/**
 * Thrown when a bulkhead rejects a call because the maximum number of concurrent
 * calls has been reached and the wait timeout (if any) has expired.
 *
 * <p>No call was made to the downstream service — the bulkhead rejected the
 * request to prevent resource exhaustion (ADR-009, ADR-020).
 *
 * @since 0.1.0
 */
public class InqBulkheadFullException extends InqException {

  /**
   * Call rejected — max concurrent calls reached.
   */
  public static final String CODE = InqElementType.BULKHEAD.errorCode(1);

  private final int concurrentCalls;
  private final int maxConcurrentCalls;

  /**
   * Creates a new exception indicating that the bulkhead is full.
   *
   * @param elementName        the bulkhead instance name
   * @param concurrentCalls    the current number of in-flight calls
   * @param maxConcurrentCalls the configured maximum
   */
  public InqBulkheadFullException(String callId, String elementName, int concurrentCalls, int maxConcurrentCalls) {
    super(callId, CODE, elementName, InqElementType.BULKHEAD,
        String.format(Locale.ROOT, "Bulkhead '%s' is full (%d/%d concurrent calls)", elementName, concurrentCalls, maxConcurrentCalls));
    this.concurrentCalls = concurrentCalls;
    this.maxConcurrentCalls = maxConcurrentCalls;
  }

  /**
   * Returns the number of concurrent calls at the time of rejection.
   *
   * @return the current concurrent call count
   */
  public int getConcurrentCalls() {
    return concurrentCalls;
  }

  /**
   * Returns the configured maximum concurrent calls.
   *
   * @return the maximum
   */
  public int getMaxConcurrentCalls() {
    return maxConcurrentCalls;
  }
}
