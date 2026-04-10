package eu.inqudium.core.element.bulkhead.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Trace event published when a bulkhead permit was temporarily acquired but had to be
 * rolled back immediately. This typically happens if the subsequent event publishing
 * crashes, ensuring no permits are permanently leaked.
 *
 * @since 0.2.0
 */
public final class BulkheadRollbackTraceEvent extends BulkheadEvent {

    private final String errorType;

    /**
     * Creates a new trace event for a bulkhead permit rollback.
     *
     * @param callId      the unique identifier of the call that requested the permit
     * @param elementName the name of the bulkhead
     * @param errorType   the class name of the exception that triggered the rollback
     * @param timestamp   the exact time the rollback occurred
     */
    public BulkheadRollbackTraceEvent(long chainId, long callId, String elementName, String errorType, Instant timestamp) {
        super(chainId, callId, elementName, timestamp);
        this.errorType = errorType;
    }

    public String getErrorType() {
        return errorType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BulkheadRollbackTraceEvent that = (BulkheadRollbackTraceEvent) o;
        return Objects.equals(getCallId(), that.getCallId()) &&
                Objects.equals(getElementName(), that.getElementName()) &&
                Objects.equals(getTimestamp(), that.getTimestamp()) &&
                Objects.equals(errorType, that.errorType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getCallId(),
                getElementName(),
                getTimestamp(),
                errorType);
    }

    @Override
    public String toString() {
        return "BulkheadRollbackTraceEvent{" +
                "callId='" + getCallId() + '\'' +
                ", elementName='" + getElementName() + '\'' +
                ", timestamp=" + getTimestamp() +
                ", errorType='" + errorType + '\'' +
                '}';
    }
}
