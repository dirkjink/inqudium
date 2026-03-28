# Events and Observability

Every element emits events through its own `InqEventPublisher`. Events are purely observational — they never control element behavior. Every event carries a `callId` for end-to-end correlation.

## Subscribing to events

Subscribe to a specific element's events:

```java
circuitBreaker.getEventPublisher()
    .onEvent(event -> log.debug("{}", event));
```

Subscribe to a specific event type:

```java
circuitBreaker.getEventPublisher()
    .onEvent(CircuitBreakerOnStateTransitionEvent.class, event ->
        log.info("State change: {} → {}", event.getFromState(), event.getToState()));
```

## Global event export

For cross-cutting observability (Kafka, JFR, Micrometer), register an `InqEventExporter`:

```java
InqEventExporterRegistry.register(event ->
    kafkaTemplate.send("inqudium-events", event.getCallId(), serialize(event)));
```

Exporters can filter by event type to avoid unnecessary serialization:

```java
public class MetricsExporter implements InqEventExporter {
    @Override
    public void export(InqEvent event) {
        meterRegistry.counter("cb.transitions").increment();
    }

    @Override
    public Set<Class<? extends InqEvent>> subscribedEventTypes() {
        return Set.of(CircuitBreakerOnStateTransitionEvent.class);
    }
}
```

Exporters are also discoverable via `ServiceLoader`. Add your exporter's class name to `META-INF/services/eu.inqudium.core.event.InqEventExporter` and it registers automatically.

## Event system design

The event system operates at two scopes:

**Per-element publishers** — Subscribe to one element, receive only that element's events. Use for dashboards and element-specific monitoring.

**Global exporters** — Receive all events from all elements. Use for infrastructure-level observability (Kafka, JFR, Micrometer).

These two scopes are bridged internally. When an element publishes an event, it flows to both local consumers and global exporters. A broken consumer or exporter is caught and logged — it never affects the element's operation.

## Event fields

Every `InqEvent` carries:

| Field | Type | Description |
|-------|------|-------------|
| `callId` | `String` | Unique identifier shared across all elements in the pipeline |
| `elementName` | `String` | The element instance that emitted this event |
| `elementType` | `InqElementType` | Which element kind emitted the event |
| `timestamp` | `Instant` | When the event occurred |

Element-specific subclasses add context: `fromState`/`toState` for circuit breaker transitions, `attemptNumber`/`waitDuration` for retries, etc.
