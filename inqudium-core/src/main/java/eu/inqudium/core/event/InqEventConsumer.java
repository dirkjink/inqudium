package eu.inqudium.core.event;

/**
 * Functional interface for consuming events from an {@link InqEventPublisher}.
 *
 * <p>Consumers are registered per-element instance and receive only events from
 * that element. For cross-cutting event consumption, use {@link InqEventExporter}.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface InqEventConsumer {

    /**
     * Called when an event is published.
     *
     * <p>Implementations must be thread-safe — events may be published from
     * any thread depending on the paradigm. Implementations must not throw —
     * exceptions are caught and logged but do not affect the element's operation.
     *
     * @param event the published event
     */
    void accept(InqEvent event);
}
