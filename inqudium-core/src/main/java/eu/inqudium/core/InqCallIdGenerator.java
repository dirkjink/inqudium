package eu.inqudium.core;

import java.util.UUID;

/**
 * Functional interface for generating unique call identifiers.
 *
 * <p>Every call through an Inqudium element receives a {@code callId} that
 * appears on all events (ADR-003) and in the context propagation (ADR-011).
 * By default, call IDs are random UUIDs. Override this for:
 *
 * <ul>
 *   <li><strong>Deterministic testing</strong> — predictable IDs make event assertions simple.</li>
 *   <li><strong>Trace integration</strong> — reuse the OpenTelemetry trace ID as the call ID.</li>
 *   <li><strong>Custom formats</strong> — ULIDs, Snowflake IDs, sequential counters.</li>
 * </ul>
 *
 * <h2>Production usage</h2>
 * <pre>{@code
 * // Default — random UUID (can be omitted)
 * var cb = CircuitBreaker.of("payment", config);
 *
 * // Custom — reuse OpenTelemetry trace ID
 * InqCallIdGenerator traceAware = () -> Span.current().getSpanContext().getTraceId();
 * }</pre>
 *
 * <h2>Test usage</h2>
 * <pre>{@code
 * var counter = new AtomicInteger(0);
 * InqCallIdGenerator sequential = () -> "call-" + counter.incrementAndGet();
 *
 * // Events will have callId "call-1", "call-2", etc.
 * }</pre>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface InqCallIdGenerator {

    /**
     * Generates a unique call identifier.
     *
     * <p>The returned value must be non-null and should be unique within
     * the scope of the application. Uniqueness is not enforced — duplicate
     * IDs will cause event correlation ambiguity but not functional failures.
     *
     * @return a unique call identifier
     */
    String generate();

    /**
     * Returns the default generator backed by {@link UUID#randomUUID()}.
     *
     * @return the UUID-based generator
     */
    static InqCallIdGenerator uuid() {
        return () -> UUID.randomUUID().toString();
    }
}
