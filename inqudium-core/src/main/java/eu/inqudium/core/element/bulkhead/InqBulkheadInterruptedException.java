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
   */
  public InqBulkheadInterruptedException(String callId, String elementName) {
    super(callId, CODE, elementName, InqElementType.BULKHEAD,
        String.format(Locale.ROOT,
            "Thread interrupted while waiting for Bulkhead '%s' permit", elementName));
  }

  /**
   * Creates a new exception with explicit concurrency values.
   *
   * @param callId             the call identifier
   * @param elementName        the bulkhead instance name
   * @param concurrentCalls    the number of in-flight calls at the time of interruption
   * @param maxConcurrentCalls the configured maximum
   * @deprecated Use {@link #InqBulkheadInterruptedException(String, String)} instead.
   * The concurrency values are read after the interrupt and are already stale —
   * they do not represent the state at the moment the interrupt occurred.
   */
  @Deprecated(since = "0.3.0", forRemoval = true)
  public InqBulkheadInterruptedException(String callId, String elementName,
                                         int concurrentCalls, int maxConcurrentCalls) {
    super(callId, CODE, elementName, InqElementType.BULKHEAD,
        String.format(Locale.ROOT,
            "Thread interrupted while waiting for Bulkhead '%s' permit (%d/%d concurrent calls)",
            elementName, concurrentCalls, maxConcurrentCalls));
  }
}
