package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Per-element event publisher contract.
 *
 * <p>Each element instance owns its own publisher. Events flow in two directions
 * from the publisher (ADR-003):
 * <ul>
 *   <li>To local consumers registered via {@link #onEvent}</li>
 *   <li>To global exporters registered in the {@link InqEventExporterRegistry}</li>
 * </ul>
 *
 * <h2>Creating a publisher</h2>
 * <pre>{@code
 * // Production — uses the global default registry
 * var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER);
 *
 * // With consumer limits and custom expiry interval
 * var config = InqPublisherConfig.of(64, 128, Duration.ofMillis(500));
 * var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER, config);
 *
 * // Testing — uses an isolated registry
 * var testRegistry = new InqEventExporterRegistry();
 * var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER, testRegistry);
 * }</pre>
 *
 * <h2>Subscribing to events</h2>
 * <pre>{@code
 * // Permanent subscription
 * InqSubscription sub = publisher.onEvent(SomeEvent.class, event -> { ... });
 *
 * // Time-limited subscription — auto-removed after 5 minutes
 * InqSubscription sub = publisher.onEvent(SomeEvent.class, event -> { ... }, Duration.ofMinutes(5));
 *
 * // Later — unsubscribe to prevent memory leaks (not needed for TTL subscriptions
 * // that are allowed to expire naturally, but always safe to call)
 * sub.cancel();
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * <p>When TTL-based subscriptions are used, the publisher starts a background
 * {@link InqConsumerExpiryWatchdog} on a virtual thread. Call {@link #close()} to
 * stop the watchdog when the publisher is no longer needed. If no TTL subscriptions
 * are ever registered, no background thread is created and {@code close()} is a no-op.
 *
 * <p>Publishers used for the lifetime of the application (the common case) typically
 * do not need explicit closing — the virtual thread is a daemon thread and will not
 * prevent JVM shutdown.
 *
 * @since 0.1.0
 */
public interface InqEventPublisher extends AutoCloseable {

  /**
   * Creates a new publisher for an element instance, using the
   * {@linkplain InqEventExporterRegistry#getDefault() global default registry}
   * and the {@linkplain InqPublisherConfig#defaultConfig() default configuration}.
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @return a new publisher
   */
  static InqEventPublisher create(String elementName, InqElementType elementType) {
    return new DefaultInqEventPublisher(elementName, elementType,
        InqEventExporterRegistry.getDefault(), InqPublisherConfig.defaultConfig());
  }

  /**
   * Creates a new publisher for an element instance with custom consumer limits,
   * using the {@linkplain InqEventExporterRegistry#getDefault() global default registry}.
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param config      the publisher configuration (consumer limits and expiry interval)
   * @return a new publisher
   * @since 0.2.0
   */
  static InqEventPublisher create(String elementName, InqElementType elementType,
                                  InqPublisherConfig config) {
    return new DefaultInqEventPublisher(elementName, elementType,
        InqEventExporterRegistry.getDefault(), config);
  }

  /**
   * Creates a new publisher for an element instance, using the specified registry
   * and the {@linkplain InqPublisherConfig#defaultConfig() default configuration}.
   *
   * <p>Useful for testing — pass an isolated registry to avoid cross-test pollution.
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param registry    the exporter registry to use
   * @return a new publisher
   */
  static InqEventPublisher create(String elementName, InqElementType elementType,
                                  InqEventExporterRegistry registry) {
    return new DefaultInqEventPublisher(elementName, elementType,
        registry, InqPublisherConfig.defaultConfig());
  }

  /**
   * Creates a new publisher for an element instance with full control over
   * registry and consumer limits.
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param registry    the exporter registry to use
   * @param config      the publisher configuration (consumer limits and expiry interval)
   * @return a new publisher
   * @since 0.2.0
   */
  static InqEventPublisher create(String elementName, InqElementType elementType,
                                  InqEventExporterRegistry registry, InqPublisherConfig config) {
    return new DefaultInqEventPublisher(elementName, elementType, registry, config);
  }

  /**
   * Publishes an event to all registered consumers and global exporters.
   *
   * <p>This method must be thread-safe. Consumer exceptions are caught and
   * do not propagate to the element. The publish path contains no expiry logic —
   * all TTL-based cleanup is handled asynchronously by the background watchdog.
   *
   * @param event the event to publish
   */
  void publish(InqEvent event);

  /**
   * Publishes a trace event only if tracing is enabled.
   * The supplier is only executed (and the event object only created)
   * if isTraceEnabled() returns true.
   *
   * @param eventSupplier Supplier for the trace event
   */
  default void publishTrace(Supplier<? extends InqEvent> eventSupplier) {}

  /**
   * Registers a consumer for all events from this publisher.
   *
   * @param consumer the event consumer
   * @return a subscription handle for cancellation
   * @throws IllegalStateException if the hard consumer limit is reached
   */
  InqSubscription onEvent(InqEventConsumer consumer);

  /**
   * Registers a typed consumer that only receives events of the specified type.
   *
   * @param eventType the event class to filter on
   * @param consumer  the typed consumer
   * @param <E>       the event type
   * @return a subscription handle for cancellation
   * @throws IllegalStateException if the hard consumer limit is reached
   */
  <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer);

  /**
   * Registers a consumer for all events with a time-to-live.
   *
   * <p>The consumer is automatically removed after the specified duration by the
   * background {@link InqConsumerExpiryWatchdog}. On the first TTL registration,
   * the watchdog is started lazily. The returned subscription can be used to
   * cancel early — cancellation is always idempotent.
   *
   * @param consumer the event consumer
   * @param ttl      the maximum lifetime of the subscription (must be positive)
   * @return a subscription handle for early cancellation
   * @throws IllegalStateException if the hard consumer limit is reached
   * @since 0.2.0
   */
  InqSubscription onEvent(InqEventConsumer consumer, Duration ttl);

  /**
   * Registers a typed consumer with a time-to-live.
   *
   * <p>The consumer is automatically removed after the specified duration and
   * only receives events assignable to {@code eventType}. On the first TTL
   * registration, the background watchdog is started lazily.
   *
   * @param eventType the event class to filter on
   * @param consumer  the typed consumer
   * @param ttl       the maximum lifetime of the subscription (must be positive)
   * @param <E>       the event type
   * @return a subscription handle for early cancellation
   * @throws IllegalStateException if the hard consumer limit is reached
   * @since 0.2.0
   */
  <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer, Duration ttl);

  /**
   * Releases resources held by this publisher.
   *
   * <p>Stops the background {@link InqConsumerExpiryWatchdog} if one was started.
   * If no TTL subscriptions were ever registered, this method is a no-op.
   *
   * <p>For publishers that live for the entire application lifetime (the common case),
   * calling this method is not strictly necessary — the watchdog runs on a daemon
   * virtual thread that does not prevent JVM shutdown.
   *
   * <p>This method is idempotent — calling it multiple times has no effect
   * after the first call.
   *
   * @since 0.2.0
   */
  @Override
  default void close() {
    // Default no-op — overridden by DefaultInqEventPublisher
  }
}
