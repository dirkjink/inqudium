package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Default implementation of {@link InqEventPublisher} that bridges per-element
 * consumers and an {@link InqEventExporterRegistry}.
 *
 * <p>When {@link #publish(InqEvent)} is called, the event flows to:
 * <ol>
 *   <li>All local consumers registered via {@link #onEvent}</li>
 *   <li>All exporters in the associated {@link InqEventExporterRegistry}</li>
 * </ol>
 *
 * <p>Consumer and exporter exceptions are caught and do not propagate to the
 * element. Subscriptions are identified by UUID — cancellation removes by key,
 * not by object identity.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for the consumer map — weakly
 * consistent iteration during publish, lock-free reads and writes.
 *
 * @since 0.1.0
 */
final class DefaultInqEventPublisher implements InqEventPublisher {

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(DefaultInqEventPublisher.class);

  private final String elementName;
  private final InqElementType elementType;
  private final InqEventExporterRegistry registry;
  private final ConcurrentHashMap<String, InqEventConsumer> consumers = new ConcurrentHashMap<>();

  DefaultInqEventPublisher(String elementName, InqElementType elementType,
                           InqEventExporterRegistry registry) {
    this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
    this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }

  @Override
  public void publish(InqEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    // Deliver to local consumers
    for (var consumer : consumers.values()) {
      try {
        consumer.accept(event);
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("[{}] Event consumer {} threw on event {}: {}",
            event.getCallId(), consumer.getClass().getName(),
            event.getClass().getSimpleName(), t.getMessage());
      }
    }

    // Forward to exporters in the associated registry
    registry.export(event);
  }

  @Override
  public InqSubscription onEvent(InqEventConsumer consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    var subscriptionId = UUID.randomUUID().toString();
    consumers.put(subscriptionId, consumer);
    return () -> consumers.remove(subscriptionId);
  }

  @Override
  public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(consumer, "consumer must not be null");

    InqEventConsumer wrapper = event -> {
      if (eventType.isInstance(event)) {
        consumer.accept(eventType.cast(event));
      }
    };
    var subscriptionId = UUID.randomUUID().toString();
    consumers.put(subscriptionId, wrapper);
    return () -> consumers.remove(subscriptionId);
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
