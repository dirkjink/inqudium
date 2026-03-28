/**
 * Event system — the canonical observability bus for all Inqudium elements.
 *
 * <p>Every element emits events through its own {@code InqEventPublisher}. Events
 * are purely observational — they document what happened but never control element
 * behavior (ADR-003). The system operates at two scopes:
 *
 * <ul>
 *   <li><strong>Per-element publishers</strong> — targeted consumption. A consumer
 *       subscribes to a specific element's publisher and receives only that element's
 *       events. This is the primary API for dashboards, custom listeners, and
 *       element-specific monitoring.</li>
 *   <li><strong>Global exporters</strong> — cross-cutting export via {@code InqEventExporter}
 *       SPI. Registered through {@code InqEventExporterRegistry}, exporters receive all
 *       events from all elements. Used for Kafka, JFR, Micrometer, and other
 *       infrastructure-level observability.</li>
 * </ul>
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code InqEvent} — abstract base carrying {@code callId}, {@code elementName},
 *       {@code elementType}, and {@code timestamp} on every event (ADR-003).</li>
 *   <li>{@code InqEventPublisher} — contract + factory: {@code create(name, elementType)}.
 *       Each element creates its own publisher during construction.</li>
 *   <li>{@code DefaultInqEventPublisher} — bridges local consumers and global exporters.
 *       This is the only point where the two scopes meet.</li>
 *   <li>{@code InqEventConsumer} — listener interface for per-element subscription.</li>
 *   <li>{@code InqEventExporter} — SPI for external event export. Discovered via
 *       {@link java.util.ServiceLoader} following ADR-014 conventions.</li>
 *   <li>{@code InqEventExporterRegistry} — global exporter registration and lifecycle.</li>
 *   <li>{@code InqProviderErrorEvent} — emitted when a ServiceLoader provider fails (ADR-014).</li>
 * </ul>
 *
 * <h2>Call identity</h2>
 * <p>Every event carries a {@code callId} — a unique identifier generated at the
 * outermost pipeline element and propagated through all inner elements. Filtering
 * by {@code callId} reconstructs the complete lifecycle of a single call across
 * all resilience elements.
 *
 * @see eu.inqudium.core.context.InqContextPropagator
 * @see eu.inqudium.core.compatibility.InqCompatibilityEvent
 */
package eu.inqudium.core.event;
