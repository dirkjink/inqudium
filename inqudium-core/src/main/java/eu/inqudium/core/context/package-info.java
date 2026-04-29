/**
 * Context propagation SPI — framework-agnostic capture, restore, and enrichment
 * of diagnostic context across execution boundaries.
 *
 * <p>This package defines the SPI that bridge modules implement to propagate their
 * specific context systems (SLF4J MDC, OpenTelemetry Baggage, Micrometer Observation)
 * through resilience elements. The SPI has no dependency on any context framework —
 * it is pure JDK (ADR-011).
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code InqContextPropagator} — SPI interface: {@code capture()}, {@code restore()},
 *       {@code enrich()}. Discovered via {@link java.util.ServiceLoader} following
 *       ADR-014 conventions.</li>
 *   <li>{@code InqContextSnapshot} — opaque marker interface for captured context state.</li>
 *   <li>{@code InqContextScope} — {@link java.lang.AutoCloseable} handle returned by {@code restore()}.
 *       Must be closed in a {@code try-with-resources} block to restore the previous context.</li>
 *   <li>{@code InqContextPropagation} — utility class encapsulating the capture/restore/enrich
 *       cycle. Element implementations use {@code activateFor(callId, elementName, elementType)}
 *       — a single line that handles all registered propagators.</li>
 *   <li>{@code InqContextPropagatorRegistry} — ServiceLoader + programmatic registration.
 *       Frozen after first access (ADR-014, Convention 5).</li>
 * </ul>
 *
 * <h2>Bridge modules</h2>
 * <p>Bridge modules provide {@code InqContextPropagator} implementations for specific
 * context systems. Adding the bridge JAR to the classpath is sufficient — ServiceLoader
 * auto-discovery handles registration.
 * <ul>
 *   <li>{@code inqudium-context-slf4j} — MDC propagation via {@code MdcContextPropagator}</li>
 *   <li>{@code inqudium-context-otel} — OpenTelemetry Baggage (planned)</li>
 *   <li>{@code inqudium-context-micrometer} — Micrometer Observation Scope (planned)</li>
 * </ul>
 *
 * @see eu.inqudium.core.event.InqEvent
 */
package eu.inqudium.core.context;
