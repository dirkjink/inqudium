# ADR-026: InqRuntime and the component registry

**Status:** Accepted
**Date:** 2026-04-26
**Deciders:** Core team
**Related:** ADR-025 (configuration architecture), ADR-027 (validation strategy)

## Context

Components in this framework are named, paradigm-typed, and live for the application's lifetime: a bulkhead
"inventory" in the imperative paradigm is one specific runtime object, distinct from a bulkhead "inventory" in
the reactive paradigm. Application code obtains these components by name and uses them to decorate suppliers,
callables, publishers, or coroutines.

Some structure mapping names to components — a *registry*, in some form — is the natural answer to "how does
the application get its component instances?". Several design questions arise around such a structure:

1. **Source of truth.** If configuration is one structure and the registry is another, they can drift: a
   component can be configured but not registered, or registered with a stale configuration. The design either
   reconciles them by construction or accepts the drift as an operational risk.

2. **Implicit creation.** Some registry styles lazily create components on first lookup, using a default
   configuration. This is convenient at small scale but blurs the line between "what was declared" and "what
   exists at runtime", and makes operational diagnostics harder.

3. **Paradigm interaction.** A name might reasonably exist in two paradigms (an imperative bulkhead
   "inventory" and a reactive bulkhead "inventory" can be separate runtime objects with separate
   configurations). Lookups must reflect this without ambiguity, and cross-paradigm read access (for
   monitoring, dashboards) should not require code that special-cases each paradigm.

4. **Lifecycle.** Components hold resources (semaphores, schedulers, listener registrations). The framework
   needs a single, predictable shutdown path. Per-element registries each owning their own lifecycle make
   this fragile and easy to get wrong.

5. **Change visibility.** When components are added, removed, or reconfigured (especially at runtime, per
   ADR-025), operational tooling needs a hook to observe topology changes. Without explicit events,
   downstream systems poll or guess.

6. **Mutability integration.** ADR-025 establishes that configuration is mutable at runtime via patches
   against atomic snapshot containers. Whatever holds the live components must integrate with this update
   mechanism cleanly — applying patches, materialising new components, draining removed ones — without
   exposing the patch machinery to user code.

This ADR specifies the central container that resolves all six concerns coherently.

## Decision

The framework exposes a single root container, `InqRuntime`, that holds:

- The general configuration snapshot (clock, observability defaults, time source).
- One `ParadigmContainer` per active paradigm (`Imperative`, `Reactive`, `RxJava3`, `Coroutines`).
- The aggregate read view across paradigms (`InqConfigView`).
- The lifecycle root (`AutoCloseable`).
- The runtime mutation entry point (`update`, `apply`, `dryRun`, `diagnose`).

The registry concept does not survive as a standalone abstraction. There is no `BulkheadRegistry`,
`CircuitBreakerRegistry`, etc. The paradigm container *is* the registry for its paradigm, and `InqRuntime` *is*
the cross-paradigm aggregator.

### Top-level shape

```java
public interface InqRuntime extends AutoCloseable {

    // ── Identity & global state ──────────────────────────────────────────
    GeneralSnapshot general();

    // ── Cross-paradigm read view ─────────────────────────────────────────
    InqConfigView config();

    // ── Paradigm containers (each may be absent if paradigm not configured)
    Imperative imperative();
    Reactive reactive();
    RxJava3 rxjava3();
    Coroutines coroutines();

    // Type-safe paradigm accessor (for generic code)
    <P extends ParadigmTag> ParadigmContainer<P> paradigm(Class<P> paradigm);

    // ── Mutation ─────────────────────────────────────────────────────────
    BuildReport update(Consumer<InqudiumUpdateBuilder> updater);
    BuildReport apply(List<? extends ComponentPatch<?>> patches);
    BuildReport dryRun(Consumer<InqudiumUpdateBuilder> updater);

    // ── Diagnostics ──────────────────────────────────────────────────────
    DiagnosisReport diagnose();

    // ── Lifecycle ────────────────────────────────────────────────────────
    @Override
    void close();
}
```

The asymmetric "may be absent" property of the paradigm accessors deserves explanation. If the user's
configuration never declared an `.imperative(...)` section *and* the imperative module is on the classpath, the
container exists but is empty — `runtime.imperative().bulkheadNames()` returns an empty set. If the imperative
module is *not* on the classpath, calling `runtime.imperative()` throws `ParadigmUnavailableException`. The
distinction is intentional: an empty paradigm is a normal state; a missing paradigm module is a configuration
error.

### Paradigm containers

Each paradigm exposes a typed view onto its components:

```java
// In inqudium-config — the abstract contract
public interface ParadigmContainer<P extends ParadigmTag> {
    ParadigmTag paradigm();

    // Bulkhead access — handles are paradigm-specific subtypes
    Set<String> bulkheadNames();
    Optional<? extends BulkheadHandle<P>> findBulkhead(String name);

    Set<String> circuitBreakerNames();
    Optional<? extends CircuitBreakerHandle<P>> findCircuitBreaker(String name);

    Set<String> retryNames();
    Optional<? extends RetryHandle<P>> findRetry(String name);

    // ... per component type

    // Aggregated view over all components in this paradigm
    Stream<ComponentHandle<P, ?>> components();
}
```

The paradigm-specific containers narrow the return types:

```java
// In inqudium-config — interface only
public interface Imperative extends ParadigmContainer<ImperativeTag> {
    ImperativeBulkhead<?, ?> bulkhead(String name);              // throws if absent
    Optional<ImperativeBulkhead<?, ?>> findBulkhead(String name);
    ImperativeCircuitBreaker<?, ?> circuitBreaker(String name);
    // ...
}

// In inqudium-imperative — implementation
final class DefaultImperative implements Imperative {
    private final ConcurrentMap<String, DefaultImperativeBulkhead<?, ?>> bulkheads;
    private final ConcurrentMap<String, DefaultImperativeCircuitBreaker<?, ?>> breakers;
    private final ConcurrentMap<String, DefaultImperativeRetry<?, ?>> retries;
    // ...
}
```

The throwing variant (`bulkhead(name)`) is for the common case where the caller wrote the configuration and
expects the component to exist. `IllegalArgumentException` with a list of available names is thrown if it
doesn't — a programmer error, not a flow-control concern. The `findBulkhead(name)` variant returns `Optional`
for lookup-and-conditionally-use code.

### Lookup keys

The lookup key for a component is `(name, paradigm)`. This is reflected in the API: there is no
`runtime.bulkhead("inventory")` that has to guess the paradigm. The caller goes through a paradigm container
first.

For genuinely paradigm-agnostic code (monitoring, diagnostics, structured logging), the cross-paradigm read
view exposes the components without the paradigm dispatch:

```java
public interface InqConfigView {
    GeneralSnapshot general();
    Stream<ComponentSnapshot> all();

    Stream<BulkheadSnapshot> bulkheads();
    Optional<BulkheadSnapshot> findBulkhead(String name, ParadigmTag paradigm);

    Stream<CircuitBreakerSnapshot> circuitBreakers();
    Optional<CircuitBreakerSnapshot> findCircuitBreaker(String name, ParadigmTag paradigm);

    // ...
}

// Usage example: dump all configured components
runtime.config().all().forEach(snapshot ->
    System.out.printf("%s [%s/%s]%n",
        snapshot.name(),
        snapshot.componentType(),
        snapshot.paradigm())
);
```

`ComponentSnapshot` is a sealed interface implemented by every concrete snapshot record. This permits exhaustive
pattern matching in Java 21+:

```java
String describe(ComponentSnapshot s) {
    return switch (s) {
        case BulkheadSnapshot b      -> "Bulkhead " + b.name() + " limit=" + b.maxConcurrentCalls();
        case CircuitBreakerSnapshot c -> "CircuitBreaker " + c.name() + " threshold=" + c.failureThreshold();
        case RetrySnapshot r         -> "Retry " + r.name() + " maxAttempts=" + r.maxAttempts();
        // ... compiler-checked exhaustiveness
    };
}
```

### Lifecycle: add, patch, remove

The DSL produced by `runtime.update(...)` distinguishes structural changes from value changes:

```java
runtime.update(u -> u
    .imperative(im -> im
        // Structural: add a new component
        .bulkhead("newOne", b -> b
            .balanced()
            .maxConcurrentCalls(50)
        )

        // Value change: patch existing component (only touched fields change)
        .bulkhead("inventory", b -> b
            .maxConcurrentCalls(25)
        )

        // Structural: remove a component
        .removeBulkhead("legacy")
    )
);
```

The implementation routes each call:

- **Add** is detected by the paradigm container: name not present → create a new live component, applying the
  patch to the system-default snapshot, then validating completeness (mandatory fields). Failure is reported in
  the `BuildReport`, no live component is created.
- **Patch** is detected by name match: patch is applied via the live container's `apply(patch)` method. The
  atomic compare-and-set mechanism handles concurrent updates; subscribers are notified once per successful
  replacement.
- **Remove** triggers the component's shutdown path: in-flight calls drain (or are aborted, per component
  shutdown semantics — the bulkhead returns permits to the underlying strategy and rejects further acquires;
  the retry executor cancels scheduled retries; the circuit breaker stops emitting state-change events). The
  paradigm container removes the entry from its map. A `ComponentRemovedEvent` is published.

Add and remove emit lifecycle events via the existing event publisher (ADR-003):

- `RuntimeComponentAddedEvent` — on every successful add.
- `RuntimeComponentRemovedEvent` — on every successful remove.
- `RuntimeComponentPatchedEvent` — on every successful patch (if any field actually changed; no-op patches do
  not emit).

These are framework-level events, not component-level. They live in the `InqRuntime` event publisher (a global
publisher established at runtime construction), distinct from the per-component event publishers documented in
ADR-003.

The patch step in particular interacts with component activity: an `inventory` bulkhead being patched while
serving live calls is a different matter from the same bulkhead being patched moments after `build()`. The
mechanics that distinguish these cases — the cold/hot lifecycle states, the veto propagation chain, and the
listener-registration API — are specified in ADR-028. This ADR is responsible only for the structural shape of
add/patch/remove and the per-component atomicity guarantee.

### Component handles

A `BulkheadHandle<P>` is the live component instance. It exposes:

- The runtime API of that component (`tryExecute`, `decorateSupplier`, etc. — the actual user-facing surface).
- A snapshot read accessor: `BulkheadSnapshot snapshot()`.
- A subscription mechanism: `AutoCloseable subscribe(Consumer<BulkheadSnapshot>)`.
- The handle's name and paradigm.

The handle delegates configuration reads to the live snapshot. The component logic — the strategy that
acquires permits, the breaker that decides open/closed/half-open — is constructed once and listens on the
snapshot stream for changes. When a snapshot replacement occurs, the strategy reacts:

```java
final class DefaultImperativeBulkhead<A, R> implements ImperativeBulkhead<A, R>, BulkheadHandle<Imperative> {
    private final LiveBulkhead live;
    private volatile BlockingBulkheadStrategy strategy;   // mutable on snapshot change

    DefaultImperativeBulkhead(LiveBulkhead live) {
        this.live = live;
        this.strategy = StrategyFactory.create(live.snapshot());
        live.subscribe(this::onSnapshotChange);
    }

    private void onSnapshotChange(BulkheadSnapshot next) {
        // For limit-only changes, mutate the existing strategy's permits.
        // For strategy-type changes, swap the strategy (with care about in-flight calls).
        this.strategy = StrategyFactory.adapt(this.strategy, next);
    }

    @Override
    public BulkheadSnapshot snapshot() {
        return live.snapshot();
    }
}
```

The exact mechanics of "adapt the strategy without breaking in-flight calls" are component-specific and
documented in each component's ADR. For the bulkhead, ADR-020 covers the strategy contract; the live-update
behaviour is an extension that this ADR introduces but does not specify in full.

### The construction sequence

```java
// 1. User writes the DSL
InqRuntime runtime = Inqudium.configure()
    .general(g -> ...)
    .imperative(im -> im
        .bulkhead("inv", b -> b.balanced())
    )
    .build();

// 2. Inqudium.configure() returns an InqudiumBuilder (in inqudium-config).

// 3. The builder collects ParadigmSection objects, each containing a list of
//    ComponentPatch objects keyed by component type.

// 4. On build():
//    a. ServiceLoader.load(ParadigmProvider.class) discovers available paradigms.
//    b. For each declared paradigm section, the matching provider builds a
//       ParadigmContainer instance (the paradigm-module-specific implementation).
//    c. The containers are populated by applying each ComponentPatch to a
//       system-default snapshot and constructing the live component (handle
//       wraps live container + paradigm-specific runtime logic).
//    d. Validation runs (per ADR-027). Errors abort the build; warnings are
//       collected into a BuildReport accessible via runtime.lastBuildReport().
//    e. The InqRuntime instance is returned, holding the GeneralSnapshot and
//       the ParadigmContainer instances.

// 5. The user calls runtime.imperative().bulkhead("inv") to obtain the live
//    component, which it uses to decorate suppliers / callables / etc.
```

### Implementation locations

| Location              | Code                                                                              |
|-----------------------|-----------------------------------------------------------------------------------|
| `inqudium-config`     | `InqRuntime` interface, `ParadigmContainer<P>` interface, paradigm-specific       |
|                       | container subinterfaces (`Imperative`, `Reactive`, `RxJava3`, `Coroutines`),      |
|                       | handle interfaces (`BulkheadHandle<P>`, ...), `InqConfigView`, `BuildReport`,     |
|                       | `DiagnosisReport`, the `ComponentSnapshot` sealed hierarchy, the                  |
|                       | `RuntimeComponentAddedEvent` family, the `Inqudium.configure()` entry point.     |
| `inqudium-imperative` | `DefaultImperative`, `DefaultImperativeBulkhead`, ..., `ImperativeProvider`       |
|                       | (registered via `META-INF/services/eu.inqudium.config.spi.ParadigmProvider`).     |
| `inqudium-reactive`   | Symmetric structure for Reactor.                                                  |
| `inqudium-rxjava3`    | Symmetric structure for RxJava3.                                                  |
| `inqudium-kotlin`     | Symmetric structure for Kotlin coroutines.                                        |

`InqRuntime` is itself implemented in `inqudium-config` as `DefaultInqRuntime`. The implementation holds
references to whichever `ParadigmContainer` instances were created at build time, and dispatches paradigm-typed
calls accordingly. It does not depend on any paradigm module at compile time — only on the SPI types.

### Concurrency contract

- `runtime.config()` returns a snapshot view that is consistent at the moment of the call. Each
  `ComponentSnapshot` returned reflects its component's state at the time the stream element was produced. The
  view is not transactionally consistent across components — if an update lands during iteration, some
  snapshots may be old, some new. Callers needing a transactional view should snapshot first
  (`runtime.config().all().toList()`) before reading.
- `runtime.imperative().bulkhead(name)` always returns the same handle instance for a given (name, paradigm).
  Handle identity is stable for the component's lifetime. After removal, the handle becomes inert (rejects all
  operations with `ComponentRemovedException`) but is not GC'd until external references drop.
- `runtime.update(...)` is thread-safe. Concurrent `update` calls are linearized via per-live-container CAS;
  there is no global lock.
- `runtime.close()` is idempotent. After close, all paradigm containers are shut down, all handles are inert,
  and further `update` calls throw `IllegalStateException`.

## Consequences

**Positive:**

- One container, one source of truth. The set of configured components and the set of running components are
  the same set, by construction.
- Lifecycle is unified. `runtime.close()` is the single entry point for shutdown.
- Runtime mutability is structural: components can be added, patched, and removed at runtime through the same
  DSL used for initialization.
- The `(name, paradigm)` key resolves the "same name in different paradigms" question without ambiguity.
- Cross-paradigm read access via `InqConfigView` enables monitoring, diagnostics, and structured logging
  without paradigm-specific code paths.
- The sealed `ComponentSnapshot` hierarchy enables exhaustive pattern matching on components — a clean idiom
  for code that walks the configuration.
- Lifecycle events (`RuntimeComponentAdded/Removed/PatchedEvent`) give operational tooling visibility into
  topology changes.
- Implementation is split cleanly: interfaces in `inqudium-config`, paradigm-specific implementations in the
  paradigm modules, ServiceLoader bridges them.

**Negative:**

- Snapshot reads via `InqConfigView` are not transactionally consistent across components. Code that needs an
  atomic snapshot of the whole configuration must materialize one (`.toList()`) and accept that it may be
  immediately stale.
- After component removal, external references to its handle remain valid but inert. This is a small memory
  leak risk if applications hoard handles; documented but not enforced.
- The `runtime.paradigm(Class<P>)` generic accessor uses a class token, which is slightly verbose. The
  paradigm-specific accessors (`runtime.imperative()`) are the recommended path for non-generic code.
- The runtime is a single instance per application by convention but not by enforcement. Multiple
  `InqRuntime`s are technically possible (e.g., for tests or multi-tenant scenarios) but are not the standard
  pattern. The framework does not provide a "default runtime" singleton.

**Neutral:**

- The ADR specifies the runtime container shape but leaves component-internal live-update mechanics to each
  component's own ADR (ADR-020 will be extended for the bulkhead). Strategy adaptation under live snapshot
  changes is non-trivial and component-specific.
- `BuildReport` and `DiagnosisReport` are specified by ADR-027. This ADR refers to them but does not define
  their structure.
- The `Inqudium.configure()` entry point and the `InqudiumBuilder` interface are part of the DSL surface
  (ADR-025), not specified here in detail.
