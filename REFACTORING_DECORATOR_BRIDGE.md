# REFACTORING_DECORATOR_BRIDGE.md — ADR-033 implementation

**Audience:** Claude Code, executing the refactoring work, plus the human reviewer.

**Scope:** Implementation of ADR-033 (pipeline integration of lifecycle-aware
components). This is a standalone refactor that runs in parallel to the
Phase 2 closure sequence in `REFACTORING.md`. It must complete before
sub-step 2.18 (AspectJ integration) begins, because 2.18 depends on
`InqBulkhead` being a fully-fledged `InqDecorator`.

**Numbering:** This document uses its own numbering (stages 1, 2, 3) rather
than continuing the Phase 2 closure sequence. The work is conceptually
distinct — it implements a separate ADR — and warrants its own document
lifecycle.

**Lifetime:** Deleted at completion, alongside the existing `REFACTORING.md`
and `AUDIT_FINDINGS.md` at the end of the bulkhead refactor.

## Why this is non-trivial

ADR-033 makes seven design decisions that together close the structural gap
between the new lifecycle-aware component architecture and the older
pipeline system. The decisions cannot be applied independently — they form
a tightly-coupled set:

- The `InqElement` rename (record-style accessors) collides with
  `BulkheadHandle.name()` if not coordinated.
- `ImperativePhase` parameterization forces the lifecycle base class to
  acquire two new type parameters; its subclasses inherit them.
- `InqBulkhead` becoming generic is the visible end of the
  parameterization that must propagate from `LayerAction` through
  `ImperativePhase` and `ImperativeLifecyclePhasedComponent` first.
- `ImperativeBulkhead` interface deletion has consequences across the
  Spring, AspectJ, and annotation-support modules; their imports change.

Done as one monolithic change, the diff is large and the failure mode is
all-or-nothing. Done as four loosely-coupled changes, intermediate states
contain awkward wildcards (`InqBulkhead extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot, ?, ?>`)
that the rest of the codebase must accommodate.

The plan below splits the work into **three stages**, each ending in a
state where a full reactor `mvn verify` is green and the codebase is
internally consistent. Stages 2 and 3 are substantively larger than 1, but
the stage 1 boundary is the natural early checkpoint.

## Stage 1 — `InqElement` rename to record-style accessors

**Goal:** Eliminate the bean-style/record-style accessor drift. After this
stage, the codebase uses `name()`, `elementType()`, `eventPublisher()`
uniformly on `InqElement`. No structural change.

This stage is a mechanical rename that touches a wide set of files but
introduces no new types, no new contracts, and no new generic parameters.
Done with an IDE refactor (Rename Method), the operation is repetitive but
low-risk per call site.

### Changes

In `inqudium-core/src/main/java/eu/inqudium/core/element/InqElement.java`:

```java
public interface InqElement {
    String name();              // was: getName()
    InqElementType elementType(); // was: getElementType()
    InqEventPublisher eventPublisher(); // was: getEventPublisher()
}
```

Every implementor in production code is updated:

- `inqudium-core` — concrete `InqElement` implementations across the
  element subpackages (bulkhead, circuit-breaker, retry, fallback,
  rate-limiter, time-limiter, traffic-shaper). Most carry inherited
  `getName`-style methods from the deprecated `InqElementCommonConfig`
  composition; both names need to be reconciled.
- `inqudium-imperative` — the deprecated legacy `ImperativeBulkhead<A,R>`
  class (which still implements `InqElement` via its config composition).

Every consumer in production code is updated:

- `inqudium-core/pipeline` — `SyncPipelineTerminal`, `JoinPointWrapper`,
  decorator factory methods that read `getName()` / `getElementType()`.
- `inqudium-aspect` — `ElementLayerProvider`, `AsyncElementLayerProvider`,
  `AspectPipelineBuilder`, `ResolvedPipeline`, `HybridAspectPipelineTerminal`.
- `inqudium-spring` — bean-name resolution, autoconfiguration.
- `inqudium-annotation-support` — annotation-driven element lookup.

Every test that overrides the old methods is updated:

- `WrapperPipelineTest`, `SyncPipelineTerminalTest`, `ProxyChainCompositionTest`,
  `InqProxyFactorySyncTest`, `ProxyPipelineTerminalTest`, `AspectPipelineTerminalTest`,
  `AsyncWrapperPipelineTest`, `AsyncPipelineTerminalTest`,
  `HybridProxyPipelineTerminalTest`, `InqProxyFactoryAsyncTest`,
  `InqElementRegistryTest`, plus tests in element-specific packages that
  construct anonymous `InqElement` instances.

The user-facing event documentation is updated:

- `docs/user-guide/events.md` — code samples use `cb.eventPublisher()` not
  `cb.getEventPublisher()`.

### Out of scope for this stage

- `BulkheadHandle` is not yet extending `InqElement`. The `name()` and
  `eventPublisher()` methods on `BulkheadHandle` remain declared explicitly;
  they happen to have the same signature as the post-rename `InqElement`
  methods, but the inheritance is established only in Stage 3.

- `InqDecorator` keeps its current interface declaration. Its method
  inheritance from `InqElement` is unchanged in form (was bean-style, is
  now record-style — same shape).

- No structural changes. No new types, no new generic parameters, no
  contract changes. Pure rename.

### Verification gate after Stage 1

- Full reactor `mvn verify` is green.
- A repository-wide search for `getName(`, `getElementType(`,
  `getEventPublisher(` returns zero hits in production and test code.
  (Hits in deprecated method bodies that delegate to the new accessors
  are acceptable, but should be rare.)
- The user guide's events page renders correctly and uses the new
  accessor style throughout.

## Stage 2 — Phase parameterization and `InqBulkhead<A, R>`

**Goal:** Establish `InqBulkhead<A, R>` as a generic component fully
implementing the synchronous pipeline contracts (`InqExecutor<A, R>`,
`InqDecorator<A, R>`). The lifecycle infrastructure underneath
(`ImperativePhase`, `ImperativeLifecyclePhasedComponent`, the inner
`ColdPhase`/`RemovedPhase`, and `BulkheadHotPhase`) acquires the same
type parameters.

This stage cannot meaningfully be split. Splitting it would leave
`InqBulkhead` parameterized at `<?, ?>` waiting for the phases to catch up,
or vice versa — both intermediate states create cascading wildcard noise
across the rest of the codebase. Stage Stage 2 does the parameterization
end-to-end in one stage so the intermediate state never exists.

### Changes

In `inqudium-imperative/src/main/java/eu/inqudium/imperative/lifecycle/spi/ImperativePhase.java`:

```java
public interface ImperativePhase<A, R> extends LayerAction<A, R> { }
```

The method-level generic `<A, R> R execute(...)` is replaced by inheritance
from `LayerAction<A, R>`, which carries the same signature at the type
level. The change aligns the phase with its sibling pipeline-layer
construct — a phase is structurally a layer that bears lifecycle state.

In `inqudium-imperative/src/main/java/eu/inqudium/imperative/lifecycle/ImperativeLifecyclePhasedComponent.java`:

```java
public abstract class ImperativeLifecyclePhasedComponent<S extends ComponentSnapshot, A, R>
        implements LifecycleAware, ListenerRegistry<S>, InternalMutabilityCheck<S> {

    private final AtomicReference<ImperativePhase<A, R>> phase;

    protected abstract ImperativePhase<A, R> createHotPhase();

    public final R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        // ...
    }
    // ...
}
```

The two new type parameters (`A`, `R`) propagate through the phase
reference, the abstract `createHotPhase()` factory method, and the
`execute(...)` contract. The inner `ColdPhase implements ImperativePhase<A, R>`
and the static-nested `RemovedPhase<A, R> implements ImperativePhase<A, R>`
acquire the parameters identically.

In `inqudium-imperative/src/main/java/eu/inqudium/imperative/bulkhead/BulkheadHotPhase.java`:

```java
final class BulkheadHotPhase<A, R>
        implements ImperativePhase<A, R>, HotPhaseMarker, PostCommitInitializable,
                   InternalMutabilityCheck<BulkheadSnapshot>, ShutdownAware {

    private final InqBulkhead<A, R> component;
    private volatile BulkheadStrategy strategy;
    // ...

    @Override
    public R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        // ...
    }
}
```

`BulkheadStrategy` itself stays type-erased — permit accounting does not
depend on the call's argument or return. The phase mediates between the
typed contract above and the type-agnostic strategy below; the cast is at
the strategy boundary, not at the phase boundary.

In `inqudium-imperative/src/main/java/eu/inqudium/imperative/bulkhead/InqBulkhead.java`:

```java
public final class InqBulkhead<A, R>
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot, A, R>
        implements ImperativeBulkhead,
                   InqExecutor<A, R>,
                   InqDecorator<A, R> {
    // ...
}
```

The class is generic in `<A, R>`. It implements `InqExecutor<A, R>`
(one-shot pipeline contract) and `InqDecorator<A, R>` (deferred-wrapper
contract) directly — both inherit `LayerAction<A, R>.execute(...)`, which
the inherited method from the lifecycle base class fulfills.

`InqBulkhead` continues to implement the existing `ImperativeBulkhead`
interface from `inqudium-config.runtime` for now. That interface's deletion
is Stage 3 work — splitting it across stages avoids touching the
Spring/Aspect/annotation-support imports while the parameterization
settles.

The async pipeline contracts (`InqAsyncExecutor`, `InqAsyncDecorator`) are
explicitly **not** added in this stage. ADR-033 defers the asynchronous
bulkhead variant to a separate ADR; the corresponding TODO.md entry
remains open.

### Consumers of `InqBulkhead` and the lifecycle base

- The `ImperativeProvider.materializeBulkhead(...)` factory now constructs
  `InqBulkhead<Object, Object>` (the canonical type-erased instantiation
  for components used through the runtime registry). Callers receiving a
  bulkhead from `runtime.imperative().bulkhead(name)` get a
  `BulkheadHandle<ImperativeTag>` (the existing interface; not yet extending
  `InqElement` — that comes in Stage 3).
- Any internal test or production code that constructs `InqBulkhead`
  directly acquires `<Object, Object>` parameters or whatever specific
  types the test exercises.
- Existing consumers of `BulkheadHotPhase` (the `BulkheadStrategyFactory`
  callers, the strategy-hot-swap mechanics from 2.10.D) acquire phase
  parameters where they reference the phase type explicitly.

### Out of scope for this stage

- `BulkheadHandle` does not yet extend `InqElement`. The two interfaces
  declare some methods with the same signature, but neither inherits from
  the other.
- `ImperativeBulkhead` interface in `inqudium-config.runtime` still
  exists. `InqBulkhead` continues to implement it.
- The aspect-pipeline consumers (`ElementLayerProvider` and friends) can
  now in principle accept `InqBulkhead<Void, Object>` — but verifying that
  end-to-end is the work of Stage 3 plus the integration tests in 2.20.

### Verification gate after Stage 2

- Full reactor `mvn verify` is green.
- The 1.988+ existing test count is preserved, ideally with a small
  delta upward as the parameterization may surface generic edge cases
  that get pinned down by new assertions.
- A short smoke test confirms `InqBulkhead<String, Integer>` can be
  constructed and `bulkhead.decorateFunction(Integer::parseInt)` returns
  a typed `Function<String, Integer>`. The smoke test does not need to be
  permanent; it can live in a scratch test class for the duration of the
  review and be deleted at commit time.

## Stage 3 — `BulkheadHandle` consolidation and `ImperativeBulkhead` deletion

**Goal:** Close the duplication between `BulkheadHandle.name()` and
`InqElement.name()` (and likewise `eventPublisher()`). Delete the
`ImperativeBulkhead` interface in `inqudium-config.runtime` ; user code
types against `BulkheadHandle<ImperativeTag>` directly.

This stage finishes the work begun in Stage 1 and Stage 2. The structural
gap from audit finding 2.17.2 closes here at the surface that user code
sees.

### Changes

In `inqudium-config/src/main/java/eu/inqudium/config/runtime/BulkheadHandle.java`:

```java
public interface BulkheadHandle<P extends ParadigmTag>
        extends InqElement,
                LifecycleAware,
                ListenerRegistry<BulkheadSnapshot>,
                InternalMutabilityCheck<BulkheadSnapshot> {

    BulkheadSnapshot snapshot();
    int availablePermits();
    int concurrentCalls();
    // name(), elementType(), eventPublisher() inherited from InqElement.
}
```

The own declarations of `name()` and `eventPublisher()` are removed; they
come from `InqElement`. The `elementType()` method is also inherited and is
guaranteed to return `BULKHEAD` for any bulkhead handle (enforced by the
implementation, not by the type system — `InqElement.elementType()`'s
return type is the open `InqElementType`).

The `ImperativeBulkhead` interface in
`inqudium-config/src/main/java/eu/inqudium/config/runtime/ImperativeBulkhead.java`
is **deleted** in its entirety.

All consumers are updated:

- `Imperative.bulkhead(name)` and `Imperative.findBulkhead(name)` change
  return type from `ImperativeBulkhead` to `BulkheadHandle<ImperativeTag>`.
- `DefaultImperative` (the implementation) updates accordingly.
- `inqudium-aspect` consumers that read `ImperativeBulkhead` type the
  variable as `BulkheadHandle<ImperativeTag>` instead.
- `inqudium-spring` and `inqudium-spring-boot` autoconfiguration
  references update.
- `inqudium-annotation-support` annotation-driven lookup updates.
- The user-guide for the bulkhead (`docs/user-guide/bulkhead/bulkhead.md`)
  has any mention of `ImperativeBulkhead` replaced with `BulkheadHandle<ImperativeTag>`.
- Tests typing variables against `ImperativeBulkhead` update.

In `inqudium-imperative/src/main/java/eu/inqudium/imperative/bulkhead/InqBulkhead.java`:

```java
public final class InqBulkhead<A, R>
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot, A, R>
        implements BulkheadHandle<ImperativeTag>,
                   InqExecutor<A, R>,
                   InqDecorator<A, R> {
    // ...
}
```

The `implements ImperativeBulkhead` clause is replaced with
`implements BulkheadHandle<ImperativeTag>`. Since `BulkheadHandle` now
extends `InqElement`, `InqBulkhead` inherits the `InqElement` contract
through this path — the explicit `name()`, `elementType()`, and
`eventPublisher()` implementations on `InqBulkhead` continue to satisfy
both the `BulkheadHandle` and the `InqElement` contracts.

### Out of scope for this stage

- The async pipeline contracts on `InqBulkhead` remain unimplemented.
  ADR-033 reserves them for a separate ADR.

- The aspect-pipeline integration tests (audit finding 2.17.4 and the
  three Lifecycle scenarios from 2.17.3) belong to sub-step 2.20, not
  here.

### Verification gate after Stage 3

- Full reactor `mvn verify` is green.
- A repository-wide search for `ImperativeBulkhead` (the deleted
  interface, not the deprecated legacy class) returns zero hits in
  production and test code, except for `import` statements that the IDE
  has missed during the rename.
- A consumer demonstration: a synthetic test (or a manual REPL session)
  shows that the audit-finding-2.17.2 code now compiles:

  ```java
  InqBulkhead<Void, Object> bh = ...;
  ElementLayerProvider provider = new ElementLayerProvider(bh, 100);
  // No "type parameter E is not within bound" error.
  ```

  This test does not need to be permanent; it is the structural-closure
  proof that 2.20's integration tests will subsume.

## Stages summary and ordering

| Stage  | Purpose                                                        | Reach           | Risk     |
|--------|----------------------------------------------------------------|-----------------|----------|
| Stage 1 | `InqElement` rename to record-style accessors                  | wide, mechanical | low     |
| Stage 2 | Phase parameterization + `InqBulkhead<A, R>` + sync contracts  | medium, structural | medium |
| Stage 3 | `BulkheadHandle` consolidation + `ImperativeBulkhead` deletion | medium, structural | medium |

Each stage ends with `mvn verify` green and the codebase internally
consistent. The stages are sequential: Stage 1 enables Stage 3 (record-style
accessors must be in place before `BulkheadHandle extends InqElement`
makes sense); Stage 2 is independent of Stage 1 but must precede Stage 3
(generic component must exist before its surface is consolidated through
inheritance).

## What follows the three stages

After Stage 3, the bulkhead's pipeline integration is complete. The Phase 2
closure sequence in `REFACTORING.md` continues from where it paused:

- **2.18** — AspectJ integration. Now able to verify that
  `ElementLayerProvider` accepts `InqBulkhead` instances, since the audit
  finding 2.17.2 has been resolved.
- **2.19** — Spring and Spring Boot integration.
- **2.20** — Bulkhead integration test module. Audit findings 2.17.3 and
  2.17.4 are absorbed here as test scenarios: Wrapper-over-cold-to-hot,
  Wrapper-over-strategy-hot-swap, Wrapper-after-removal, plus realistic
  stack tests using `InqBulkhead<A, R>` constructed via
  `Inqudium.configure()`.

After 2.20 the Phase 2 closure final step runs: `REFACTORING.md`,
`AUDIT_FINDINGS.md`, and this document are deleted. The imperative
bulkhead is officially complete as a resilience pattern. The next document
(the ADR audit) gets its own dedicated `REFACTORING.md`.
