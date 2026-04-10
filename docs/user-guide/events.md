# Events and Observability

Every element emits events through its own `InqEventPublisher` when diagnostic events are enabled. Events are purely
observational — they never control element behavior. Every generated event carries a `callId` for end-to-end
correlation.

## Subscribing to events

```java
var cb = CircuitBreaker.of("paymentService", config);

// Subscribe to specific events
cb.getEventPublisher()
  .onEvent(CircuitBreakerOnStateTransitionEvent.class, event -> {
      log.info("Circuit breaker {} changed state: {} -> {}",
          event.getElementName(),
          event.getFromState(),
          event.getToState());
  });

// Subscribe to all events
cb.getEventPublisher()
  .onEvent(event -> metrics.increment("inqudium.events." + event.getClass().getSimpleName()));
```

## Global exporters

If you want to send all events to a centralized system (like Kafka or a logging aggregator), register an exporter
globally. Exporters receive events from all elements.

```java
InqEventExporterRegistry.getDefault().register(new MyKafkaExporter());
```

See the [Event System Guide](event-system-guide.md) for details on consumers, TTL subscriptions, and the
`InqEventExporter` SPI.

## Event properties

Every event provides:

| Property      | Type             | Description                                                |
|---------------|------------------|------------------------------------------------------------|
| `callId`      | `String`         | Unique ID shared across all pipeline elements for one call |
| `elementName` | `String`         | The element instance that emitted this event               |
| `elementType` | `InqElementType` | Which element kind emitted the event                       |
| `timestamp`   | `Instant`        | When the event occurred                                    |

Element-specific events provide additional context, such as wait durations, attempt numbers, and exceptions.