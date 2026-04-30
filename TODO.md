# TODO.md

Known gaps in the current implementation that need follow-up work. These are *not* future
ideas — they are concrete issues identified in completed work that we have decided to revisit.
Each entry describes the problem, the conditions under which it surfaces, the current
mitigation (if any), and the rough shape of a fix.

When an entry is resolved, it moves out of this file — either deleted, or noted in the commit
that closed it.

The bar for adding an entry: a real gap in shipped behaviour, not a wishlist item.
Wishlist items go in `IDEAS.md`.

---

## Strategy config tweaks without strategy-type change

**Where:** `BulkheadHotPhase.strategyChanged(...)` and `BulkheadHotPhase.onSnapshotChange(...)`
in the imperative paradigm.

**The gap:** A patch that touches `STRATEGY` but lands on the same strategy type with
different field values currently produces a silent no-op. The running strategy keeps its
old configuration; the snapshot reflects the new one; nothing in between updates.

**Concrete example:** A bulkhead is running `CoDelBulkheadStrategy` with `targetDelay=50ms`
and `interval=500ms`. A subsequent update patches the strategy to a new
`CoDelStrategyConfig(targetDelay=80ms, interval=800ms)`. The patch goes through the veto
chain (no veto since `STRATEGY` is touched but `concurrentCalls()==0` at evaluation time).
The CAS commits the new snapshot. The snapshot now reports the new CoDel parameters.
But `strategyChanged(latest)` returns `false` because it checks type identity:

```java
case CoDelStrategyConfig codel ->
        !(current instanceof CoDelBulkheadStrategy);
```

Both *are* CoDel — type identity matches — so the swap path is not entered. The fall-through
branch only handles `MAX_CONCURRENT_CALLS` re-tune for `SemaphoreBulkheadStrategy` and is a
no-op for CoDel. Result: the bulkhead silently keeps running on the old `targetDelay=50ms`
and `interval=500ms`, while every observer that reads `bulkhead.snapshot().strategy()` sees
the new values.

The same applies to `AdaptiveStrategyConfig` and `AdaptiveNonBlockingStrategyConfig` — a
change of algorithm parameters (AIMD rates, Vegas thresholds) within the same strategy type
is not reflected in the running strategy.

A particularly dramatic case sits inside the adaptive variants: a complete *algorithm*
switch from AIMD to Vegas (or vice versa) without a strategy-type change. A patch that
replaces `AdaptiveStrategyConfig(AimdLimitAlgorithmConfig)` with
`AdaptiveStrategyConfig(VegasLimitAlgorithmConfig)` falls through both `instanceof` checks
the same way (both are `AdaptiveBulkheadStrategy`), so the swap path is not entered. The
snapshot reflects the new algorithm choice, the running strategy keeps its old algorithm.

This is *not* a parameter tweak as the CoDel `targetDelay` case is — it is a complete
switch between mathematically different limit-computation algorithms. The operator-expected
effect ("AIMD is too conservative, switch to Vegas") does not occur, with no indication.
Same code path as the parameter-tweak case, same fix shape — but the dramaticness of this
sub-case makes it worth flagging explicitly so a reviewer addressing the gap sees both
flavours.

**Why it does not always surface in practice:** The DSL form in
`BulkheadBuilderBase.codel(c -> c.targetDelay(...).interval(...))` always produces a
*complete* `CoDelStrategyConfig` and writes it via `touchStrategy(...)`. So the user
typically chooses one of two patterns:

- *Same strategy type, different config* — the gap. This requires the user to write a `codel`
  call with parameters different from the current ones, on a bulkhead already running CoDel.
- *Different strategy type* — works correctly. The `strategyChanged(...)` check fires, the
  swap path runs, the new strategy materializes.

The gap surfaces when an operator iterates on strategy parameters: *"CoDel with 50ms target,
let's try 80ms instead"*. With current code, that update appears to commit (no error, the
snapshot updates) but has no observable effect on traffic.

**Why it is subtle:**

1. There is no error message, no rejection, no warning. The patch reports `PATCHED`, the
   snapshot accurately reports the new config, dashboards subscribed to
   `RuntimeComponentPatchedEvent` see the event, listeners in the veto chain are consulted as
   normal. Everything looks like a successful update.
2. The disconnect between snapshot state and runtime state is invisible until someone
   measures actual bulkhead behaviour and notices it does not match the configured values.
3. The mutability check passes the patch correctly per the contract specified in ADR-028
   and ADR-032 — strategy hot-swap requires zero in-flight calls, which is met. The check
   does not (and currently cannot) distinguish between "swap to a different type" and
   "rebuild on the same type with different fields".

**Current mitigation:** None at the code level. Operators using strategy DSL must
recreate the bulkhead (full removal + re-add) when changing within the same strategy type.

**Shape of a fix:**

`strategyChanged(...)` extended to detect config-value differences within a same-type
strategy:

```java
private boolean strategyChanged(BulkheadSnapshot latest) {
    BulkheadStrategy current = this.strategy;
    BulkheadStrategyConfig latestConfig = latest.strategy();
    return switch (latestConfig) {
        case SemaphoreStrategyConfig ignored ->
                !(current instanceof SemaphoreBulkheadStrategy);
        case CoDelStrategyConfig codel ->
                !(current instanceof CoDelBulkheadStrategy currentCoDel)
                        || !currentCoDelConfigMatches(currentCoDel, codel);
        // ... and so on for the adaptive variants
    };
}
```

The "config matches" check needs the running strategy to expose its current config, or the
hot phase to remember the config it last materialized from. Storing the last-materialized
`BulkheadStrategyConfig` as a private field on `BulkheadHotPhase` is the simpler form: each
swap (or rebuild) updates the field; the field is read in `strategyChanged(...)` for
record-equality comparison against the snapshot's current strategy config.

`swapStrategy(...)` (the inline branch in `onSnapshotChange`) needs no change — it already
calls `BulkheadStrategyFactory.create(latest, component.general())` which produces a fresh
strategy from the latest snapshot. Whether the fresh strategy is a different type or the
same type with different fields is invisible to the factory.

**Veto chain interaction:** The mutability check would need to apply to same-type rebuilds
too — a CoDel-to-CoDel rebuild with new field values is just as state-disrupting as a
CoDel-to-Semaphore swap. The `evaluate(...)` precondition (`concurrentCalls() > 0` →
veto) is the right gate for both. The current `evaluate(...)` already vetoes any
`STRATEGY`-touching patch when permits are held, regardless of whether the touch results
in a type change or a same-type rebuild — so the veto chain is *already correct*. Only
the apply-side path in `onSnapshotChange` needs to learn the same-type rebuild case.

**Tests that would pin the fix:**

- A bulkhead running CoDel(50ms, 500ms) with zero in-flight calls receives a patch
  setting CoDel(80ms, 800ms). After commit, the running strategy reports the new field
  values (verifiable through bulkhead behaviour or, if exposed, through a config accessor
  on the strategy).
- The same patch with one in-flight call is vetoed with `Source.COMPONENT_INTERNAL` and
  a reason mentioning concurrent count — pinning that the existing veto behaviour applies
  identically.
- A bulkhead running CoDel(50ms, 500ms) receives a patch with CoDel(50ms, 500ms) — the
  identical config. No swap happens (the structural-equality check on the records returns
  no difference), no event fires beyond `UNCHANGED`, the strategy reference is unchanged.
- An adaptive bulkhead receiving a patch that changes only the algorithm sub-config (AIMD
  rates) without changing the strategy type rebuilds the strategy and therefore the
  algorithm.

**Scope:** Imperative paradigm only for now. Future paradigms (reactive, RxJava,
coroutines) will face the same question and should adopt the same pattern.

**Why we deferred it:** The gap was identified at the end of step 2.10.D's review, after
the implementation was complete and tested for the type-change case. Closing it would
have meant extending `BulkheadHotPhase` with a remembered-config field, adding the
config-equality check, and writing the new tests — substantial enough to warrant a
dedicated review rather than tacking it onto 2.10.D.

---

## Listener that throws (instead of returning a clean veto) — behaviour unspecified

**Where:** `ImperativeLifecyclePhasedComponent.evaluate(...)` and the dispatcher's
listener iteration in the veto chain.

**The gap:** A listener registered via `bh.onChangeRequest(...)` is contractually expected
to return a `ChangeDecision` — either `accept()` or `veto(reason)`. What happens if a
listener instead throws a `RuntimeException` is not specified, not tested, and not obvious
from the code.

**Concrete example:** A listener has a bug, a misconfigured external service it consults,
or simply throws an `IllegalStateException` from a defensive check. The dispatcher iterating
the listeners encounters the throw mid-chain. From code reading, the exception likely
propagates up through `dispatchUpdate` to `runtime.update(...)`, surfacing to the caller
as an update-time failure rather than as a veto with a synthetic reason. But that path is
not test-covered, and the semantics are not pinned in any ADR.

**Why it matters:** Operator trust in the veto chain depends on its predictability. A
listener bug that takes down `runtime.update(...)` for unrelated components is a worse
failure mode than the same bug being absorbed as a veto with a "listener X threw" reason.
The current behaviour is plausibly the former; the desired behaviour is undecided.

**Three options for the fix:**

- **(a) Throw propagates, update fails.** Current de-facto behaviour. Listener bugs are
  loud and visible. But the update fails for components unrelated to the listener's target,
  which violates the cross-component-atomicity contract from ADR-026.
- **(b) Throw is treated as veto with synthetic reason.** "listener X threw IllegalStateException".
  The patch is rejected for the affected component but the rest of the update proceeds.
  Conjunctive veto chain stays intact. Operator gets a clear `BuildReport.vetoFindings` entry.
- **(c) Throw is logged and the listener is skipped.** Other listeners run, and the patch
  may still commit. Most permissive, but hides bugs from the operator.

Recommendation when this is taken up: variant (b). It preserves the cross-component-atomicity
contract, keeps the bug visible (in the BuildReport, not as a thrown exception), and matches
the "listeners must provide a non-blank reason" discipline by synthesizing one when they fail
to.

**Tests that would pin the fix:**

- A listener that throws — the patch on its component is reported as `VETOED` with
  `Source.LISTENER` and a reason naming the thrown exception type.
- The same update touching multiple components only fails for the listener's component;
  the others commit per cross-component-atomicity.
- The throw is logged via `general().loggerFactory()` at `error` level so the operator can
  trace it to source.

**Scope:** Veto-chain semantics, paradigm-agnostic. Decision belongs in the dispatcher
(`UpdateDispatcher` in `inqudium-config`) and applies uniformly to all current and future
paradigms.

---

## Asynchronous variant of `InqBulkhead` is not implemented

**Where:** `InqBulkhead.java` (line 137 carries an explicit code-level TODO that flags this).

**The gap:** The legacy `ImperativeBulkhead` implements both synchronous (`execute`) and
asynchronous (`executeAsync`) paths. The latter integrates with the `InqAsyncDecorator`
contract — `CompletionStage`-based decorate methods, two-phase around-advice with the
release running on stage completion rather than in a synchronous `finally`. ADR-020
specifies the asynchronous path as part of the bulkhead pattern.

The new `InqBulkhead` covers only the synchronous path. Users who need the async form
must still go through the deprecated `ImperativeBulkhead`, which is targeted for removal
in a future refactor cycle.

**Why it matters:** Pattern completeness requires both call styles. Users with
`CompletionStage`-returning service methods cannot decorate them through the new
architecture. With virtual threads the use case is rarer than it used to be, but it has
not disappeared — and the deprecation of the legacy class will eventually create a gap
that needs filling.

**Why this likely warrants its own ADR:** The async path's interaction with the cold-to-hot
lifecycle is not the same problem the synchronous form solves. The reactive paradigm's
deferred-CAS pattern (ADR-029) is closer in spirit — first invocation must wire up the
stage chain, but the cold-to-hot transition cannot rely on a synchronous "first execute"
trigger. That design decision is non-trivial.

A code-level TODO in `InqBulkhead.java` already flags this:

> An asynchronous variant of `InqBulkhead` — analogous to the old `InqAsyncDecorator`
> contract (CompletionStage-based decorate methods, two-phase around-advice with the
> release running on stage completion rather than in a synchronous finally) — has not yet
> been designed in the new architecture. The phase for this work is undecided; it likely
> warrants its own ADR because the cold/hot transition under deferred subscription is not
> the same problem the synchronous form solves (the reactive paradigm's deferred CAS
> pattern from ADR-029 is closer in spirit).

This TODO entry is the formal sibling: the question lives both in the source and here in
TODO.md so it is not lost when the class itself is read or when the deprecated
`ImperativeBulkhead` is finally removed.

**Scope:** Imperative paradigm. Other paradigms (reactive, RxJava, coroutines) have their
own native async forms and do not face this exact gap.

**Concrete consequences observed in audits 2.18 and 2.19:** The aspect module's hybrid
and async paths require both `InqDecorator` and `InqAsyncDecorator` contracts on every
pipeline element. `InqBulkhead` implements only `InqDecorator`. Three specific
consequences:

- `HybridAspectPipelineTerminal.of(InqPipeline)` validates eagerly in its constructor that
  every pipeline element implements both contracts (audit 2.18 finding F-2.18-1). Any
  pipeline containing an `InqBulkhead` throws `ClassCastException` on construction, even
  when the intercepted method is synchronous and the hybrid async path is never entered
  at runtime. The eager validation is a defensible design choice for a hybrid terminal —
  but it makes hybrid dispatch unusable with bulkhead until the async variant exists or
  the validation is made lazy.

- `AsyncElementLayerProvider`'s constructor type bound is
  `<E extends InqElement & InqAsyncDecorator<Void, Object>>` (audit 2.18 finding
  F-2.18-2). This rejects `InqBulkhead` at compile time. Today only the deprecated
  `Bulkhead` / `ImperativeBulkhead` pair satisfies the bound. Async aspect setups using
  bulkhead are pinned to deprecated types until the async variant lands; users cannot
  migrate off the deprecated pair without losing async aspect support.

- The Spring `InqShieldAspect` casts elements to `InqAsyncDecorator` in its async-method
  dispatch path (audit 2.19 finding F-2.19-6). This is the runtime manifestation of the
  same root cause: a Spring user who registers a real `InqBulkhead` as an `InqElement` bean
  and intercepts an async-returning method (`CompletionStage<T>`, `Mono<T>`, etc.) gets a
  `ClassCastException` at first invocation. The synchronous path remains clean. The cast
  is wrapped in a generic exception handler, but the failure mode is still: deprecated
  bulkhead works, new bulkhead breaks for async-returning methods.

Three different sites in the codebase, three different failure modes (compile-time
rejection, eager constructor failure, runtime cast), one root cause: `InqBulkhead` does
not implement `InqAsyncDecorator`.

All consequences resolve when the async variant of `InqBulkhead` is introduced.
Alternative partial mitigations (loosening hybrid eager validation, dual constructors on
`AsyncElementLayerProvider`, runtime feature-detection in `InqShieldAspect`) exist but
each carries its own design trade-offs and would need their own decision in the
async-variant ADR.

**Why we deferred it:** The async path is a substantial new feature, not a bug fix. It
warrants its own ADR (lifecycle interaction with deferred subscription is non-trivial),
and the legacy class still exists as a bridge. Closing it is appropriate work for after
the bulkhead pattern is otherwise complete and other patterns are being addressed. The
audit-2.18 consequences above sharpen the urgency: as long as deprecated `Bulkhead` /
`ImperativeBulkhead` exists, users have a working migration path; once removal of those
deprecated types is scheduled, the async variant must land first.

---

## Bulkhead user guide does not mark strategy-dependent behaviour outside the Strategies section

**Where:** `docs/user-guide/bulkhead/bulkhead.md` — specifically the Configuration Reference
table, the Presets table, and any other section outside the Strategies chapter that makes
statements which only hold for some strategies.

**The gap:** The user guide added a Strategies chapter in step 2.15. Inside it, strategy
behaviour is documented well. Outside it, several statements are silently strategy-dependent
without saying so. A reader cannot tell from the formatting whether a sentence describes
universal bulkhead behaviour or behaviour that holds only for the semaphore (which the
section was originally written for, before strategies became a first-class concept).

**Concrete examples:**

- The Configuration Reference says `maxConcurrentCalls` is the *"maximum number of concurrent
  calls"*. Strictly true for `semaphore` and `codel`; the adaptive variants ignore the field
  entirely and use the algorithm's `initialLimit` / `maxLimit` instead. The reader of the
  Reference table has no way to know.
- The Presets table describes `protective` as *"conservative limits, fail-fast — critical
  services"*. The "fail-fast" half is semaphore-implicit: a `protective().adaptive(...)`
  bulkhead is not fail-fast, it parks for `maxWaitDuration`. The preset description suggests
  a behaviour that does not hold strategy-independently.
- Sections that *are* universally true (per-call events, the `runtime.update(...)` invocation
  shape, the error-code table) carry no marker either, so the reader cannot distinguish them
  from the silently-strategy-dependent sections.

**Why it matters:** A first-time reader naturally treats statements made before the
Strategies chapter as foundational — "this is what every bulkhead does". When some of those
statements are actually only true for the default strategy, the mental model the reader
builds is subtly wrong. The error surfaces only when they configure an adaptive bulkhead and
the documented behaviour does not match what they observe.

**Why we deferred it:** The current guide is not factually wrong — the Strategies chapter
contains the corrective information. A determined reader does end up with a correct picture.
The library is also pre-alpha; there are no external users to get confused yet. Closing the
gap is a polish step, not a correctness fix.

**Shape of the fix (Option C from review):** Add a "Strategy" column to the Configuration
Reference and Presets tables. Each strategy-dependent field's row gets a `varies — see
Strategies` marker plus a short description of the variation in the Description column.
Strategy-independent rows leave the new column blank. Add a one-paragraph header note before
the Configuration Reference establishing the convention: *"Several configuration fields
behave differently depending on the active strategy. Each such field carries a 'varies — see
Strategies' marker. Sections without that marker apply to all strategies."*

This makes the question "does this apply to all strategies?" answerable from the formatting
alone, without re-reading the Strategies chapter for every statement.

**Alternatives considered and rejected:**

- **Annotations only, no header note (Option A):** scatters strategy hints across the
  document without establishing the convention. Aggravates the "what about the unmarked
  ones?" problem rather than solving it.
- **Re-order the document so the Strategies chapter comes first (Option B):** shifts
  complexity to the front of the guide and breaks the "simple first" reading order, without
  actually solving the strategy-dependence question for tables that come after.

**Scope:** Documentation only. No code change.

**When to address:** When the library moves out of pre-alpha and gains its first external
users, or when other pattern user guides (Circuit Breaker, Retry, Time Limiter) reach a
similar shape and the convention should be established consistently across all of them.
Whichever comes first.

---

## ADR-020 Configuration and Registry sections describe pre-Phase-1 architecture

**Where:** `docs/adr/020-bulkhead.md` — sections "Configuration" and "Registry".

**The gap:** The Configuration section describes the legacy `InqBulkheadConfig` record and
the original builder DSL; the Registry section describes the deprecated standalone-registry
approach. Both pre-date Phase 1 and the snapshot/patch architecture (ADR-025 onwards). The
bulkhead's actual configuration surface today is `BulkheadSnapshot` plus the
`Inqudium.configure()...imperative(...)` DSL; registry semantics moved to
`Imperative.bulkhead(name)` lookup on the runtime.

**Why it matters:** ADR-020 is the canonical reference for the bulkhead pattern. A reader
consulting it for design rationale or implementation guidance gets a description that
matches code which is either deprecated or removed. The "what really runs" is documented
across ADR-025, -026, -028, -029, -032, -033, but ADR-020 still presents the pre-refactor
model as if current.

**Why we deferred it:** ADR rewrites are out of scope for the bulkhead pattern-completion
refactor. They belong to a dedicated ADR-audit pass that reviews all ADRs against the
current code and updates them coherently.

**Shape of the fix:** The Configuration and Registry sections are rewritten against the
post-Phase-2 architecture: snapshots, patches, the Inqudium DSL, the runtime registry.
Cross-references to ADR-025/-026/-028/-029/-032/-033 are added where they add detail. Other
sections of ADR-020 (the strategy contract, exception-optimization performance discussion)
survive intact and are linked to from the rewritten sections.

**When to address:** During the planned ADR-audit refactor that follows the bulkhead
refactor's completion.

---

## `inqudium-core` declares SLF4J as a compile-scope dependency

**Where:** `inqudium-core/pom.xml` — `org.slf4j:slf4j-api` with `<scope>compile</scope>` and
`ch.qos.logback:logback-classic` with `<scope>provided</scope>`. Affected classes:
`eu.inqudium.core.log.Slf4jLoggerFactory` and the `Slf4j*Action` helpers.

**The gap:** `CLAUDE.md` and ADR-005 describe `inqudium-core` as carrying *"JDK-only
dependencies"*. The current pom contradicts that: SLF4J is on the compile path, transitively
pulled into every consumer of `inqudium-core` whether they want logging or not.

**Why it matters:** Documentation and module structure are out of sync. A consumer reading
`CLAUDE.md` builds a mental model that does not match what the build files produce. The
architectural commitment ("a resilience library that does not impose a logging framework")
is silently broken.

**Why we deferred it:** The fix is mechanical (move classes between modules, adjust pom
scopes) but the design decision (which resolution path) is for the ADR-audit refactor to
settle. Three plausible resolutions: (a) `inqudium-core` becomes truly JDK-only and the
SLF4J classes move to `inqudium-context-slf4j` (companion entry below); (b) the docs are
updated to acknowledge SLF4J on `inqudium-core`; (c) middle path — `<optional>true</optional>`
or `<scope>provided</scope>` on the SLF4J dependency.

**Shape of the fix:** Resolution (a) fits the "JDK-only" framing of the documentation and is
the most likely path. Move `Slf4jLoggerFactory` and `Slf4j*Action` to
`inqudium-context-slf4j` (which is currently effectively empty — see companion entry); leave
only the `LoggerFactory` abstraction in `inqudium-core`.

**When to address:** ADR-audit refactor. Bundle with the companion `inqudium-context-slf4j`
entry below — the same decision settles both.

---

## `inqudium-context-slf4j` is effectively empty

**Where:** `inqudium-context-slf4j/` — module exists in the multi-module build but contains
no Java sources, only an SPI marker file.

**The gap:** The module's intended purpose is unclear from the code alone. The companion
finding above suggests it was meant as the home for the SLF4J bridge code that currently
lives in `inqudium-core`.

**Why it matters:** A module that contains nothing is either a stub waiting for content or a
leftover that should be deleted. Either way it confuses readers of the build structure.

**Why we deferred it:** Routed together with the SLF4J-in-core entry — the same decision
settles both. If `inqudium-core` becomes JDK-only, this module gains its first real content
(the SLF4J bridge) and starts earning its keep. If `inqudium-core` keeps SLF4J, this module
is redundant and should be deleted.

**Shape of the fix:** Whichever resolution the SLF4J-in-core entry takes, this module
follows: either receives the bridge code, or is deleted from the multi-module build.

**When to address:** ADR-audit refactor, paired with the SLF4J-in-core entry.

---

## Spring aspect cache is not invalidated when an `InqElement` bean is replaced

**Where:** `inqudium-spring/src/main/java/eu/inqudium/spring/InqShieldAspect.java` —
the per-method element cache.

**The gap:** `InqShieldAspect` looks up `InqElement` instances via the registry once per
intercepted method, then caches the resolved element keyed by the method signature. The
cache has no invalidation hook. If a user re-registers an element with the same name —
either by replacing the registry bean or by mutating the registry directly — the aspect
continues to use the cached reference to the original element.

**Why it matters:** Spring users sometimes wire elements through `@Bean` methods that
expose `BulkheadHandle<ImperativeTag>` or other element types into the application
context. If they replace one of these beans at runtime (rare but legitimate, e.g. via
`@Profile`-driven re-creation, or hot reload during development), the aspect cache holds
the old reference. The intercepted method then bypasses the new element silently —
exactly the kind of stale-cache bug that is hard to spot because the call still succeeds,
just against the wrong element.

The deprecated `Bulkhead` / `ImperativeBulkhead` pair has the same shape and the same
gap; this is not new drift, but the post-ADR-033 picture makes it more visible because
the new architecture's snapshot-based lifecycle was supposed to surface state changes
through the volatile-and-AtomicReference patterns that the aspect cache deliberately
breaks.

**Why we deferred it:** Element-replacement-via-registry is not a documented or supported
operation today. The Inqudium runtime registry is first-registration-wins (CLAUDE.md
"first-registration-wins registries" principle). The user-guide does not encourage or
demonstrate replacement; the aspect's cache was a deliberate performance choice under the
assumption of stable bean topology. Closing the gap requires deciding what
"replacement" means at the architecture level — is it supported? Through which API? Is
the cache invalidation a Spring-aspect concern or a registry-level concern? — and that
decision deserves its own ADR.

**Shape of the fix:** Two plausible directions, depending on the architectural answer:

- If element replacement is to be a supported operation: the registry exposes a
  change-listener API (similar to the snapshot/listener pattern), and `InqShieldAspect`
  subscribes to invalidate the cache on replacement.
- If element replacement remains unsupported: the user-guide states explicitly that bean
  replacement after first interception is undefined behaviour, and a runtime check (e.g.
  identity comparison on cache hit, with a one-time WARN log on mismatch) catches the
  most common misuse without imposing a listener overhead.

The choice is design work, not implementation work — neither direction is obviously
better without a stated semantics for what registries are.

**When to address:** Together with the registry-semantics review in the next architecture
audit cycle. Not on the critical path for the bulkhead pattern's completion.
