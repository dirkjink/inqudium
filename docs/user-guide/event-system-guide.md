# Inqudium Event System — User Guide

## Overview

The Inqudium event system provides a lightweight, allocation-free diagnostic observability layer for resilience
elements. Every element instance (circuit breaker, retry, rate limiter, etc.) owns its own `InqEventPublisher` that
emits structured events as the element operates when diagnostic events are enabled. These events flow in two directions:

- **Local consumers** — registered per-element via `onEvent()`, useful for element-specific monitoring, dashboards, or
  diagnostic tooling.
- **Global exporters** — registered once in the `InqEventExporterRegistry`, receiving events from *all* elements. Ideal
  for forwarding to Kafka, CloudEvents, webhooks, or centralized monitoring.

The system is designed for zero overhead on the hot path: publishing is lock-free, allocation-free in steady state, and
uses copy-on-write arrays for optimal CPU cache locality. A JMH benchmark under 4-thread contention confirms ~58,000
ops/ms with zero GC pressure when both consumers and exporters are active.

> **Important:** The event system is strictly for diagnostic observation and analysis. It is designed to be enabled
> on-demand for troubleshooting. Consumers and exporters must not trigger business logic, external I/O, or any side effect
> that could affect the application's functional behavior.

## Quick Start

### Creating a publisher

Every resilience element creates its own publisher during construction. In most cases, the two-argument factory is
sufficient:

```java
var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER);
```

This uses the global default exporter registry and default configuration (soft limit 256, no hard limit, 60-second
expiry sweep).

For full control — particularly in tests — use the four-argument factory:

```java
var registry = new InqEventExporterRegistry();  // isolated for testing
var config = InqPublisherConfig.of(64, 128, Duration.ofMillis(500));
var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER,
    registry, config);
```

### Subscribing to events

```java
// Receive all events from this element
InqSubscription sub = publisher.onEvent(event -> log.info("Event: {}", event));

// Receive only state transition events
InqSubscription sub = publisher.onEvent(CircuitBreakerOnStateTransitionEvent.class,
    event -> log.info("State: {} → {}", event.getFromState(), event.getToState()));

// Unsubscribe when no longer needed
sub.cancel();
```

### Exporting events globally

```java
public class KafkaEventExporter implements InqEventExporter {

    @Override
    public void export(InqEvent event) {
        // Buffer internally — this runs on the caller's thread
        kafkaBuffer.enqueue(serialize(event));
    }
}
```

Register before the first event is published:

```java
InqEventExporterRegistry.getDefault().register(new KafkaEventExporter());
```

Or use ServiceLoader discovery by adding a `META-INF/services/eu.inqudium.core.event.InqEventExporter` file.

## Core Concepts

### Event identity

Every event carries four correlation fields defined by ADR-003:

| Field         | Purpose                                               |
|---------------|-------------------------------------------------------|
| `callId`      | Unique call identifier, shared across pipeline stages |
| `elementName` | The named instance that emitted the event             |
| `elementType` | Which element kind emitted the event                  |
| `timestamp`   | When the event occurred                               |

Element-specific subclasses add context: `fromState`/`toState` for circuit breaker transitions, `attemptNumber`/
`waitDuration` for retries, and so on.

### Execution model

Consumers and exporters run **synchronously on the thread that publishes the event** — typically the application's
calling thread inside the resilience element. This has two implications:

1. A slow consumer directly delays all subsequent consumers, the exporter forward, and the resilience element's calling
   thread.
2. No thread pool, no queuing, no reordering — events arrive in deterministic order.

This design is intentional: it keeps the publish path simple, allocation-free, and predictable. If your consumer
requires I/O, buffer events internally and drain them on a separate thread.

### Consumer vs. exporter

| Aspect       | `InqEventConsumer`                       | `InqEventExporter`                               |
|--------------|------------------------------------------|--------------------------------------------------|
| Scope        | Single element instance                  | All elements using the same registry             |
| Registration | `publisher.onEvent(...)`                 | `registry.register(...)` or ServiceLoader        |
| Lifecycle    | Managed by the publisher (cancel or TTL) | Managed by the registry (frozen at first export) |
| Use case     | Element-specific dashboards, diagnostics | Kafka, CloudEvents, centralized monitoring       |

## Local Consumers

### Untyped consumers

Receive every event from the publisher:

```java
InqSubscription sub = publisher.onEvent(event -> {
    metrics.counter("events." + event.getClass().getSimpleName()).increment();
});
```

### Typed consumers

Receive only events assignable to the specified class:

```java
publisher.onEvent(RetryOnRetryEvent.class, event -> {
    log.info("Retry attempt #{}, waiting {}",
        event.getAttemptNumber(), event.getWaitDuration());
});
```

Non-matching events are silently skipped. The type check (`isInstance`) is performed per event.

### TTL-based subscriptions

Consumers can be registered with a time-to-live. They are automatically removed after the specified duration by a
background watchdog:

```java
// Auto-removed after 5 minutes
InqSubscription sub = publisher.onEvent(event -> collectDiagnostics(event),
    Duration.ofMinutes(5));

// Also works with typed consumers
InqSubscription sub = publisher.onEvent(SomeEvent.class,
    event -> collectDiagnostics(event), Duration.ofMinutes(5));
```

Early cancellation is always safe:

```java
sub.cancel();  // idempotent — safe to call even after TTL expiry
```

### Double registration

Registering the same consumer instance twice is allowed. The consumer will be invoked once per registration on each
event, and each registration receives an independent subscription for independent cancellation.

### Subscription lifecycle

```
   onEvent(consumer)           publish(event)
         │                          │
         ▼                          ▼
  ┌───────────────┐           ┌─────────────────┐
  │ ConsumerEntry │◄──────────│ Array snapshot  │
  │ added via CAS │           │ (volatile read) │
  └───────────────┘           └─────────────────┘
         │
         ▼
   cancel() removes
   entry via CAS
```

## Global Exporters

### Programmatic registration

```java
InqEventExporterRegistry.getDefault().register(new MyExporter());
```

Registration must happen **before** the first event is exported. After the first `export()` call, the registry freezes
and rejects further registrations with `IllegalStateException`.

### ServiceLoader discovery

Create a file `META-INF/services/eu.inqudium.core.event.InqEventExporter` containing the fully qualified class name of
your exporter:

```
com.example.monitoring.KafkaEventExporter
```

ServiceLoader providers are discovered lazily on the first `export()` call. Provider construction failures are logged
and collected as `InqProviderErrorEvent`s, which are replayed to all successfully resolved exporters after the registry
freezes.

### Event type filtering

Exporters can declare interest in specific event types to avoid unnecessary processing:

```java
public class StateTransitionExporter implements InqEventExporter {

    @Override
    public void export(InqEvent event) {
        // Only called for CircuitBreakerOnStateTransitionEvent
        publish((CircuitBreakerOnStateTransitionEvent) event);
    }

    @Override
    public Set<Class<? extends InqEvent>> subscribedEventTypes() {
        return Set.of(CircuitBreakerOnStateTransitionEvent.class);
    }
}
```

Returning an empty set (the default) means the exporter receives all events. The filter result is cached at freeze
time — `subscribedEventTypes()` is called once and the result is stored for the lifetime of the registry.

### Ordering

Exporters that implement `Comparable<InqEventExporter>` are sorted and executed first. Non-comparable exporters follow
in discovery order, then programmatically registered exporters last.

**Important:** You must implement `Comparable<InqEventExporter>` exactly — not `Comparable<MyConcreteExporter>`. A
type-specific implementation will cause a `ClassCastException` at runtime and be downgraded to unordered.

### Error isolation

A failing exporter never affects other exporters or the resilience element. Exceptions are caught and logged:

```
WARN [call-42] InqEventExporter com.example.BrokenExporter threw on event RetryOnRetryEvent
```

## Configuration

### Publisher configuration

`InqPublisherConfig` controls consumer limits and expiry behavior:

```java
// Default: soft limit 256, no hard limit, 60s expiry sweep, trace disabled
var config = InqPublisherConfig.defaultConfig();

// Custom limits and sweep interval
var config = InqPublisherConfig.of(64, 128, Duration.ofMillis(500));

// With trace publishing enabled
var config = InqPublisherConfig.of(64, 128, Duration.ofMillis(500), true);
```

| Parameter             | Default             | Description                                           |
|-----------------------|---------------------|-------------------------------------------------------|
| `softLimit`           | 256                 | Logs a warning when active consumers reach this count |
| `hardLimit`           | `Integer.MAX_VALUE` | Rejects new registrations at this count               |
| `expiryCheckInterval` | 60 seconds          | How often the watchdog sweeps expired TTL consumers   |
| `traceEnabled`        | `false`             | Whether`publishTrace()` evaluates its supplier        |

Both limits are evaluated against *active* (non-expired) consumers. Expired TTL consumers are swept before any limit
check during registration.

### Trace publishing

Trace events are typically high-volume, fine-grained events that are too expensive to create unconditionally. The
`publishTrace()` method provides lazy evaluation:

```java
// The supplier is only invoked if tracing is enabled
publisher.publishTrace(() -> new DetailedTraceEvent(
    callId, elementName, elementType, Instant.now(),
    /* expensive diagnostic data */));
```

If `isTraceEnabled()` returns `false` (the default), the supplier is never called and no event object is allocated.

## Background Watchdog

When the first TTL-based subscription is registered, the publisher lazily starts an `InqConsumerExpiryWatchdog` on a
virtual thread. The watchdog sleeps for the configured interval, then sweeps expired consumers from the array using a
single CAS attempt.

Key properties:

- **Lazy start** — no background thread exists until a TTL subscription is registered. If your application never uses
  TTL subscriptions, there is zero overhead.
- **Virtual thread** — runs as a daemon thread via Project Loom. Does not prevent JVM shutdown.
- **Weak reference** — holds the publisher via `WeakReference`. If the publisher is garbage collected, the watchdog
  stops automatically.
- **Failure resilient** — sweep exceptions are logged but never kill the watchdog.

### Lifecycle

```
  First TTL subscription
         │
         ▼
  ┌──────────────────┐     sleep(interval)     ┌───────────────┐
  │ Watchdog created ├────────────────────────►│ Sweep expired │
  │ (virtual thread) │◄────────────────────────┤ consumers     │
  └──────────────────┘         loop            └───────────────┘
         │
         ▼
  publisher.close()
  or owner GC'd
         │
         ▼
  Thread interrupted
  and exits
```

For publishers that live for the entire application lifetime (the common case), calling `close()` is not strictly
necessary.

## Testing

### Isolated registries

Always use a dedicated `InqEventExporterRegistry` in tests to avoid cross-test pollution:

```java
var registry = new InqEventExporterRegistry();
var publisher = InqEventPublisher.create("test-element", InqElementType.CIRCUIT_BREAKER,
    registry, InqPublisherConfig.defaultConfig());
```

### Asserting events

```java
var received = new CopyOnWriteArrayList<InqEvent>();
publisher.onEvent(received::add);

// ... exercise the element ...

assertThat(received)
    .hasSize(3)
    .extracting(InqEvent::getClass)
    .containsExactly(
        CircuitBreakerOnSuccessEvent.class,
        CircuitBreakerOnSuccessEvent.class,
        CircuitBreakerOnStateTransitionEvent.class);
```

### Testing TTL behavior

Avoid `Thread.sleep()` in tests. Instead, cast to `DefaultInqEventPublisher` and call `performExpirySweep()` directly:

```java
var publisher = (DefaultInqEventPublisher) InqEventPublisher.create(...);

publisher.onEvent(consumer, Duration.ofMillis(1));
Thread.sleep(50);  // only wait long enough for the TTL to expire

publisher.performExpirySweep();  // trigger sweep deterministically
publisher.publish(event);

assertThat(consumer.received()).isEmpty();  // consumer was swept
```

### Registry reset

For test suites that share a registry instance across methods:

```java
@AfterEach
void tearDown() {
    registry.reset();  // returns to Open state, accepts new registrations
}
```

> `reset()` is for testing only. Never call it in production while events are being published.

## Implementation Notes

### Thread safety model

The publisher stores consumers in an immutable array wrapped in an `AtomicReference`. All mutations (add, remove, sweep)
produce a new array and install it via CAS. Readers take a volatile snapshot and iterate without synchronization. This
guarantees:

- Lock-free reads on the publish path
- No allocation during iteration (the array is pre-built)
- Optimal CPU cache locality (contiguous memory, no pointer chasing)

### Performance characteristics

Based on JMH benchmarks under `@Threads(4)`:

| Scenario                   | Throughput (ops/ms) | GC alloc (B/op) |
|----------------------------|--------------------:|----------------:|
| Empty publisher (baseline) |             176,346 |              ≈0 |
| Local consumers only       |              85,820 |              ≈0 |
| Global exporters only      |              83,481 |              ≈0 |
| Both consumers + exporters |              58,867 |              ≈0 |
| With event allocation      |              45,958 |              56 |

The first four scenarios produce zero GC activity. The allocation in the last scenario (56 B/op) is the event object
itself, not the publish machinery.

### Copy-on-write trade-offs

The copy-on-write design optimizes for the read-heavy steady state (many publishes, rare subscription changes). Each
`onEvent()` or `cancel()` call allocates a new array, which is acceptable because subscription changes are infrequent
compared to event publishing. Under write-heavy scenarios (rapid subscribe/unsubscribe), the CAS loop may retry, but
this is bounded and non-blocking.

## API Reference Summary

### InqEventPublisher

| Method                                 | Description                                                |
|----------------------------------------|------------------------------------------------------------|
| `create(name, type)`                   | Creates a publisher with default registry and config       |
| `create(name, type, registry, config)` | Creates a publisher with full control                      |
| `publish(event)`                       | Delivers event to all consumers and exporters              |
| `publishTrace(supplier)`               | Lazy publish — supplier only evaluated if trace is enabled |
| `isTraceEnabled()`                     | Whether trace publishing is active                         |
| `onEvent(consumer)`                    | Registers an untyped permanent consumer                    |
| `onEvent(type, consumer)`              | Registers a typed permanent consumer                       |
| `onEvent(consumer, ttl)`               | Registers an untyped consumer with TTL                     |
| `onEvent(type, consumer, ttl)`         | Registers a typed consumer with TTL                        |
| `close()`                              | Stops the background watchdog (idempotent)                 |

### InqEventExporterRegistry

| Method                 | Description                                            |
|------------------------|--------------------------------------------------------|
| `getDefault()`         | Returns the shared global registry                     |
| `setDefault(registry)` | Replaces the global registry                           |
| `register(exporter)`   | Registers an exporter programmatically (before freeze) |
| `export(event)`        | Exports to all registered exporters (triggers freeze)  |
| `reset()`              | Returns to open state (testing only)                   |

### InqPublisherConfig

| Method                            | Description                                   |
|-----------------------------------|-----------------------------------------------|
| `defaultConfig()`                 | Soft 256, no hard limit, 60s sweep, trace off |
| `of(soft, hard, interval)`        | Custom limits and sweep interval              |
| `of(soft, hard, interval, trace)` | Custom limits, sweep interval, and trace flag |

### InqSubscription

| Method     | Description                                          |
|------------|------------------------------------------------------|
| `cancel()` | Removes the consumer from the publisher (idempotent) |