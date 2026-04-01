package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.exception.InqException;

import java.util.Locale;

/**
 * Thrown when a thread is interrupted while waiting for a bulkhead permit.
 *
 * <p>This is semantically different from {@link InqBulkheadFullException}: the
 * bulkhead was not necessarily full — the calling thread was interrupted by an
 * external signal (e.g. executor shutdown, parent timeout, or manual cancellation).
 *
 * <p>The thread's interrupt flag is restored before this exception is thrown,
 * allowing callers to observe the interrupt via {@link Thread#isInterrupted()}.
 *
 * @since 0.1.0
 */
public class InqBulkheadInterruptedException extends InqException {

  /**
   * Thread interrupted while waiting for a bulkhead permit.
   */
  public static final String CODE = InqElementType.BULKHEAD.errorCode(2);

  private final int concurrentCalls;
  private final int maxConcurrentCalls;

  /**
   * Creates a new exception indicating that the thread was interrupted during permit acquisition.
   *
   * @param callId             the call identifier
   * @param elementName        the bulkhead instance name
   * @param concurrentCalls    the number of in-flight calls at the time of interruption
   * @param maxConcurrentCalls the configured maximum
   */
  public InqBulkheadInterruptedException(String callId, String elementName,
                                         int concurrentCalls, int maxConcurrentCalls) {
    super(callId, CODE, elementName, InqElementType.BULKHEAD,
        String.format(Locale.ROOT,
            "Thread interrupted while waiting for Bulkhead '%s' permit (%d/%d concurrent calls)",
            elementName, concurrentCalls, maxConcurrentCalls));
    this.concurrentCalls = concurrentCalls;
    this.maxConcurrentCalls = maxConcurrentCalls;
  }

  /**
   * Returns the number of concurrent calls at the time of interruption.
   */
  public int getConcurrentCalls() {
    return concurrentCalls;
  }

  /**
   * Returns the configured maximum concurrent calls.
   */
  public int getMaxConcurrentCalls() {
    return maxConcurrentCalls;
  }
}
