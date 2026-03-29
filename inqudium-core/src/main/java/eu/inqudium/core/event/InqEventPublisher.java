package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.util.function.Consumer;

/**
 * Per-element event publisher contract.
 *
 * <p>Each element instance owns its own publisher. Events flow in two directions
 * from the publisher (ADR-003):
 * <ul>
 *   <li>To local consumers registered via {@link #onEvent}</li>
 *   <li>To global exporters registered in {@link InqEventExporterRegistry}</li>
 * </ul>
 *
 * <h2>Creating a publisher</h2>
 * <pre>{@code
 * // Inside element construction — automatic, no external wiring needed
 * var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER);
 * }</pre>
 *
 * <h2>Subscribing to events</h2>
 * <pre>{@code
 * circuitBreaker.getEventPublisher()
 *     .onEvent(CircuitBreakerOnStateTransitionEvent.class, event -> { ... });
 * }</pre>
 *
 * @since 0.1.0
 */
public interface InqEventPublisher {

  /**
   * Creates a new publisher for an element instance.
   *
   * <p>The publisher bridges local consumers (registered via {@link #onEvent})
   * and global exporters (registered in {@link InqEventExporterRegistry}).
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @return a new publisher wired to the global exporter registry
   */
  static InqEventPublisher create(String elementName, InqElementType elementType) {
    return new DefaultInqEventPublisher(elementName, elementType);
  }

  /**
   * Publishes an event to all registered consumers and global exporters.
   *
   * <p>This method must be thread-safe. Consumer exceptions are caught and
   * do not propagate to the element.
   *
   * @param event the event to publish
   */
  void publish(InqEvent event);

  /**
   * Registers a consumer for all events from this publisher.
   *
   * @param consumer the event consumer
   */
  void onEvent(InqEventConsumer consumer);

  /**
   * Registers a typed consumer that only receives events of the specified type.
   *
   * @param eventType the event class to filter on
   * @param consumer  the typed consumer
   * @param <E>       the event type
   */
  <E extends InqEvent> void onEvent(Class<E> eventType, Consumer<E> consumer);
}
