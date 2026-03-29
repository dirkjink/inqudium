package eu.inqudium.core;

import java.util.Locale;

/**
 * Identifies the six resilience element kinds.
 *
 * <p>Each element type carries a two-character symbol from the periodic table
 * of resilience and can generate structured error codes in the format
 * {@code INQ-XX-NNN} (ADR-021).
 *
 * <p>Used as a discriminator in events (ADR-003), exceptions (ADR-009),
 * context propagation (ADR-011), and pipeline ordering (ADR-017).
 * Every {@link InqElement} instance carries an {@code InqElementType}.
 *
 * @since 0.1.0
 */
public enum InqElementType {

    /** Circuit breaker — cascading failure shield. */
    CIRCUIT_BREAKER("CB"),

    /** Retry — configurable backoff on transient failures. */
    RETRY("RT"),

    /** Rate limiter — throughput control via token bucket. */
    RATE_LIMITER("RL"),

    /** Bulkhead — failure isolation via concurrency limiting. */
    BULKHEAD("BH"),

    /** Time limiter — caller wait time bound, no thread interrupt. */
    TIME_LIMITER("TL"),

    /** Cache — response caching to reduce load. */
    CACHE("CA"),

    /** No element — used for system-level codes outside any specific element (pipeline, ServiceLoader, registry). */
    NO_ELEMENT("XX");

    private final String symbol;

    InqElementType(String symbol) {
        this.symbol = symbol;
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
}
