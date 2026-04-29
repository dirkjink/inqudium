# ADR-028: Update propagation and component lifecycle

**Status:** Accepted
**Date:** 2026-04-26
**Deciders:** Core team
**Related:** ADR-025 (configuration architecture), ADR-026 (runtime and registry), ADR-027 (validation strategy)

## Context

ADR-025 establishes that configuration is mutable at runtime via the same DSL used for initialization. ADR-026
specifies the runtime container that holds live components and routes update calls. ADR-027 specifies how
configurations are validated for technical correctness.

None of the three covers the central question that arises once components are actually running: **what should
happen when an update arrives at a component that is currently serving calls?**

A bulkhead that was just built and has never granted a permit can have its `maxConcurrentCalls` changed
without consequence — there is no in-flight state to disturb. The same bulkhead, once it has been integrating
into application traffic for hours, may be holding permits, queueing waiters, and feeding adaptive-limit
algorithms with RTT data. A naïve "swap the snapshot" against the second case can produce concrete defects:
permits revoked from a thread that already passed the acquire check, an adaptive algorithm reseeded with stale
state, listener subscribers notified of changes the component cannot honour, or worst, a partially-applied
patch leaving the component in a state no validation rule had a chance to assess.

The framework needs a mechanism that:

1. **Recognizes the difference** between an "early" update (component still in setup) and a "live" update
   (component actively serving traffic). Updates in the early phase should be cheap and unrestricted; updates
   in the live phase should pass through safety checks.

2. **Lets the component itself participate** in the safety decision. A component knows what its own internal
   state can tolerate; the framework cannot know in advance whether a strategy hot-swap is safe for a given
   strategy implementation.

3. **Lets the application participate.** Operational policy may dictate that certain updates are not
   acceptable regardless of whether the component is technically able to apply them — for instance, a
   `maxWaitDuration` below 10ms might be disallowed by site convention. The application code that knows about
   such conventions must have a hook to enforce them.

4. **Preserves all invariants.** A safety mechanism that lets through partially-applied patches would be
   worse than no mechanism — it would allow the configuration to enter states that no validation pass had
   ever sanctioned.

5. **Stays simple to use.** Application developers who do not care about live-update semantics should not be
   confronted with lifecycle states, listener registrations, or veto callbacks. The default behaviour must be
   sensible and the surface must be opt-in.

This ADR specifies the lifecycle and update-propagation mechanics that satisfy all five requirements.

## Decision

The mechanism has three parts: a per-component lifecycle (cold/hot), a veto chain that runs only in the hot
state, and a listener API that lets application code participate. Each part is small on its own; their
combination is the full update-propagation contract.

### Component lifecycle: cold and hot

Every live component carries an internal `LifecycleState` enum:

```java
public enum LifecycleState {
    COLD,   // configured, not yet actively serving calls
    HOT     // has begun serving calls; updates require veto-chain processing
}
```

The transition `COLD → HOT` is **monotonic, automatic, and component-driven**. Once a component has reached
the hot state, it cannot return to cold. The trigger for the transition is uniform across components: **the
first call to `execute` (or its paradigm-specific equivalent — `executeAsync`, the first subscription on a
returned `Mono`/`Flux`, the first `suspend`-call, etc.) marks the component as hot.**

A finer-grained trigger ("first successful permit grant" for a bulkhead, "first outcome recorded" for a
circuit breaker) was considered and rejected. The fine-grained form would distinguish "calls that reached the
component but were rejected before producing live state" from "calls that produced live state". In practice,
this distinction matters only in pathological scenarios — a bulkhead that rejects every call from the first
moment is a misconfiguration that surfaces immediately; the difference between "hot at first call" and "hot
at first permit grant" describes at most a few seconds of operational time. The uniform "first execute"
trigger has two benefits: it lets the lifecycle implementation be centralized in a per-paradigm base class
(see ADR-029) without per-component logic, and it removes the lifecycle-state check from every subsequent
hot-path execution because the cold phase delegates to the hot phase exactly once and is then unreachable.

The trigger is automatic, observable, and one-directional. Components do not expose a way for application
code to alter or short-circuit it.

The lifecycle is observable but not controllable from outside the component:

```java
ImperativeBulkhead<?, ?> bh = runtime.imperative().bulkhead("inventory");
LifecycleState state = bh.lifecycleState();   // returns COLD or HOT
```

There is no public `markHot()` API, no configuration option to influence the trigger, no way to force a
component into hot before its natural conditions are met. This is a deliberate simplification: lifecycle is
an implementation detail of each component, not a knob for application code.

The implementation pattern that components in the framework's own paradigm modules use to realize the
cold/hot lifecycle — a per-paradigm base class with cold/hot phase classes orchestrated through an
`AtomicReference` — is specified separately in ADR-029. This ADR is responsible only for the *behaviour*
(when transitions happen, what they mean for update routing); ADR-029 specifies the *implementation form*.

### Update routing by lifecycle state

When a `runtime.update(...)` call produces a patch for a component, the routing decision is made based on the
component's current state:

```
patch arrives at LiveBulkhead
    │
    ├─ if state == COLD ─┐
    │                    │
    │            apply patch directly
    │            (Class 1, 2, 3 validation already happened earlier;
    │             listeners are not consulted)
    │
    └─ if state == HOT ──┐
                         │
                  run veto chain:
                    1. registered listeners (in registration order)
                    2. component's internal mutability check
                  if any vetoes → patch rejected, BuildReport entry: VETOED
                  if all accept → apply patch
```

**Cold updates are deterministic.** Cold-state updates skip the veto chain entirely. They go through the same
classes-1-2-3 validation as initialization patches (ADR-027), which guarantees the resulting snapshot is
internally consistent, and they are then applied without further intervention. This is essential to the
Spring-Boot-style early-customization scenario where `@PostConstruct` beans, `ApplicationListener`s, or
property-binding hooks adjust the configuration during application bootstrap. Those updates should be cheap,
fast, and free from the conceptual baggage of veto handling.

**Hot updates are negotiated.** Once a component has begun serving calls, every patch goes through the veto
chain. The chain is *conjunctive*: the patch is applied if and only if every listener and the component
itself accept it. A single veto rejects the patch entirely.

### Veto atomicity: per-component, all-or-nothing

A veto rejects an entire component patch — not individual fields. This is the central simplification of the
design. The reasoning is laid out in detail in the project's design discussion; the short version is that
field-level veto produces post-veto snapshots that may violate snapshot invariants (a partially-vetoed
`{minLimit=10, maxLimit=15}` patch against `{minLimit=2, maxLimit=8}` could land at `{minLimit=10,
maxLimit=8}`, breaking the `min ≤ max` invariant).

Per-component patch atomicity sidesteps the entire problem. The patch either applies fully or not at all;
the resulting snapshot has gone through the same validation that any initialization snapshot does; no
re-validation pass after veto is necessary, because no partial state ever exists.

A listener that wants to block one specific field change must veto the whole patch and explain why:

```java
bulkhead.onChangeRequest(request -> {
    if (request.touchedFields().contains(BulkheadField.MAX_WAIT_DURATION)) {
        Duration proposed = request.proposedValue(BulkheadField.MAX_WAIT_DURATION, Duration.class);
        if (proposed.toMillis() < 10) {
            return ChangeDecision.veto(
                "maxWaitDuration below 10ms is disallowed by site policy. "
                    + "Patch covered fields: " + request.touchedFields());
        }
    }
    return ChangeDecision.accept();
});
```

If the listener vetoes, the application code that issued `runtime.update(...)` sees a `VETOED` outcome in the
`BuildReport` and can resubmit a more conservative patch. The framework does not auto-retry, partially apply,
or attempt to negotiate.

Cross-component atomicity is unchanged from ADR-026: a single `runtime.update(...)` call can affect multiple
components, each evaluated independently. A veto on bulkhead A does not affect circuit breaker B in the same
update; both may proceed, neither, or any combination. The `BuildReport` reports each component's outcome
separately.

### Listener API

Listeners are registered per component handle and are scoped to that handle's lifetime. The registration
contract itself is extracted into a small paradigm-agnostic interface, `ListenerRegistry<S>`:

```java
/**
 * Paradigm-agnostic contract for component handles that accept change-request listeners.
 *
 * Lives in inqudium-config so the dispatcher (also in inqudium-config) can consult listeners
 * without depending on any paradigm module's concrete handle type. Each per-paradigm lifecycle
 * base class (ImperativeLifecyclePhasedComponent, ReactiveLifecyclePhasedComponent, ...)
 * implements this interface; the dispatcher works against the interface.
 */
public interface ListenerRegistry<S extends ComponentSnapshot> {

    /**
     * Register a listener that will be consulted before any hot-state update.
     * Returns an AutoCloseable for unregistration; the handle is scoped to the
     * registry's lifetime — when the component is removed, all listeners are
     * silently discarded.
     */
    AutoCloseable onChangeRequest(ChangeRequestListener<S> listener);

    /**
     * Returns the registered listeners in registration order. Used by the
     * dispatcher to drive the veto chain.
     */
    List<ChangeRequestListener<S>> listeners();
}

// Per-component handles (BulkheadHandle, CircuitBreakerHandle, ...) extend the three
// dispatcher-facing interfaces. The dispatcher works against the intersection
// `LifecycleAware & ListenerRegistry<S> & InternalMutabilityCheck<S>` — paradigm-agnostic and
// component-agnostic. There is no shared `ComponentHandle` super-interface; component handles
// share contracts, not a class hierarchy.
public interface BulkheadHandle<P extends ParadigmTag>
        extends LifecycleAware, ListenerRegistry<BulkheadSnapshot>,
                InternalMutabilityCheck<BulkheadSnapshot> {
    // ... bulkhead-specific accessors (snapshot, name, paradigm, etc.)
}

@FunctionalInterface
public interface ChangeRequestListener<S extends ComponentSnapshot> {

    /**
     * Decide whether the proposed patch is acceptable. Called once per registered listener,
     * in registration order, on the dispatcher's hot path. Vetoing aborts the patch — see
     * {@link ChangeDecision#veto(String)}.
     */
    ChangeDecision decide(ChangeRequest<S> request);

    /**
     * Decide whether structural removal of the component is acceptable. Default-accept so
     * patch-only listeners do not need to opt in. Listeners that want to block removals
     * override this method.
     */
    default ChangeDecision decideRemoval(S currentSnapshot) {
        return ChangeDecision.accept();
    }
}

public interface ChangeRequest<S extends ComponentSnapshot> {
    S currentSnapshot();
    S postPatchSnapshot();                            // current with the patch applied
    Set<? extends ComponentField> touchedFields();    // typed enum per component
    <T> T proposedValue(ComponentField field, Class<T> type);
    Map<ComponentField, Object> allProposedValues();   // for listeners that want to inspect everything
}

public sealed interface ChangeDecision permits ChangeDecision.Accept, ChangeDecision.Veto {
    static ChangeDecision accept() { return Accept.INSTANCE; }
    static ChangeDecision veto(String reason) { return new Veto(reason); }

    record Accept() implements ChangeDecision { static final Accept INSTANCE = new Accept(); }
    record Veto(String reason) implements ChangeDecision {
        public Veto {
            Objects.requireNonNull(reason, "veto reason must not be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("veto reason must not be blank");
            }
        }
    }
}
```

Five properties of this API deserve explicit mention:

**`ListenerRegistry<S>` is a separate interface, not part of `ComponentHandle` directly.** The dispatcher in
`inqudium-config` consumes the registry contract; it does not need any other handle method to drive the
veto chain. Keeping the registry as its own interface lets the per-paradigm lifecycle base classes
(specified in ADR-029) implement it directly without forcing implementation of every other handle method.
A second consumer of the registry — for instance, a Spring Boot integration that wants to register a
policy listener at bean-binding time — can also work against the narrower interface. This is interface
segregation applied at the right granularity: small enough to be useful, large enough to be meaningful.

**Vetoes must carry a reason.** The `Veto` record has a non-null, non-blank `reason` field, enforced in the
compact constructor. This is a deliberate design choice: silent vetoes are debugging nightmares. The reason
is propagated into the `BuildReport` and into the lifecycle event for the rejected patch, so an operator
investigating "why did my update not apply?" can find the answer immediately.

**Listeners see typed proposed values.** The `ChangeRequest<S>` interface is parameterized by snapshot type,
and `proposedValue(field, Class<T>)` does the typed unwrap. This avoids the `Object` casts that plague raw
JavaBeans `PropertyChangeEvent`. The price is that listeners must know the snapshot type at registration —
which they do, because they registered through a typed component handle.

**`ChangeRequest` carries both the current and the post-patch snapshot.** The dispatcher computes the
post-patch snapshot once when constructing the request — `patch.applyTo(currentSnapshot)` — and exposes it
through `postPatchSnapshot()`. Listeners and the component-internal mutability check that want to reason
about *the snapshot the system would land on* can do so directly, without re-applying the patch
themselves. The two views (current and post-patch) coexist because some checks belong to one and some to
the other: a listener vetoing on "no breaking changes for SLA-tier customers" reasons against the
post-patch snapshot; a transition-precondition check ("zero in-flight calls before strategy swap")
reasons against the current runtime state. The component-internal veto section below makes this
distinction explicit.

**Listener registration is scoped to handle lifetime, not application lifetime.** When a component is
removed, its handle becomes inert; pending listeners are silently discarded. There is no leak. Listeners
that need cross-removal continuity must re-register on the new component if it is recreated.

### Component-internal veto

After all external listeners have accepted, the component itself is consulted via the
`InternalMutabilityCheck<S>` interface. The interface lives in `inqudium-config` so the
dispatcher can call into it without depending on any paradigm module:

```java
public interface InternalMutabilityCheck<S extends ComponentSnapshot> {

    /**
     * Decide whether the proposed patch can be applied without corrupting internal state.
     * Called by the dispatcher after all listeners have accepted, as the last gate before
     * the apply. The check evaluates against the request's snapshot views — typically the
     * post-patch snapshot for field-value validation, the current runtime state for
     * transition-operation preconditions.
     */
    ChangeDecision evaluate(ChangeRequest<S> request);

    /**
     * Decide whether structural removal can proceed. Default-accept so components that have
     * no removal-time invariants do not need to opt in. Components with removal-time
     * concerns (in-flight calls, drainage windows) override this method.
     */
    default ChangeDecision evaluateRemoval(S currentSnapshot) {
        return ChangeDecision.accept();
    }
}
```

The contract is that *the component owns the check*. A listener chain that erroneously accepts a
patch which would corrupt internal state cannot bypass it, because the check is consulted by
the dispatcher as the last gate before `LiveContainer.apply(patch)` and is owned by the
component, not by the listener registry. The chain remains conjunctive — a single
`ChangeDecision.Veto` from the check rejects the whole component patch and surfaces in the
`BuildReport` with `Source.COMPONENT_INTERNAL`.

#### What the check evaluates against

The `ChangeRequest<S>` carries two snapshot views: `currentSnapshot()` is the live state at
the moment the request was constructed, and `postPatchSnapshot()` is the same with the patch
applied. Which view a check consults depends on what the rule is about:

- **Field-value validation against the post-patch state.** When the rule expresses
  *"this field can only be in this configuration if the rest of the snapshot supports it"*,
  the natural view is the post-patch one. If the patch also changes a related field, the
  check sees the combined effect — for example, a patch that sets
  `MAX_CONCURRENT_CALLS=50` together with `STRATEGY=SemaphoreStrategyConfig` is evaluated
  against a post-patch snapshot whose strategy *is* the semaphore, regardless of which
  strategy the runtime is currently on. This avoids the trap of evaluating a multi-field
  patch field-by-field against the runtime state and rejecting combinations the user
  explicitly composed to be valid.

- **Transition-operation preconditions against the runtime state.** When the rule
  expresses *"this operation can only run when the runtime is in a certain state"*, the
  view is the live runtime, not the snapshot. Strategy hot-swap is the canonical example:
  the precondition is *"no permits held"*, which is a property of the running strategy
  instance, not of any snapshot field. A check that consulted the post-patch snapshot here
  would conflate the swap's *target* (which the post-patch snapshot describes) with the
  swap's *feasibility* (which only the runtime can answer).

A bulkhead's hot phase combines both:

```java
// In a hot phase, e.g. BulkheadHotPhase
@Override
public ChangeDecision evaluate(ChangeRequest<BulkheadSnapshot> request) {
    Set<? extends ComponentField> touched = request.touchedFields();
    BulkheadSnapshot postPatch = request.postPatchSnapshot();

    // Transition operation: STRATEGY swap requires the runtime to be quiescent.
    if (touched.contains(BulkheadField.STRATEGY)) {
        int inFlight = strategy.concurrentCalls();
        if (inFlight > 0) {
            return ChangeDecision.veto(
                    "strategy swap requires zero in-flight calls; current = " + inFlight);
        }
    }

    // Field-value validation: MAX_CONCURRENT_CALLS is live-tunable only when the
    // post-patch strategy is the semaphore. A combined STRATEGY=Semaphore +
    // MAX_CONCURRENT_CALLS=N patch passes this check on a hot CoDel bulkhead, because
    // post-patch is what matters here.
    if (touched.contains(BulkheadField.MAX_CONCURRENT_CALLS)
            && !(postPatch.strategy() instanceof SemaphoreStrategyConfig)) {
        return ChangeDecision.veto(
                "maxConcurrentCalls is not live-tunable on "
                        + postPatch.strategy().getClass().getSimpleName());
    }

    return ChangeDecision.accept();
}
```

Because the dispatcher invokes the check via the handle's intersection-typed reference (see
"Listener API" above for the full contract: `LifecycleAware & ListenerRegistry<S> &
InternalMutabilityCheck<S>`), the handle exposes `evaluate(...)` as part of its API. This is a
deliberate trade-off: keeping the check on a handle-internal interface would require a separate
dispatcher-only handle reference, and the cost — three small interfaces visible on the handle —
is small in exchange for the dispatcher remaining paradigm-agnostic. Application code that
discovers `evaluate(...)` via the handle should not call it directly: the framework calls it on
every hot patch, and ad-hoc invocations bypass the listener chain that ADR-028 sequences before
the check.

The hook's specific logic is documented in each component's own ADR (ADR-020 for bulkhead,
etc.) — this ADR specifies only that the hook exists, that it runs after external listeners,
that vetoes carry `Source.COMPONENT_INTERNAL`, and that the post-patch / runtime-state
distinction is the right axis along which check rules organize themselves.

#### Live tunability as a component contract

The post-patch branch of the check encodes what the project calls *live tunability*: the set
of fields a hot component can take a new value for without being torn down and rebuilt. The
check is the place where each component declares this for itself. Fields outside the
live-tunable set are not silently rejected at runtime — they are vetoed at evaluation time
with a reason that says exactly which field, under which conditions, cannot be tuned.

This matters because the alternative is failure modes that look like success. A component
that accepts every field touch but cannot honour some of them at runtime would let the
snapshot move ahead of reality: the `BuildReport` says PATCHED, the snapshot reports the
new value, but the running component keeps behaving by the old one. The mutability check
makes that mismatch impossible by surfacing the constraint as an explicit veto. The
`postPatchSnapshot()` view is what lets a component answer "is this field tunable on this
post-patch configuration?" without conflating that question with "what is the runtime doing
right now?"

### Update execution sequence

A single `runtime.update(...)` invocation may carry both patches and removals across multiple
components. The runtime processes them in two passes within the same update: all patches
first, then all removals. This ordering is observable through the runtime's topology events
and is part of the contract — a subscriber that sees `RuntimeComponentPatchedEvent` for `A`
followed by `RuntimeComponentRemovedEvent` for `B` knows the patch on `A` committed before
`B` was torn down.

#### Patch flow

The full sequence for a single component patch reaching a hot component:

```
1. patch enters runtime.update(...)
2. Class 1 (argument-range) — already enforced at DSL setter time, never reaches here
3. Class 2 (snapshot invariants) — performed when constructing the would-be-resulting snapshot
4. Class 3 (consistency rules) — performed against the would-be-resulting snapshot
5. If 3 or 4 fail: BuildReport entry REJECTED (with validation findings); patch discarded.
6. Lifecycle check: COLD or HOT?
7. If COLD: apply patch atomically via LiveContainer.apply(patch); BuildReport entry PATCHED
   (or UNCHANGED if the apply produced a snapshot equal to the prior one).
8. If HOT:
    a. For each registered listener (in registration order):
        - call listener.decide(request)
        - if Veto: BuildReport entry VETOED with Source.LISTENER; abort.
    b. Component-internal mutability check (InternalMutabilityCheck.evaluate(request)):
        - if Veto: BuildReport entry VETOED with Source.COMPONENT_INTERNAL; abort.
    c. apply patch atomically via LiveContainer.apply(patch); BuildReport entry PATCHED
       (or UNCHANGED on a no-op).
9. Subscribers (the snapshot-change subscribers from ADR-025, distinct from listeners) are notified.
10. RuntimeComponentPatchedEvent is published (or RuntimeComponentAddedEvent if the patch
    materialized a new component, or RuntimeComponentVetoedEvent if step 8 aborted).
```

#### Removal flow

A removal request reaches a component through a separate dispatch path. It uses the same veto
chain as a patch but consults the removal-specific listener and check methods, and the
post-acceptance work tears down the component instead of mutating its snapshot:

```
1. removeBulkhead("name") enters runtime.update(...)
2. Component lookup. If unknown name: BuildReport entry UNCHANGED; nothing happens.
3. Lifecycle check: COLD or HOT?
4. If COLD: skip the veto chain (no listeners can be active on a cold component, and the
   internal check has nothing to evaluate); proceed to step 6.
5. If HOT:
    a. For each registered listener (in registration order):
        - call listener.decideRemoval(currentSnapshot)
        - if Veto: BuildReport entry VETOED with Source.LISTENER; abort. The component
          stays.
    b. Component-internal removal check
       (InternalMutabilityCheck.evaluateRemoval(currentSnapshot)):
        - if Veto: BuildReport entry VETOED with Source.COMPONENT_INTERNAL; abort.
6. Component shutdown:
    a. The current phase's shutdown hook runs (closing live-container subscriptions, etc.)
       if the phase implements ShutdownAware.
    b. The phase reference is replaced with a removed-phase sentinel via CAS. A retry loop
       converges if a concurrent cold-to-hot transition is in flight.
    c. The component is removed from the paradigm container's name → handle map.
    d. BuildReport entry REMOVED; RuntimeComponentRemovedEvent is published.
```

After step 6, any external reference still held to the component's handle is **inert**: every
operational method (execute, snapshot, lifecycleState, evaluate, ...) raises
`ComponentRemovedException`; identity-only methods (`name()`, `elementType()`) keep returning
their stable values so error messages on the inert handle remain readable. Listener
registration on an inert handle is silently retained but never consulted again — the handle
is the only anchor, so it becomes garbage along with its listener list when the holder
releases it.

Steps 7 and 8c of the patch flow both perform `LiveContainer.apply(patch)`, which is a CAS
retry loop against the `AtomicReference<Snapshot>` (ADR-025). The CAS handles concurrent
updates from multiple `runtime.update(...)` calls running in parallel — a patch that loses a
CAS race re-applies itself against the new current snapshot and retries the CAS, without
re-running the validation or veto chain. The validation and veto evaluations that already ran
on the original snapshot stand; only the actual snapshot replacement is retried.

This is a deliberate simplification. Re-running the veto chain on every CAS retry would be the conservative
choice — listeners that base their decision on concrete field values would see the new snapshot and could
revise their verdict — but it would significantly complicate the dispatcher, because the CAS retry currently
lives inside `LiveContainer.apply` while the veto chain lives in the dispatcher. The narrow case where a
listener *would* decide differently against the post-CAS snapshot is rare in practice: real veto policies
typically depend on the component's identity and the patch's touched fields, both of which are unchanged
across a CAS retry. If a concrete listener policy ever requires snapshot-sensitive re-evaluation, the
dispatcher can be reworked to wrap the CAS retry — but until that need is documented, the simpler form
stands.

#### Dry-run path

`runtime.dryRun(...)` runs the same validation and veto chain as `runtime.update(...)` but
does not commit any state change. The dispatcher implements this by exposing a `decide(...)`
method parallel to `dispatch(...)`: `decide` performs steps 2–8b of the patch flow (and the
analogous steps 1–5 of the removal flow), then *stops* — no `LiveContainer.apply`, no
component shutdown, no map mutation, no topology event.

The result of a dry-run is a `BuildReport` with the same shape and semantics as a real update:
component outcomes, validation findings, and veto findings are all populated as if the update
had been committed. Subscribers on the runtime publisher do not observe any of the topology
events that a real update would emit; the dry-run path is silent on the event channel.

`ADDED` outcomes during dry-run are validated by constructing the would-be snapshot through
the snapshot's compact constructor (catching class-2 invariant violations) without invoking
the paradigm provider's full materialization path — no live container is allocated, no
publisher is provisioned, no subscriptions are wired. Materialization runs only on commit, and
dry-run does not commit.

### `BuildReport` outcome extension

The `ApplyOutcome` enum from ADR-025 is extended with one new value:

```java
public enum ApplyOutcome {
    ADDED,         // new component created from patch
    PATCHED,       // existing component snapshot updated
    REMOVED,       // component shut down and removed
    REJECTED,      // patch failed validation (Class 2 or 3); see ValidationFindings
    VETOED,        // patch declined by a listener or component-internal check; see VetoFindings
    UNCHANGED      // patch was a no-op (touched fields all matched current values)
}
```

The `BuildReport` is a record that gains `vetoFindings` as a parallel collection alongside the
existing `findings`. Per-component outcomes are keyed by `ComponentKey` — the `(name, paradigm)`
tuple — so that same-name components in different paradigms cannot collide silently:

```java
public record BuildReport(
    Instant timestamp,
    List<ValidationFinding> findings,
    List<VetoFinding> vetoFindings,
    Map<ComponentKey, ApplyOutcome> componentOutcomes
) {
    public boolean isSuccess() {
        // ERROR-level findings flip the flag; veto findings do not — a vetoed patch is a
        // policy outcome, not a validation failure, and the runtime continues.
    }
}

public record VetoFinding(
    ComponentKey componentKey,                 // (name, paradigm) — same-name components in
                                               // different paradigms cannot collide
    Set<? extends ComponentField> touchedFields,
    String reason,                             // from the ChangeDecision.Veto, non-blank
    Source source                              // LISTENER or COMPONENT_INTERNAL
) {
    public enum Source { LISTENER, COMPONENT_INTERNAL }
}
```

The `BuildReport.isSuccess()` predicate considers only `ERROR`-level validation findings; veto
findings do not flip the flag. This is deliberate: a veto reflects a policy decision against a
technically-correct patch, not a validation failure. Operational dashboards conflating the two
would over-count "broken builds" and mask real validation errors behind ordinary policy
rejections.

### Lifecycle events

The runtime publisher carries five topology events:

```java
public record RuntimeComponentAddedEvent(
    String componentName,
    InqElementType elementType,
    Instant timestamp) implements InqEvent { ... }

public record RuntimeComponentPatchedEvent(
    String componentName,
    InqElementType elementType,
    Set<? extends ComponentField> touchedFields,
    Instant timestamp) implements InqEvent { ... }

public record RuntimeComponentRemovedEvent(
    String componentName,
    InqElementType elementType,
    Instant timestamp) implements InqEvent { ... }

public record RuntimeComponentVetoedEvent(
    String componentName,
    InqElementType elementType,
    VetoFinding vetoFinding,
    Instant timestamp) implements InqEvent { ... }

public record ComponentBecameHotEvent(
    String componentName,
    InqElementType elementType,
    Instant timestamp) implements InqEvent { ... }
```

`ComponentBecameHotEvent` fires exactly once per component lifetime, on the cold-to-hot
transition. The other four fire from the dispatcher and from `DefaultImperative.applyUpdate`
when the corresponding outcome is reached.

Two contracts deserve explicit mention:

- **No event for `UNCHANGED`.** A no-op patch (touched values that match current snapshot
  values) commits silently. Subscribers that want to be notified on configuration redraws
  even when the values match must subscribe to the snapshot stream from ADR-025, not to the
  topology events.
- **`RuntimeComponentVetoedEvent` carries the `VetoFinding` directly.** Subscribers that
  drive policy-rejection dashboards can read the reason and source from the event without
  walking the `BuildReport`. The `componentName` and `elementType` fields are redundant with
  `vetoFinding.componentKey()` but kept on the event so subscribers filtering by name do not
  need to navigate two levels of the payload.

These events follow the same publishing semantics as the existing runtime-level events
(ADR-026): they live on the `InqRuntime` event publisher, separate from per-component
publishers (ADR-030).

### Implementation locations

| Layer                                            | Module               | Code                                                            |
|--------------------------------------------------|----------------------|-----------------------------------------------------------------|
| Layer                                            | Module               | Code                                                            |
|--------------------------------------------------|----------------------|-----------------------------------------------------------------|
| `LifecycleState`, `ChangeDecision`,              | `inqudium-config`    | Lifecycle and veto types, `ChangeRequestListener` SPI           |
| `ChangeRequest`, `VetoFinding`,                  |                      | (with `decide` / `decideRemoval`), `BuildReport` and            |
| `ComponentField`, `ListenerRegistry`,            |                      | `BuildReport.vetoFindings`, `ApplyOutcome` extensions,          |
| `InternalMutabilityCheck`,                       |                      | `ComponentRemovedException`, runtime event types                |
| `ComponentRemovedException`                      |                      | (`RuntimeComponentAdded/Patched/Removed/Vetoed`,                |
|                                                  |                      | `ComponentBecameHotEvent`).                                     |
| `UpdateDispatcher`                               | `inqudium-config`    | The dispatcher class itself: parallel `dispatch(...)` for       |
|                                                  |                      | patches and `dispatchRemoval(...)` for removals; `decide(...)`  |
|                                                  |                      | / `decideRemoval(...)` for the dry-run path. Cold/hot routing,  |
|                                                  |                      | listener iteration, internal-mutability invocation,             |
|                                                  |                      | veto-finding construction. Paradigm-agnostic.                   |
| Lifecycle base class for each paradigm           | paradigm modules     | Each paradigm provides a base class (see ADR-029) that          |
|                                                  |                      | concrete components extend. The transition trigger is           |
|                                                  |                      | the uniform "first execute" rule, implemented once per          |
|                                                  |                      | paradigm in the base class. The base class implements           |
|                                                  |                      | `LifecycleAware`, `ListenerRegistry<S>`, and                    |
|                                                  |                      | `InternalMutabilityCheck<S>` so the dispatcher can drive        |
|                                                  |                      | the chain through the typed component handle. The base          |
|                                                  |                      | class also owns the removed-phase sentinel and the              |
|                                                  |                      | `markRemoved()` CAS-retry teardown.                             |
| Per-component mutability logic                   | paradigm modules     | Each component's hot phase implements                           |
|                                                  |                      | `InternalMutabilityCheck<S>` with component-specific logic      |
|                                                  |                      | for both `evaluate` and `evaluateRemoval`. The base class       |
|                                                  |                      | delegates to the current phase via a `final` bridge method.     |
|                                                  |                      | Hot phases that need teardown (closing live-container           |
|                                                  |                      | subscriptions etc.) implement a `ShutdownAware` marker that     |
|                                                  |                      | the base class invokes during `markRemoved()`.                  |
| Topology event emission                          | paradigm container   | `DefaultImperative.applyUpdate` (and equivalent for other       |
|                                                  |                      | paradigms) publishes `RuntimeComponentAddedEvent`,              |
|                                                  |                      | `RuntimeComponentPatchedEvent`,                                 |
|                                                  |                      | `RuntimeComponentRemovedEvent`, and                             |
|                                                  |                      | `RuntimeComponentVetoedEvent` in the patches-then-removals      |
|                                                  |                      | order. `ComponentBecameHotEvent` is published from the          |
|                                                  |                      | lifecycle base class on the cold-to-hot CAS commit.             |

The dispatcher receives the component handle as a single argument, typed as the intersection
`LifecycleAware & ListenerRegistry<S> & InternalMutabilityCheck<S>` so all three contracts are
guaranteed at the call site without the dispatcher knowing the concrete handle type.

## Consequences

**Positive:**

- The cold/hot distinction matches the real-world phases of component life. Early-phase configuration changes
  (Spring Boot bootstrap, post-construct customization, dynamic property binding) are unburdened by veto
  machinery; live updates are protected by it.
- The lifecycle is component-internal and automatic. Application developers who do not care about it never
  encounter it. Those who do care can observe it via `lifecycleState()`.
- Per-component patch atomicity is the simplest possible veto semantics. There is no field-level negotiation,
  no partial state, no re-validation pass. The validation framework from ADR-027 covers the technical
  correctness of the resulting snapshot without any extension.
- Listeners must provide a non-blank reason for vetoes. This produces a debuggable system — every rejected
  update has a traceable cause in the `BuildReport`.
- Cross-component atomicity is preserved: a veto on one component does not affect updates to others in the
  same `runtime.update(...)` call. The semantics established in ADR-026 carry over unchanged.
- Component-internal veto is independent of external listeners: the component cannot be tricked into an
  unsafe state by a listener that accepts everything. This is defence-in-depth.
- Structural removal goes through the same veto chain as patches. Listeners and components can both
  reject removals, with the same conjunctive semantics and the same veto-finding reporting. This makes
  removal a first-class lifecycle event rather than an out-of-band operation.
- Lifecycle events (`ComponentBecameHotEvent`, `RuntimeComponentVetoedEvent`) make the lifecycle and veto
  activity observable to operational tooling without requiring polling or inspection of every `BuildReport`.
- The CAS-based apply mechanism (ADR-025) interacts correctly with concurrent updates: a lost CAS triggers
  a re-application of the patch against the new current snapshot. The veto chain that ran on the original
  base is not re-evaluated; the simplification is documented under "Update execution sequence".

**Negative:**

- Listeners that want fine-grained control over individual fields are forced to veto whole patches and ask
  the caller to resubmit. This is a deliberate simplification but may produce friction in cases where a
  listener cares about exactly one field and the user issues bundled patches.
- Tests that need to put a component into the hot state must invoke a real `execute` call rather than
  calling a public `markHot()` API. This is a usability regression for tests but a correctness win for
  production: there is no test-only entry point that could accidentally be used in application code, and
  the test gesture (calling `execute` once) reflects how production traffic produces the transition.
- Listener execution adds latency to hot-state updates. With many listeners, an update can take measurable
  time. Mitigation: listeners are intended to be cheap, decision-only logic; expensive checks should be
  precomputed.
- Listener execution order is registration-order, which is not always meaningful. If two listeners disagree
  on a patch, the first one to veto wins, regardless of which has the "more important" reason. There is no
  priority mechanism. Acceptable because listener registration is typically scoped and singular; if the same
  component has two competing veto policies, that is an application-level inconsistency to resolve outside
  the framework.

**Neutral:**

- The cold-to-hot transition is one-directional. A component that briefly served calls and then went idle
  for a long period stays in the hot state. This is a deliberate simplification: a "cooldown to cold" rule
  would require deciding what "idle long enough" means, which is application-specific. The cost — that
  long-idle components have unnecessarily restrictive update semantics — is small in practice.
- The internal mutability check runs after external listeners. This means external listeners can see and
  react to patches that the component would ultimately reject anyway. An alternative ordering (component
  first, listeners second) was considered and rejected: it would mean external listeners do not see patches
  the component would not honour, hiding information from operational tooling. The current order favours
  visibility.
- `ApplyOutcome.VETOED` is distinct from `ApplyOutcome.REJECTED`. Some users may merge them in dashboards
  ("anything not applied is bad"), but the distinction is preserved at the framework level for diagnostics:
  a `REJECTED` patch is technically wrong; a `VETOED` patch is technically correct but disallowed by
  policy. These are different operational situations.
- Component-internal veto logic is documented in each component's own ADR. This ADR specifies the contract
  (the hook exists, runs at the right point, takes a `ChangeRequest`, returns a `ChangeDecision`) but not
  the per-component policy. That partitioning matches how the rest of the architecture distributes
  component-specific concerns.
