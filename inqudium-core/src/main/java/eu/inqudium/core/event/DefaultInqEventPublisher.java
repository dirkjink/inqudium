package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * element. Subscriptions are identified by UUID — cancellation removes by key,
 * not by object identity. This avoids reliance on lambda equality semantics.
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
  private final ConcurrentHashMap<String, InqEventConsumer> consumers = new ConcurrentHashMap<>();

  DefaultInqEventPublisher(String elementName, InqElementType elementType) {
    this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
    this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
  }

  /**
   * Rethrows errors that indicate a fatal JVM condition which must not be swallowed.
   */
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
        // Non-fatal errors are swallowed — a broken consumer must never
        // affect the resilience element's operation (ADR-003, ADR-014 Convention 3)
        LOGGER.warn("[{}] Event consumer {} threw on event {}: {}",
            event.getCallId(), consumer.getClass().getName(),
            event.getClass().getSimpleName(), t.getMessage());
      }
    }

    // Forward to global exporters
    InqEventExporterRegistry.export(event);
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

    // Wrap the typed consumer in a filtering InqEventConsumer
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
