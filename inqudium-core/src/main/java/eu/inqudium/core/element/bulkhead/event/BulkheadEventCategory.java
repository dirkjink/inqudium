package eu.inqudium.core.element.bulkhead.event;

import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;

/**
 * Categories of bulkhead events that can be independently enabled or disabled.
 *
 * <h2>Design philosophy</h2>
 * <p>The event system is a <b>production-grade diagnostic tracing</b> mechanism,
 * not the primary source of metrics. Metrics are delivered via polling-based
 * gauges (e.g., Micrometer {@code MeterBinder} reading
 * {@link eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy#concurrentCalls()})
 * which have zero per-call overhead. Events are the Flight Recorder you enable
 * when something is wrong.
 *
 * <p>The {@linkplain BulkheadEventConfig#standard() standard configuration}
 * disables all per-call event categories. Only rejection events are enabled,
 * because rejections are exceptional and operationally relevant. Lifecycle
 * and trace events are enabled on demand via the
 * {@linkplain BulkheadEventConfig#diagnostic() diagnostic configuration}.
 *
 * <h2>Categories</h2>
 *
 * <h3>{@link #LIFECYCLE}</h3>
 * <p>Controls {@link BulkheadOnAcquireEvent} and {@link BulkheadOnReleaseEvent}.
 * These fire on <em>every successful call</em> and provide a complete permit
 * timeline: when was a permit granted, when was it released, how many calls
 * were active at each point.
 *
 * <p><b>Cost:</b> ~80 bytes/op (two {@code Instant} objects + two event instances).
 * This overhead is acceptable for short diagnostic sessions but prohibitive for
 * always-on production telemetry. Use polling-based gauges for continuous metrics.
 *
 * <p><b>When to enable:</b> Investigating unexpected concurrency behavior,
 * debugging fairness issues, tracing permit lifecycle during incident analysis.
 *
 * <h3>{@link #REJECTION}</h3>
 * <p>Controls {@link BulkheadOnRejectEvent}. Emitted only when a permit request
 * is denied — an inherently exceptional event. Enabled in the standard
 * configuration because rejections indicate capacity pressure and should always
 * be observable. The per-rejection cost (one event + one {@code Instant}) is
 * irrelevant because the rejection path is already dominated by exception
 * creation and error handling.
 *
 * <h3>{@link #TRACE}</h3>
 * <p>Controls {@link BulkheadWaitTraceEvent} and {@link BulkheadRollbackTraceEvent}.
 * High-detail diagnostic events that capture wait durations and rollback causes.
 * The most granular category — useful for latency analysis and debugging
 * edge cases in the acquire path.
 *
 * @since 0.3.0
 */
public enum BulkheadEventCategory {

  /**
   * Acquire and release events — emitted on every successful call.
   *
   * <p>Diagnostic tracing of the permit lifecycle. Disabled by default
   * to avoid per-call allocation overhead (~80 B/op). Enable for
   * short diagnostic sessions when investigating concurrency issues.
   */
  LIFECYCLE,

  /**
   * Rejection events — emitted when a permit request is denied.
   *
   * <p>Enabled by default. Rejections are exceptional, operationally
   * relevant, and their per-event cost is negligible relative to
   * the rejection path itself.
   */
  REJECTION,

  /**
   * Trace-level diagnostic events (wait times, rollback details).
   *
   * <p>The most granular diagnostic category. Disabled by default.
   * Enable for latency analysis and edge-case debugging.
   */
  TRACE
}
