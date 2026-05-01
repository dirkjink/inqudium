# ADR-032: Bulkhead strategy hot-swap and strategy-config DSL

**Status:** Proposed
**Date:** 2026-04-29
**Deciders:** Core team

## Introduction

A bulkhead's runtime behaviour is shaped by a `BulkheadStrategy` — semaphore, controlled
delay (CoDel), adaptive limit, or adaptive non-blocking. Each strategy has its own state
machinery and its own configuration shape. This ADR specifies how strategy choice is
expressed in the bulkhead's snapshot and DSL, and how a running bulkhead transitions from
one strategy to another at runtime.

The ADR settles three things:

1. How strategy configuration lives on `BulkheadSnapshot` — a sealed-type sub-record carried
   alongside the bulkhead's other configuration fields, analogous to the event-config
   sub-record pattern.
2. The DSL surface that selects a strategy — a sub-builder per strategy, with semaphore
   serving as the default when no choice is made.
3. What strategy hot-swap means — an **atomic** transition gated by zero in-flight calls.
   The bulkhead's component-internal mutability check rejects any swap attempted while
   permits are held. There is no drainage, no phased overlap, no transition window where two
   strategies coexist.

The atomicity choice is deliberate. A phased swap that lets in-flight calls finish on the old
strategy while new acquires bind to the new one is more powerful in operations, but introduces
race conditions, transition-window bookkeeping, and re-entrancy concerns under repeated
swaps. The atomic form is deterministic, slots cleanly into the existing veto-chain pattern,
and is implementable by extending mechanisms already in place. Phased swap remains a future
extension; the design here does not foreclose it.

## Context

The bulkhead's runtime architecture splits into a cold phase and a hot phase. The cold phase
holds no per-call state and answers all queries from the snapshot; the hot phase materializes
on first execution and owns a `BulkheadStrategy` instance plus the live-container subscription
that feeds snapshot changes back into that strategy.

Without strategy configuration on the snapshot, the hot phase has no way to know which
strategy to instantiate. A naive solution — hard-code a single strategy in the hot-phase
constructor — does not scale: the four strategy implementations (`SemaphoreBulkheadStrategy`,
`CoDelBulkheadStrategy`, `AdaptiveBulkheadStrategy`, `AdaptiveNonBlockingBulkheadStrategy`)
each have different state machinery and different configuration parameters. CoDel needs
`targetDelay` and `interval`. Adaptive variants need an algorithm choice (AIMD or Vegas) with
its own parameters. The semaphore needs only `maxConcurrentCalls`.

A snapshot-driven configuration architecture must therefore carry a strategy-typed field that
discriminates between the available shapes. The hot phase reads this field at materialization
time to instantiate the correct strategy.

The architecture's component-internal mutability check is the natural gate for the more
delicate question — what happens when a running bulkhead's strategy is changed via a snapshot
update. A change to `maxConcurrentCalls` flows through the live-container subscription and
gets re-tuned in place. A change to the *strategy itself* cannot work that way: the new
strategy is a different object with its own state. The mutability check inspects the patch's
touched fields and rejects strategy changes while the bulkhead is non-quiescent.

### What "atomic" means concretely

A strategy hot-swap transitions the bulkhead from running on strategy `A` to running on
strategy `B`. **Atomic** means:

- Either every call after the swap commit runs on `B` and no call straddles the boundary, or
  the swap is rejected and every call continues to run on `A`.
- The transition does not produce a window where some calls run on `A` and others on `B`. The
  cutover is a single CAS, not a phased migration.
- The transition is gated by a precondition that the dispatcher can check before applying:
  zero in-flight calls. If the bulkhead has any active permits at the moment the patch reaches
  the component-internal check, the swap is vetoed with a clear reason.

This is symmetric to how the cold-to-hot transition already works: the lifecycle base class
performs a single CAS that either succeeds or loses to a concurrent CAS, and there is no state
in which "some calls see cold and others see hot" — observers see one or the other,
deterministically.

### What atomicity costs

The cost is operational. A continuously-busy bulkhead — one that always has at least one
permit held — never sees a moment of quiescence and therefore can never be swapped. Operators
who want to migrate a hot bulkhead from semaphore to CoDel under sustained load must either
drain it manually (route traffic away until concurrent calls reach zero) or accept the swap
attempt as a no-op-with-veto outcome until traffic naturally subsides.

This is acknowledged. Phased swap is the answer if and when the operational pain becomes
material, and is documented below as a non-goal of this ADR.

## Decision

### Strategy configuration on `BulkheadSnapshot`

Strategy configuration becomes a sealed type carried as a single new field on
`BulkheadSnapshot`:

```java
public sealed interface BulkheadStrategyConfig
        permits SemaphoreStrategyConfig, CoDelStrategyConfig, AdaptiveStrategyConfig,
                AdaptiveNonBlockingStrategyConfig {
}

public record SemaphoreStrategyConfig() implements BulkheadStrategyConfig { }

public record CoDelStrategyConfig(
    Duration targetDelay,
    Duration interval
) implements BulkheadStrategyConfig {
    public CoDelStrategyConfig {
        Objects.requireNonNull(targetDelay, "targetDelay");
        Objects.requireNonNull(interval, "interval");
        if (targetDelay.isNegative() || targetDelay.isZero()) {
            throw new IllegalArgumentException("targetDelay must be positive");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }
}

public record AdaptiveStrategyConfig(
    LimitAlgorithm algorithm
) implements BulkheadStrategyConfig {
    public AdaptiveStrategyConfig {
        Objects.requireNonNull(algorithm, "algorithm");
    }
}

public sealed interface LimitAlgorithm permits AimdLimitAlgorithmConfig, VegasLimitAlgorithmConfig {
}

public record AimdLimitAlgorithmConfig(/* ... */) implements LimitAlgorithm { }
public record VegasLimitAlgorithmConfig(/* ... */) implements LimitAlgorithm { }
```

`BulkheadSnapshot` gains one field:

```java
public record BulkheadSnapshot(
    String name,
    int maxConcurrentCalls,
    Duration maxWaitDuration,
    Set<String> tags,
    String derivedFromPreset,
    BulkheadEventConfig events,
    BulkheadStrategyConfig strategy        // ← new
) { ... }
```

The default value, applied by `ImperativeProvider.defaultSnapshot`, is `new
SemaphoreStrategyConfig()`. A bulkhead constructed without an explicit strategy choice runs
on a semaphore.

`BulkheadField` gains a `STRATEGY` enum value that the patch's `touchedFields()` reports when
the strategy field is touched, parallel to existing fields.

### DSL surface: per-strategy sub-builders

The `BulkheadBuilderBase` gains four mutually-exclusive setters, one per strategy:

```java
b.semaphore()                                  // explicit no-op (the default)
b.codel(c -> c.targetDelay(...).interval(...))
b.adaptive(c -> c.aimd(a -> a.minLimit(...).maxLimit(...)))
b.adaptiveNonBlocking(c -> c.vegas(v -> v.alpha(...).beta(...)))
```

Each setter is a sub-builder over its strategy's configuration. Calling more than one is a
last-writer-wins override (consistent with every other DSL setter). Calling none leaves the
default `SemaphoreStrategyConfig` in place.

The sub-builders mirror the snapshot record shape: a `CoDelConfigBuilder` builds a
`CoDelStrategyConfig`, an `AdaptiveConfigBuilder` builds an `AdaptiveStrategyConfig` and
internally hosts an `AimdAlgorithmBuilder` / `VegasAlgorithmBuilder` for the nested
algorithm choice.

A bulkhead builder using the new DSL:

```java
.bulkhead("inventory", b -> b
    .balanced()                              // preset for limits / wait
    .codel(c -> c
        .targetDelay(Duration.ofMillis(50))
        .interval(Duration.ofMillis(500)))
    .events(BulkheadEventConfig.allEnabled()))
```

### Strategy materialization in the hot phase

`BulkheadHotPhase` no longer hard-codes the strategy choice. The constructor delegates to a
`BulkheadStrategyFactory` that pattern-matches on the snapshot's `BulkheadStrategyConfig`:

```java
final class BulkheadStrategyFactory {
    static BulkheadStrategy create(BulkheadSnapshot snapshot, /* ... */) {
        return switch (snapshot.strategy()) {
            case SemaphoreStrategyConfig s -> new SemaphoreBulkheadStrategy(
                    snapshot.maxConcurrentCalls());
            case CoDelStrategyConfig c -> new CoDelBulkheadStrategy(
                    snapshot.maxConcurrentCalls(), c.targetDelay(), c.interval(), /* ... */);
            case AdaptiveStrategyConfig a -> new AdaptiveBulkheadStrategy(
                    snapshot.maxConcurrentCalls(), buildAlgorithm(a.algorithm()), /* ... */);
            case AdaptiveNonBlockingStrategyConfig a -> new AdaptiveNonBlockingBulkheadStrategy(
                    snapshot.maxConcurrentCalls(), buildAlgorithm(a.algorithm()), /* ... */);
        };
    }
}
```

The pattern match is exhaustive by construction (sealed type), so adding a new strategy
later requires updating the factory and triggers a compile error if forgotten — the right kind
of fail-fast for a small extension surface.

The hot phase's existing `onSnapshotChange(...)` handler keeps responsibility only for fields
the active strategy can adapt in place — `maxConcurrentCalls` for every strategy, plus
strategy-specific fields a particular strategy may want to re-tune (e.g. CoDel's interval, if
exposed as live-tunable). A change to `STRATEGY` does **not** flow through
`onSnapshotChange` — it goes through hot-swap.

The hot phase remembers the `BulkheadStrategyConfig` it last materialized from in a
`lastMaterializedConfig` field. When a snapshot change is dispatched, change detection is a
single record-equality check against this field. This covers both flavours uniformly: a
different strategy type triggers a fresh materialization through the factory; the same
strategy type with different field values (a CoDel `targetDelay` tweak, an Adaptive algorithm
switch from AIMD to Vegas) goes through the same path. The veto in `evaluate(...)` —
`concurrentCalls() > 0` rejects any `STRATEGY`-touching patch — gates both equally.

### The atomic hot-swap mechanism

When a patch touches `BulkheadField.STRATEGY`, the dispatcher routes through the existing
veto chain. The component-internal mutability check inspects two things: the runtime state
(for the strategy-swap precondition) and the post-patch snapshot (for any field-value
constraints that depend on the strategy the runtime will land on). Per ADR-028, this split
between *transition-operation checks* (runtime state) and *field-value checks* (post-patch
state) is how the bulkhead reasons about which patches are safe to apply.

```java
@Override
public ChangeDecision evaluate(ChangeRequest<BulkheadSnapshot> request) {
    Set<? extends ComponentField> touched = request.touchedFields();
    BulkheadSnapshot postPatch = request.postPatchSnapshot();

    // Transition-operation check: strategy swap requires the runtime to be quiescent.
    if (touched.contains(BulkheadField.STRATEGY)) {
        int inFlight = strategy.concurrentCalls();
        if (inFlight > 0) {
            return ChangeDecision.veto(
                    "strategy swap requires zero in-flight calls; current = " + inFlight);
        }
    }

    // Field-value check: maxConcurrentCalls is live-tunable only when the post-patch
    // strategy is the semaphore. A combined STRATEGY=Semaphore + MAX_CONCURRENT_CALLS=N
    // patch passes here even on a hot CoDel bulkhead, because the post-patch view is
    // what matters for live-tunability.
    if (touched.contains(BulkheadField.MAX_CONCURRENT_CALLS)
            && !(postPatch.strategy() instanceof SemaphoreStrategyConfig)) {
        return ChangeDecision.veto(
                "maxConcurrentCalls is not live-tunable on "
                        + postPatch.strategy().getClass().getSimpleName());
    }

    return ChangeDecision.accept();
}
```

The bulkhead's live-tunability matrix is small: every field is live-tunable when the
post-patch strategy is the semaphore; only `maxConcurrentCalls` has the strategy-dependent
constraint. The other fields (`MAX_WAIT_DURATION`, `TAGS`, `EVENTS`, `DERIVED_FROM_PRESET`)
are read fresh from the snapshot on every operation and need no strategy mutation, so they
fall through to accept regardless of the post-patch strategy.

If the check accepts, the dispatcher proceeds to `live.apply(patch)`. The CAS on the
snapshot succeeds, the snapshot now reports the new `BulkheadStrategyConfig`, and the
live-container subscription handler in `BulkheadHotPhase` receives the update.

This is where atomicity is delivered. The handler detects that the snapshot's strategy
config differs from the running strategy and performs the swap synchronously:

```java
private void onSnapshotChange(BulkheadSnapshot dispatchedSnapshot) {
    BulkheadSnapshot latest = component.snapshot();

    if (strategyChanged(latest)) {
        BulkheadStrategy old = this.strategy;
        this.strategy = BulkheadStrategyFactory.create(latest, component.general());
        closeStrategy(old);     // best-effort, never propagates exceptions
        return;
    }

    // No strategy swap, just an in-place re-tune.
    if (strategy instanceof SemaphoreBulkheadStrategy semaphore) {
        semaphore.adjustMaxConcurrent(latest.maxConcurrentCalls());
    }
}
```

`strategyChanged(...)` performs the record-equality check described above against the
cached `lastMaterializedConfig`. The `instanceof` guard in the in-place branch is
defence-in-depth: the mutability check has already vetoed any `MAX_CONCURRENT_CALLS` patch
on a non-semaphore strategy before reaching this branch, but the guard ensures a coherent
state if the dispatcher were ever bypassed.

The `strategy` field is `volatile`. Every call site that reads it (the execute path's
`tryAcquire`, the accessors `availablePermits()` and `concurrentCalls()`, the mutability
check) reads the volatile reference once per operation. The swap is a single volatile
write — that write is the atomicity boundary.

### Why the field-write is sufficient for atomicity

The veto check guarantees `inFlight == 0` at the time of the dispatcher's evaluation. Between
the check and the field write, two questions arise:

**Q1: Can a new acquire start between the check and the swap?**

In principle yes — the dispatcher runs on the update-caller's thread; an unrelated thread
could call `bulkhead.execute(...)` and reach `strategy.tryAcquire(...)` between the veto check
and the volatile write. If that happens, the new call binds to the *old* strategy. It then
runs to completion on the old strategy and releases its permit there. The new strategy never
sees that call.

This is technically a violation of strict atomicity ("every call after the swap commit runs
on B"), but it is benign:

- The call uses old-strategy semantics from beginning to end. It does not straddle.
- Permit accounting on the old strategy is consistent: acquire and release both go to the old
  reference held by the executing thread.
- The new strategy starts with a clean state: zero in-flight, full permits, no leakage from
  the old strategy.

The window is small (a single CAS plus a field write) and the affected calls are rare under
the conditions where the swap happened (the operator chose to swap *because* the bulkhead was
quiet). The cost of closing the window — a stop-the-world synchronization that prevents new
acquires for the duration of the swap — is not justified by the marginal benefit.

**Q2: Can a release happen on the old strategy after the swap?**

Yes, and that is fine. A release call routed to the old strategy's `release()` is a legitimate
finishing call for an acquire that bound to that strategy. Permit accounting on the old
strategy stays consistent. The old strategy is not "closed" in any sense that would prevent
this — `closeStrategy(old)` is best-effort cleanup of subscription-style resources, not a hard
shutdown.

The mental model is: each call carries its own strategy reference for the duration of its
execute. When the volatile field is replaced, all *new* `strategy.tryAcquire` calls go to the
new reference; calls already past `tryAcquire` complete on whatever reference they captured.

### Listener visibility of strategy patches

Listeners registered on the bulkhead handle see strategy patches like any other patch — the
veto chain runs them in registration order, and any of them can reject. The veto chain is
conjunctive (any single veto kills the patch); a strategy swap that survives every listener
reaches the component-internal check and is then gated on the in-flight precondition.

A listener that vetoes specifically on strategy changes uses the same mechanism every other
veto-policy listener uses:

```java
bulkhead.onChangeRequest(req -> req.touchedFields().contains(BulkheadField.STRATEGY)
    ? ChangeDecision.veto("strategy locked by policy")
    : ChangeDecision.accept());
```

### What this ADR does not decide

- **Phased / drainage-based hot-swap** is not introduced. The atomic precondition can refuse
  swaps under load; if and when that becomes an operational pain, a future ADR introduces a
  drainage mechanism (set the bulkhead to drain mode, refuse new acquires, wait for in-flight
  to reach zero, then swap). The veto check designed here naturally extends — drainage simply
  becomes a different precondition signal.
- **Per-paradigm strategy availability.** The four strategies named here live in the
  imperative module. Other paradigms (reactive, RxJava, coroutines) provide their own
  strategy implementations and may or may not honour the same `BulkheadStrategyConfig`
  types. The sealed type is stable across paradigms; the concrete strategies are
  paradigm-private.
- **Strategy SPI for application-supplied strategies.** Application code cannot plug in
  custom `BulkheadStrategy` implementations through the configuration. The four built-in
  strategies cover the design space the framework targets. A future ADR can introduce a
  custom-strategy SPI if a real need surfaces.

## Consequences

### Positive

- Three previously unreachable strategy implementations become first-class user choices
  through a discoverable DSL surface.
- The sealed-type approach for `BulkheadStrategyConfig` is exhaustive at compile time:
  adding a strategy later requires updating exactly one switch (the factory) and the compiler
  catches the omission.
- Atomic semantics are simple to reason about and to test. The veto chain is the visible gate;
  the swap itself is a single volatile write. There is no transition state to debug.
- The live-container subscription mechanism extends without redesign: the same handler that
  re-tunes `maxConcurrentCalls` carries the strategy-swap branch.
- The component-internal mutability check — a generic veto hook in the architecture — gets a
  first concrete "veto on something genuine" use. The infrastructure pays back here.

### Negative

- A continuously-busy bulkhead cannot be hot-swapped under load. Operators must drain manually
  or accept that the swap is a no-op-with-veto until traffic subsides. This is a conscious
  trade-off, documented as a non-goal.
- The strategy field on `BulkheadHotPhase` becomes `volatile`, adding a per-execute volatile
  read. The cost is negligible (`volatile` reads are essentially free on modern CPUs for
  cached lines) but worth noting.
- The window between veto check and field write allows a new acquire to bind to the old
  strategy. The window is small and the consequence benign (one more call on the old
  strategy, fully accounted for there), but not strictly zero.
- The DSL grows by four sub-builders. The user-facing surface is larger; documentation must
  introduce strategies as a first-class concept rather than something operators discover only
  when they need it.

### Neutral

- `BulkheadField` gains one enum value (`STRATEGY`). Patches that don't touch the strategy
  see no behavioural difference.
- The factory dispatch pattern (sealed-type `switch`) follows precedent already established
  by `BulkheadEventConfig` and other configuration sub-records.
- Listener registrations on the bulkhead handle work unchanged. Strategy patches surface
  through `ChangeRequest.touchedFields()` exactly like every other field touch.

## Implementation notes

The implementation breaks naturally into four sub-steps. Sequencing matters: each builds on
the previous and lands a coherent green build.

### A: Snapshot field, sealed-type configs, default value

- Add `BulkheadStrategyConfig` sealed interface with the four permits.
- Add the four config records with their compact-constructor invariants.
- Add `BulkheadSnapshot.strategy` field with `SemaphoreStrategyConfig` as the default in
  `ImperativeProvider.defaultSnapshot`.
- Add `BulkheadField.STRATEGY` enum value.
- `BulkheadPatch.touchStrategy(BulkheadStrategyConfig)` method on the patch.
- Tests: snapshot construction with each strategy config, patch touching strategy field, patch
  reports `STRATEGY` in `touchedFields()`.
- The hot phase still hard-codes semaphore at this point — sub-step A only carries the
  configuration shape, not the materialization change.

### B: Strategy factory, hot-phase materialization

- Add `BulkheadStrategyFactory` with the exhaustive sealed-type switch.
- `BulkheadHotPhase` constructor delegates to the factory.
- Tests: each strategy materializes correctly when its config is set on the snapshot. Cold
  bulkhead with each strategy reads correct `maxConcurrentCalls` from the snapshot;
  warming up activates the configured strategy.
- The hot-swap path is not yet wired — patches touching `STRATEGY` after warm-up still go
  through the existing path (which would hit `onSnapshotChange` and silently misbehave).
  Sub-step C closes that.

### C: DSL sub-builders

- `b.semaphore()`, `b.codel(c -> ...)`, `b.adaptive(a -> ...)`,
  `b.adaptiveNonBlocking(a -> ...)` setters on `BulkheadBuilderBase`.
- Sub-builders for each strategy config plus the nested algorithm choices for adaptive.
- Tests: every DSL combination produces the expected snapshot. Last-writer-wins semantics
  pinned. The DSL works in both build (initial materialization) and update (subsequent
  patch) paths.

### D: Hot-swap path with veto

- `BulkheadHotPhase.evaluate` grows the `STRATEGY`-touch branch returning veto when
  `concurrentCalls() > 0`.
- The strategy field becomes `volatile`.
- `onSnapshotChange` detects strategy changes and performs `swapStrategy(...)`.
- Tests:
  - Hot-swap with zero in-flight: succeeds, new strategy installed, subsequent calls go
    through new strategy.
  - Hot-swap with in-flight calls: vetoed, old strategy stays, BuildReport reports
    `Source.COMPONENT_INTERNAL` finding with reason mentioning concurrent count.
  - Listener veto on strategy patch: short-circuits before the component-internal check
    (regression sanity for the conjunctive-veto contract).
  - In-flight call on old strategy completes correctly after swap: acquire pre-swap, swap
    happens during business logic, release post-swap goes to the old strategy reference,
    new strategy unaffected.
  - Repeated swap: A → B → C in sequence each works under quiescent conditions.
  - Pattern-match exhaustiveness: deleting a permit from the sealed type breaks compilation
    of the factory (manual verification rather than test).

The split lets us pause for review after each sub-step. A and C produce green builds with no
behavioural change for users; B introduces strategy materialization without exposing it
through the DSL; D lights everything up.
