package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.exception.InqException;

/**
 * Thrown when a thread is interrupted while waiting for a bulkhead permit.
 *
 * <p>This is semantically different from {@link InqBulkheadFullException}: the
 * bulkhead was not necessarily full — the calling thread was interrupted by an
 * external signal (e.g. executor shutdown, parent timeout, or manual cancellation).
 *
 * <p>No {@link eu.inqudium.core.element.bulkhead.strategy.RejectionContext} is available
 * because the strategy never made a rejection decision — the interrupt occurred during
 * the wait, not as a result of a capacity check.
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

  /**
   * Creates a new exception indicating that the thread was interrupted during
   * permit acquisition.
   *
   * <p>No concurrency values are included because the strategy never made a
   * rejection decision — reading them after the interrupt would produce stale
   * snapshots that misrepresent the cause.
   *
   * @param callId      the call identifier
   * @param elementName the bulkhead instance name
   * @param enableExceptionOptimization whether suppression is enabled or disabled, and whether the stack trace
   *                                    should be writable.
   */
  public InqBulkheadInterruptedException(String callId, String elementName, boolean enableExceptionOptimization) {
    super(callId,
        CODE,
        elementName,
        InqElementType.BULKHEAD,
        "Thread interrupted while waiting for Bulkhead '" + elementName + "' permit",
        null,
        enableExceptionOptimization
    );
  }
}
