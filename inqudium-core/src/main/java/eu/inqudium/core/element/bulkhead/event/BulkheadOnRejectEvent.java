package eu.inqudium.core.element.bulkhead.event;

import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.element.bulkhead.strategy.RejectionReason;

import java.time.Instant;

/**
 * Emitted when a bulkhead permit request is denied.
 *
 * <p>The optional {@link RejectionContext} provides the exact state at the moment of
 * rejection, including the reason, the limit, and the active call count. It is
 * {@code null} only when the rejection was caused by a thread interrupt (no strategy
 * decision was made).
 *
 * @since 0.1.0
 */
public class BulkheadOnRejectEvent extends BulkheadEvent {

  private final RejectionContext rejectionContext;

  /**
   * Creates a reject event with a rejection context captured at the moment of rejection.
   *
   * @param callId           the call identifier
   * @param elementName      the bulkhead instance name
   * @param rejectionContext the rejection snapshot, or {@code null} if the rejection was
   *                         caused by a thread interrupt (no strategy decision was made)
   * @param timestamp        the event timestamp
   */
  public BulkheadOnRejectEvent(String callId, String elementName,
                               RejectionContext rejectionContext, Instant timestamp) {
    super(callId, elementName, timestamp);
    this.rejectionContext = rejectionContext;
  }

  /**
   * Creates a reject event with an explicit concurrent call count.
   *
   * @param callId          the call identifier
   * @param elementName     the bulkhead instance name
   * @param concurrentCalls the number of concurrent calls at rejection time
   * @param timestamp       the event timestamp
   * @deprecated Use {@link #BulkheadOnRejectEvent(String, String, RejectionContext, Instant)}.
   * The explicit value is a post-hoc snapshot that may already be stale.
   */
  @Deprecated(since = "0.3.0", forRemoval = true)
  public BulkheadOnRejectEvent(String callId, String elementName,
                               int concurrentCalls, Instant timestamp) {
    super(callId, elementName, timestamp);
    this.rejectionContext = RejectionContext.capacityReached(concurrentCalls, concurrentCalls);
  }

  /**
   * Returns the rejection context captured at the moment of rejection, or {@code null}
   * if the rejection was caused by a thread interrupt.
   *
   * @return the rejection snapshot, or {@code null} for interrupt-based rejections
   */
  public RejectionContext getRejectionContext() {
    return rejectionContext;
  }

  /**
   * Returns the reason the request was rejected, or {@code null} if the rejection
   * was caused by a thread interrupt.
   *
   * <p>Convenience accessor — equivalent to
   * {@code getRejectionContext() != null ? getRejectionContext().reason() : null}.
   *
   * @return the rejection reason, or {@code null}
   */
  public RejectionReason getRejectionReason() {
    return rejectionContext != null ? rejectionContext.reason() : null;
  }

  /**
   * Returns the number of concurrent calls at the time of rejection.
   *
   * @return the active call count, or {@code -1} if not available (interrupt case)
   * @deprecated Use {@link #getRejectionContext()} for the full snapshot.
   */
  @Deprecated(since = "0.3.0", forRemoval = true)
  public int getConcurrentCalls() {
    return rejectionContext != null ? rejectionContext.activeCallsAtDecision() : -1;
  }
}
