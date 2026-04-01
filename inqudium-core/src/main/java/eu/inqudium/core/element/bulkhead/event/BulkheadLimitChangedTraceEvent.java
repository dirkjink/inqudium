package eu.inqudium.core.element.bulkhead.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Trace event published when an adaptive limit algorithm (e.g., Vegas or AIMD)
 * adjusts the maximum number of concurrent calls based on telemetry.
 *
 * @since 0.2.0
 */
public final class BulkheadLimitChangedTraceEvent extends BulkheadEvent {

  private final int oldLimit;
  private final int newLimit;
  private final long rttNanos;

  /**
   * Creates a new trace event for a bulkhead limit change.
   *
   * @param elementName the name of the bulkhead
   * @param oldLimit    the previous concurrency limit
   * @param newLimit    the newly calculated concurrency limit
   * @param rttNanos    the round-trip time in nanoseconds that triggered this change
   * @param timestamp   the exact time the limit was changed
   */
  public BulkheadLimitChangedTraceEvent(String callId,
                                        String elementName,
                                        int oldLimit,
                                        int newLimit,
                                        long rttNanos,
                                        Instant timestamp) {
    super(callId, elementName, timestamp);
    this.oldLimit = oldLimit;
    this.newLimit = newLimit;
    this.rttNanos = rttNanos;
  }

  public int getOldLimit() {
    return oldLimit;
  }

  public int getNewLimit() {
    return newLimit;
  }

  public long getRttNanos() {
    return rttNanos;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    BulkheadLimitChangedTraceEvent that = (BulkheadLimitChangedTraceEvent) o;
    return oldLimit == that.oldLimit &&
        newLimit == that.newLimit &&
        rttNanos == that.rttNanos &&
        Objects.equals(getCallId(), that.getCallId()) &&
        Objects.equals(getElementName(), that.getElementName()) &&
        Objects.equals(getTimestamp(), that.getTimestamp());
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        getCallId(),
        getElementName(),
        getTimestamp(),
        oldLimit,
        newLimit,
        rttNanos);
  }

  @Override
  public String toString() {
    return "BulkheadLimitChangedTraceEvent{" +
        "callId='" + getCallId() + '\'' +
        ", elementName='" + getElementName() + '\'' +
        ", timestamp=" + getTimestamp() +
        ", oldLimit=" + oldLimit +
        ", newLimit=" + newLimit +
        ", rttNanos=" + rttNanos +
        '}';
  }
}