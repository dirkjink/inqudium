package eu.inqudium.core.event;

import java.util.Set;

/**
 * SPI for exporting Inqudium events to external systems (Kafka, CloudEvents,
 * webhooks, custom monitoring).
 *
 * <p>Exporters are registered globally via {@link InqEventExporterRegistry} and
 * receive all events from all elements. Discovery follows ADR-014 conventions:
 * lazy on first access, Comparable-sorted, error-isolated, singleton lifecycle.
 *
 * <h2>Implementation requirements</h2>
 * <ul>
 *   <li><strong>Thread-safe</strong> — called concurrently from multiple elements.</li>
 *   <li><strong>Non-blocking</strong> — must not block the thread that emits the event.
 *       If the target system requires I/O, buffer internally.</li>
 *   <li><strong>Exception-safe</strong> — exceptions are caught by the registry and
 *       do not affect other exporters or the resilience element.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface InqEventExporter {

    /**
     * Called for every event emitted by any element.
     *
     * @param event the event to export
     */
    void export(InqEvent event);

    /**
     * Returns the set of event types this exporter is interested in.
     *
     * <p>If empty (the default), the exporter receives all event types.
     * If non-empty, only events whose class is assignable to one of the
     * returned types are forwarded — preventing unnecessary serialization
     * for exporters that only care about specific events.
     *
     * @return the set of subscribed event types, or empty for all
     */
    default Set<Class<? extends InqEvent>> subscribedEventTypes() {
        return Set.of();
    }
}
