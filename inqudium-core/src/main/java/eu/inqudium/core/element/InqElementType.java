package eu.inqudium.core.element;

import java.util.Locale;

/**
 * Identifies the resilience element kinds.
 *
 * <p>Each element type carries a two-character symbol from the periodic table
 * of resilience and can generate structured error codes in the format
 * {@code INQ-XX-NNN} (ADR-021).
 *
 * <p>Additionally, each type defines a {@link #defaultPipelineOrder()} that
 * establishes the standard nesting when multiple elements are composed into a
 * pipeline. Lower values produce outermost layers — the element that should
 * act <em>first</em> (and release <em>last</em>) has the lowest order.</p>
 *
 * <h3>Standard pipeline ordering (ADR-017)</h3>
 * <pre>
 *   TIME_LIMITER (100)                 ← outermost: bound total caller wait time including retries (ADR-010)
 *     └── TRAFFIC_SHAPER (200)             ← smooth bursts into steady flow before rate limiting
 *           └── RATE_LIMITER (300)              ← throttle before breaker's sliding window
 *                 └── BULKHEAD (400)                ← concurrency bounded at pipeline level
 *                       └── CIRCUIT_BREAKER (500)       ← sees each retry attempt individually
 *                             └── RETRY (600)               ← innermost: retries only the call
 * </pre>
 *
 * <p>Cache is deliberately excluded from this ordering — it is not a pipeline
 * element but a separate interceptor that short-circuits the entire call on a
 * hit (see ADR-017, ADR-024). {@code CACHE} retains its symbol and error codes
 * but has a {@code defaultPipelineOrder()} of 0.</p>
 *
 * <p>The values are spaced by 100 to leave room for custom or
 * application-specific elements that need to be inserted between standard
 * layers. The default order is a recommendation for the common case and can
 * be overridden per-provider or replaced entirely via
 * {@link eu.inqudium.aspect.pipeline.PipelineOrdering}.</p>
 *
 * <p>Used as a discriminator in events (ADR-003), exceptions (ADR-009),
 * context propagation (ADR-011), and pipeline ordering (ADR-017).
 * Every {@link InqElement} instance carries an {@code InqElementType}.
 *
 * @since 0.1.0
 */
public enum InqElementType {

    /**
     * Circuit breaker — cascading failure shield.
     *
     * <p>Default pipeline order: 500. Inside bulkhead, outside retry: sees
     * each individual retry attempt in its sliding window, enabling fast
     * failure detection.</p>
     */
    CIRCUIT_BREAKER("CB", 500),

    /**
     * Retry — configurable backoff on transient failures.
     *
     * <p>Default pipeline order: 600. Innermost by default: retries only
     * the actual call. The circuit-breaker counts each attempt individually,
     * and the time-limiter bounds total wait time across all attempts.</p>
     */
    RETRY("RT", 600),

    /**
     * Rate limiter — throughput control via token bucket.
     *
     * <p>Default pipeline order: 300. Outside bulkhead and circuit-breaker:
     * controls the rate at which calls enter the pipeline. Retries don't
     * consume additional permits since retry sits inside.</p>
     */
    RATE_LIMITER("RL", 300),

    /**
     * Bulkhead — failure isolation via concurrency limiting.
     *
     * <p>Default pipeline order: 400. Inside rate-limiter, outside
     * circuit-breaker: concurrency is bounded at the pipeline level,
     * not just the call level.</p>
     */
    BULKHEAD("BH", 400),

    /**
     * Time limiter — caller wait time bound, no thread interrupt.
     *
     * <p>Default pipeline order: 100. Outside traffic-shaper and retry
     * (ADR-010): bounds total caller wait time including shaping delays
     * and all retry attempts. For per-attempt time bounding, use a higher
     * order value to place the time limiter inside retry.</p>
     */
    TIME_LIMITER("TL", 100),

    /**
     * Traffic shaper — burst smoothing via controlled delay.
     *
     * <p>Default pipeline order: 200. Inside time-limiter (shaping delays
     * are covered by the caller's time budget), outside rate-limiter
     * (smooths bursts into a steady flow before tokens are consumed).</p>
     *
     * @since 0.8.0
     */
    TRAFFIC_SHAPER("TS", 200),

    /**
     * Cache — response caching to reduce load.
     *
     * <p>Cache is <strong>not a pipeline element</strong>. Unlike the other
     * resilience elements which wrap the method call with additional behavior,
     * a cache interceptor (e.g. Spring {@code @Cacheable}) <em>replaces</em>
     * the entire method execution on a hit — the pipeline is never entered.</p>
     *
     * <p>This type is retained for error codes (ADR-021) and event
     * identification (ADR-003), but its {@code defaultPipelineOrder()} is 0
     * (same as {@link #NO_ELEMENT}). The ordering of cache interceptors
     * relative to the Inqudium pipeline is governed by ADR-024.</p>
     */
    CACHE("CA", 0),

    /**
     * No element — used for system-level codes outside any specific element
     * (pipeline, ServiceLoader, registry).
     *
     * <p>Default pipeline order: 0. Not intended for pipeline composition —
     * the value is a placeholder.</p>
     */
    NO_ELEMENT("XX", 0);

    private final String symbol;

    /**
     * Higher precedence for smaller numbers — lowest value becomes outermost layer.
     */
    private final int defaultPipelineOrder;

    InqElementType(String symbol, int defaultPipelineOrder) {
        this.symbol = symbol;
        this.defaultPipelineOrder = defaultPipelineOrder;
    }

    /**
     * Returns the two-character element symbol (e.g. "CB", "RT", "RL").
     *
     * <p>Symbols are used in error codes (ADR-021) and as compact identifiers
     * in logs and metrics.
     *
     * @return the two-character symbol
     */
    public String symbol() {
        return symbol;
    }

    /**
     * Generates a structured error code for this element type.
     *
     * <p>Error codes follow the format {@code INQ-XX-NNN} where {@code XX} is
     * the element symbol and {@code NNN} is a zero-padded three-digit number.
     *
     * <ul>
     *   <li>{@code errorCode(1)} → {@code "INQ-CB-001"}</li>
     *   <li>{@code errorCode(0)} → {@code "INQ-CB-000"} (reserved for wrapped checked exceptions)</li>
     * </ul>
     *
     * @param number the error number (0–999)
     * @return the formatted error code
     * @throws IllegalArgumentException if number is negative or greater than 999
     */
    public String errorCode(int number) {
        if (number < 0 || number > 999) {
            throw new IllegalArgumentException("Error number must be 0-999, got: " + number);
        }
        return String.format(Locale.ROOT, "INQ-%s-%03d", symbol, number);
    }

    /**
     * Returns the recommended pipeline order for this element type.
     *
     * <p>Lower values produce outermost layers. The spacing between standard
     * types allows custom elements to be inserted at intermediate positions.</p>
     *
     * @return the default pipeline priority
     */
    public int defaultPipelineOrder() {
        return defaultPipelineOrder;
    }
}
