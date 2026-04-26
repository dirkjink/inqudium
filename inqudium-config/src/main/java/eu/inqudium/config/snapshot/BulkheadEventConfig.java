package eu.inqudium.config.snapshot;

/**
 * Per-event flags gating which bulkhead events the runtime publishes.
 *
 * <p>Events are opt-in. The default supplied by {@code BulkheadBuilderBase} is
 * {@link #disabled()} so the hot path stays unweighted unless the user asks for it. Once any
 * flag is on, the bulkhead pays the publish cost only for the specific events flagged —
 * unflagged events skip the call site without constructing a payload.
 *
 * <p>The five flags follow the events the pre-refactor {@code ImperativeBulkhead} already
 * publishes:
 *
 * <ul>
 *   <li>{@code onAcquire} — gates {@code BulkheadOnAcquireEvent} after a successful permit
 *       grant.</li>
 *   <li>{@code onRelease} — gates {@code BulkheadOnReleaseEvent} on the finally-release
 *       branch.</li>
 *   <li>{@code onReject} — gates {@code BulkheadOnRejectEvent} on the rejection path before
 *       throwing.</li>
 *   <li>{@code waitTrace} — gates {@code BulkheadWaitTraceEvent} around {@code tryAcquire}
 *       when the configured wait is non-zero.</li>
 *   <li>{@code rollbackTrace} — gates {@code BulkheadRollbackTraceEvent} when an event publish
 *       itself fails after acquire and the permit must be rolled back.</li>
 * </ul>
 *
 * <p>The record is paradigm-agnostic. Reactive and other paradigms can reuse the same gating
 * decisions when their own bulkhead variants come online.
 *
 * @param onAcquire     publish {@code BulkheadOnAcquireEvent}.
 * @param onRelease     publish {@code BulkheadOnReleaseEvent}.
 * @param onReject      publish {@code BulkheadOnRejectEvent}.
 * @param waitTrace     publish {@code BulkheadWaitTraceEvent}.
 * @param rollbackTrace publish {@code BulkheadRollbackTraceEvent}.
 */
public record BulkheadEventConfig(
        boolean onAcquire,
        boolean onRelease,
        boolean onReject,
        boolean waitTrace,
        boolean rollbackTrace) {

    private static final BulkheadEventConfig DISABLED =
            new BulkheadEventConfig(false, false, false, false, false);

    private static final BulkheadEventConfig ALL_ENABLED =
            new BulkheadEventConfig(true, true, true, true, true);

    /**
     * @return the all-off configuration. The default supplied by
     *         {@code BulkheadBuilderBase} when the user does not explicitly opt into events.
     */
    public static BulkheadEventConfig disabled() {
        return DISABLED;
    }

    /**
     * @return the all-on configuration. Useful for tests, diagnostic snapshots, and dashboards
     *         that want every event for visibility.
     */
    public static BulkheadEventConfig allEnabled() {
        return ALL_ENABLED;
    }

    /**
     * @return {@code true} iff at least one event flag is on. The bulkhead can use this to
     *         skip the publisher lookup entirely when the answer is no.
     */
    public boolean anyEnabled() {
        return onAcquire || onRelease || onReject || waitTrace || rollbackTrace;
    }
}
