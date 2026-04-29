# ADR-031: Per-runtime event registry isolation

**Status:** Proposed
**Date:** 2026-04-29
**Deciders:** Core team

## Introduction

The framework's event system uses a global exporter registry by default. Every
`InqEventPublisher` constructed without an explicit registry binds to
`InqEventExporterRegistry.getDefault()`, which means an exporter registered there sees events
from every publisher in the JVM. This works correctly for the common case of one resilience
runtime per JVM. It raises a question for cases where multiple `InqRuntime` instances coexist
in the same process: should each runtime own its own isolated exporter registry, or continue
to share the global one?

This ADR keeps the global default and adds an opt-in convenience for per-runtime isolation
through the existing builder hooks. The reasoning below explains why flipping the default
would do more harm than good, and what the opt-in path looks like for callers that need
isolation.

## Context

The runtime exposes one `GeneralSnapshot` containing the cross-cutting collaborators every
component shares — clock, nano time source, logger factory, and two event-related fields:

- A **runtime-scoped publisher** (`GeneralSnapshot.eventPublisher`) that carries lifecycle
  topology events such as `ComponentBecameHotEvent`, `RuntimeComponentAddedEvent`, and
  `RuntimeComponentVetoedEvent`.
- A **per-component publisher factory** (`GeneralSnapshot.componentPublisherFactory`) that
  hands out per-component `InqEventPublisher`s at component-materialization time per
  ADR-030.

Both default paths delegate to `InqEventPublisher.create(elementName, elementType)`, which in
turn binds to the global default registry returned by `InqEventExporterRegistry.getDefault()`.
The defaults preserve the established event/exporter contract documented in ADR-003: an
`InqEventExporter` registered once via `ServiceLoader` (or programmatically on `getDefault()`)
sees events from every publisher in the JVM.

### What "isolation" would mean concretely

Two `InqRuntime` instances `A` and `B` materialized in the same JVM — a multi-tenant test
harness, an application embedding two independent resilience configurations, a CI pipeline
that constructs throwaway runtimes per scenario — currently share the global default
registry. An exporter that registered on `getDefault()` receives events from `A`'s bulkheads,
`B`'s bulkheads, and any other publisher in the JVM. The exporter has no reliable way to know
which runtime an event came from beyond `event.getElementName()`, which was never designed as
a runtime discriminator.

Per-runtime isolation would mean: each `InqRuntime` constructs its own
`InqEventExporterRegistry` at build time. Every publisher created through that runtime — the
runtime-scoped publisher and every component publisher produced by the factory — binds to the
runtime's own registry. Exporters that the application registered on `getDefault()` are
*not* automatically discovered by the runtime; the application must wire them into the
runtime's registry explicitly if cross-runtime aggregation is desired.

### Why the question is not trivially "yes"

Three considerations push back on a hard isolation default.

**Migration cost.** Downstream applications currently rely on global-registry binding —
directly via `getDefault()` or transitively via `InqEventPublisher.create(name, type)`. An
isolated default flips this contract. Applications that worked yesterday would silently stop
seeing events from new `InqRuntime`s. The behavioural break is invisible — no compile error,
no exception — until an operator notices missing data on their dashboard.

**Existing behaviour is well-understood.** `ServiceLoader`-discovered exporters via
`getDefault()` are how the framework's exporters (Kafka, JFR, anything users have built on
top) plug in. Switching the default to isolated changes the registration story for the entire
ecosystem, not just for any single component.

**The use case for isolation has a small audience.** Most deployments have one `InqRuntime`
per JVM. The multi-runtime case shows up in tests and in some multi-tenant scenarios; the
default behaviour serves the common case correctly today.

### What already works for the people who need isolation

`GeneralSnapshotBuilder.eventPublisher(...)` and `componentPublisherFactory(...)` both let
callers inject custom values. A test or a multi-tenant integration that wants isolated event
flow can already do:

```java
var registry = new InqEventExporterRegistry();
var runtimePublisher = InqEventPublisher.create(
    "runtime-A", InqElementType.NO_ELEMENT, registry, InqPublisherConfig.defaultConfig());
ComponentEventPublisherFactory factory = (n, t) -> InqEventPublisher.create(
    n, t, registry, InqPublisherConfig.defaultConfig());

InqRuntime runtimeA = Inqudium.configure()
    .general(g -> g.eventPublisher(runtimePublisher).componentPublisherFactory(factory))
    .imperative(...)
    .build();
```

This works today. It is not concise, but it is composable, explicit, and uses no private
API. The test suite in `GeneralSnapshotBuilderTest` and `GeneralSnapshotTest` already
exercises this pattern.

The question this ADR settles is therefore narrower than "should isolation exist?" — it
does, through the existing override hooks. The question is whether *the default behaviour*
should flip to isolated, and if so, what the migration path is.

## Decision

**Keep the global default. Add a documented opt-in convenience for per-runtime isolation.**

The default — `getDefault()`-bound publishers for both the runtime-scoped publisher and the
component factory — stays as it is. This preserves the established event/exporter contract,
requires no migration of existing applications, and matches the JVM-wide-singleton mental
model that the ADR-003 event system was built around.

For the cases that do need isolation, a new convenience entry point on
`GeneralSnapshotBuilder` constructs a fresh `InqEventExporterRegistry` and wires both the
runtime-scoped publisher and the component factory to it in one step:

```java
InqRuntime isolatedRuntime = Inqudium.configure()
    .general(g -> g.isolatedEventRegistry())   // ← new
    .imperative(...)
    .build();
```

`isolatedEventRegistry()` is sugar over the explicit override pattern shown above. The fresh
registry is owned by the runtime and follows the runtime's lifecycle: when
`InqRuntime.close()` runs, the runtime closes its own publishers and the registry becomes
eligible for collection. There is no `setDefault()` interaction, no global state mutation.

The design rationale collapses to three points.

**Defaults serve the common case correctly.** One runtime per JVM with shared exporters is
the common case. Flipping the default would break far more applications than it would
benefit, silently and irreversibly until detected.

**Isolation is composable, not magical.** The override mechanism that enables isolation
already works. The new builder method is a one-line wrapper around it, not a new
architectural feature. This keeps the surface area small and the test story unchanged.

**The choice is reversible.** If a future major version decides isolation should be the
default, the migration is straightforward: flip the default, add a `sharedEventRegistry()`
method as the opposite convenience, document the change in release notes. Today's choice
does not lock that out.

### Lifecycle and ownership

When `isolatedEventRegistry()` is used:

- The runtime constructs an `InqEventExporterRegistry` at `build()` time. The registry is
  reachable only through the runtime's publishers; no static reference, no `setDefault()`.
- Both the runtime-scoped publisher and the component factory bind to this registry. The
  runtime is the single owner.
- Runtime-level event publishers are constructed with the standard
  `InqEventPublisher.create(name, type, registry, config)` overload. Per-component
  publishers flow through a factory closure that captures the registry.
- `InqRuntime.close()` does not need to call `registry.reset()` or any registry-level
  cleanup — the publishers themselves close, and the registry becomes garbage along with the
  runtime.

When `isolatedEventRegistry()` is *not* used (the default):

- The runtime-scoped publisher is constructed via `InqEventPublisher.create(name, type)`
  with no explicit registry — it binds to `getDefault()`.
- The component factory defaults to `InqEventPublisher::create` (the two-argument
  overload), which similarly binds to `getDefault()`.

### What `isolatedEventRegistry()` does not do

- It does not register an exporter, ServiceLoader-discovered or otherwise. The runtime gets
  a fresh registry with whatever programmatic exporters the application registers on it; if
  the application wants ServiceLoader-discovered exporters in the isolated registry, it
  must trigger discovery explicitly (via the registry's existing freeze-on-first-use
  behaviour).
- It does not affect any other runtime, the global default registry, or any publisher
  constructed outside the runtime.
- It does not isolate by component or by paradigm — the unit of isolation is the entire
  runtime.

## Consequences

### Positive

- No migration cost for existing applications. Code that worked before keeps working.
- Multi-runtime tests can express isolation in a single line. The verbose pattern that
  works today still works for callers who need finer control over the registry.
- The framework-wide event/exporter contract documented in ADR-003 is preserved unchanged.
- The new builder method adds one line of public API, with no internal restructuring.

### Negative

- The `getDefault()` registry remains a piece of global state that interacts with the
  runtime through a non-obvious default path. A reader who is unaware of ADR-003's
  default-registry-on-`InqEventPublisher.create(name, type)` rule may not realize their
  publisher binds to a JVM-wide registry. This is mitigated by Javadoc and by the
  `isolatedEventRegistry()` opt-in being a discoverable alternative.
- Multi-tenant production scenarios that need isolation must opt in. The option is exposed
  but not advertised as the right choice for any specific workload.

### Neutral

- The `InqEventExporterRegistry` class itself needs no changes. Its existing per-instance
  constructor and freeze-on-first-export semantics are exactly what isolation needs.
- The component-publisher factory contract from ADR-030 is unchanged. The factory was
  always a closure over whatever registry binding the implementation chose; this ADR adds
  one more ready-made closure that captures an isolated registry.

## Implementation notes

The work is small, given that the override hooks already exist:

1. Add `isolatedEventRegistry()` to `GeneralSnapshotBuilder`. The method allocates a fresh
   `InqEventExporterRegistry`, constructs a runtime-scoped publisher bound to it, and
   stores both the publisher (via the existing `eventPublisher` field) and a closure-based
   factory (via `componentPublisherFactory`) on the builder.
2. Document on both the new method and the existing `eventPublisher` /
   `componentPublisherFactory` methods what the registry binding is in each case (default →
   `getDefault()`; `isolatedEventRegistry()` → fresh registry; explicit override →
   caller's choice).
3. Tests:
   - The convenience method produces a registry that does not see events from another
     runtime.
   - The convenience method's registry sees events from both the runtime publisher and
     from component publishers created through the factory.
   - The default behaviour is unchanged: events from a default-built runtime reach
     `getDefault()`-registered exporters.
   - `InqRuntime.close()` releases the publishers; subsequent publishes throw the
     documented exception (or are no-ops, depending on the publisher contract).

The change does not affect any existing test, because the default path is preserved.
