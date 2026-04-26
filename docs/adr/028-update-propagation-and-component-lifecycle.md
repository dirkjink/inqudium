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

Listeners are registered per component handle and are scoped to that handle's lifetime:

```java
public interface ComponentHandle<P extends ParadigmTag, S extends ComponentSnapshot> {
    // ... existing accessors (snapshot, name, paradigm, etc.)

    /**
     * Register a listener that will be consulted before any hot-state update.
     * Returns an AutoCloseable for unregistration.
     */
    AutoCloseable onChangeRequest(ChangeRequestListener<S> listener);
}

@FunctionalInterface
public interface ChangeRequestListener<S extends ComponentSnapshot> {
    ChangeDecision decide(ChangeRequest<S> request);
}

public interface ChangeRequest<S extends ComponentSnapshot> {
    S currentSnapshot();
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

Three properties of this API deserve explicit mention:

**Vetoes must carry a reason.** The `Veto` record has a non-null, non-blank `reason` field, enforced in the
compact constructor. This is a deliberate design choice: silent vetoes are debugging nightmares. The reason
is propagated into the `BuildReport` and into the lifecycle event for the rejected patch, so an operator
investigating "why did my update not apply?" can find the answer immediately.

**Listeners see typed proposed values.** The `ChangeRequest<S>` interface is parameterized by snapshot type,
and `proposedValue(field, Class<T>)` does the typed unwrap. This avoids the `Object` casts that plague raw
JavaBeans `PropertyChangeEvent`. The price is that listeners must know the snapshot type at registration —
which they do, because they registered through a typed component handle.

**Listener registration is scoped to handle lifetime, not application lifetime.** When a component is
removed, its handle becomes inert; pending listeners are silently discarded. There is no leak. Listeners
that need cross-removal continuity must re-register on the new component if it is recreated.

### Component-internal veto

After all external listeners have accepted, the component itself is consulted via an internal hook. This is
implementation-private to each component (not part of the user-visible API), but its presence is normative:

```java
// In each component's implementation, e.g., DefaultImperativeBulkhead
ChangeDecision evaluateInternalMutability(ChangeRequest<BulkheadSnapshot> request) {
    // Component-specific logic. Examples:
    // - Strategy hot-swap requires zero in-flight calls? Veto if there are any.
    // - Adaptive algorithm change while in flight? Veto.
    // - Pure numeric limit change? Accept.
    return ChangeDecision.accept();
}
```

This hook cannot be replaced or bypassed by application code. It is the component's last line of defence
against patches that would corrupt its internal state. The hook's logic is documented in the component's own
ADR (ADR-020 for bulkhead, etc.) — this ADR specifies only that the hook exists and runs after external
listeners, not before.

### Update execution sequence

The full sequence for a single component patch reaching a hot component:

```
1. patch enters runtime.update(...)
2. Class 1 (argument-range) — already enforced at DSL setter time, never reaches here
3. Class 2 (snapshot invariants) — performed when constructing the would-be-resulting snapshot
4. Class 3 (consistency rules) — performed against the would-be-resulting snapshot
5. If 3 or 4 fail: BuildReport entry REJECTED (with validation findings); patch discarded.
6. Lifecycle check: COLD or HOT?
7. If COLD: apply patch atomically via LiveContainer.apply(patch); BuildReport entry PATCHED.
8. If HOT:
    a. For each registered listener (in registration order):
        - call listener.decide(request)
        - if Veto: BuildReport entry VETOED with reason; abort.
    b. Component-internal mutability check:
        - if Veto: BuildReport entry VETOED with reason; abort.
    c. apply patch atomically via LiveContainer.apply(patch); BuildReport entry PATCHED.
9. Subscribers (the snapshot-change subscribers from ADR-025, distinct from listeners) are notified.
10. RuntimeComponentPatchedEvent is published.
```

Steps 7 and 8c both perform `LiveContainer.apply(patch)`, which is a CAS retry loop against the
`AtomicReference<Snapshot>` (ADR-025). The CAS handles concurrent updates from multiple `runtime.update(...)`
calls running in parallel — a patch that loses a CAS race re-runs the validation and veto chain against the
new current snapshot. This is correct: if the snapshot changed during processing, the listeners should see
the new state, not the stale one they originally evaluated against.

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

The `BuildReport` gains a parallel collection alongside the existing `findings()`:

```java
public interface BuildReport {
    // ... existing accessors
    List<VetoFinding> vetoFindings();
}

public record VetoFinding(
    String componentName,
    ParadigmTag paradigm,
    Set<? extends ComponentField> touchedFields,
    String reason,                           // from the listener's ChangeDecision.Veto
    Source source                            // LISTENER or COMPONENT_INTERNAL
) {
    public enum Source { LISTENER, COMPONENT_INTERNAL }
}
```

### Lifecycle events

Two new framework-level events are emitted by `InqRuntime` (alongside the `RuntimeComponentAdded/Removed/Patched`
events from ADR-026):

- `ComponentBecameHotEvent` — emitted when a component transitions from `COLD` to `HOT`. Fired exactly once
  per component lifetime. Useful for operational tooling that wants to know when the system has fully warmed
  up.
- `RuntimeComponentVetoedEvent` — emitted when a patch is vetoed (listener or internal). Contains the
  `VetoFinding` payload. Operational tooling subscribed to this event can build dashboards of "rejected
  config changes" without needing to inspect every `BuildReport`.

These events follow the same publishing semantics as the existing runtime-level events (ADR-026): they live
on the `InqRuntime` event publisher, separate from per-component publishers.

### Implementation locations

| Layer                                | Module               | Code                                                            |
|--------------------------------------|----------------------|-----------------------------------------------------------------|
| `LifecycleState`, `ChangeDecision`,  | `inqudium-config`    | Lifecycle and veto types, `ChangeRequestListener` SPI,          |
| `ChangeRequest`, `VetoFinding`,      |                      | `BuildReport` extensions, runtime event types.                  |
| `ComponentField` base interfaces     |                      |                                                                 |
| `LifecycleState` machinery in handles | paradigm modules     | Each paradigm provides a base class (see ADR-029) that          |
|                                      |                      | concrete components extend. The transition trigger is           |
|                                      |                      | the uniform "first execute" rule, implemented once per          |
|                                      |                      | paradigm in the base class.                                     |
| Component-internal mutability hooks  | paradigm modules     | Implementation-private per component; consulted by the          |
|                                      |                      | dispatcher in `inqudium-config`.                                |

The dispatcher itself — the code that performs the routing in step 6 above and runs the chain in step 8 — is
in `inqudium-config`. It receives the lifecycle state through a small read-only interface that each handle
implements (`LifecycleAware { LifecycleState lifecycleState(); }`), and it calls into the component's
internal mutability hook through another small interface (`InternalMutabilityCheck<S> { ChangeDecision
evaluate(ChangeRequest<S>); }`). Both interfaces are in `inqudium-config`; the implementations are in the
paradigm modules.

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
- Lifecycle events (`ComponentBecameHotEvent`, `RuntimeComponentVetoedEvent`) make the lifecycle and veto
  activity observable to operational tooling without requiring polling or inspection of every `BuildReport`.
- The CAS-based apply mechanism (ADR-025) interacts correctly with concurrent updates: a lost CAS triggers
  re-validation and re-veto against the current state, which is the right behaviour.

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
