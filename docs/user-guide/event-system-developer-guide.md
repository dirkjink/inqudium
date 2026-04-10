# Inqudium Event System — Developer Guide

This guide is for developers who need to extend, maintain, or debug the event system internals. It assumes familiarity
with the [User Guide](event-system-guide.md) and focuses on architecture, design rationale, internal data structures,
and extension points.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  Resilience Element (e.g. CircuitBreaker, Retry)                    │
│                                                                     │
│   element.publish(event)                                            │
│         │                                                           │
│         ▼                                                           │
│  ┌──────────────────────────────────────┐                           │
│  │      DefaultInqEventPublisher        │                           │
│  │                                      │                           │
│  │  AtomicReference<ConsumerEntry[]>    │  copy-on-write array      │
│  │         │                            │                           │
│  │         ▼                            │                           │
│  │  ┌─────────────────────────┐         │                           │
│  │  │ ConsumerEntry[0]        │─► consumer.accept(event)            │
│  │  │ ConsumerEntry[1]        │─► consumer.accept(event)            │
│  │  │ ConsumerEntry[n]        │─► consumer.accept(event)            │
│  │  └─────────────────────────┘         │                           │
│  │         │                            │                           │
│  │         ▼                            │                           │
│  │  registry.export(event) ─────────────┼───┐                       │
│  │                                      │   │                       │
│  │  ┌──────────────────────────┐        │   │                       │
│  │  │ InqConsumerExpiryWatchdog│        │   │                       │
│  │  │ (virtual thread, lazy)   │        │   │                       │
│  │  └──────────────────────────┘        │   │                       │
│  └──────────────────────────────────────┘   │                       │
└─────────────────────────────────────────────┼───────────────────────┘
                                              │
                                              ▼
                               ┌────────────────────────────┐
                               │ InqEventExporterRegistry   │
                               │                            │
                               │  State machine:            │
                               │  Open → Resolving → Frozen │
                               │                            │
                               │  List<CachedExporter>      │
                               │    ├─ exporter.export()    │
                               │    ├─ exporter.export()    │
                               │    └─ exporter.export()    │
                               └────────────────────────────┘
```

Each resilience element owns exactly one `DefaultInqEventPublisher`. The publisher holds local consumers in an
`AtomicReference<ConsumerEntry[]>` and delegates global export to a shared `InqEventExporterRegistry`. These are the
only two mutable structures on the publish path.

## Design Constraints

Every change to the event system must respect these invariants. They are non-negotiable because the publish path sits
inside every resilience element's hot loop.

**1. Zero allocation on the publish path.** The `publish()` method must not create objects. No iterators, no boxed
primitives, no temporary collections. The JMH benchmark suite verifies this — any regression shows up as non-zero
`gc.alloc.rate.norm`.

**2. No locks on the publish path.** Readers (publishers) must never block. All synchronization uses `AtomicReference`
with volatile reads for the fast path and CAS loops for mutations.

**3. Consumer exceptions must never propagate.** A broken consumer must not crash the resilience element. Every consumer
invocation is wrapped in try-catch. Fatal errors (e.g. `OutOfMemoryError`) are rethrown via
`InqException.rethrowIfFatal()`.

**4. Observation only.** The event system must not influence the functional behavior of the application. This is a
design contract, not a technical enforcement — but it guides every API decision.

## Internal Data Structures

### ConsumerEntry array

```java
record ConsumerEntry(
    long id,
    InqEventConsumer consumer,
    String description,
    Instant expiresAt
) {
    boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
```

Consumers are stored in a plain `ConsumerEntry[]` wrapped in `AtomicReference<ConsumerEntry[]>`. This was chosen over
`ConcurrentHashMap` or `CopyOnWriteArrayList` for three reasons:

- **Cache locality.** Array iteration touches contiguous memory. A HashMap or linked structure causes pointer chasing
  and cache misses.
- **Zero-allocation reads.** `AtomicReference.get()` returns the array reference directly. No iterator allocation, no
  entry set views.
- **Deterministic ordering.** Consumers are invoked in registration order. Array preserves insertion order naturally.

The trade-off: every mutation (add, remove, sweep) allocates a new array. This is acceptable because mutations are rare
relative to publishes.

### Subscription IDs

Each publisher maintains an `AtomicLong subscriptionCounter`. Every registration increments it and assigns the resulting
value as the entry's `id`. Cancellation searches the array by ID and removes the matching entry. This avoids
identity-based comparison on consumer lambdas (which would fail for captures of the same method reference) and supports
independent cancellation of double-registered consumers.

### CAS patterns

The codebase uses two CAS patterns:

**Optimistic CAS loop** — used in `addConsumer()`. Read the current array, compute the new array, attempt CAS. On
failure, re-read and retry. The loop includes a synchronous sweep of expired entries so that limit checks operate on
clean data.

```java
while (true) {
    ConsumerEntry[] current = consumers.get();
    ConsumerEntry[] cleaned = sweepExpired(current, now);
    // ... limit checks, build new array ...
    if (consumers.compareAndSet(current, newArr)) {
        return;  // success
    }
    // CAS failed — another thread mutated; retry with fresh snapshot
}
```

**Single-attempt CAS** — used in `performExpirySweep()` (called by the watchdog). If the CAS fails, the sweep is simply
deferred to the next watchdog cycle. This avoids contention between the watchdog thread and application threads.

**`updateAndGet`** — used in `removeConsumer()`. The `AtomicReference.updateAndGet()` call handles the CAS loop
internally, which is safe here because the update function (array filtering) is side-effect-free and idempotent.

## Adding a New Event Type

This is the most common extension. Each resilience element defines its own event subclasses.

### Step 1: Create the event class

Extend `InqEvent` and add element-specific fields:

```java
public final class CircuitBreakerOnStateTransitionEvent extends InqEvent {

    private final CircuitBreakerState fromState;
    private final CircuitBreakerState toState;

    public CircuitBreakerOnStateTransitionEvent(String callId, String elementName,
                                                 InqElementType elementType, Instant timestamp,
                                                 CircuitBreakerState fromState,
                                                 CircuitBreakerState toState) {
        super(callId, elementName, elementType, timestamp);
        this.fromState = Objects.requireNonNull(fromState, "fromState must not be null");
        this.toState = Objects.requireNonNull(toState, "toState must not be null");
    }

    public CircuitBreakerState getFromState() { return fromState; }
    public CircuitBreakerState getToState() { return toState; }
}
```

**Conventions to follow:**

- Make the class `final`. Event subclassing hierarchies beyond one level create ambiguity in typed consumer dispatch and
  exporter filtering.
- Validate all fields in the constructor. Follow the null-safety pattern established in `InqEvent` — use
  `Objects.requireNonNull` for objects and `requireNonBlank` for correlation strings.
- Make all fields immutable. Events are shared across threads without synchronization.
- Keep events lightweight. The event object is allocated on every invocation. Avoid expensive computations or large data
  copies in the constructor.

### Step 2: Emit from the element

```java
// Inside the resilience element's internal logic
publisher.publish(new CircuitBreakerOnStateTransitionEvent(
    callId, elementName, elementType, Instant.now(),
    previousState, newState));
```

For high-frequency diagnostic events, use trace publishing:

```java
publisher.publishTrace(() -> new CircuitBreakerDetailedTraceEvent(
    callId, elementName, elementType, Instant.now(),
    /* expensive diagnostic payload */));
```

The supplier is only evaluated when `isTraceEnabled()` returns `true`, avoiding the allocation entirely in the default
case.

### Step 3: Document the event

Add the new event to the element's Javadoc and to any architecture decision records that track the event catalog.

## Adding a New Resilience Element

When creating a new resilience element that emits events:

### Step 1: Own a publisher

Every element instance must own its own `InqEventPublisher`. Create it during construction:

```java
public final class RateLimiter implements AutoCloseable {

    private final InqEventPublisher eventPublisher;

    RateLimiter(String name, RateLimiterConfig config,
                InqEventExporterRegistry registry, InqPublisherConfig publisherConfig) {
        this.eventPublisher = InqEventPublisher.create(
            name, InqElementType.RATE_LIMITER, registry, publisherConfig);
    }

    // Expose the publisher for consumer registration
    public InqEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    @Override
    public void close() {
        eventPublisher.close();
    }
}
```

### Step 2: Define element-specific events

Create one event class per distinct occurrence. Typical patterns:

| Event pattern        | Example                                | When emitted                     |
|----------------------|----------------------------------------|----------------------------------|
| Success              | `RateLimiterOnSuccessEvent`            | Call permitted and completed     |
| Failure              | `RateLimiterOnFailureEvent`            | Call permitted but threw         |
| Rejection            | `RateLimiterOnRejectionEvent`          | Call rejected (limit exceeded)   |
| State transition     | `CircuitBreakerOnStateTransitionEvent` | State machine changed state      |
| Configuration change | `RetryOnConfigChangeEvent`             | Dynamic reconfiguration occurred |

### Step 3: Add the element type

Register the new element kind in `InqElementType`:

```java
public enum InqElementType {
    CIRCUIT_BREAKER,
    RETRY,
    RATE_LIMITER,    // ← new
    NO_ELEMENT;

    public String errorCode(int index) {
        return name() + "-" + index;
    }
}
```

### Step 4: Publish on the calling thread

Events must be published synchronously inside the element's execution path. Do not offload publishing to a separate
thread — this would decouple the event timestamp from the actual occurrence and break the sequential delivery guarantee.

```java
public <T> T execute(Callable<T> callable) throws Exception {
    if (!tryAcquirePermit()) {
        var event = new RateLimiterOnRejectionEvent(callId, name, ...);
        eventPublisher.publish(event);
        throw new RateLimitExceededException(...);
    }
    try {
        T result = callable.call();
        eventPublisher.publish(new RateLimiterOnSuccessEvent(callId, name, ...));
        return result;
    } catch (Exception e) {
        eventPublisher.publish(new RateLimiterOnFailureEvent(callId, name, ...));
        throw e;
    }
}
```

## Implementing a Custom Exporter

### Basic exporter

```java
public class PrometheusEventExporter implements InqEventExporter {

    private final Counter eventCounter;

    public PrometheusEventExporter(MeterRegistry meterRegistry) {
        this.eventCounter = meterRegistry.counter("inqudium.events");
    }

    @Override
    public void export(InqEvent event) {
        eventCounter.increment();
    }
}
```

### Exporter with event type filtering

```java
public class StateTransitionAlertExporter implements InqEventExporter {

    @Override
    public void export(InqEvent event) {
        // Safe to cast — only called for matching types
        var transition = (CircuitBreakerOnStateTransitionEvent) event;
        alerting.fire(transition.getElementName(), transition.getToState());
    }

    @Override
    public Set<Class<? extends InqEvent>> subscribedEventTypes() {
        return Set.of(CircuitBreakerOnStateTransitionEvent.class);
    }
}
```

**Important:** `subscribedEventTypes()` is called exactly once when the registry freezes. The result is cached in a
`CachedExporter` record. Return a stable, immutable set. Changes after freeze are silently ignored.

### Ordered exporter

If execution order matters (e.g. an audit exporter that must run before a metrics exporter), implement
`Comparable<InqEventExporter>`:

```java
public class AuditExporter implements InqEventExporter, Comparable<InqEventExporter> {

    @Override
    public void export(InqEvent event) { /* ... */ }

    @Override
    public int compareTo(InqEventExporter other) {
        // Lower value = earlier execution
        if (other instanceof AuditExporter) return 0;
        return -1;  // always run before non-audit exporters
    }
}
```

The type parameter must be exactly `Comparable<InqEventExporter>`. The registry validates this at freeze time via
reflection (see `isCorrectlyComparable()`) and demotes incorrectly typed implementations to unordered with a warning.

### Non-blocking I/O exporter

Since `export()` runs on the caller's thread, I/O-bound exporters must buffer internally:

```java
public class KafkaEventExporter implements InqEventExporter {

    private final BlockingQueue<InqEvent> buffer = new LinkedBlockingQueue<>(10_000);

    public KafkaEventExporter() {
        Thread.ofVirtual().name("kafka-exporter-drain").start(this::drainLoop);
    }

    @Override
    public void export(InqEvent event) {
        if (!buffer.offer(event)) {
            // Buffer full — drop event, log warning
            LOG.warn("Kafka export buffer full — event dropped: {}", event.getClass().getSimpleName());
        }
    }

    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                InqEvent event = buffer.take();
                kafkaProducer.send(serialize(event));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
```

## Registry State Machine

The `InqEventExporterRegistry` uses a sealed interface state machine with three states:

```
           register()               first export()
  ┌──────┐          ┌──────┐                       ┌─────────┐
  │ Open │◄────────►│ Open │──────────────────────►│Resolving│
  └──┬───┘  CAS     └──────┘    CAS wins           └────┬────┘
     │      loop                                        │
     │                              ServiceLoader       │
     │                              discovery           │
     │                                                  │
     │                              ┌────────┐          │
     │                              │ Frozen │◄─────────┘
     │                              └────────┘   CAS succeeds
     │                                  │
     │         reset()                  │
     └──────────────────────────────────┘
              (testing only)
```

**Open** — accepts `register()` calls. Holds a `List<InqEventExporter>` of programmatically registered exporters.
Multiple concurrent `register()` calls use CAS to append to this list.

**Resolving** — entered when the first thread calls `getExporters()`. That thread performs ServiceLoader discovery,
sorts exporters, caches event type filters, and transitions to Frozen. Other threads park with exponential backoff (
1µs → 100ms) and a 30-second timeout. If the resolver thread dies, waiting threads reset to Open.

**Frozen** — the terminal production state. The exporter list is immutable. `register()` throws `IllegalStateException`.
`export()` iterates the cached list directly.

### Why a state machine instead of double-checked locking?

Double-checked locking would require a `synchronized` block on the resolution path. The state machine avoids locks
entirely — all transitions use CAS. The `Resolving` intermediate state explicitly communicates "another thread is doing
ServiceLoader I/O" and allows waiters to park instead of spinning.

### Timeout-based recovery

If the resolver thread hangs (e.g. a ServiceLoader provider blocks in its constructor), waiting threads detect the
timeout after 30 seconds and reset the state to Open via CAS. This triggers a fresh resolution attempt. The resolver
thread's stale CAS will fail harmlessly when it eventually completes.

## Watchdog Internals

The `InqConsumerExpiryWatchdog` is intentionally simple — a sleep loop on a virtual thread:

```
  sleep(interval)
       │
       ▼
  owner = ownerRef.get()  ──► null? → stop (owner was GC'd)
       │
       ▼
  sweepAction.accept(owner)
       │
       ▼
  loop
```

### Lazy start via CAS

The watchdog is created and started in `ensureWatchdogStarted()`:

```java
private void ensureWatchdogStarted() {
    if (watchdog.get() != null) return;

    var newWatchdog = new InqConsumerExpiryWatchdog(this, interval, sweepAction);

    if (watchdog.compareAndSet(null, newWatchdog)) {
        newWatchdog.startThread();  // only the CAS winner starts the thread
    }
    // CAS loser: newWatchdog is silently discarded (no thread was started)
}
```

Construction and thread start are split into two steps. This ensures that only the thread that wins the CAS actually
starts a virtual thread. The losing watchdog instance has no running thread and is eligible for GC immediately.

### WeakReference design

The watchdog holds the publisher via `WeakReference<DefaultInqEventPublisher>`. If the publisher is garbage collected (
e.g. the element is discarded without calling `close()`), the watchdog detects `ownerRef.get() == null` on its next
sweep cycle and exits the loop. This prevents the watchdog from keeping a dead publisher alive indefinitely.

The `ownerName` string is captured eagerly at construction time for logging — after the owner is GC'd, we still want
meaningful log messages identifying which watchdog stopped.

### Known race condition: close() vs. ensureWatchdogStarted()

After `close()` sets the watchdog `AtomicReference` to `null`, a concurrent `onEvent(..., ttl)` call can create and
start a new watchdog. This is documented as a known limitation. A `closed` flag would fix this but adds a volatile read
to every `onEvent()` call. Given that `close()` is rarely called (most publishers live for the application's lifetime),
this trade-off is acceptable. If you change this, add a test that races `close()` against TTL registration.

## Error Handling Patterns

### rethrowIfFatal()

The codebase uses `InqException.rethrowIfFatal(t)` after every catch-all block. This rethrows errors that should never
be swallowed:

- `VirtualMachineError` (including `OutOfMemoryError`, `StackOverflowError`)
- `ThreadDeath`
- `LinkageError`

Every new catch block on the publish or export path must call this before logging.

### Provider error audit trail

When a ServiceLoader provider fails during discovery, the error is captured as an `InqProviderErrorEvent` and added to a
collection. After the registry freezes, these events are replayed to all successfully resolved exporters. This ensures
that even bootstrap-phase failures are observable through the event system itself.

The replay happens in a separate try-block so that a failing exporter during replay cannot undo the successfully frozen
registry state.

### ProviderPhase enum

`InqProviderErrorEvent.ProviderPhase` maps failure phases to structured error codes:

| Phase          | Error index | Meaning                                       |
|----------------|-------------|-----------------------------------------------|
| `CONSTRUCTION` | 1           | Provider instantiation failed (ServiceLoader) |
| `EXECUTION`    | 2           | Provider method threw during operation        |

When adding a new failure phase (e.g. for a future provider validation step), add a new enum constant with the next
sequential error index.

## Performance-Critical Code Paths

### The publish hot path

This is the most performance-sensitive code in the entire system. Any change here must be validated with the JMH
benchmark:

```java
public void publish(InqEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    ConsumerEntry[] currentConsumers = consumers.get();  // volatile read — only sync point

    for (int i = 0; i < currentConsumers.length; i++) {  // indexed loop, no iterator
        ConsumerEntry entry = currentConsumers[i];
        try {
            entry.consumer().accept(event);
        } catch (Throwable t) {
            InqException.rethrowIfFatal(t);
            LOGGER.warn(...);
        }
    }

    try {
        registry.export(event);
    } catch (Throwable t) {
        InqException.rethrowIfFatal(t);
        LOGGER.warn(...);
    }
}
```

**Things you must not do in this method:**

- Allocate objects (no `new`, no `List.of()`, no string concatenation outside the catch block)
- Call `Instant.now()` (the event already carries a timestamp)
- Check expiry (delegated to the watchdog — keep the hot path clean)
- Add synchronized blocks or ReentrantLock
- Use enhanced for-each on the array (the JIT usually eliminates the difference, but indexed access is explicit about
  intent)

### The export hot path

After freezing, `getExporters()` returns the cached list immediately:

```java
if (current instanceof Frozen frozen) {
    return frozen.exporters;  // single volatile read + pattern match
}
```

The `instanceof` check and pattern extraction compile to a type guard and field load. This is the only overhead per
export call in steady state.

### Sweep on the add path

`addConsumer()` calls `sweepExpired()` synchronously before checking limits. This is necessary for correctness (expired
consumers must not count towards the hard limit) but allocates a new array if expired entries exist. This allocation is
acceptable because `addConsumer()` is not on the publish hot path — it runs during subscription setup, not during event
delivery.

## Extending the Configuration

### Adding a new config parameter

`InqPublisherConfig` is a Java record. Adding a parameter requires:

1. Add the field to the record declaration.
2. Add validation in the compact constructor.
3. Update `defaultConfig()` with a sensible default.
4. Update all `of()` factory methods — consider whether the new parameter needs to appear in the common factory or only
   in the full-control variant.
5. Update `DefaultInqEventPublisher` to read the new parameter.
6. Update the user guide's configuration table.

```java
public record InqPublisherConfig(
    int softLimit,
    int hardLimit,
    Duration expiryCheckInterval,
    boolean traceEnabled,
    boolean newParameter       // ← step 1
) {
    public InqPublisherConfig {
        // ... existing validation ...
        // step 2: validate newParameter if needed
    }
}
```

Keep the number of factory methods minimal. The previous codebase had six factory methods for four fields — three were
removed during the consolidation. Before adding a new convenience factory, verify that it serves at least two distinct
call sites.

## Testing Strategy

### Unit test structure

Tests are organized by `@Nested` inner classes, one per concern:

```
InqEventSystemTest
├── Publishing                  — event delivery, ordering, error isolation
├── TypedConsumerFiltering      — type-based dispatch
├── SubscriptionManagement      — cancel, idempotency, double registration
├── TtlSubscriptions            — TTL lifecycle, early cancel, validation
├── ConsumerLimits              — soft/hard limit enforcement
├── TracePublishing             — lazy supplier evaluation
├── PublisherLifecycle          — close, toString
├── ExpirySweep                 — sweep logic, array identity
├── ExporterRegistry            — registration, freeze, reset, filtering
├── PublisherConfiguration      — defaults, validation
├── ProviderErrorEventTests     — enum mapping, field storage
├── InqEventValidation          — base class null/blank checks
├── ConcurrentPublishing        — multi-threaded safety
└── ConsumerEntryExpiry         — record-level expiry semantics
```

### Testing concurrency

Concurrency tests use `CountDownLatch` for synchronization and `CopyOnWriteArrayList` for thread-safe collection.
Assertions verify that no events are lost and no exceptions occur, but do not assert exact ordering across threads (
which would be non-deterministic).

### Testing TTL deterministically

Never rely on `Thread.sleep()` for TTL expiry in tests. Instead:

1. Register a consumer with a very short TTL (e.g. 1ms).
2. Sleep just long enough for the TTL to expire (e.g. 50ms).
3. Call `performExpirySweep()` directly on the cast publisher.
4. Assert the consumer no longer receives events.

This avoids flaky tests caused by watchdog scheduling variance.

### Testing the registry in isolation

Always create a fresh `InqEventExporterRegistry` per test. Never use `InqEventExporterRegistry.getDefault()` in tests —
it is a shared global singleton that causes cross-test pollution. If a test needs a frozen registry, trigger `export()`
explicitly.

## Checklist for Event System Changes

Before submitting a change to the event system:

- [ ] **Benchmark.** Run `InqEventSystemBenchmark` and compare against the baseline. Any increase in
  `gc.alloc.rate.norm` on the first four scenarios is a regression.
- [ ] **Thread safety.** If you touched the publish path or consumer management, run the concurrency tests with
  `-Djunit.jupiter.execution.parallel.enabled=true`.
- [ ] **Fatal error handling.** Every new `catch (Throwable t)` block must call `InqException.rethrowIfFatal(t)` before
  logging.
- [ ] **Null safety.** Every public method parameter must be validated with `Objects.requireNonNull`. Constructor fields
  must be validated before assignment.
- [ ] **Immutability.** Event objects must be immutable. Config records must be validated in the compact constructor.
- [ ] **Idempotency.** `cancel()` and `close()` must be safe to call multiple times.
- [ ] **Documentation.** Update the user guide if the change affects the public API. Update this developer guide if the
  change affects internals.
- [ ] **Tests.** Follow the Given/When/Then structure with AssertJ assertions. Add the test to the appropriate `@Nested`
  category.
