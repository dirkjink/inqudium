package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Default implementation of {@link InqEventPublisher} that bridges per-element
 * consumers and the global {@link InqEventExporterRegistry}.
 *
 * <p>When {@link #publish(InqEvent)} is called, the event flows to:
 * <ol>
 *   <li>All local consumers registered via {@link #onEvent}</li>
 *   <li>All global exporters registered in {@link InqEventExporterRegistry}</li>
 * </ol>
 *
 * <p>Consumer and exporter exceptions are caught and do not propagate to the
 * element. This implementation is thread-safe via {@link CopyOnWriteArrayList}
 * for the consumer list — optimized for frequent reads (publish) and rare
 * writes (subscription/cancellation).
 *
 * @since 0.1.0
 */
final class DefaultInqEventPublisher implements InqEventPublisher {

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(DefaultInqEventPublisher.class);

  private final String elementName;
  private final InqElementType elementType;
  private final List<InqEventConsumer> consumers = new CopyOnWriteArrayList<>();

  DefaultInqEventPublisher(String elementName, InqElementType elementType) {
    this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
    this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
  }

  @Override
  public void publish(InqEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    // Deliver to local consumers
    for (var consumer : consumers) {
      try {
        consumer.accept(event);
      } catch (Exception e) {
        // Consumer exceptions are swallowed — a broken consumer must never
        // affect the resilience element's operation (ADR-003, ADR-014 Convention 3)
        LOGGER.warn("[{}] Event consumer {} threw on event {}: {}",
            event.getCallId(), consumer.getClass().getName(),
            event.getClass().getSimpleName(), e.getMessage());
      }
    }

    // Forward to global exporters
    InqEventExporterRegistry.export(event);
  }

  @Override
  public InqSubscription onEvent(InqEventConsumer consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    consumers.add(consumer);
    return () -> consumers.remove(consumer);
  }

  @Override
  public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(consumer, "consumer must not be null");

    // Wrap the typed consumer in a filtering InqEventConsumer
    InqEventConsumer wrapper = event -> {
      if (eventType.isInstance(event)) {
        consumer.accept(eventType.cast(event));
      }
    };
    consumers.add(wrapper);
    return () -> consumers.remove(wrapper);
  }

  @Override
  public String toString() {
    return "InqEventPublisher{" +
        "elementName='" + elementName + '\'' +
        ", elementType=" + elementType +
        ", consumers=" + consumers.size() +
        '}';
  }
}
