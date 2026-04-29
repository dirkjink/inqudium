# ADR-003: Event-driven observability

**Status:** Accepted
**Date:** 2026-03-22
**Last updated:** 2026-04-24
**Deciders:** Core team

## Context

Resilience elements produce observable state changes: a circuit breaker opening, a retry attempt, a rate limiter denying
a request. Consumers need to react to these changes for logging, metrics, alerting, and custom business logic.

Common approaches include:

1. **Direct logging** — elements log their own state changes. Inflexible: the consumer can't control the log level,
   format, or destination.
2. **Callback interfaces** — element-specific listeners (e.g. `CircuitBreakerListener`). Works but fragments the
   observability surface.
3. **Unified event framework** — all elements emit typed events through a shared publisher. Consumers attach listeners
   to the publisher.

## Decision

We adopt a **unified event-publishing framework** built on `InqEventPublisher` and an abstract `InqEvent` contract that
lives in `inqudium-core`. Element-owned publishers deliver typed `InqEvent` instances to local consumers and to a global
exporter registry.

### Scope of this ADR

This ADR defines the **framework**:

- the `InqEvent` base contract,
- the `InqEventPublisher` contract, its lifecycle and delivery semantics,
- the subscription handle (`InqSubscription`) including TTL-based subscriptions,
- the publisher configuration (`InqPublisherConfig`) with soft/hard consumer limits,
- the `InqEventExporter` SPI and the lifecycle of `InqEventExporterRegistry`.

The **catalog of element-specific event types** — which subclasses each element emits, what fields they carry, when
they fire — is intentionally **out of scope** here. Each element's events belong in that element's own ADR and in the
corresponding `event` package's Javadoc.

**Known divergence (current state).** Today, only `BulkheadEvent` (with seven concrete subclasses),
`InqCompatibilityEvent`, and `InqProviderErrorEvent` extend `InqEvent` and flow through `InqEventPublisher`. The other
element families — Retry, Rate Limiter, Time Limiter, Traffic Shaper, Fallback — emit standalone `record` types with
their own `Type` enum that do *not* extend `InqEvent` and do *not* pass through the publisher. The Circuit Breaker emits
no events at all today. This split is tracked as a separate inconsistency item in
`docs/adr/_refactor-notes.md` §2.6. The framework described below is the target every element is expected to adopt.

### `InqEvent` — the base contract

Every event that flows through `InqEventPublisher` extends `InqEvent` and carries five identity fields:

| Field         | Type             | Purpose                                                      |
|---------------|------------------|--------------------------------------------------------------|
| `chainId`     | `long`           | Pipeline-wide identifier; same for every element in a chain  |
| `callId`      | `long`           | Per-call identifier; monotonically increasing within a chain |
| `elementName` | `String`         | The named instance that emitted the event                    |
| `elementType` | `InqElementType` | Which element kind emitted it                                |
| `timestamp`   | `Instant`        | Wall-clock time of emission                                  |

`chainId` and `callId` are primitive `long` values minted by `PipelineIds` — see ADR-022 for the propagation model.
They replace the earlier `String`/UUID `callId` and `CallContext` design.

`InqElementType` enumerates the actual element kinds present in core: `CIRCUIT_BREAKER`, `RETRY`, `RATE_LIMITER`,
`BULKHEAD`, `TIME_LIMITER`, `TRAFFIC_SHAPER`, plus the sentinel `NO_ELEMENT` used by infrastructure events such as
`InqProviderErrorEvent`.

### Event types live in `inqudium-core`

`InqEvent` and its first-party subclasses (the `BulkheadEvent` family, `InqCompatibilityEvent`,
`InqProviderErrorEvent`) are defined in core, not in paradigm modules. This keeps Micrometer bindings, JFR bindings, and
custom listeners independent of execution paradigm: a `BulkheadOnRejectEvent` is structurally identical whether it came
from the imperative, coroutine, Reactor, or RxJava bulkhead.

External modules cannot define their own `InqEvent` subtypes for transport through `InqEventPublisher` — see
"Open for consumption, closed for emission" below.

### Publisher contract

```java
public interface InqEventPublisher extends AutoCloseable {

    // Factories
    static InqEventPublisher create(String elementName, InqElementType elementType);
    static InqEventPublisher create(String elementName, InqElementType elementType,
                                    InqEventExporterRegistry registry,
                                    InqPublisherConfig config);

    // Publishing
    void publish(InqEvent event);
    default void publishTrace(Supplier<? extends InqEvent> eventSupplier);
    default boolean isTraceEnabled();

    // Subscriptions — return cancellable handles
    default InqSubscription onEvent(InqEventConsumer consumer);
    <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer);

    // TTL-based subscriptions
    default InqSubscription onEvent(InqEventConsumer consumer, Duration ttl);
    <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer, Duration ttl);

    // Lifecycle
    @Override default void close();
}
```

Each element instance owns its own publisher and exposes it via an `getEventPublisher()` accessor. Consumers subscribe
per-instance:

```java
InqSubscription sub = bulkhead.getEventPublisher()
    .onEvent(BulkheadOnRejectEvent.class, event -> dashboard.update(event.getElementName()));

// Later — release the subscription
sub.cancel();
```

### Publisher creation and wiring

The element creates its own publisher during construction:

```java
public static Bulkhead of(String name, BulkheadConfig config) {
    var publisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
    return new Bulkhead(name, config, publisher);
}
```

`InqEventPublisher.create(name, type)` uses the global default registry (`InqEventExporterRegistry.getDefault()`) and
the default `InqPublisherConfig`. The four-argument overload allows tests and embedded scenarios to inject an isolated
registry and a custom config.

For unit tests, the element constructor accepts a publisher directly so a recording publisher can capture events for
assertion.

When `publish(event)` is called, `DefaultInqEventPublisher`:

1. **Delivers to local consumers** — the per-instance consumer list registered via `onEvent`.
2. **Forwards to the registry** — calls `registry.export(event)`, reaching all registered `InqEventExporter`
   implementations.

```
element.publish(event)
   │
   ├─► Local consumers (registered via this.onEvent())
   │     → Dashboard widget, custom listener, etc.
   │
   └─► InqEventExporterRegistry.export(event)
         → KafkaExporter, JfrBinder, MicrometerBinder, etc.
```

The element and its consumers are unaware of the global exporter layer. The exporter layer is unaware of individual
per-element subscriptions. The `DefaultInqEventPublisher` bridges both — this is the only point where the two scopes
meet.

**Why the element creates the publisher itself:**

- **No external wiring needed.** The developer calls `Bulkhead.of(name, config)` — the publisher is ready immediately.
  No builder step, no factory injection, no Spring bean dependency.
- **Lifecycle alignment.** The publisher lives exactly as long as the element. No orphaned publishers, no dangling
  subscriptions.
- **Testability.** In tests, `InqEventPublisher.create()` can be replaced with a recording implementation that captures
  events for assertion. The four-argument factory and the constructor injection both support this.

### Hot-path delivery semantics

The publish path is intentionally lean and predictable:

- **Synchronous.** Consumers and exporters run on the thread that called `publish`. A slow consumer directly delays the
  protected call.
- **Sequential.** Consumers fire in registration order; the exporter forward runs after all local consumers.
- **Lock-free.** Consumers are stored in an immutable array wrapped in an `AtomicReference`; iteration takes a snapshot
  and never blocks. Subscribe and cancel use copy-on-write CAS.
- **Exception-isolated.** Any non-fatal exception thrown by a consumer or by the exporter registry is caught and logged
  at WARN with the offending event's `callId`. Fatal errors (`Error` subclasses such as `OutOfMemoryError`) are rethrown
  via `InqException.rethrowIfFatal`.
- **No expiry work.** TTL bookkeeping never runs on the publish path; expired entries are removed asynchronously by the
  watchdog and synchronously inside `addConsumer` to keep limit checks accurate.

`publishTrace(Supplier)` is the cheap form for high-volume diagnostic events: the supplier is only invoked when
`isTraceEnabled()` returns `true` (driven by `InqPublisherConfig.traceEnabled()`). When trace is off, no event object
is ever allocated.

### Subscription handles — `InqSubscription`

Every `onEvent(...)` call returns an `InqSubscription`:

```java
@FunctionalInterface
public interface InqSubscription {
    void cancel();
}
```

`cancel()` is idempotent — calling it more than once has no further effect. The same consumer instance can be registered
multiple times; each registration produces an independent subscription with an independent cancel handle and is invoked
once per registration on each event.

### TTL-based subscriptions and the expiry watchdog

The TTL overloads register a consumer that is automatically removed after a `Duration`:

```java
InqSubscription sub = publisher.onEvent(SomeEvent.class, e -> ..., Duration.ofMinutes(5));
```

The first TTL registration on a publisher lazily starts an `InqConsumerExpiryWatchdog` on a daemon virtual thread
(`Thread.ofVirtual().name("inq-expiry-watchdog-<elementName>").start(...)`). The watchdog:

- holds the publisher via a `WeakReference` so a GC of the owner stops it,
- sleeps for `InqPublisherConfig.expiryCheckInterval()` between sweeps (default 60 s),
- catches and logs any sweep failure without dying,
- exits when `close()` is called or the owner is collected.

If no TTL subscription is ever registered, no virtual thread is created. Returning the `InqSubscription` from a TTL
registration is still useful: it allows early cancellation before the TTL elapses, and `cancel()` after expiry is a
safe no-op.

### Publisher configuration — `InqPublisherConfig`

`InqPublisherConfig` controls consumer limits, watchdog cadence, and tracing per publisher instance:

| Field                 | Default                  | Effect                                                                                |
|-----------------------|--------------------------|---------------------------------------------------------------------------------------|
| `softLimit`           | `256`                    | When the active consumer count crosses this, registration logs WARN. No rejection.    |
| `hardLimit`           | `Integer.MAX_VALUE`      | When this is reached, further `onEvent(...)` throws `IllegalStateException`.          |
| `expiryCheckInterval` | `Duration.ofSeconds(60)` | Period of the lazy-started TTL watchdog.                                              |
| `traceEnabled`        | `false`                  | Drives `isTraceEnabled()` and gates `publishTrace(Supplier)` evaluation.              |

Both limits are evaluated against *active* (non-expired) consumers — expired TTL consumers are swept before the limit
check during registration, so they cannot inflate the count and trigger spurious rejections.

```java
// Custom — warn at 64, reject at 128, sweep every 500 ms, tracing on
var config = InqPublisherConfig.of(64, 128, Duration.ofMillis(500), true);
var publisher = InqEventPublisher.create(name, type,
        InqEventExporterRegistry.getDefault(), config);
```

### Lifecycle — `close()`

`InqEventPublisher` extends `AutoCloseable`. `close()` stops the expiry watchdog (if started) and is a no-op otherwise.
It is idempotent.

For publishers that live as long as the application — the typical case — `close()` is not strictly required: the
watchdog runs on a daemon virtual thread that does not block JVM shutdown. Embedded scenarios (e.g. test frameworks
creating and disposing many short-lived publishers) should call `close()` to release the watchdog promptly.

### Event scope: two layers by design

The framework operates at two scopes — each serves a different audience.

#### Per-element publishers (targeted consumption)

Each element instance owns its own `InqEventPublisher`. A consumer subscribes to a specific element and receives only
that element's events:

```java
var paymentBh = registry.bulkhead("paymentService");
paymentBh.getEventPublisher()
    .onEvent(BulkheadOnRejectEvent.class, event ->
        dashboard.update(event.getElementName()));

// A different bulkhead — completely independent event stream
var orderBh = registry.bulkhead("orderService");
orderBh.getEventPublisher()
    .onEvent(BulkheadOnRejectEvent.class, event -> { ... });
```

**Why per-element:** the most common consumption pattern is targeted. A dashboard widget for the payment service wants
payment service events — not a firehose it must filter. Per-element publishers make this zero-cost: no filtering, no
routing, no topic matching. Subscribe and receive.

There is **no global consumer registry**. A consumer that wants events from multiple elements subscribes to each
individually. This avoids the "subscribe once, get everything" pattern, where consumers spend hot-path cycles filtering
events they don't care about.

#### Global exporters (cross-cutting export)

`InqEventExporter` operates at the global scope. Every event published by any element flows through
`InqEventExporterRegistry.export(...)` to all registered exporters:

```java
InqEventExporterRegistry.getDefault().register(
    new KafkaEventExporter(producer, "inqudium-events"));
// This exporter receives ALL events from ALL elements
```

**Why global:** exporters are infrastructure concerns — a Kafka topic, a JFR recording, a Micrometer registry. They
want everything because they serve a different purpose than targeted consumers: long-term storage, alerting,
cross-service correlation. Forcing them to subscribe per element would be fragile (miss a new element → miss its
events) and boilerplate-heavy.

#### How the two layers interact

```
Element emits event
  ├─► Per-element InqEventPublisher → registered consumers for this element
  └─► InqEventExporterRegistry → all global exporters
```

The per-element publisher and the global exporter mechanism are independent. There is no duplication — each event is
delivered once to each scope.

#### Rejected alternatives

**Global singleton event bus.** A single `InqEventBus.getInstance().onEvent(...)` that receives all events from all
elements. Rejected because: (a) singleton complicates testing — the global bus must be reset between tests; (b) every
consumer receives every event from every element and must filter; (c) the "subscribe to everything" default encourages
consumers that process more than they need, degrading performance under high event volume.

**Per-pipeline event bus.** Each `InqPipeline` owns an event bus that aggregates events from all elements in the
pipeline. Rejected because: (a) the `chainId` already provides per-pipeline correlation — filtering by it reconstructs
a chain's event sequence without a separate bus; (b) a pipeline-scoped bus needs lifecycle management (created with the
pipeline, disposed when?) that adds complexity without clear benefit; (c) elements can be shared across pipelines (the
same Bulkhead instance in multiple pipelines) — which pipeline's bus would receive the event?

**Application-scoped event bus (like registries).** The application creates event bus instances and passes them to
elements. Rejected because: (a) the per-element publisher already exists and is simpler; (b) an application-scoped bus
is effectively a filtered view of the global scope — achievable by subscribing to the elements you care about; (c) it
requires extra lifecycle management (who creates the bus, who disposes it) without clear benefit over per-element +
global exporter.

### `InqEventExporter` SPI

```java
public interface InqEventExporter {

    void export(InqEvent event);

    default Set<Class<? extends InqEvent>> subscribedEventTypes() {
        return Set.of(); // empty = all events
    }
}
```

Exporters are registered globally via `InqEventExporterRegistry` and receive events from all elements. The
`subscribedEventTypes()` filter prevents unnecessary work — an exporter interested only in `BulkheadOnRejectEvent` is
not invoked for other types.

**Stability contract.** `subscribedEventTypes()` is called once at registry freeze time and the result is cached for
the lifetime of the registry. Implementations must return a stable, immutable set; changes after registration are
silently ignored.

**Implementation requirements:**

- **Thread-safe.** The same exporter instance is invoked concurrently from many elements.
- **Non-blocking.** An exporter must not block the publishing thread. If the target system requires I/O (Kafka produce,
  HTTP webhook), the exporter is responsible for buffering internally.
- **Exception-safe.** Exceptions are caught by the registry and logged; they do not affect other exporters or the
  resilience element.
- **No backpressure to the element.** If the exporter cannot keep up, it drops or buffers — it never slows down the
  protected call.
- **Optional ordering.** An exporter may implement `Comparable<InqEventExporter>`. Comparable exporters are sorted
  ascending and invoked before non-Comparable ones. A misparameterised `Comparable` (e.g. `Comparable<MyExporter>`) is
  downgraded to non-Comparable and the issue is reported as an `InqProviderErrorEvent`.

See ADR-014 for the general ServiceLoader provider conventions that this SPI follows.

### `InqEventExporterRegistry` lifecycle

The registry is a per-instance class with a sealed state machine:

```
Open ──┐
       │ first export() call ─►  Resolving ─► Frozen
       │ register(exporter)
       └───────────────────────►  Open
```

- **Open.** Programmatic registrations via `register(exporter)` are accepted. A snapshot of registered exporters is
  held but ServiceLoader discovery has not yet run.
- **Resolving.** Triggered by the first `export()` call. Exactly one thread runs
  `ServiceLoader.load(InqEventExporter.class, classLoader)`; concurrent callers park with exponential backoff
  (`LockSupport.parkNanos`) up to a bounded total timeout. If the resolver gets stuck past the timeout, the state is
  forcibly reset to `Open` so callers can retry. Provider failures during ServiceLoader iteration are logged and
  collected as `InqProviderErrorEvent`s rather than propagated.
- **Frozen.** The resolved exporter list is published. Further `register(...)` throws `IllegalStateException`
  ("exporters must be registered before the first event is exported"). Each exporter's `subscribedEventTypes()` is
  captured here and cached.

After the freeze succeeds, the registry **replays** the collected `InqProviderErrorEvent`s to all successfully resolved
exporters — so a Kafka or JFR exporter sees the bootstrap-phase failures even though they happened before any exporter
was alive.

The class loader used for ServiceLoader is captured at construction (preferring the thread context class loader) via a
`WeakReference` and cleared after freeze, so it can be collected.

#### Default instance and isolation

```java
InqEventExporterRegistry.getDefault();   // shared global instance
InqEventExporterRegistry.setDefault(r);  // override (e.g. in test fixtures)
new InqEventExporterRegistry();          // isolated instance for a single test
```

The per-instance design lets tests use a fresh registry without polluting the global one. Production code uses
`getDefault()` implicitly through `InqEventPublisher.create(name, type)`.

A `reset()` method exists for tests that need to reuse a frozen instance across methods. It is not safe to call
concurrently with `export()`.

### No internal logging

Elements do **not** log their own state changes. All observability flows through this framework — either via
per-element subscriptions or via global exporters.

### Two-tier observability: polling metrics vs. diagnostic events

The event framework's role is **diagnostic tracing**, not primary metrics delivery. Production metrics and diagnostic
events serve different audiences, have different performance characteristics, and should be independently controllable.

#### Tier 1: Polling-based metrics (always on, zero per-call overhead)

Continuous metrics — current concurrency, available permits, configured limits, rejection counts — are delivered via
**polling-based gauges** that read the element's introspection methods at scrape time:

```java
meterRegistry.gauge("inqudium.bulkhead.concurrent.calls",
    Tags.of("name", bulkhead.getName()),
    bulkhead, b -> b.getConcurrentCalls());

meterRegistry.gauge("inqudium.bulkhead.available.permits",
    Tags.of("name", bulkhead.getName()),
    bulkhead, b -> b.getAvailablePermits());
```

These gauges cost nothing on the hot path — no event objects, no `Instant` allocation, no publisher dispatch. The
values are read from the strategy's atomic state when Prometheus scrapes or Micrometer polls. This matches Resilience4j's
`TaggedBulkheadMetrics` and achieves 0 B/op on the happy path.

Rejection counts are tracked by a `LongAdder` in the rejection path (already dominated by exception creation) and
exposed as a Micrometer counter.

**This tier is the recommended default for dashboards, alerting, and SLA monitoring.**

#### Tier 2: Diagnostic events (off by default, enable for troubleshooting)

Per-call events provide a complete timeline for a specific call's journey through an element. They are gated by
element-specific configuration. The Bulkhead, today, does this through `BulkheadEventConfig` and `BulkheadEventCategory`:

| Preset                                         | What fires                  | Happy-path cost |
|------------------------------------------------|-----------------------------|-----------------|
| `BulkheadEventConfig.standard()` **(default)** | Rejection events only       | 0 B/op          |
| `BulkheadEventConfig.diagnostic()`             | All categories enabled      | ~80 B/op        |

Categories (`LIFECYCLE`, `REJECTION`, `TRACE`) are independently toggleable via `BulkheadEventConfig.of(...)`:

| Category    | Events                                                    | Standard | Diagnostic |
|-------------|-----------------------------------------------------------|----------|------------|
| `LIFECYCLE` | `BulkheadOnAcquireEvent`, `BulkheadOnReleaseEvent`        | off      | on         |
| `REJECTION` | `BulkheadOnRejectEvent`, `BulkheadCodelRejectedTraceEvent`| **on**   | on         |
| `TRACE`     | `BulkheadWaitTraceEvent`, `BulkheadRollbackTraceEvent`,   | off      | on         |
|             | `BulkheadLimitChangedTraceEvent`                          |          |            |

The `standard()` preset is the recommended production configuration. The `diagnostic()` preset is enabled on demand —
like Flight Recorder activation when something is wrong.

```java
// Production (default) — zero happy-path event overhead
var config = bulkhead()
    .name("paymentService")
    .eventConfig(BulkheadEventConfig.standard())
    .build();

// Troubleshooting — full per-call tracing
var config = bulkhead()
    .name("paymentService")
    .eventConfig(BulkheadEventConfig.diagnostic())
    .build();
```

Other elements are expected to follow the same pattern (per-element event-config records gating which categories fire);
see each element's ADR for its specific configuration surface.

#### Why not event-driven metrics?

Per-call event creation costs ~80 B/op (two `Instant` objects + two event instances), which under high throughput
(>10K ops/sec) generates measurable GC pressure. Polling-based gauges that read the strategy's existing atomic state
achieve the same observability at zero cost. The event framework adds diagnostic depth (per-call timeline, wait
durations, rejection context) that gauges cannot provide — but this depth is only valuable during troubleshooting, not
for continuous metrics.

This is consistent with Resilience4j's Micrometer integration, which registers only gauges for the semaphore bulkhead
and achieves 0 B/op on the happy path.

### Relationship to JFR, Micrometer, and other observability systems

`InqEventPublisher` and the `InqEvent` hierarchy are the **diagnostic event bus** of Inqudium. They live in
`inqudium-core` and have no dependency on any external observability system.

External observability modules interact with Inqudium through **two independent channels**:

```
Polling-based metrics (Tier 1 — always on)
       │
       ├── inqudium-micrometer     bulkhead.concurrentCalls() → Gauge
       │                           bulkhead.availablePermits() → Gauge
       │                           rejectionCounter → Counter
       └── Any MeterBinder         (reads element state at scrape time)

Diagnostic events (Tier 2 — on demand)
       │
       ├── inqudium-micrometer     InqEvent → Timer.record() (when diagnostic mode is active)
       ├── inqudium-jfr            InqEvent → jdk.jfr.Event subclass → commit()
       ├── Custom SLF4J listener   InqEvent → log.info(...)
       └── Any user-defined consumer
```

The `inqudium-micrometer` module serves both tiers: it registers gauges unconditionally (Tier 1) and optionally
subscribes to events for detailed timer recordings when diagnostic mode is active (Tier 2). The `inqudium-jfr` module
operates exclusively in Tier 2 — JFR events are meaningful only as detailed per-call records, not as polling targets.

Critically, `InqEvent` does **not** extend `jdk.jfr.Event`, and JFR event classes do **not** extend `InqEvent`. The
two type hierarchies are fully independent:

- `InqEvent` hierarchy — lightweight POJOs in `inqudium-core`, zero dependencies, emitted only when the corresponding
  event category is enabled.
- `jdk.jfr.Event` hierarchy — JFR-annotated classes in `inqudium-jfr`, only instantiated when the JFR module is on the
  classpath and diagnostic events are active.

This separation is intentional. Binding `InqEvent` to `jdk.jfr.Event` would force JFR's class hierarchy
(`begin()`/`end()`/`commit()` lifecycle, `@Name`/`@Label` annotations) onto the core event model — an abstraction leak
that couples the internal pub/sub mechanism to a specific observability backend.

The JFR binder (ADR-007) bridges the gap: it subscribes to `InqEventPublisher`, receives `InqEvent` instances, and
creates the corresponding `jdk.jfr.Event` — mapping fields one-to-one.

### Events are observational — not a control mechanism

Events flow in **one direction: out.** They are notifications about something that has already happened. No element
uses events from another element to make state decisions. This is a deliberate constraint with two important
implications.

#### Cross-element communication happens through the pipeline, not through events

Consider a typical composition:

```
Call → CircuitBreaker → TimeLimiter → external service
       (outer layer)     (inner layer)
```

When the TimeLimiter fires, it throws. The exception propagates upward through the normal pipeline call stack
(ADR-002). The Circuit Breaker records it as a failure, counts it in its sliding window, and opens when the failure
rate threshold is exceeded.

The Circuit Breaker **already reacts to timeouts** — not by subscribing to TimeLimiter events, but because errors
propagate naturally through the decoration layers. This is exactly what functional decoration (ADR-002) is designed
for.

If the Circuit Breaker also consumed TimeLimiter events through the bus, two problems emerge:

1. **Double-counting.** The same timeout would land in the sliding window twice — once via exception, once via event.
   Deduplication logic would be needed.
2. **Module coupling.** The Circuit Breaker package would need to depend on TimeLimiter event types — violating the
   per-element-package independence in core.

The pipeline already provides the semantics that event-based cross-element communication would attempt to replicate —
without the coupling, without the deduplication, and without the indirection overhead on the hot path.

#### Cross-instance coordination is an explicit API, not event subscription

There is a legitimate scenario the pipeline does not cover: **cross-instance reaction.** *Example:* "When the Circuit
Breaker for Service-A opens, the breaker for Service-B should also open because B depends on A." These two breakers
protect different calls — there is no shared pipeline.

Even here, the answer is not event subscription between elements. A breaker subscribing to another breaker's events
creates hidden coupling: behavior depends on subscription order, event-bus timing, and whether the subscription was
set up before the first failure. This is fragile and invisible in code.

The intended pattern is an **explicit, declarative API** — for instance, a future `CircuitBreakerGroup` that calls
`forceOpen()` on dependent breakers directly, rather than reacting to bus events. No such API exists in core today;
this rule is documented here so that future cross-instance features take this shape rather than a hidden
event-subscription one.

After any such state change, events are emitted as usual — they document the propagation, they don't drive it.

#### The rule

```
Pipeline       → controls what happens within a call chain
Explicit API   → controls what happens across element instances
Events         → document what happened (observability only)
```

### Open for consumption, closed for emission

The framework is intentionally open in one direction and closed in another.

#### Open: consuming events

Any external code can register as an event consumer through `InqEventPublisher.onEvent(...)`. This is a first-class
API, not a back door. The shipped consumers (`inqudium-micrometer`, `inqudium-jfr`) have no privileged access — they
use the same `onEvent()` API any external consumer uses.

#### Open: exporting events to external systems

The `InqEventExporter` SPI lets external code see all events in one place — Kafka, CloudEvents, webhooks, custom
monitoring. Exporter discovery follows ADR-014 conventions (lazy on first access, Comparable ordering, error isolation,
singleton lifecycle). The `subscribedEventTypes()` filter prevents unnecessary serialization for exporters that only
care about specific events.

#### Closed: emitting custom event types

`InqEventPublisher.publish(InqEvent)` only accepts `InqEvent` subtypes defined in `inqudium-core`. External modules
**cannot** define their own event types and push them through the publisher.

This boundary is deliberate. If the publisher accepted arbitrary types, every consumer would need to handle unknown
events — requiring type registries, schema evolution, and defensive deserialization. The framework would become a
generic event bus, which is not the responsibility of a resilience library. Projects that need a general-purpose event
bus should use dedicated infrastructure (Spring `ApplicationEvent`, CDI Events, Kafka, etc.).

The `InqEventExporter` SPI bridges the gap: events flow **out** of Inqudium into external systems, but external events
do not flow **in**.

## Consequences

**Positive:**

- A single observability framework — one publisher contract, one exporter SPI, one event-identity model — reusable
  across paradigms.
- Two-tier observability cleanly separates concerns: polling gauges for metrics (zero overhead), diagnostic events for
  troubleshooting (on demand). Standard configuration achieves 0 B/op on the happy path.
- TTL-based subscriptions remove a common consumer-leak source (a forgotten dashboard listener) without leaking
  watchdog overhead onto users who do not need TTLs.
- `InqSubscription` makes cancellation explicit and idempotent, replacing the older "subscribe and hope" model.
- Soft/hard consumer limits (`InqPublisherConfig`) make consumer leaks visible (WARN log) and survivable (hard
  rejection rather than unbounded growth).
- The exporter registry's `Open → Resolving → Frozen` state machine prevents silent re-registration after events have
  started flowing — exporter sets are deterministic per registry instance.
- Provider-error replay preserves the bootstrap audit trail in long-lived exporters.
- `chainId`/`callId` enables end-to-end correlation across elements that participate in the framework (see ADR-022).
- `InqEventExporter` SPI allows integration with arbitrary external systems without core needing to know the target.

**Negative:**

- Each element implementation must remember to emit events at the correct points when the relevant category is
  enabled. This is enforced by behavioral contract tests in core.
- The two-tier model requires Micrometer-style binders to do double duty: register gauges unconditionally (Tier 1) and
  optionally subscribe to events for detailed timer recordings (Tier 2). The complexity is justified by the 0 B/op vs.
  ~80 B/op gap.
- Exporter implementations must handle their own buffering and error recovery — the framework provides the SPI, not a
  production-ready exporter for every target system.
- Synchronous in-thread delivery means a misbehaving consumer can stall the protected call. Documented in
  `InqEventConsumer` Javadoc; mitigated by the exception-isolation guarantee but not eliminated.

**Neutral:**

- Elements ship with zero logging dependency. The `inqudium-test` module may optionally log events for debugging
  convenience.
- The framework is open for consumption and export, but closed for external event emission. This keeps the type
  contract stable.
- Per-call event overhead (~80 B/op in `diagnostic()` mode) is acceptable for short troubleshooting sessions but not
  for continuous production use. By design — `standard()` ensures it is never paid unknowingly.
- As of this revision, only `BulkheadEvent` (and its subclasses), `InqCompatibilityEvent`, and `InqProviderErrorEvent`
  actually flow through the publisher. The other element families maintain their own non-`InqEvent` record types and
  do not yet participate. Tracked in `_refactor-notes.md` §2.6.
