package eu.inqudium.core.context;

import eu.inqudium.core.InqElementType;

/**
 * SPI for propagating diagnostic context across execution boundaries.
 *
 * <p>Implementations bridge to specific context systems (SLF4J MDC, OpenTelemetry
 * Baggage, Micrometer Observation Scope). The SPI has no dependency on any context
 * framework — it is pure JDK (ADR-011).
 *
 * <p>Discovered via {@link java.util.ServiceLoader} following ADR-014 conventions.
 * Adding a bridge module JAR to the classpath is sufficient — ServiceLoader
 * auto-discovery handles registration.
 *
 * <h2>Implementation requirements</h2>
 * <ul>
 *   <li>Thread-safe — called concurrently from multiple elements.</li>
 *   <li>Capture must be fast — called on the hot path before every cross-boundary operation.</li>
 *   <li>Restore must be paired with close — every {@link #restore} must be followed by
 *       {@link InqContextScope#close()} in a finally block.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface InqContextPropagator {

    /**
     * Captures the current context from the calling thread/scope.
     *
     * @return an opaque snapshot that can be restored later on a different thread
     */
    InqContextSnapshot capture();

    /**
     * Restores a previously captured snapshot on the current thread/scope.
     *
     * @param snapshot the snapshot to restore (from a previous {@link #capture()} call)
     * @return a scope handle that must be closed to restore the previous context
     */
    InqContextScope restore(InqContextSnapshot snapshot);

    /**
     * Enriches the current context with Inqudium-specific entries.
     *
     * <p>Called after {@link #restore}, before the protected call executes.
     * The default implementation is a no-op — propagators that support enrichment
     * override this method.
     *
     * @param callId      the unique call identifier (ADR-003)
     * @param elementName the current element name
     * @param elementType the current element type
     */
    default void enrich(String callId, String elementName, InqElementType elementType) {
        // no-op by default
    }
}
