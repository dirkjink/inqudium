package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.element.bulkhead.strategy.RejectionReason;
import eu.inqudium.core.exception.InqException;

/**
 * Thrown when a bulkhead rejects a call because the maximum number of concurrent
 * calls has been reached, the wait timeout has expired, or the CoDel algorithm
 * determined that the request waited too long.
 *
 * <p>No call was made to the downstream service — the bulkhead rejected the
 * request to prevent resource exhaustion (ADR-009, ADR-020).
 *
 * <p>The {@link #getRejectionContext()} provides the exact state at the moment of
 * rejection, captured inside the strategy's decision logic. Unlike post-hoc snapshots,
 * these values are guaranteed to reflect the true cause.
 *
 * @since 0.1.0
 */
public class InqBulkheadFullException extends InqException {

    /**
     * Call rejected — max concurrent calls reached.
     */
    public static final String CODE = InqElementType.BULKHEAD.errorCode(1);

    private final RejectionContext rejectionContext;

    /**
     * Creates a new exception with a {@link RejectionContext} captured at the moment of rejection.
     *
     * <p>This is the preferred constructor. The rejection context contains the reason,
     * the limit, the active call count, and timing information — all captured inside the
     * strategy's decision logic where they are guaranteed accurate.
     *
     * @param callId                      the call identifier
     * @param elementName                 the bulkhead instance name
     * @param rejectionContext            the rejection snapshot from the strategy
     * @param enableExceptionOptimization whether suppression is enabled or disabled, and whether the stack trace
     *                                    should be writable.
     */
    public InqBulkheadFullException(long chainId,
                                    long callId,
                                    String elementName,
                                    RejectionContext rejectionContext,
                                    boolean enableExceptionOptimization) {
        super(chainId,
                callId,
                CODE,
                elementName,
                InqElementType.BULKHEAD,
                "Bulkhead '" + elementName + "' rejected: " + rejectionContext,
                null,
                enableExceptionOptimization);
        this.rejectionContext = rejectionContext;
    }

    /**
     * Suppresses stack trace generation.
     *
     * <p>A bulkhead rejection is an expected flow-control signal, not a programming error.
     * The {@link #getRejectionContext()} already contains all diagnostic information
     * (reason, limit, active calls, timing). A stack trace would only tell the caller
     * "you were rejected inside the bulkhead facade" — which they already know.
     *
     * <p>The cost of {@code fillInStackTrace()} is substantial: it walks the entire call
     * stack, allocates {@code StackTraceElement[]} arrays, and resolves method names via
     * native calls. Under high rejection rates (which is exactly when bulkheads are doing
     * their job), this dominates the rejection path's CPU and allocation cost.
     *
     * <p>If a stack trace is needed for debugging, wrap the catch site:
     * <pre>{@code
     * catch (InqBulkheadFullException e) {
     *     throw new RuntimeException("unexpected rejection", e); // gets its own stack trace
     * }
     * }</pre>
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    /**
     * Returns the rejection context captured at the exact moment of rejection.
     *
     * @return the immutable rejection snapshot, never {@code null}
     */
    public RejectionContext getRejectionContext() {
        return rejectionContext;
    }

    /**
     * Returns the reason the bulkhead rejected the request.
     *
     * <p>Convenience accessor — equivalent to {@code getRejectionContext().reason()}.
     *
     * @return the rejection reason
     */
    public RejectionReason getRejectionReason() {
        return rejectionContext.reason();
    }

    /**
     * Returns the concurrency limit that was enforced at the moment of rejection.
     *
     * @return the limit at decision time
     */
    public int getLimitAtDecision() {
        return rejectionContext.limitAtDecision();
    }

    /**
     * Returns the number of concurrent calls at the moment of rejection.
     *
     * @return the active call count at decision time
     * @deprecated Use {@link #getRejectionContext()} for the full snapshot.
     */
    @Deprecated(since = "0.3.0", forRemoval = true)
    public int getConcurrentCalls() {
        return rejectionContext.activeCallsAtDecision();
    }

    /**
     * Returns the concurrency limit at the moment of rejection.
     *
     * @return the limit at decision time
     * @deprecated Use {@link #getLimitAtDecision()} or {@link #getRejectionContext()}.
     */
    @Deprecated(since = "0.3.0", forRemoval = true)
    public int getMaxConcurrentCalls() {
        return rejectionContext.limitAtDecision();
    }
}
