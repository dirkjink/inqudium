# ADR-030: Component event publisher scope

**Status:** Proposed
**Date:** 2026-04-26
**Deciders:** Core team
**Related:** ADR-003 (event publisher), ADR-025 (configuration architecture), ADR-026 (runtime
container), ADR-029 (component lifecycle implementation pattern). Driver for refactor step 1.9.

## Context

Phase 1.9 of the configuration refactor ports the bulkhead's per-call events
({`BulkheadOnAcquireEvent`, `BulkheadOnReleaseEvent`, `BulkheadOnRejectEvent`,
`BulkheadWaitTraceEvent`, `BulkheadRollbackTraceEvent`}, all gated by `BulkheadEventConfig`)
into the new {`InqBulkhead`}/`BulkheadHotPhase`. The TODO comment above
`BulkheadHotPhase#execute` flags the work and points at this ADR for the design decision.

The current state after step 1.7 is:

- `GeneralSnapshot` has one `InqEventPublisher` field — the **runtime-scoped publisher**,
  created with element name `"inqudium-runtime"` and `InqElementType.NO_ELEMENT`.
- The lifecycle base class (`ImperativeLifecyclePhasedComponent`) uses this publisher to emit
  `ComponentBecameHotEvent` (a runtime-topology event per ADR-026 / ADR-028).
- No per-component publisher exists yet. The bulkhead has no event channel of its own.

The pre-refactor `ImperativeBulkhead` followed ADR-003: each bulkhead instance owned a private
`InqEventPublisher` created with `(name, InqElementType.BULKHEAD)`. The runtime-scoped publisher
in `GeneralSnapshot` is a new addition introduced in step 1.7 and serves a different purpose
(runtime topology, not per-call traces).

To wire 1.9 events through, we have to decide: where do per-call bulkhead events go?

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
- Backpressure leaks across components. A high-traffic bulkhead's events delay every
  consumer's view of every other component's events because dispatch is sequential
  (per ADR-003: "Consumers and exporters are invoked sequentially on the calling thread").
- Lifecycle is wrong. When a component is removed (phase 2), per-component subscriptions
  cannot be cleaned up automatically — the runtime publisher does not know which subscribers
  belonged to which component.
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

The bulkhead constructs its publisher via the factory:

```java
// In InqBulkhead constructor (phase 1.9)
this.eventPublisher = general.componentPublisherFactory().create(name, InqElementType.BULKHEAD);
```

Subscribers reach a specific component's publisher through its handle:

```java
// Subscriber side
ImperativeBulkhead inventory = runtime.imperative().bulkhead("inventory");
inventory.eventPublisher().onEvent(BulkheadOnAcquireEvent.class, event -> { ... });
```

(A new accessor `eventPublisher()` lands on the bulkhead handle as part of 1.9.)

**Pros:**
- Subscription granularity matches the user's mental model: subscribe to *this* bulkhead's
  events, not "all events filtered down to this bulkhead". No per-event filter cost on the hot
  path.
- Backpressure isolated. A noisy bulkhead's consumers contend only with each other; quiet
  bulkheads stay snappy regardless.
- Lifecycle binding is automatic. When phase 2 introduces structural removal, the component
  closes its publisher; subscribers are released; nothing leaks.
- Consistent with ADR-003. The pre-refactor architecture already worked this way; the new
  architecture preserves that property without a special case.
- Continuity with `InqEventPublisher.create(name, type)` semantics. The publisher's own
  identity (name, type) lines up with the events it carries — no synthetic
  `"inqudium-runtime"` name behind events that conceptually belong to "inventory".

**Cons:**
- Cross-component dashboards have to subscribe to multiple publishers (one per component).
  Mitigated by a thin helper on the runtime: a future `runtime.config().forEachBulkhead(c -> ...)`
  iterator that subscribes to each. Out of scope for 1.9.
- The factory is one extra field on `GeneralSnapshot`. The default factory delegates to
  `InqEventPublisher.create(name, type)` so the common case (no test injection, no custom
  exporter wiring) requires zero code.
- `runtime.close()` does not currently propagate to per-component publishers in phase 1
  because phase 1 has no structural removal. The publishers stay alive until JVM shutdown.
  This is a known phase-1 trade-off; phase 2's structural removal closes them as part of
  component shutdown. Documented in 1.9; explicitly noted as "phase-2 responsibility".

## Decision

**Option B.** Add a `ComponentEventPublisherFactory` to `GeneralSnapshot` and have each
component construct its own publisher at materialization time. The runtime-scoped publisher
stays for topology events.

The decision is driven primarily by ADR-003 continuity (per-element publishers were already the
established pattern) and by the lifecycle correctness gain (component removal in phase 2
closes only the affected component's publisher; subscribers are released cleanly without the
runtime needing a registry of which subscribers belong to which component).

The cross-component-dashboard cost is real but addressable — a paradigm-iterating helper on
the runtime closes the gap with a few lines of code, and it can land when there is concrete
demand. Until then, a dashboard that wants every bulkhead subscribes per-bulkhead in a loop
over `runtime.imperative().bulkheadNames()`.

## Consequences

**Positive:**

- ADR-003 stays the rule, not the exception. Per-component publishers continue across the
  refactor without a special case for the new architecture.
- Hot-path filtering cost removed. Subscribers see only the events they asked for; the
  dispatcher does not iterate over irrelevant consumers.
- Backpressure isolated per component. A noisy `inventory` bulkhead's consumer pool does not
  delay `payments` event delivery.
- Phase-2 structural removal has an obvious cleanup path: closing the component's publisher.
  No per-subscriber tracking at the runtime level.
- Tests can inject a custom factory through `GeneralSnapshotBuilder.componentPublisherFactory(...)`
  to capture per-component events into isolated registries — already the pattern lifecycle
  tests use today for the runtime publisher.

**Negative:**

- Cross-component subscribers must subscribe per-component. Mitigated by a future helper;
  acceptable as a phase-1 cost.
- `GeneralSnapshot` grows by one field. The default factory keeps the common case
  zero-ceremony; users opting out of the default pay the explicit-setter cost only when they
  want isolation.
- Runtime close in phase 1 does not propagate to per-component publishers. Known trade-off,
  documented; phase 2's structural removal closes them as part of component shutdown.

**Neutral:**

- The runtime-scoped publisher stays unchanged. Topology events
  (`ComponentBecameHotEvent`, `RuntimeComponentAddedEvent`, `RuntimeComponentPatchedEvent`,
  `RuntimeComponentVetoedEvent`) keep their separate channel. The split is by concern (topology
  vs per-call traces), not by accident.
- The factory's signature uses `(String name, InqElementType type)` to mirror the existing
  `InqEventPublisher.create(name, type)` form. Adding a third parameter (e.g. publisher
  config) later is a backwards-compatible interface change with default methods.
- The bulkhead handle gains an `eventPublisher()` accessor in 1.9. Other paradigm bulkhead
  handles (`ReactiveBulkhead`, etc.) get the same accessor when they land — the contract is
  paradigm-agnostic.

## Phase-1 implementation outline (driven by step 1.9)

Once this ADR is accepted:

1. Add `ComponentEventPublisherFactory` interface in `eu.inqudium.config.snapshot` (next to
   `GeneralSnapshot`).
2. Add the factory field to `GeneralSnapshot` and `GeneralSnapshotBuilder`. Default delegates
   to `InqEventPublisher.create(name, type)`.
3. `InqBulkhead` constructor builds its own publisher from the factory; a new field
   `private final InqEventPublisher componentEventPublisher` stores it. Add the
   `eventPublisher()` accessor on `ImperativeBulkhead`.
4. `BulkheadHotPhase` reads `component.eventPublisher()` and publishes the four bulkhead
   events at the documented points (acquire / release / reject / wait trace), gated by a new
   `BulkheadEventConfig` field on `BulkheadSnapshot`.
5. The lifecycle base class continues to publish `ComponentBecameHotEvent` on the
   runtime-scoped publisher; that path does not change.
6. Tests cover: default factory equivalence to legacy behaviour, custom factory injection,
   per-component subscription via the handle, gating via `BulkheadEventConfig`, event payload
   correctness for each of the four events.

The runtime-`close` propagation to per-component publishers remains explicitly out of scope
for phase 1 and is the responsibility of phase 2's structural-removal work item.
