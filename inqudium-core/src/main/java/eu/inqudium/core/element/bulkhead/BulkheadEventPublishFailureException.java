package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.exception.InqException;

/**
 * Thrown when a bulkhead-event publish itself fails after a permit has already been acquired.
 *
 * <p>The acquire-and-publish sequence is not atomic: a permit grant by the bulkhead's strategy
 * happens-before the publish of {@code BulkheadOnAcquireEvent} (and, when the wait-trace flag
 * is on, of the success-branch {@code BulkheadWaitTraceEvent}). If a subscriber on either of
 * those events throws — listener defect, downstream exporter outage, OutOfMemoryError under
 * stress — the bulkhead would leak a permit unless it actively rolls back. This exception is
 * the result of that rollback: the permit has been released, the rollback-trace event has been
 * best-effort published (when the {@code rollbackTrace} flag is on), and the original publish
 * failure is wrapped here as the {@linkplain #getCause() cause}.
 *
 * <p>Receiving this exception means the user lambda <em>did not run</em> — the bulkhead never
 * called the downstream chain. Callers can distinguish "rejected by capacity"
 * ({@link InqBulkheadFullException}) from "accepted by capacity but observability stack failed"
 * by exception type alone, which avoids the alternative of either swallowing the failure
 * silently or surfacing a bare {@code RuntimeException} from a subscriber.
 *
 * @since 0.7.0
 */
public class BulkheadEventPublishFailureException extends InqException {

    /** Bulkhead permit acquired but a follow-up event publish failed and the permit was rolled back. */
    public static final String CODE = InqElementType.BULKHEAD.errorCode(3);

    private final String failedEventType;

    /**
     * @param chainId         the chain identifier of the call.
     * @param callId          the call identifier of the call.
     * @param elementName     the bulkhead instance name.
     * @param failedEventType the simple class name of the event whose publish triggered the
     *                        rollback (e.g. {@code "BulkheadOnAcquireEvent"}); never null.
     * @param cause           the underlying publisher / subscriber failure; non-null. Becomes
     *                        this exception's {@linkplain #getCause() cause} so its stack trace
     *                        survives the wrap.
     */
    public BulkheadEventPublishFailureException(
            long chainId,
            long callId,
            String elementName,
            String failedEventType,
            Throwable cause) {
        super(chainId,
                callId,
                CODE,
                elementName,
                InqElementType.BULKHEAD,
                "Bulkhead '" + elementName + "' rolled back permit after "
                        + failedEventType + " publish failed: " + cause,
                cause,
                false);
        this.failedEventType = failedEventType;
    }

    /**
     * @return the simple class name of the event whose publish triggered the rollback.
     *         Convenience accessor for tooling that wants to fan out by event type without
     *         walking the cause chain.
     */
    public String getFailedEventType() {
        return failedEventType;
    }
}
