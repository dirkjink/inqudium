package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.element.bulkhead.event.BulkheadEventCategory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable configuration that controls which {@link BulkheadEventCategory categories}
 * of bulkhead events are enabled.
 *
 * <p>The enabled state is resolved at construction time into plain {@code boolean}
 * fields — one per category. This makes the hot-path guard a single field read
 * with no set lookup, no virtual dispatch, and no allocation.
 *
 * <h2>Design philosophy</h2>
 * <p>The event system is a <b>diagnostic tracing mechanism</b>, not the primary
 * source of metrics. Production metrics (concurrent calls, available permits,
 * rejection counts) are delivered via polling-based gauges with zero per-call
 * overhead. Events provide deep per-call context for troubleshooting.
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li>{@link #standard()} — <b>the default</b>. Only rejection events are enabled.
 *       Zero allocation on the happy path. Use this in production.</li>
 *   <li>{@link #diagnostic()} — all event categories enabled. Use when investigating
 *       concurrency issues, unexpected rejections, or latency anomalies.
 *       Expect ~80 B/op overhead from lifecycle events.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Production (default) — zero happy-path overhead
 * BulkheadEventConfig config = BulkheadEventConfig.standard();
 *
 * // Troubleshooting — full per-call tracing
 * BulkheadEventConfig config = BulkheadEventConfig.diagnostic();
 *
 * // Custom: rejections + trace, no lifecycle
 * BulkheadEventConfig config = BulkheadEventConfig.of(
 *     BulkheadEventCategory.REJECTION,
 *     BulkheadEventCategory.TRACE
 * );
 * }</pre>
 *
 * @since 0.3.0
 * @deprecated Replaced by {@link eu.inqudium.config.snapshot.BulkheadEventConfig} (the new
 *             record in the configuration architecture, ADR-030, with the same simple name in
 *             a different package). Retained because the legacy {@link InqBulkheadConfig}
 *             still carries it; removed alongside the legacy resilience surface (top-level
 *             {@code Resilience} DSL, pre-{@code Inqudium.configure()} bulkhead and
 *             circuit-breaker stacks) once those callers are migrated to the new architecture.
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public final class BulkheadEventConfig {

    private static final BulkheadEventConfig STANDARD = new BulkheadEventConfig(Collections.emptySet());

    private static final BulkheadEventConfig DIAGNOSTIC = new BulkheadEventConfig(
            EnumSet.allOf(BulkheadEventCategory.class));

    private final boolean lifecycleEnabled;
    private final boolean rejectionEnabled;
    private final boolean traceEnabled;

    private BulkheadEventConfig(Set<BulkheadEventCategory> enabled) {
        this.lifecycleEnabled = enabled.contains(BulkheadEventCategory.LIFECYCLE);
        this.rejectionEnabled = enabled.contains(BulkheadEventCategory.REJECTION);
        this.traceEnabled = enabled.contains(BulkheadEventCategory.TRACE);
    }

    /**
     * Standard production configuration — only rejection events enabled.
     *
     * <p>This is the <b>recommended default</b>. The happy path (acquire → execute →
     * release) produces zero event allocations. Rejection events fire only when
     * the bulkhead denies a request, which is inherently exceptional.
     *
     * <p>Production metrics (concurrent calls, available permits) are delivered
     * by polling-based gauges via the strategy's introspection methods —
     * not by events. This configuration reflects that separation.
     *
     * @return the standard production event configuration
     */
    public static BulkheadEventConfig standard() {
        return STANDARD;
    }

    /**
     * Diagnostic configuration — all event categories enabled.
     *
     * <p>Enables lifecycle events (acquire/release on every call), rejection events,
     * and trace events (wait durations, rollback details). Provides a complete
     * per-call timeline for troubleshooting.
     *
     * <p><b>Overhead:</b> ~80 B/op from lifecycle events (two {@code Instant} objects +
     * two event instances per successful call). Suitable for short diagnostic sessions,
     * not for always-on production use.
     *
     * <p><b>When to use:</b> Investigating unexpected rejections, debugging fairness
     * issues, analyzing permit wait times, tracing concurrency anomalies during
     * incident response.
     *
     * @return the full diagnostic event configuration
     */
    public static BulkheadEventConfig diagnostic() {
        return DIAGNOSTIC;
    }

    /**
     * Custom set of enabled categories.
     *
     * @param first     the first enabled category
     * @param remaining additional enabled categories
     * @return an immutable event configuration
     */
    public static BulkheadEventConfig of(BulkheadEventCategory first,
                                         BulkheadEventCategory... remaining) {
        return new BulkheadEventConfig(EnumSet.of(first, remaining));
    }

    /**
     * {@code true} if acquire and release events should be created and published.
     *
     * <p>Disabled in {@link #standard()} mode. When {@code false}, the facade
     * skips all lifecycle event creation — no {@code Instant} allocation, no
     * event object construction, no publish attempt.
     */
    public boolean isLifecycleEnabled() {
        return lifecycleEnabled;
    }

    /**
     * {@code true} if rejection events should be created and published.
     *
     * <p>Enabled in both {@link #standard()} and {@link #diagnostic()} mode.
     */
    public boolean isRejectionEnabled() {
        return rejectionEnabled;
    }

    /**
     * {@code true} if trace-level diagnostic events should be created and published.
     *
     * <p>Disabled in {@link #standard()} mode. When {@code false}, the facade
     * also skips the {@code nanoTimeSource} call for wait-time measurement,
     * eliminating an additional native call on the happy path.
     */
    public boolean isTraceEnabled() {
        return traceEnabled;
    }
}
