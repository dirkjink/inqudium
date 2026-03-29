package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 * <h2>Error handling in publish</h2>
 * <p>All errors — including fatal ones like {@link LinkageError} — are caught
 * during the consumer loop so that every consumer and every global exporter
 * gets a chance to see the event. If a fatal error occurs, it is deferred
 * and rethrown <strong>after</strong> the entire publish cycle completes.
 * This ensures the event chain is never silently truncated.
 *
 * <h2>Subscription identity</h2>
 * <p>Subscriptions are keyed by a monotonic counter (not UUID) — zero allocation,
 * zero GC pressure for high-frequency subscribe/unsubscribe patterns.
 *
 * @since 0.1.0
 */
final class DefaultInqEventPublisher implements InqEventPublisher {

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(DefaultInqEventPublisher.class);

  /**
   * Monotonic subscription ID generator — lighter than UUID.randomUUID().
   */
  private static final AtomicLong SUBSCRIPTION_COUNTER = new AtomicLong(0);

  private final String elementName;
  private final InqElementType elementType;
  private final InqEventExporterRegistry registry;
  private final ConcurrentHashMap<Long, InqEventConsumer> consumers = new ConcurrentHashMap<>();

  DefaultInqEventPublisher(String elementName, InqElementType elementType,
                           InqEventExporterRegistry registry) {
    this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
    this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  private static boolean isFatal(Throwable t) {
    return t instanceof VirtualMachineError
        || t instanceof LinkageError;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void rethrowFatal(Throwable t) throws T {
    throw (T) t;
  }

  @Override
  public void publish(InqEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    // Collect the first fatal error — rethrow AFTER all consumers and exporters
    // have had a chance to see the event (Fix #3)
    Throwable deferred = null;

    // Deliver to local consumers
    for (var consumer : consumers.values()) {
      try {
        consumer.accept(event);
      } catch (Throwable t) {
        if (isFatal(t) && deferred == null) {
          deferred = t;
        }
        LOGGER.warn("[{}] Event consumer {} threw on event {}",
            event.getCallId(), consumer.getClass().getName(),
            event.getClass().getSimpleName(), t);
      }
    }

    // Forward to exporters — even if a consumer threw a fatal error
    try {
      registry.export(event);
    } catch (Throwable t) {
      if (isFatal(t) && deferred == null) {
        deferred = t;
      }
      LOGGER.warn("[{}] Exporter registry threw on event {}",
          event.getCallId(), event.getClass().getSimpleName(), t);
    }

    // Rethrow the first fatal error after the entire publish cycle
    if (deferred != null) {
      rethrowFatal(deferred);
    }
  }

  @Override
  public InqSubscription onEvent(InqEventConsumer consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    var subscriptionId = SUBSCRIPTION_COUNTER.incrementAndGet();
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
    var subscriptionId = SUBSCRIPTION_COUNTER.incrementAndGet();
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
