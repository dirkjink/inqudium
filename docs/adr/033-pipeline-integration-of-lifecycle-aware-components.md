# ADR-033: Pipeline integration of lifecycle-aware components

## Status

Proposed.

## Context

The configuration refactor (ADR-025 through ADR-032) introduced a new architecture
for resilience components: snapshots, patches, lifecycle-aware components with
cold/hot/removed phases, listener registries, and atomic strategy hot-swap. The
new bulkhead — `InqBulkhead` — implements this architecture cleanly. It is
constructed from a `BulkheadSnapshot`, transitions through phases, supports
listener-based veto chains, and lives behind a `BulkheadHandle` read-surface that
the runtime exposes to user code.

But there is a parallel, older architecture in the same codebase: the **pipeline
system** in `inqudium-core/pipeline`. This system pre-dates the refactor and is
the basis for the function-wrapper, proxy-wrapper, and aspect-integration
modules. It defines:

- `LayerAction<A,R>` — the around-advice contract carrying the
  `execute(chainId, callId, arg, next)` signature.
- `InqElement` — the identity contract carrying `getName`, `getElementType`,
  `getEventPublisher`.
- `InqExecutor<A,R>` — one-shot execution surface (functional interface, intended
  for ad-hoc lambda creation) with `executeRunnable`/`executeSupplier`/etc.
  default methods.
- `InqDecorator<A,R>` — deferred, reusable wrapper construction surface, with
  `decorateRunnable`/`decorateSupplier`/etc. default methods. Today
  `InqDecorator extends InqElement, LayerAction`.
- Async counterparts: `AsyncLayerAction`, `InqAsyncExecutor`, `InqAsyncDecorator`
  — analogous shapes for `CompletionStage`-returning paths, living in
  `inqudium-imperative/pipeline`.
- Consumers: `RunnableWrapper`, `SupplierWrapper`, `FunctionWrapper`,
  `InqProxyFactory`, `SyncPipelineTerminal`, `ElementLayerProvider`. They
  consume `InqDecorator` (or `InqAsyncDecorator`) — never `InqBulkhead`.

The audit in REFACTORING.md sub-step 2.17 surfaced the structural gap: **the new
`InqBulkhead` does not implement any pipeline contract**. It has the right
`execute(...)` signature structurally — inherited from
`ImperativeLifecyclePhasedComponent` — but no interface declares it. As a
result:

- `ElementLayerProvider`'s type bound `<E extends InqElement & InqDecorator<Void, Object>>`
  rejects every `InqBulkhead` at compile time.
- The legacy `ImperativeBulkhead` class (deprecated, scheduled for deletion) is
  the only bridge between the new bulkhead and the pipeline system today.
- Once the legacy class is deleted, nothing replaces the bridge.

There is also a cosmetic but consequential drift between the two halves of the
codebase: the new architecture (snapshots, `BulkheadHandle`, `GeneralSnapshot`)
uses **record-style accessors** — `name()`, `eventPublisher()` — while the
pipeline system uses **bean-style getters** — `getName()`, `getEventPublisher()`.
A handle's `name()` and the same component's `getName()` (inherited via
`InqElement`) return the same value with two method names. This is real,
present drift, not future divergence.

This ADR resolves the gap and the style drift in one consolidated step.

## Decision

### Rule 1: `LayerAction<A, R>` is the single source of `execute(...)`

Today `execute(...)` is declared in four places: `LayerAction`,
`ImperativePhase`, the legacy `ImperativeBulkhead` runtime interface, and
`InqBulkhead` (inherited via `ImperativeLifecyclePhasedComponent`).
`LayerAction<A, R>` is the only legitimate owner of the contract. Everything
else either extends `LayerAction` or is removed.

`ImperativePhase` becomes parameterized:

```java
public interface ImperativePhase<A, R> extends LayerAction<A, R> { }
```

Today `ImperativePhase` carries a method-level generic `<A, R> R execute(...)`
form. That deviation from the pipeline pattern was an implementation choice
made when the lifecycle base class was non-generic; aligning it with
`LayerAction<A, R>` removes the irregularity. The phase sits at the same
position as a pipeline layer in the call flow — every layer of a single
pipeline call carries the same method signature, and the phase is no
exception. A phase that pins its type-level genericity matches what the
phase semantically *is*: a pipeline layer that bears lifecycle state.

The lifecycle base class follows:

```java
public abstract class ImperativeLifecyclePhasedComponent<S extends ComponentSnapshot, A, R>
        implements LifecycleAware, ListenerRegistry<S>, InternalMutabilityCheck<S> {

    private final AtomicReference<ImperativePhase<A, R>> phase;

    protected abstract ImperativePhase<A, R> createHotPhase();

    public final R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        // ...
    }
}
```

Its phase classes — `ColdPhase`, `RemovedPhase` — are parameterized
identically. `RemovedPhase<A, R>` throws `ComponentRemovedException`
regardless of the type parameters, but carries them through the type system
so the phase reference in the base class stays homogeneous.

This alignment makes the architecture easier to understand: phase, layer
action, and component all live at the same generic level. The pipeline call
and the lifecycle transition do share an anchor — the call itself is what
triggers cold-to-hot — and the type-level genericity makes that shared anchor
explicit and uniform across the system.

### Rule 2: `InqDecorator extends InqElement` is preserved; `InqExecutor` does not

`InqDecorator` and `InqAsyncDecorator` continue to extend `InqElement`. The
"is a" relationship is genuine for decorators: a decorator wraps a delegate
into a wrapper object whose name and type are part of its identity, and
consumers (`SyncPipelineTerminal`, `ElementLayerProvider`) routinely read those
fields when constructing the wrapper. A nameless decorator is a contradiction.

`InqExecutor` and `InqAsyncExecutor` continue to extend only `LayerAction` /
`AsyncLayerAction`. They do **not** extend `InqElement`. The difference is
intentional: an executor is the *one-shot* form, designed to be created as a
single-method lambda for an ad-hoc around-advice. Adding the three
`InqElement` accessors would force the lambda form into anonymous-inner-class
form everywhere it is used — including throughout the existing test surface,
where `InqExecutor` lambdas are the canonical pattern. The cost of that
change is large; the conceptual benefit is small (executors that need
identity can implement `InqElement` separately, and decorators are the path
for users who want named, reusable instances).

This produces an asymmetry between executors and decorators that is real and
deliberate: executors are anonymous, decorators are named.

### Rule 3: `InqElement` adopts record-style accessors

`InqElement` is renamed:

| Before                | After              |
|-----------------------|--------------------|
| `getName()`           | `name()`           |
| `getElementType()`    | `elementType()`    |
| `getEventPublisher()` | `eventPublisher()` |

Reasons:

- **The new architecture already uses record-style accessors.** `BulkheadHandle`,
  `GeneralSnapshot`, `BulkheadSnapshot`, every snapshot record — all of them
  return values via record-style methods. The bean-style `InqElement` is the
  outlier.

- **Two contracts on the same object would otherwise return identical values
  through differently-named methods.** A bulkhead implementing both
  `BulkheadHandle` (`name()`) and `InqElement` (`getName()`) would have to
  declare both, even though they return the same string. Record-style on
  both ends closes this drift permanently.

- **Inqudium uses Java records pervasively** for snapshots, patches, and
  configuration. The accessor style on interfaces is consistent with the
  style on records.

This is an API-breaking change. The reach is wide — consumers in
`inqudium-core`, `inqudium-imperative`, `inqudium-aspect`, all tests across
those modules, and the user-facing event documentation. Pre-alpha status
makes the cost acceptable; the consolidation makes the result coherent for
the long term.

### Rule 4: `BulkheadHandle` extends `InqElement` and drops its own `name()` / `eventPublisher()`

After Rule 3, `BulkheadHandle.name()` and `InqElement.name()` have the same
signature and the same meaning. The same applies to `eventPublisher()`. The
duplication is resolved by making `BulkheadHandle` extend `InqElement` and
removing its own declarations of these two methods.

`BulkheadHandle<P>` becomes:

```java
public interface BulkheadHandle<P extends ParadigmTag>
        extends InqElement,
                LifecycleAware,
                ListenerRegistry<BulkheadSnapshot>,
                InternalMutabilityCheck<BulkheadSnapshot> {

    BulkheadSnapshot snapshot();
    int availablePermits();
    int concurrentCalls();
    // name() and eventPublisher() are inherited from InqElement.
    // elementType() is inherited from InqElement and is always BULKHEAD
    //   for any bulkhead handle — this is enforced by the implementation,
    //   not the type system.
}
```

The handle's own surface is reduced to the three bulkhead-specific accessors:
`snapshot`, `availablePermits`, `concurrentCalls`. Everything else comes from
the inherited contracts.

### Rule 5: The `ImperativeBulkhead` runtime interface is deleted

The `ImperativeBulkhead` interface in `inqudium-config.runtime` carried only
the `execute(chainId, callId, arg, next)` method on top of `BulkheadHandle`.
After Rule 4 (`BulkheadHandle` carries identity and read accessors) and the
concrete-class arrangement below (Rule 6), the interface is empty of
concrete value: it would be either a marker interface or a mere
`extends BulkheadHandle<ImperativeTag>` alias. Neither earns its keep.

The interface is deleted. Users who previously typed against
`ImperativeBulkhead` type against `BulkheadHandle<ImperativeTag>`. The reactive
counterpart (`ReactiveBulkhead`, when it lands) follows the same pattern: the
read-and-listener surface is `BulkheadHandle<ReactiveTag>`, and the call
surface is whatever reactive-specific interfaces the concrete class carries.

### Rule 6: Concrete components carry the pipeline contracts; the lifecycle base class does not

`ImperativeLifecyclePhasedComponent<S>` is an *implementation detail*. It
houses the cold/hot/removed phase machinery, the CAS-based phase transitions,
the listener registry, the snapshot subscription mechanism. It does not
participate in user-facing API.

The pipeline contracts — `InqElement`, `InqExecutor`, `InqDecorator`, plus
async counterparts — are *user-facing API*. They go on the **concrete
component class**, not on the abstract lifecycle base.

For `InqBulkhead`:

```java
public final class InqBulkhead<A, R>
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot>
        implements BulkheadHandle<ImperativeTag>,
                   InqExecutor<A, R>,
                   InqDecorator<A, R> {
    // ...
}
```

The async counterparts (`InqAsyncExecutor`, `InqAsyncDecorator`) are explicitly
**not** included. The asynchronous bulkhead variant is an open design question
(see `TODO.md` — *"Asynchronous variant of InqBulkhead is not implemented"*)
and warrants its own ADR. Until that design is settled, `InqBulkhead` is
synchronous-only at the pipeline-contract level.

`BulkheadHotPhase<A, R>` — the cold/hot lifecycle's hot-state implementation —
is parameterized in `<A, R>` to match `InqBulkhead<A, R>` and
`ImperativePhase<A, R>`. The phase, the component, and the pipeline-contract
interfaces all carry the same type parameters; there is no boundary at which
a cast is needed, no point at which the type information is dropped. The
strategy held by the phase (`BulkheadStrategy`) is itself type-erased — the
permit/release accounting does not depend on the call's argument or return
— so the phase mediates between a typed pipeline-contract layer above it
and a type-agnostic strategy implementation below it. This boundary has
always existed in the bulkhead and remains unchanged after this ADR.

Why concrete components carry these contracts directly:

- Each component decides which contracts it semantically supports. A bulkhead
  supports both sync and async (eventually). A future time-limiter might
  support async only. Putting the contracts on the lifecycle base would
  force one-size-fits-all.

- The lifecycle base is a *mechanism* shared across components; the pipeline
  contracts are *concepts* in the user-facing API. Mixing them on one type
  conflates layers.

- The boilerplate cost is small: the contracts' methods are mostly default
  methods on the interfaces themselves. The concrete class needs only to
  inherit `execute(...)` (which the base class already provides) plus the
  three `InqElement` accessors.

Future components (Circuit Breaker, Retry, Time Limiter) follow the same
pattern: each declares its supported pipeline contracts directly.

### Rule 7: `InqBulkhead<A, R>` is generic in `<A, R>`

`InqBulkhead<A, R>` is parameterized by the same `<A, R>` that the pipeline
contracts it implements use. The generic parameters propagate from
`LayerAction<A, R>` through `InqExecutor<A, R>` and `InqDecorator<A, R>`
into `InqBulkhead<A, R>` without erasure:

```java
public final class InqBulkhead<A, R>
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot>
        implements BulkheadHandle<ImperativeTag>,
                   InqExecutor<A, R>,
                   InqDecorator<A, R> {
    // ...
}
```

The dominant path through which the framework is used — Spring AOP, AspectJ,
dynamic proxies — operates type-erased at the proceed boundary. In those
paths the component's generic parameters do not appear, impose no overhead,
and require no developer attention. The user writes a normal annotated
service method; the aspect mechanism wraps it transparently.

The manual `decorateFunction(...)` path is the edge case where the generic
parameters earn their cost. A caller who knows the types of their function
gets a typed wrapper without unchecked casts:

```java
InqBulkhead<String, Integer> bh = ...;
Function<String, Integer> wrapped = bh.decorateFunction(Integer::parseInt);
```

The legacy `Bulkhead<A, R>` interface was generic at the consumer boundary
in the same way; this rule continues that contract.

Lookup through `Imperative.bulkhead(name)` returns
`BulkheadHandle<ImperativeTag>` — the paradigm-tagged read-and-listener
surface, no `<A, R>`. Manual access to the typed call surface uses the
concrete class directly, with type witnesses supplied by the calling code's
own type information (the `Function<A, R>` parameter at the `decorateFunction`
call site, or an explicit cast of the handle when the caller already has
type knowledge).

The component's generic parameters propagate uniformly through the whole
lifecycle-and-pipeline stack: `LayerAction<A, R>` at the contract level,
`ImperativePhase<A, R>` at the lifecycle level, `BulkheadHotPhase<A, R>` at
the hot-state level, `InqBulkhead<A, R>` at the user-facing component
level. There is no point at which `<A, R>` is cast away or pinned; the same
type parameters flow from the user's `decorateFunction(Function<A, R>)`
call all the way down to the strategy boundary, where the type-agnostic
permit accounting takes over.

## Consequences

### What changes in the codebase

A non-exhaustive list of the modifications this ADR drives, in approximate
order of cost:

- **`InqElement` accessor rename.** Three methods on the interface; every
  implementor in production and test code; all consumers reading those
  methods; the user-facing event documentation. Estimated impact: 30-50
  files.

- **`ImperativePhase extends LayerAction<Object, Object>`.** One interface
  declaration plus verification that consumers (`ColdPhase`, `RemovedPhase`,
  `BulkheadHotPhase`) still compile.

- **`BulkheadHandle extends InqElement`.** One interface declaration; the
  `BulkheadHandle` declarations of `name()` and `eventPublisher()` are
  removed (they are inherited).

- **`ImperativeBulkhead` runtime-interface deletion.** Search-replace
  `ImperativeBulkhead` to `BulkheadHandle<ImperativeTag>` at every consumer.
  The legacy package-private class with the same name in
  `inqudium-imperative.bulkhead` (the deprecated implementation class) is
  unaffected by this rule — only the new `inqudium-config.runtime` interface
  goes away.

- **`InqBulkhead<A, R>` becomes generic and implements three new contracts.**
  The class declaration adds `<A, R>` type parameters and implements
  `InqExecutor<A, R>`, `InqDecorator<A, R>` (provides the `decorate*` default
  methods that user code expects), and `BulkheadHandle<ImperativeTag>`
  (already implemented via the deleted `ImperativeBulkhead`; the rename is
  the only change).

- **`ImperativeLifecyclePhasedComponent<S>` becomes
  `ImperativeLifecyclePhasedComponent<S, A, R>`.** The two new type
  parameters thread through the phase reference, the abstract
  `createHotPhase()` factory method, and the `execute(...)` contract.
  Subclasses (today only `InqBulkhead`; later `InqCircuitBreaker`,
  `InqRetry`, etc.) declare their own `<A, R>` and pass them up.

- **`ImperativePhase` becomes `ImperativePhase<A, R> extends LayerAction<A, R>`.**
  Its method-level generic form is replaced by type-level parameters. The
  inner `ColdPhase` and the static-nested `RemovedPhase` follow the same
  parameterization. `BulkheadHotPhase` becomes `BulkheadHotPhase<A, R>`,
  parameterized identically.

  This parameterization is the largest single change driven by this ADR
  outside the `InqElement` rename. Every place that constructs or stores a
  phase reference acquires the new parameters. The benefit is a uniformly
  generic stack from user-facing component down to phase implementation,
  with no cast required between layers.

- **The structural gap from audit finding 2.17.2 closes.**
  `ElementLayerProvider`, `RunnableWrapper`, `SupplierWrapper`,
  `FunctionWrapper`, `InqProxyFactory`, `SyncPipelineTerminal`, and any
  other consumer of the `InqElement & InqDecorator` type bound now
  accept `InqBulkhead` instances directly, with no adapter object and no
  method-reference workaround.

### What this ADR does not decide

- **Asynchronous bulkhead variant.** `InqBulkhead` does not implement
  `InqAsyncExecutor` or `InqAsyncDecorator` after this ADR. The async path
  is an open design question — see `TODO.md`. A separate ADR resolves it.

- **Whether other future components extend `InqElement` directly.** This ADR
  applies to bulkhead. Circuit breaker, retry, and time limiter will each
  follow the same pattern, but the pattern is documented here as guidance,
  not as a binding constraint on their respective ADRs.

- **The ordering between adopting Rule 3 (record-style rename) and the rest
  of this ADR.** They could be done in two sub-steps or one. The
  implementation plan in REFACTORING.md decides the granularity.

### Risk

The `InqElement` rename touches many files. The risk is not architectural —
the resulting structure is clearly better than the current state — but
operational: a careless rename can leave a few callers using the old method
name and break compilation in modules whose tests are not in the active
verify run. Mitigations:

- Run a full reactor `mvn verify` after the rename, not a single-module
  build.
- Search the repository for `getName(`, `getElementType(`, `getEventPublisher(`
  exhaustively before declaring the rename complete; expect zero hits when
  done.
- Apply the rename mechanically (IDE refactor) rather than manually.

The pipeline-contract additions on `InqBulkhead` are low-risk: the contracts'
default methods cover most of the surface, and the inherited
`execute(chainId, callId, arg, next)` from the lifecycle base provides the
`LayerAction` half automatically once `InqExecutor` and `InqDecorator` are
declared.

## Notes

The four pipeline contracts (`InqExecutor`, `InqDecorator`, `InqAsyncExecutor`,
`InqAsyncDecorator`) form a 2×2 matrix along two orthogonal axes:

- **Aufruf-Form:** one-shot (executor) vs. deferred (decorator).
- **Synchronicity:** sync (no async marker) vs. async (`InqAsync*`).

Components implement whichever subset of the matrix they semantically support.
Bulkhead, after this ADR, supports the sync row only. Future components may
support the async row, the full matrix, or neither — case by case.

The orthogonality is preserved by keeping these as separate interfaces rather
than collapsing them into a single super-interface. A single super-interface
would force every component to declare a position in the matrix even where it
does not apply.
