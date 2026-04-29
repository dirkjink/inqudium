# ADR-029: Component lifecycle implementation pattern

**Status:** Accepted
**Date:** 2026-04-26
**Deciders:** Core team
**Related:** ADR-025 (configuration architecture), ADR-026 (runtime and registry), ADR-028 (update propagation and component lifecycle)

## Context

ADR-028 specifies the *behaviour* of the cold/hot lifecycle: when components transition, what the transition
means for update routing, how the veto chain interacts with the lifecycle state. It deliberately leaves the
*implementation form* open, because behaviour and implementation are independent design questions and binding
them together would conflate two decisions that can evolve at different rates.

But "implementation form is open" cannot be the final answer, because every component in every paradigm has to
solve the same shape of problem: hold the lifecycle-stable resources, switch from cold to hot exactly once,
delegate calls to the right phase. Solved independently per component, this would produce four to a dozen
implementations of the same underlying pattern, each subtly different, each with its own bugs.

This ADR specifies the implementation pattern as a normative recommendation. Components in the framework's
own paradigm modules use this pattern; third-party extensions may follow it for consistency but are not
required to.

The pattern needs to satisfy several constraints simultaneously:

1. **Lifecycle-stable resources are shared across phases.** The component's name, its `LiveContainer` (which
   owns the snapshot `AtomicReference`), its listener list, its event publisher — all of these exist from
   construction onward and outlive the cold-to-hot transition. They must not be re-allocated or copied during
   the transition.

2. **Hot-only resources are constructed lazily.** The strategy object, adaptive-algorithm state, scheduler
   subscriptions — these belong to the hot phase only. Constructing them eagerly at component build time
   would defeat the cold-phase intent (a component never used should not allocate hot-phase resources) and
   would deny the hot phase access to the live snapshot at the moment of transition.

3. **The transition runs exactly once and is observable.** Application code must be able to query
   `lifecycleState()` at any time. The transition itself fires a `ComponentBecameHotEvent` (per ADR-028)
   exactly once per component lifetime.

4. **The hot path is free of lifecycle checks.** Once the component is hot, every subsequent `execute` call
   should run without re-checking lifecycle state. Branch-prediction-friendly, allocation-free, no memory
   barriers beyond what the underlying logic itself requires.

5. **The pattern is paradigm-aware.** Imperative `execute` returns a value directly. Reactive `execute`
   returns a `Mono` or `Flux`. RxJava3 returns `Single`/`Observable`. Kotlin coroutines use `suspend`
   functions with a `CoroutineContext`. These signature differences are not cosmetic — they affect the
   shape of the cold-to-hot delegation, the timing of hot-phase construction, and how subscriber
   notifications propagate.

6. **The pattern composes with strategy-style variation within components.** A bulkhead can have different
   acquire strategies (semaphore, CoDel, AIMD); a circuit breaker can have different failure-counting
   strategies (sliding window, time-based). These intra-component variations should remain compositional —
   they are runtime-replaceable parts of the hot phase, not separate component classes.

The implementation pattern that satisfies all six is the State pattern with a shared base class, applied
per paradigm. The remainder of this ADR specifies it.

## Decision

The pattern has three structural pieces and one architectural rule.

The three pieces:

- **A paradigm-specific base class** that holds the lifecycle-stable resources and orchestrates the
  cold-to-hot transition. One base class per paradigm (`ImperativeLifecyclePhasedComponent`,
  `ReactiveLifecyclePhasedComponent`, `RxJava3LifecyclePhasedComponent`,
  `KotlinLifecyclePhasedComponent`), each in its own paradigm module.

- **A cold phase**, implemented as a non-static inner class of the base class. Its only behaviour is to
  perform the CAS transition and delegate to the resulting hot phase. It carries no state of its own.

- **A hot phase**, implemented as a separate class per concrete component (e.g.,
  `BulkheadHotPhase`, `CircuitBreakerHotPhase`). It holds the hot-only resources (strategy, counters,
  subscriptions) and executes the actual component logic.

The architectural rule: **inheritance for structural commonality across components within a paradigm;
composition for behavioural variation within a component.** The base class is inherited; strategies inside
the hot phase are composed.

### Why per-paradigm base classes, not one in `inqudium-config`

The natural question is whether the base class belongs in `inqudium-config`, parameterized by paradigm. The
answer is no, and the reason is execute-signature divergence.

The imperative form returns a value directly:
```java
Object execute(long chainId, long callId, Object argument, InternalExecutor next);
```

The reactive form returns a publisher:
```java
Mono<Object> execute(long chainId, long callId, Object argument, ReactiveExecutor next);
```

The RxJava3 form returns an `Observable` or `Single` with subtly different subscription semantics. The
Kotlin form is a `suspend fun` that participates in structured concurrency.

A common base class would have to abstract over these as a generic return type, which forces every
non-trivial method into either generic-laden ceremony or unsafe casts. The cold-to-hot transition logic in
particular interacts with the return type — in the reactive case, the transition can be deferred until
subscription time, which is a meaningful optimization the imperative form does not have.

Four parallel base classes with the same shape but different signatures express this honestly. The shared
ideas — the `AtomicReference<Phase>` field, the CAS-and-delegate pattern, the lifecycle accessor, the
listener list, the event-publisher reference — appear identically in all four. They are not factored into a
common parent because the cost of generic gymnastics outweighs the cost of explicit duplication of structure.

This is consistent with the principle established in ADR-025: paradigm-specific code lives in paradigm
modules, paradigm-agnostic interfaces live in `inqudium-config`. The base classes are paradigm-specific by
nature; their interfaces (`LifecycleAware`, `Component`) live in `inqudium-config`.

### The imperative base class

The imperative form is the clearest illustration. Other paradigms follow the same structure with adjusted
method signatures.

```java
package eu.inqudium.imperative.lifecycle;

import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.LifecycleState;
// ...

public abstract class ImperativeLifecyclePhasedComponent<S extends ComponentSnapshot>
        implements LifecycleAware {

    // Lifecycle-stable resources: present from construction, shared across phases.
    private final String name;
    private final LiveContainer<S> live;
    private final List<ChangeRequestListener<S>> listeners;
    private final InqEventPublisher eventPublisher;

    // The phase reference. Starts pointing at the cold phase; CAS-replaced once.
    private final AtomicReference<ImperativePhase> phase;

    protected ImperativeLifecyclePhasedComponent(
            String name,
            LiveContainer<S> live,
            InqEventPublisher eventPublisher) {
        this.name = name;
        this.live = live;
        this.listeners = new CopyOnWriteArrayList<>();
        this.eventPublisher = eventPublisher;
        this.phase = new AtomicReference<>(new ColdPhase());
    }

    // Subclasses (one per concrete component) implement this to construct their hot phase.
    // Called exactly once per component lifetime, on the first execute call.
    protected abstract ImperativePhase createHotPhase();

    @Override
    public final LifecycleState lifecycleState() {
        return phase.get() instanceof HotPhaseMarker
                ? LifecycleState.HOT
                : LifecycleState.COLD;
    }

    public final String name() {
        return name;
    }

    public final S snapshot() {
        return live.snapshot();
    }

    // The execute entry point. Delegates to whichever phase is current.
    public final Object execute(
            long chainId, long callId, Object argument, InternalExecutor next) {
        return phase.get().execute(chainId, callId, argument, next);
    }

    // Listener registration is paradigm-agnostic but lives here because the base class
    // owns the listener list. Phases consult it during update dispatch (see ADR-028).
    public final AutoCloseable onChangeRequest(ChangeRequestListener<S> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    List<ChangeRequestListener<S>> listeners() {
        return listeners;
    }

    InqEventPublisher eventPublisher() {
        return eventPublisher;
    }

    // The phase contract internal to this base class.
    interface ImperativePhase {
        Object execute(long chainId, long callId, Object argument, InternalExecutor next);
    }

    // Marker interface so the base class can detect hot phases without subtype coupling.
    public interface HotPhaseMarker {}

    // The cold phase: non-static inner class so it implicitly references the enclosing
    // component's phase field. Its only job is to swap itself out for the hot phase.
    private final class ColdPhase implements ImperativePhase {
        @Override
        public Object execute(long chainId, long callId, Object argument, InternalExecutor next) {
            ImperativePhase hot = createHotPhase();
            // CAS: only one thread succeeds; the rest discard their hot phase candidate
            // and use whichever hot phase won. The discarded candidates have not yet
            // performed any side effects (event publish, subscription registration),
            // so the discard is safe.
            if (phase.compareAndSet(this, hot)) {
                eventPublisher.publish(new ComponentBecameHotEvent(name));
            }
            return phase.get().execute(chainId, callId, argument, next);
        }
    }
}
```

The discarded-hot-phase concern: when multiple threads race the cold-to-hot transition, only one CAS
succeeds. The threads that lose the race have constructed a hot-phase candidate that will be garbage-collected
without use. This happens at most a handful of times per component lifetime — the transition occurs exactly
once, and the contention window is the brief interval between `createHotPhase()` and `compareAndSet`. The
allocation cost is real but trivial in absolute terms. The simpler CAS form is preferred over more elaborate
double-checked-locking idioms because the operational impact is negligible and the simpler code is easier to
reason about. Hot-phase constructors **must not have side effects** — no event publishes, no subscription
registrations, no resource acquisition that survives garbage collection — precisely so that discarded
candidates are safe. Side effects like the `ComponentBecameHotEvent` happen *after* the successful CAS, in
the base class, exactly once.

### The concrete component

A bulkhead implementation using this base class:

```java
package eu.inqudium.imperative.bulkhead;

public final class InqBulkhead
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot>
        implements Bulkhead {

    public InqBulkhead(
            String name,
            LiveContainer<BulkheadSnapshot> live,
            InqEventPublisher eventPublisher) {
        super(name, live, eventPublisher);
    }

    @Override
    protected ImperativePhase createHotPhase() {
        // Read the current snapshot to construct the hot phase from up-to-date config.
        // No side effects here; pure construction.
        BulkheadSnapshot s = snapshot();
        return new BulkheadHotPhase(this, s);
    }

    // Bulkhead-specific public methods that read state through the snapshot.
    @Override
    public int getAvailablePermits() {
        ImperativePhase p = currentPhase();
        return p instanceof BulkheadHotPhase hot ? hot.availablePermits() : snapshot().maxConcurrentCalls();
    }

    // ...

    // Package-private accessor used by the cold phase via the base class.
    private ImperativePhase currentPhase() { ... }
}
```

The hot phase carries the bulkhead-specific runtime state:

```java
package eu.inqudium.imperative.bulkhead;

final class BulkheadHotPhase
        implements ImperativeLifecyclePhasedComponent.ImperativePhase,
                   ImperativeLifecyclePhasedComponent.HotPhaseMarker {

    private final InqBulkhead component;       // back-reference to the base class for shared resources
    private final BlockingBulkheadStrategy strategy;   // the composed strategy (see architectural rule)

    BulkheadHotPhase(InqBulkhead component, BulkheadSnapshot snapshot) {
        this.component = component;
        this.strategy = StrategyFactory.create(snapshot);
        // Subscribe to snapshot changes — but only after the CAS commits this phase.
        // The base class arranges this via a post-commit hook (see "Snapshot subscription" below).
    }

    @Override
    public Object execute(long chainId, long callId, Object argument, InternalExecutor next) {
        Permit p = strategy.tryAcquire(component.snapshot());
        if (!p.granted()) {
            throw new BulkheadFullException(component.name());
        }
        try {
            return next.execute(chainId, callId, argument);
        } finally {
            p.release();
        }
    }

    int availablePermits() {
        return strategy.availablePermits();
    }
}
```

### Snapshot subscription

The hot phase needs to listen on the `LiveContainer`'s snapshot stream so it can adapt its internal state
when the configuration changes (a permit count adjustment, an algorithm parameter tweak). The subscription
must happen *after* the CAS commits the hot phase, otherwise discarded candidates would leave dangling
subscribers behind. The base class provides a hook:

```java
// In ImperativeLifecyclePhasedComponent
private final class ColdPhase implements ImperativePhase {
    @Override
    public Object execute(...) {
        ImperativePhase hot = createHotPhase();
        if (phase.compareAndSet(this, hot)) {
            // Post-commit work — runs exactly once per component lifetime.
            eventPublisher.publish(new ComponentBecameHotEvent(name));
            if (hot instanceof PostCommitInitializable post) {
                post.afterCommit(live);
            }
        }
        return phase.get().execute(...);
    }
}

public interface PostCommitInitializable {
    void afterCommit(LiveContainer<?> live);
}
```

Hot phases that need post-commit initialization — subscriptions, scheduled tasks, any setup with side
effects that must happen exactly once — implement `PostCommitInitializable`. The base class invokes it
between the successful CAS and the delegated execute.

### The architectural rule applied

The bulkhead example illustrates the rule: **inheritance for structural commonality, composition for
behavioural variation.**

The lifecycle phasing — cold/hot, the AtomicReference, the CAS, the listener list — is structural commonality
across all imperative components. It lives in the inherited base class. Every imperative component that
participates in the lifecycle gets it for free.

The acquire strategy inside the hot phase — semaphore vs. CoDel vs. AIMD — is behavioural variation within
the bulkhead component. It lives as a composed `BlockingBulkheadStrategy` field that the hot phase delegates
to. Different bulkheads with different strategies share the same hot-phase class; they differ only in which
strategy was constructed at hot-phase build time.

This rule is consistent across the codebase. Other components that have strategy variations follow the same
pattern: inherit the lifecycle base, compose the strategy.

### Per-paradigm differences

The four paradigm base classes share the structure described above but differ in three key respects:

**Imperative.** Methods return values directly; the cold phase performs the CAS and delegates synchronously.

**Reactive.** Methods return `Mono`/`Flux`. The cold phase can defer the CAS to subscription time:

```java
// Sketch — full version in inqudium-reactive
public Mono<Object> execute(...) {
    return Mono.defer(() -> {
        ReactivePhase hot = createHotPhase();
        if (phase.compareAndSet(this, hot)) {
            eventPublisher.publish(new ComponentBecameHotEvent(name));
        }
        return phase.get().execute(...);
    });
}
```

This deferral matters: a reactive bulkhead constructed but never subscribed to never transitions. The cold
phase remains, no hot-phase resources are allocated. This is a genuine semantic match with reactive
laziness.

**RxJava3.** Similar to reactive but with RxJava3-specific `Single`/`Observable` types and a slightly
different subscription model.

**Kotlin coroutines.** Methods are `suspend fun`. The cold-to-hot transition happens in the suspending
context, allowing the hot phase to be constructed with access to the calling coroutine's `CoroutineContext`
where relevant. Implemented in the `inqudium-kotlin` module.

These differences are *not* expressible as variants of a single generic base class without losing
type-safety on the return paths or imposing unhelpful constraints on subclasses. Per-paradigm base classes
keep each paradigm's implementation idiomatic for that paradigm.

### Lifecycle of the hot phase under updates

Once the component is hot, snapshot updates from `runtime.update(...)` are routed through the veto chain
(ADR-028) and, when accepted, applied to the `LiveContainer`. The hot phase's snapshot subscriber sees the
new snapshot and adapts. *Adaptation* may mean:

- Adjusting strategy parameters in place (e.g., changing a semaphore's permit count).
- Replacing the strategy entirely (e.g., switching from semaphore to CoDel — only legal if the strategy's
  internal-mutability check accepts; see ADR-028).
- Restarting subscriptions or re-scheduling tasks.

The hot phase is responsible for adaptation logic. The base class does not participate beyond delivering
the new snapshot. Adaptation that fails (e.g., a strategy hot-swap discovers in-flight calls it cannot drain)
must be detected by the component-internal mutability check *before* the snapshot is committed, not after —
this is the contract from ADR-028.

The hot phase is not replaced on update. The same `BulkheadHotPhase` instance lives for the entire hot
period of the component; only its internal state evolves. This keeps subscriber identity stable for any
external observer holding a reference to the hot phase.

### Implementation locations

| Layer                                             | Module                | Code                                                                                  |
|---------------------------------------------------|-----------------------|---------------------------------------------------------------------------------------|
| `LifecycleAware`, `LifecycleState`,               | `inqudium-config`     | Paradigm-agnostic interfaces and event types.                                         |
| `ComponentBecameHotEvent`, `PostCommitInitializable` |                       |                                                                                       |
| `ImperativeLifecyclePhasedComponent`              | `inqudium-imperative` | Imperative base class plus inner `ColdPhase`.                                         |
| `ReactiveLifecyclePhasedComponent`                | `inqudium-reactive`   | Reactive base class with deferred-subscription cold phase.                            |
| `RxJava3LifecyclePhasedComponent`                 | `inqudium-rxjava3`    | RxJava3 base class.                                                                   |
| `KotlinLifecyclePhasedComponent`                  | `inqudium-kotlin`     | Kotlin coroutines base class.                                                         |
| Concrete components (`InqBulkhead`,               | paradigm modules      | Each component extends its paradigm's base class and provides `createHotPhase()`.     |
| `InqCircuitBreaker`, ...) and their hot phases    |                       |                                                                                       |

## Consequences

**Positive:**

- The lifecycle pattern is implemented exactly once per paradigm, in the base class. New components in a
  paradigm gain lifecycle support by extending the base — no per-component reimplementation, no opportunity
  for subtle deviations between components.
- The State-pattern structure (cold and hot as separate classes) makes the behavioural difference between
  the two phases explicit. The cold phase has one job; the hot phase has a different one. Reading the code
  reveals which phase the logic belongs to.
- The hot path after transition is free of lifecycle overhead. `phase.get().execute(...)` reads a stable
  reference; once the component is hot, the reference never changes again. Branch prediction sees a stable
  target; no per-call lifecycle check is necessary.
- Hot-phase resources are constructed lazily and from the live snapshot at the moment of transition. A
  bulkhead built with a snapshot of `maxConcurrentCalls=10` and updated to `15` before its first use sees
  `15` when it transitions, not `10`.
- The architectural rule (inherit for structure, compose for behaviour) is consistent and gives developers a
  clear heuristic for new code. Strategies, algorithms, and other intra-component variations stay
  compositional; the lifecycle scaffolding stays inherited.
- Per-paradigm base classes preserve idiomatic signatures. Reactive code returns `Mono`/`Flux` without
  generic gymnastics; Kotlin code uses `suspend` natively.
- The discarded-hot-phase concern under CAS contention is bounded (at most a few cases per component
  lifetime) and harmless (hot phase constructors are required to be side-effect-free), so the simple CAS
  form is sufficient.

**Negative:**

- Inheritance consumes the single Java extends slot. Components that need to extend an unrelated framework
  base class — Spring's `Lifecycle`, a metrics integration's `MeterBinder` — cannot inherit both. In
  practice, components delegate such concerns to wrapper classes rather than extending; this is the standard
  pattern and not unique to this design.
- Four parallel base classes mean the same structural ideas are written four times. A bug fix in the
  imperative base must be considered for the other three. Ameliorated by the fact that the base classes are
  small (under 200 lines each) and structurally near-identical; reviewing four similar diffs is faster than
  reviewing one large generic abstraction.
- The cold-phase inner class is non-static, which holds a reference to the enclosing component. This is
  intentional (the cold phase needs to reach the `phase` field for the CAS) but means the cold phase cannot
  exist independently of its enclosing component. Acceptable because the cold phase has no other purpose.
- Hot-phase constructors must be discipline-bound to be side-effect-free, supported by the
  `PostCommitInitializable` hook for any setup that genuinely needs to run only after a successful CAS.
  Discipline alone is insufficient; the convention must be enforced in code review and (where practical) by
  static analysis.
- Components that do not fit the cold/hot model — e.g., a stateless validator that has no hot phase to speak
  of — should not extend the lifecycle base classes. The pattern is for components with meaningful runtime
  state, not for everything. Components must self-select.

**Neutral:**

- The base class is paradigm-specific, not framework-wide. New paradigms (e.g., a future Loom-based
  structured-concurrency module) require their own base class. This is consistent with the rest of the
  module structure.
- Snapshot subscription via `PostCommitInitializable` is opt-in. Hot phases that do not need post-commit
  setup (rare but possible) skip the interface. This keeps the simplest case simple.
- The hot phase persists for the component's hot lifetime; it is not replaced on update. External observers
  holding a hot-phase reference see consistent identity. This is by design, but means the hot phase cannot
  be cleanly "reset" — the only reset is component removal and re-addition (ADR-026).
- The architectural rule (inheritance for commonality, composition for variation) is established here for
  the lifecycle pattern but applies generally across the codebase. Future ADRs may invoke it explicitly when
  the same trade-off arises.
