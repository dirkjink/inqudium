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
 * <h3>Standard pipeline ordering</h3>
 * <pre>
 *   CACHE (50)                ← outermost: return cached result before any resilience work
 *     └── BULKHEAD (100)          ← reject early, before expensive downstream work
 *           └── CIRCUIT_BREAKER (200) ← fail fast if downstream is known-broken
 *                 └── RATE_LIMITER (300)  ← throttle outgoing call rate
 *                       └── RETRY (400)       ← retry transient failures
 *                             └── TIME_LIMITER (500) ← bound each individual attempt
 * </pre>
 *
 * <p>The values are spaced by 50–100 to leave room for custom or
 * application-specific elements that need to be inserted between standard
 * layers. The default order is a recommendation for the common case and can
 * be overridden per-provider.</p>
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
     * <p>Default pipeline order: 200. Wraps retry so that sustained failures
     * trip the breaker before retry budgets are exhausted.</p>
     */
    CIRCUIT_BREAKER("CB", 200),

    /**
     * Retry — configurable backoff on transient failures.
     *
     * <p>Default pipeline order: 400. Inside circuit-breaker so that each
     * attempt is visible to the breaker's failure counter.</p>
     */
    RETRY("RT", 400),

    /**
     * Rate limiter — throughput control via token bucket.
     *
     * <p>Default pipeline order: 300. Between circuit-breaker and retry:
     * a tripped breaker short-circuits before consuming rate tokens; retries
     * each consume a token independently.</p>
     */
    RATE_LIMITER("RL", 300),

    /**
     * Bulkhead — failure isolation via concurrency limiting.
     *
     * <p>Default pipeline order: 100. Rejects immediately when capacity is
     * exhausted, avoiding work in inner layers.</p>
     */
    BULKHEAD("BH", 100),

    /**
     * Time limiter — caller wait time bound, no thread interrupt.
     *
     * <p>Default pipeline order: 500. Innermost by default: bounds each
     * individual attempt. To bound total wall-clock time across retries,
     * use a lower order value to place the time limiter outside retry.</p>
     */
    TIME_LIMITER("TL", 500),

    /**
     * Cache — response caching to reduce load.
     *
     * <p>Default pipeline order: 50. Outermost by default: returns a cached
     * result before any resilience work (permit acquisition, rate tokens,
     * etc.) is performed.</p>
     */
    CACHE("CA", 50),

    /**
     * No element — used for system-level codes outside any specific element
     * (pipeline, ServiceLoader, registry).
     *
     * <p>Default pipeline order: 0. Not intended for pipeline composition —
     * the value is a placeholder.</p>
     */
    NO_ELEMENT("XX", 0);

    private final String symbol;

    /** Higher precedence for smaller numbers — lowest value becomes outermost layer. */
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
