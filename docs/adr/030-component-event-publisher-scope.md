# ADR-030: Component event publisher scope

**Status:** Accepted
**Date:** 2026-04-26
**Deciders:** Core team
**Related:** ADR-003 (event publisher), ADR-025 (configuration architecture), ADR-026 (runtime
container), ADR-029 (component lifecycle implementation pattern).

## Context

Every resilience component (bulkhead, circuit breaker, retry, time limiter, ...) emits per-call
events — typically a small set of state-transition events plus optional traces, gated by a
component-specific event-config record. The bulkhead is the first component refactored against
the new configuration architecture, so it serves as the running example here, but the question
this ADR settles is framework-wide: where do per-call component events go?

The bulkhead's events are {`BulkheadOnAcquireEvent`, `BulkheadOnReleaseEvent`,
`BulkheadOnRejectEvent`, `BulkheadWaitTraceEvent`, `BulkheadRollbackTraceEvent`}, gated by
`BulkheadEventConfig`. Other components carry analogous event sets — `CircuitBreakerOnOpenEvent`,
`RetryOnAttemptEvent`, etc. — that need the same channel decision.

Two channels exist in the configuration architecture:

- The **runtime-scoped publisher** carried on `GeneralSnapshot`. It is created with element
  name `"inqudium-runtime"` and `InqElementType.NO_ELEMENT`, and is used by the lifecycle base
  class (`ImperativeLifecyclePhasedComponent`) to emit topology events
  (`ComponentBecameHotEvent` per ADR-026 / ADR-028).
- A **per-component publisher** owned by each component instance, created with the component's
  own `(name, type)` identity. ADR-003 already established this pattern: *"Each element
  instance owns its own publisher."*

The architectural question is whether per-call component events should flow through the
runtime-scoped publisher (one channel for everything) or through a per-component publisher
owned by the component instance (one channel per component). The answer is a framework-wide
rule, not a bulkhead-only decision.

## Two options

### Option A — Share the runtime publisher across every component

Every `InqBulkhead` (and later `InqCircuitBreaker`, `InqRetry`, ...) publishes its events
through `GeneralSnapshot.eventPublisher`. Subscribers register at the runtime level and filter
by event type plus the event's own `elementName` / `elementType` metadata.

```java
// Subscriber side
runtime.general().eventPublisher().onEvent(BulkheadOnAcquireEvent.class, event -> {
    if ("inventory".equals(event.getElementName())) {
        // ...
    }
});
```

**Pros:**
- One publisher to manage. One close path. One `ServiceLoader` exporter binding.
- Cross-component dashboards subscribe once and filter; no per-component wiring.

**Cons:**
- Subscription granularity is coarse — every consumer sees every component's events and must
  filter. Filtering costs CPU on the publish path (the dispatcher iterates every consumer for
  every event).
- Backpressure leaks across components. A high-traffic component's events (e.g. a busy
  bulkhead) delay every consumer's view of every other component's events because dispatch is
  sequential (per ADR-003: "Consumers and exporters are invoked sequentially on the calling
  thread").
- Lifecycle is wrong. When a component is removed, per-component subscriptions cannot be
  cleaned up automatically — the runtime publisher does not know which subscribers belonged
  to which component.
- Conflicts with ADR-003. The existing rule is *"Each element instance owns its own
  publisher"*; sharing the runtime publisher across components flips that on its head.

### Option B — Per-component publisher factory on `GeneralSnapshot`

Add a factory field to `GeneralSnapshot`. Each component constructs its own publisher at
materialization time, with element name and element type baked in. The runtime-scoped
publisher stays — it carries lifecycle topology events
(`ComponentBecameHotEvent`, `RuntimeComponentAddedEvent`, `RuntimeComponentVetoedEvent`).

```java
public record GeneralSnapshot(
        InqClock clock,
        InqNanoTimeSource nanoTimeSource,
        InqEventPublisher runtimeEventPublisher,                  // existing — topology
        ComponentEventPublisherFactory componentPublisherFactory, // new — per-component
        LoggerFactory loggerFactory) { ... }

@FunctionalInterface
public interface ComponentEventPublisherFactory {
    InqEventPublisher create(String elementName, InqElementType elementType);
}
```

Each component constructs its publisher via the factory at materialization time. For the
bulkhead, that looks like:

```java
// In InqBulkhead constructor — the same shape applies to other component constructors
this.eventPublisher = general.componentPublisherFactory().create(name, InqElementType.BULKHEAD);
```

Subscribers reach a specific component's publisher through its handle:

```java
// Subscriber side — using a bulkhead as the example component
ImperativeBulkhead inventory = runtime.imperative().bulkhead("inventory");
inventory.eventPublisher().onEvent(BulkheadOnAcquireEvent.class, event -> { ... });
```

Every component handle exposes an `eventPublisher()` accessor for this purpose. The contract
is paradigm-agnostic and component-type-agnostic.

**Pros:**
- Subscription granularity matches the user's mental model: subscribe to *this* component's
  events, not "all events filtered down to this component". No per-event filter cost on the
  hot path.
- Backpressure isolated. A noisy component's consumers contend only with each other; quiet
  components stay snappy regardless.
- Lifecycle binding is automatic. When structural removal lands, the component closes its
  publisher; subscribers are released; nothing leaks.
- Consistent with ADR-003. Per-element publishers are the established framework rule; this
  decision preserves the rule rather than carving out an exception.
- Continuity with `InqEventPublisher.create(name, type)` semantics. The publisher's own
  identity (name, type) lines up with the events it carries — events that conceptually belong
  to a specific component (say, the `"inventory"` bulkhead) are not delivered behind a
  synthetic `"inqudium-runtime"` name.

**Cons:**
- Cross-component dashboards have to subscribe to multiple publishers (one per component).
  Mitigated by a thin helper on the runtime: a future iterator that walks every component of
  a given type (e.g. every bulkhead) and subscribes to each.
- The factory is one extra field on `GeneralSnapshot`. The default factory delegates to
  `InqEventPublisher.create(name, type)` so the common case (no test injection, no custom
  exporter wiring) requires zero code.
- `runtime.close()` does not currently propagate to per-component publishers because the
  runtime does not yet support structural component removal. The publishers stay alive until
  JVM shutdown. This is a temporary state — once structural removal exists, component
  shutdown closes its publisher as part of the cascade, and the cleanup gap closes
  automatically.

## Decision

**Option B.** Add a `ComponentEventPublisherFactory` to `GeneralSnapshot` and have each
component construct its own publisher at materialization time. The runtime-scoped publisher
stays for topology events.

The decision is driven primarily by ADR-003 continuity (per-element publishers are the
established pattern) and by the lifecycle correctness gain: component removal closes only the
affected component's publisher, releasing its subscribers cleanly without the runtime needing
a registry of which subscribers belong to which component.

The cross-component-dashboard cost is real but addressable — a component-iterating helper on
the runtime closes the gap with a few lines of code, and it can land when there is concrete
demand. Until then, a dashboard that wants every component of a given type (e.g. every
bulkhead) iterates the per-paradigm container's name list and subscribes to each.

## Consequences

**Positive:**

- ADR-003 stays the rule, not the exception. Per-component publishers are the framework-wide
  pattern for per-call events.
- Hot-path filtering cost removed. Subscribers see only the events they asked for; the
  dispatcher does not iterate over irrelevant consumers.
- Backpressure isolated per component. A noisy component's consumer pool (e.g. an
  `inventory` bulkhead's) does not delay event delivery for any other component (e.g. a
  `payments` retry).
- Structural removal has an obvious cleanup path: closing the component's publisher. No
  per-subscriber tracking at the runtime level.
- Tests can inject a custom factory through `GeneralSnapshotBuilder.componentPublisherFactory(...)`
  to capture per-component events into isolated registries.

**Negative:**

- Cross-component subscribers must subscribe per-component. Mitigated by a future helper on
  the runtime; an acceptable cost.
- `GeneralSnapshot` grows by one field. The default factory keeps the common case
  zero-ceremony; users opting out of the default pay the explicit-setter cost only when they
  want isolation.
- Until structural component removal exists, `runtime.close()` does not propagate to
  per-component publishers — they stay alive until JVM shutdown. A temporary state, resolved
  once component shutdown becomes part of the runtime cascade.

**Neutral:**

- The runtime-scoped publisher stays unchanged. Topology events
  (`ComponentBecameHotEvent`, `RuntimeComponentAddedEvent`, `RuntimeComponentPatchedEvent`,
  `RuntimeComponentVetoedEvent`) keep their separate channel. The split is by concern (topology
  vs per-call traces), not by accident.
- The factory's signature uses `(String name, InqElementType type)` to mirror the existing
  `InqEventPublisher.create(name, type)` form. Adding a third parameter (e.g. publisher
  config) later is a backwards-compatible interface change with default methods.
- Every component handle exposes an `eventPublisher()` accessor — bulkheads, circuit
  breakers, retries, time limiters. Across paradigms (imperative, reactive, RxJava 3,
  coroutines) the accessor carries the same contract; the publisher is paradigm-agnostic.

