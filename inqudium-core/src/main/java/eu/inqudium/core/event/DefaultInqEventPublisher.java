package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.exception.InqException;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Default implementation of {@link InqEventPublisher} that bridges per-element
 * consumers and an {@link InqEventExporterRegistry}.
 *
 * <p>Optimized for read-heavy operations: consumers are stored in an immutable array
 * wrapped in an {@link AtomicReference}. This guarantees lock-free, zero-allocation
 * publishing with optimal CPU cache locality.
 *
 * @since 0.1.0
 */
final class DefaultInqEventPublisher implements InqEventPublisher {

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(DefaultInqEventPublisher.class);

  // Pre-allocated empty array to avoid allocations when resetting to empty
  private static final ConsumerEntry[] EMPTY_CONSUMERS = new ConsumerEntry[0];

  private final String elementName;
  private final InqElementType elementType;
  private final InqEventExporterRegistry registry;

  /**
   * Copy-on-write array holding the local consumers. Array iteration is significantly
   * faster and more cache-friendly than traversing a ConcurrentHashMap.
   */
  private final AtomicReference<ConsumerEntry[]> consumers = new AtomicReference<>(EMPTY_CONSUMERS);

  /**
   * Per-instance subscription ID generator.
   */
  private final AtomicLong subscriptionCounter = new AtomicLong(0);

  DefaultInqEventPublisher(String elementName, InqElementType elementType,
                           InqEventExporterRegistry registry) {
    this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
    this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public void publish(InqEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    // Deliver to local consumers using a cache-friendly array iteration
    ConsumerEntry[] currentConsumers = consumers.get();
// In DefaultInqEventPublisher.publish()
    for (int i = 0; i < currentConsumers.length; i++) {
      InqEventConsumer consumer = currentConsumers[i].consumer();
      try {
        consumer.accept(event);
      } catch (Throwable t) {
        InqException.rethrowIfFatal(t);
        LOGGER.warn("[{}] Event consumer {} threw on event {}",
            event.getCallId(), consumer.getClass().getName(),
            event.getClass().getSimpleName(), t);
      }
    }
    // Forward to exporters
    try {
      registry.export(event);
    } catch (Throwable t) {
      InqException.rethrowIfFatal(t);
      LOGGER.warn("[{}] Exporter registry threw on event {}",
          event.getCallId(), event.getClass().getSimpleName(), t);
    }
  }

  @Override
  public InqSubscription onEvent(InqEventConsumer consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    long subscriptionId = subscriptionCounter.incrementAndGet();
    addConsumer(new ConsumerEntry(subscriptionId, consumer));
    return () -> removeConsumer(subscriptionId);
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
    long subscriptionId = subscriptionCounter.incrementAndGet();
    addConsumer(new ConsumerEntry(subscriptionId, wrapper));
    return () -> removeConsumer(subscriptionId);
  }

  private void addConsumer(ConsumerEntry entry) {
    consumers.updateAndGet(arr -> {
      ConsumerEntry[] newArr = Arrays.copyOf(arr, arr.length + 1);
      newArr[arr.length] = entry;
      return newArr;
    });
  }

  private void removeConsumer(long id) {
    consumers.updateAndGet(arr -> {
      int index = -1;
      for (int i = 0; i < arr.length; i++) {
        if (arr[i].id() == id) {
          index = i;
          break;
        }
      }

      // Not found or already removed (Idempotent behavior)
      if (index < 0) {
        return arr;
      }

      // If it's the last element, return the shared empty array to avoid allocation
      if (arr.length == 1) {
        return EMPTY_CONSUMERS;
      }

      // Create a new array without the cancelled consumer
      ConsumerEntry[] newArr = new ConsumerEntry[arr.length - 1];
      System.arraycopy(arr, 0, newArr, 0, index);
      System.arraycopy(arr, index + 1, newArr, index, arr.length - index - 1);
      return newArr;
    });
  }

  @Override
  public String toString() {
    return "InqEventPublisher{" +
        "elementName='" + elementName + '\'' +
        ", elementType=" + elementType +
        ", consumers=" + consumers.get().length +
        '}';
  }

  /**
   * Pairs a subscription ID with its consumer so we can identify it for removal.
   */
  private record ConsumerEntry(long id, InqEventConsumer consumer) {
  }
}