# Refactoring Specification: Configuration & Lifecycle Redesign

**Audience:** Claude Code, executing the refactoring work
**Authoritative sources:** ADR-025, ADR-026, ADR-027, ADR-028, ADR-029
**Scope:** Replace the existing configuration system with the snapshot/patch/runtime architecture, introduce the cold/hot lifecycle and veto-based update propagation, then propagate the new patterns to all components.

---

## How to use this document

This document is the **execution plan**. The ADRs are the **specifications**. Whenever this document refers to "as specified in ADR-NNN", you must read that ADR before implementing — this document does not duplicate ADR content; it sequences and disambiguates the work.

The work is split into **three phases**. Each phase ends with the project in a fully buildable state, with all tests passing. Do not start the next phase until the previous one is green.

When you encounter a question this document does not answer, prefer the ADR. When the ADR is silent, ask before deciding.

---

## Clarifications captured during execution

The following points refine or override what is written in the ADRs and in the work-item bodies of this
document. They were resolved before phase 1 began and supersede any contradicting passage.

1. **No veto chain in phase 1.** Phase 1 implements neither external listener vetoes nor the
   component-internal mutability check. Hot patches go straight through `LiveContainer.apply(patch)`; the
   hot phase reacts solely via snapshot subscription. Strategy hot-swaps are not supported in phase 1 and
   arrive together with the veto mechanism in phase 2. The "respecting the component-internal mutability
   check from ADR-028" wording in step 1.6 is read as "no mutability check yet". `BulkheadHotPhase` in
   phase 1 only handles changes the live snapshot can express against the existing strategy
   (e.g. simple permit-count adjustments); anything that would require a strategy replacement is out of
   scope until phase 2.

2. **Snapshots are paradigm-agnostic.** `ParadigmTag` is *not* a field on `BulkheadSnapshot` (or any other
   `ComponentSnapshot`). The paradigm is a structural property of the handle and the `LiveContainer`, and
   is exposed through them. `InqConfigView` surfaces the paradigm via the handle, not via the snapshot.
   This deviates from the illustrative snippet in ADR-025; the ADR will be reconciled separately.

3. **`derivedFromPreset` follows normal touch logic.** `BulkheadField.DERIVED_FROM_PRESET` exists and
   participates in the patch's BitSet like every other field. Preset methods touch it (setting the preset
   label); individual setters do not. A patch that does not call a preset inherits the previous value, so
   class-3 rules such as `BULKHEAD_PROTECTIVE_WITH_LONG_WAIT` continue to fire correctly after a hot
   update. No explicit `clearPreset()` setter is needed.

4. **Reminder for step 1.7 — `eventPublisher` and `clock` come from `GeneralSnapshot`.** The
   imperative lifecycle base class introduced in step 1.3 currently accepts both as separate
   constructor parameters. When the runtime container is built in step 1.7 and components are
   materialized through `Inqudium.configure()...build()`, the publisher and clock must be sourced
   from the per-runtime `GeneralSnapshot` (clock, observability defaults) rather than passed in
   independently — otherwise we end up with two truth sources for the same configuration. The
   constructor signature stays as-is for testability; the wiring code in 1.7 must read both values
   from the general snapshot and pass them along.

---

## Guiding principles

These apply throughout all three phases:

1. **The existing implementation is not preserved.** No backward compatibility, no migration shims. Old types are removed cleanly when nothing references them anymore. The codebase has no external consumers yet, so this is safe.

2. **The bulkhead is the reference implementation.** It is the most mature component. Patterns established for the bulkhead are then replicated for circuit breaker, retry, and time limiter when those components are built or refactored. Do not invent new patterns for later components — extend or reuse the bulkhead patterns.

3. **Tests are part of the deliverable, not an afterthought.** Every snapshot record needs tests for its compact constructor invariants. Every patch class needs tests for its `applyTo` semantics. Every component needs tests for its cold/hot transition and update routing. JUnit 5 conventions apply per the project's standard test style.

4. **One commit per logical step.** When this document lists work items as separate bullet points, prefer separate commits. This keeps review and rollback granular.

5. **Module names from ADR-025 are normative.** Where this document mentions modules, the names match: `inqudium-core`, `inqudium-config`, `inqudium-imperative`, `inqudium-reactive`, `inqudium-rxjava3`, `inqudium-kotlin`, plus the format-adapter modules.

---

## Handling obsolete types: delete or deprecate

The refactoring replaces a set of types (configurations, builders, DSL hub interfaces) that are currently referenced from multiple places. Some referencing code is also being refactored in this session — those references go away as part of the work. But some referencing code belongs to components that are **not** being refactored yet (circuit breaker, retry, time limiter — only partially or not at all implemented). Deleting types those components still depend on would break the build.

Therefore, every obsolete type follows this rule:

**If no production code references it after the current phase's refactoring is complete, delete it.** Test code that references it is updated or removed as part of the same step.

**If any production code outside the components being refactored still references it, mark it `@Deprecated` with a comment explaining why and what replaces it.** Keep the type compiling. Do not change its behavior. Document its removal as part of a future refactoring of the still-referencing component.

The deprecation marker has three required pieces:

```java
/**
 * @deprecated Replaced by {@link eu.inqudium.config.snapshot.BulkheadSnapshot} as part of the
 *             configuration redesign (ADR-025). This type is retained only because
 *             {@code InqCircuitBreakerConfig} still references it; it will be removed when the
 *             circuit breaker is migrated to the new architecture (phase 3).
 */
@Deprecated(forRemoval = true, since = "0.4.0")
public record InqBulkheadConfig(...) { ... }
```

The three pieces:

1. **The Javadoc `@deprecated` tag** — explains *what replaces it* and *why it cannot be deleted yet* (which component still references it). This is the human-readable part; the next developer reading this code needs to understand the situation immediately.
2. **The `@Deprecated` annotation** — `forRemoval = true` because removal is genuinely planned, not a "soft deprecation". `since` matches the project's current development version.
3. **No behavioral change** — the type continues to function exactly as before. The deprecation is documentation, not a runtime signal.

Tests for deprecated types are kept as long as the type itself exists. They get a class-level `@Deprecated` and a comment explaining that they will be removed alongside their type.

When the still-referencing component is eventually refactored (in this session or later), the deprecated types are deleted as part of that work, not earlier. Do not delete deprecated types in this session unless the work specifically migrates the last referencing component.

The work items in each phase below distinguish "delete" from "deprecate" explicitly. When in doubt: deprecate. A live build with deprecation warnings is recoverable; a broken build is not.

---

# Phase 1 — Foundation: Config module, snapshots, runtime skeleton

**Goal:** Establish the new configuration module, the snapshot/patch infrastructure, the runtime container, and the lifecycle base classes. Migrate the bulkhead as the reference component. At the end of this phase, the project builds and runs the bulkhead end-to-end via the new architecture, with all bulkhead tests adapted to the new APIs.

**Why this order:** Every later component depends on the framework pieces in this phase. Doing it once, well, with the bulkhead as the proving ground, means the rest of the work is largely replication.

## Phase 1 work items

### 1.1 Create the `inqudium-config` module

Add the new module to the build (Maven/Gradle as appropriate to the project's existing setup). Dependencies: `inqudium-core` only. The module has no `inqudium-imperative` or paradigm-module dependency — paradigm modules will depend on it, not the other way around.

Initial package structure:

```
eu.inqudium.config                      — DSL entry point (Inqudium.configure())
eu.inqudium.config.snapshot             — ComponentSnapshot sealed hierarchy
eu.inqudium.config.patch                — ComponentPatch base interfaces
eu.inqudium.config.live                 — LiveContainer<S>
eu.inqudium.config.runtime              — InqRuntime, ParadigmContainer
eu.inqudium.config.lifecycle            — LifecycleState, LifecycleAware, ChangeRequest, ChangeDecision, ChangeRequestListener, PostCommitInitializable
eu.inqudium.config.validation           — BuildReport, ValidationFinding, ConsistencyRule, CrossComponentRule
eu.inqudium.config.spi                  — ParadigmProvider SPI
eu.inqudium.config.event                — RuntimeComponentAddedEvent, RuntimeComponentRemovedEvent, RuntimeComponentPatchedEvent, ComponentBecameHotEvent, RuntimeComponentVetoedEvent
```

### 1.2 Implement the foundational types

In `inqudium-config`, in this order:

- **`LifecycleState`, `LifecycleAware`** — trivial types from ADR-029.
- **`ComponentSnapshot`** sealed interface from ADR-026. No concrete records yet — they come with each component.
- **`ComponentPatch<S>`** interface from ADR-025, with the `applyTo(S base): S` contract.
- **`LiveContainer<S>`** from ADR-025 — `AtomicReference<S>` plus listener subscription mechanism. Generic on snapshot type.
- **`ChangeRequest<S>`, `ChangeDecision` (sealed: `Accept` / `Veto` records)** from ADR-028. Veto reason validated in compact constructor (non-null, non-blank).
- **`ChangeRequestListener<S>`** functional interface.
- **`PostCommitInitializable`** marker interface from ADR-029.
- **`ValidationFinding`, `BuildReport`, `ApplyOutcome` (with all six values including `VETOED`)** from ADR-027 and ADR-028.
- **`VetoFinding`** record from ADR-028.
- **`ConsistencyRule<S>`, `CrossComponentRule`** SPI interfaces from ADR-027.

Each type gets full Javadoc and a unit test for its invariants where applicable. The `ChangeDecision.Veto` test case is non-trivial — verify the reason validation.

### 1.3 Implement the imperative lifecycle base class

In `inqudium-imperative`, package `eu.inqudium.imperative.lifecycle`:

- **`ImperativeLifecyclePhasedComponent<S extends ComponentSnapshot>`** as specified in ADR-029, including the inner `ColdPhase`, the `ImperativePhase` interface, the `HotPhaseMarker` interface, the listener list, and the `PostCommitInitializable` hook in the post-commit work after CAS success.

Test coverage required:

- Cold-to-hot transition fires exactly once.
- `ComponentBecameHotEvent` is published exactly once even under multi-thread CAS contention.
- `lifecycleState()` returns `COLD` before first execute and `HOT` after.
- Listener registration/unregistration via `AutoCloseable` works.
- Post-commit hook fires after the successful CAS, not on discarded candidates.

Concurrency tests must use real threads (not stubs) and verify the contract holds under contention. Use `CountDownLatch`-based coordination patterns standard for the project.

### 1.4 Implement the bulkhead snapshot, patch, and live container

In `inqudium-config.snapshot`:

- **`BulkheadSnapshot`** record per ADR-025/027. Compact constructor enforces all class-2 invariants (non-null name, positive `maxConcurrentCalls`, non-negative `maxWaitDuration`, immutable tag set). Includes `derivedFromPreset` field per ADR-027 (nullable string).
- **`BulkheadField`** enum naming each patchable field. Used by `BulkheadPatch` and as `ComponentField` instances in `ChangeRequest`.

In `inqudium-config.patch`:

- **`BulkheadPatch`** class. BitSet-based touch tracking, `applyTo(BulkheadSnapshot): BulkheadSnapshot` per ADR-025.

The `LiveContainer<BulkheadSnapshot>` does not need a bulkhead-specific subclass; it is generic.

Tests:

- Snapshot compact constructor rejects every invalid combination (one test per invariant).
- Patch with no fields touched produces an unchanged snapshot.
- Patch with each individual field touched produces a snapshot identical to base except for that field.
- Patch with all fields touched produces a snapshot with all new values.

### 1.5 Implement the bulkhead DSL builder

The DSL surface from ADR-025 — `Inqudium.configure().imperative(im -> im.bulkhead("name", b -> ...))` — needs concrete builder classes.

In `inqudium-config`:

- **`BulkheadBuilder<P extends ParadigmTag>`** interface — the paradigm-agnostic methods. `name(...)` is *not* a setter (it's a method argument); but `maxConcurrentCalls`, `maxWaitDuration`, `tags`, `observability`, the three presets (`protective`, `balanced`, `permissive`), and the `derivedFromPreset` tracking go here.
- **`ImperativeBulkheadBuilder extends BulkheadBuilder<Imperative>`** — adds imperative-specific setters (strategy injection if applicable, adaptive limit sub-builders).
- **`BulkheadBuilderBase`** — abstract base implementation that all paradigms can extend in their respective modules. It owns the patch state, the touch tracking, the preset-then-customize guard.

Apply class-3 strategy A (preset-then-customize) per ADR-027: every individual setter sets a `customized = true` flag; every preset method calls `guardPresetOrdering()` first. The guard's exception message is documented in ADR-027.

In `inqudium-imperative`:

- **`DefaultImperativeBulkheadBuilder`** — concrete implementation of `ImperativeBulkheadBuilder` extending `BulkheadBuilderBase`.

Tests:

- All setters validate arguments (class 1 per ADR-027) — one test per setter for each invalid input.
- Preset-then-customize works (preset, then individual setter, build succeeds).
- Customize-then-preset throws `IllegalStateException` with the documented message.
- Each preset produces the expected baseline values.

### 1.6 Implement `InqBulkhead` using the lifecycle base class

In `inqudium-imperative.bulkhead`:

- **`InqBulkhead extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot> implements Bulkhead`** — concrete component as specified in ADR-029. Its `createHotPhase()` reads the current snapshot and constructs the hot phase.

- **`BulkheadHotPhase implements ImperativePhase, HotPhaseMarker, PostCommitInitializable`** — owns the strategy, performs the actual `tryAcquire`/`release` work, subscribes to live snapshot changes in `afterCommit`.

The hot phase delegates strategy selection to the existing `StrategyFactory` logic (preserve and adapt), but constructed from a snapshot rather than the old `InqBulkheadConfig`. Strategy adaptation under live updates (changing `maxConcurrentCalls` while running) is part of this work — the hot phase must update its strategy when the snapshot changes, respecting the component-internal mutability check from ADR-028.

The existing `ImperativeBulkhead` class (the old one) is **deleted** at the end of this step. Its responsibilities have been split between `InqBulkhead` (the new lifecycle-aware component) and `BulkheadHotPhase` (the runtime work).

Tests:

- Bulkhead in cold state: `lifecycleState() == COLD`, `availablePermits()` reads from snapshot.
- First `execute` call: transitions to hot, publishes `ComponentBecameHotEvent`, executes successfully.
- Subsequent calls: stay in hot, run through hot-phase strategy.
- Existing bulkhead tests adapted: rejection paths, event publication, exception handling, async variant. All must pass.

### 1.7 Implement the runtime container skeleton

In `inqudium-config.runtime`:

- **`InqRuntime`** interface from ADR-026. Includes `imperative()`, `reactive()`, `rxjava3()`, `coroutines()` accessors; the cross-paradigm `config()` view; lifecycle (`close()`); and `update(...)` / `apply(...)` / `dryRun(...)` / `diagnose(...)` entry points.
- **`ParadigmContainer<P>`, `Imperative`** interfaces from ADR-026.
- **`InqConfigView`** read-only view interface.
- **`ParadigmProvider`** SPI from ADR-025/026.
- **`Inqudium.configure()`** entry point — returns the top-level builder.
- **`InqudiumBuilder`** with `.general(...)`, `.imperative(...)`, `.reactive(...)`, etc. The non-imperative entry points throw `ParadigmUnavailableException` for now (their providers don't exist yet).
- **`DefaultInqRuntime`** — implementation, holds the paradigm containers it was built with, dispatches accessor calls.

In `inqudium-imperative`:

- **`DefaultImperative`** — implementation of the `Imperative` paradigm container interface, holds maps of components by name.
- **`ImperativeProvider`** — `ParadigmProvider` implementation, registered via `META-INF/services/eu.inqudium.config.spi.ParadigmProvider`.

The update DSL (`runtime.update(u -> u.imperative(im -> ...))`) goes through the same builder types as initial configuration — the only difference is the starting snapshot (live vs. default).

For this phase, only `add` and `patch` need to be implemented for updates. `remove` and `dryRun` and `diagnose` can be stubbed and are completed in phase 2.

Tests:

- Build a runtime with one bulkhead. Verify `runtime.imperative().bulkhead("x")` returns the configured component.
- Cross-paradigm view: `runtime.config().bulkheads()` includes the configured bulkhead.
- `runtime.update(u -> u.imperative(im -> im.bulkhead("x", b -> b.maxConcurrentCalls(20))))` patches the snapshot (verify via `bulkhead.snapshot().maxConcurrentCalls()`).
- `runtime.close()` shuts down components and renders subsequent operations inert.
- Adding a new bulkhead via update succeeds.

### 1.8 Implement the validation framework (classes 1, 2, 3 — without rule SPI yet)

Class 1 validation lives in DSL setters (already done in 1.5). Class 2 validation lives in snapshot compact constructors (already done in 1.4). Class 3 strategy A (preset-then-customize) is already done in 1.5.

This step adds:

- The `ConsistencyRule<S>` invocation pipeline — runs every registered rule against newly built snapshots, collects findings, integrates with `BuildReport`.
- A few built-in rules for the bulkhead: e.g., `BULKHEAD_PROTECTIVE_WITH_LONG_WAIT` from ADR-027.
- The strict-mode flag (`Inqudium.configure().strict()`) that elevates warnings to errors.
- `BuildReport` returned from `Inqudium.configure()...build()` and from `runtime.update(...)`.

`ConsistencyRule` registration via `ServiceLoader` happens in phase 2; for now, rules are hardcoded into the framework's startup.

Tests:

- Valid configuration produces a successful `BuildReport` with no findings.
- Configuration triggering `BULKHEAD_PROTECTIVE_WITH_LONG_WAIT` warning produces a `BuildReport` with one warning.
- Same configuration in strict mode aborts the build with a `ConfigurationException` carrying the report.

### 1.9 Adapt the existing bulkhead tests

The bulkhead has substantial existing test coverage. All of it must be adapted to the new APIs:

- Tests that constructed bulkheads via the old `Bulkhead.of(...)` paths now go through `Inqudium.configure()...build()` plus `runtime.imperative().bulkhead(...)`.
- Tests that referenced `InqBulkheadConfig`, `InqImperativeBulkheadConfig`, `BulkheadConfig` (the DSL record), `InqBulkheadConfigBuilder`, etc., now reference `BulkheadSnapshot` and the new builders.
- Tests that referenced `BulkheadProtection`, `BulkheadNaming`, `DefaultBulkheadProtection`, `InternalBulkheadConfigBuilder` now use the new DSL.

The test adaptation is mechanical but voluminous. Plan for this — it is the largest single chunk of phase 1.

### 1.10 Delete or deprecate the obsolete configuration types

Once 1.9 is green, the old bulkhead-related and config-framework types are addressed per the **delete-or-deprecate** policy described above.

**Approach:** First, run a full reference search across all modules for each type listed below. For each type, classify it into one of three categories:

- **Category A (delete):** Only referenced by bulkhead production code (now refactored) and bulkhead tests (now adapted). These are safe to delete.
- **Category B (deprecate):** Still referenced by circuit breaker, retry, time limiter, or any other component not part of this session's refactoring. Mark `@Deprecated(forRemoval = true)` with a Javadoc `@deprecated` block explaining the situation, as specified in the deprecation policy.
- **Category C (refactor in place):** The type is fundamentally needed in the new architecture but in a different shape — `GeneralConfig` and `InqElementCommonConfig` are the main candidates. These are not deprecated; they are replaced or restructured.

Candidate types and their expected categories (verify with a real reference search before acting — these are the *expected* classifications, not authoritative):

| Type                                                     | Expected category | Notes                                                                                                                      |
|----------------------------------------------------------|-------------------|----------------------------------------------------------------------------------------------------------------------------|
| `InqBulkheadConfig`                                       | B (deprecate)     | Likely referenced indirectly via `InqElementConfig` or by bulkhead-aware code in non-refactored components. Verify.        |
| `InqBulkheadConfigBuilder` and DSL hub interfaces        | A (delete)        | Bulkhead-internal. Safe.                                                                                                   |
| `BulkheadConfig` (DSL record in `core.element.bulkhead.dsl`) | A (delete)    | Bulkhead-internal. Safe.                                                                                                   |
| `BulkheadProtection`, `BulkheadNaming`                   | A (delete)        | Bulkhead-internal DSL interfaces. Safe.                                                                                    |
| `DefaultBulkheadProtection`, `InternalBulkheadConfigBuilder` | A (delete)    | Bulkhead-internal. Safe.                                                                                                   |
| `InqImperativeBulkheadConfig`                             | A (delete)        | Bulkhead-internal. Safe.                                                                                                   |
| `InqImperativeBulkheadConfigBuilder`                      | A (delete)        | Bulkhead-internal. Safe.                                                                                                   |
| Old `ImperativeBulkhead` class                            | A (delete)        | Already removed in 1.6.                                                                                                    |
| `InqConfig`                                               | B (deprecate)     | Top-level config record; almost certainly referenced by every component config. Replaced by `InqRuntime` plus snapshots.   |
| `InqConfig.MandatoryStep`, `TopicHub`, `Buildable`        | B (deprecate)     | Step-builder interfaces; same situation as `InqConfig`.                                                                    |
| `ConfigExtension<T>`, `ExtensionBuilder<T>`               | B (deprecate)     | The extension SPI. Used by every component config. Will be removed when the last component is migrated.                    |
| `InqElementConfig`, `InqElementCommonConfig`              | C (refactor)      | These hold `name`, `elementType`, `eventPublisher` — all things the new `ComponentSnapshot` needs. Decide: replace via a per-component property or extract into a small base record `CommonComponentMetadata` that snapshots compose. |
| `GeneralConfig`, `GeneralConfigBuilder`                   | C (refactor)      | Replace with `GeneralSnapshot` record (consistent with the snapshot pattern), or keep as the data model behind the new `Inqudium.configure().general(...)` block. The latter is less invasive in this phase. |

**Decision rule for category C in this session:** prefer the *less invasive* refactor path. Wrap rather than replace where possible. The next refactoring session that touches the circuit breaker or another component can migrate them fully when those components are themselves being touched.

**Concrete steps:**

1. For each type, run a project-wide reference search.
2. Classify it (A/B/C).
3. For A, delete it (and its tests, if separate).
4. For B, add the `@Deprecated` annotation and the Javadoc `@deprecated` block per the deprecation policy.
5. For C, decide between minimal-wrap and full-replace per the rule above; document the decision in a comment on the type itself.

**Verification:** after this step, the build must complete without errors. Deprecation warnings are expected and acceptable for category-B types. The compiler should report exactly the warnings you intended.

A final note: the project's `package-info.java` files reference some of these types (e.g., `eu.inqudium.core.element.bulkhead.package-info.java` mentions `AbstractBulkhead`, `BulkheadConfig`, `InqBulkheadFullException`). Update package-info Javadoc to reflect the new architecture for already-refactored packages, but only for those — don't update package-infos of packages whose components haven't been migrated yet, since their content is still accurate for their current code.

### 1.11 Phase 1 acceptance criteria

Before declaring phase 1 done:

- `mvn clean verify` (or the project's equivalent build command) succeeds across all modules.
- All bulkhead-related tests pass.
- Examples in the project's documentation that use bulkheads are updated to the new DSL.
- The runtime can be built, queried, mutated (add + patch), and closed.
- ADR-025, 026, 027, 028, 029 are accurately reflected in the code (any discrepancies discovered during implementation must be reported back so the ADRs can be reconciled).
- Compiler deprecation warnings are present (expected, due to category-B types from step 1.10) and are limited to references from non-refactored components. New code in the new architecture must not produce or trigger deprecation warnings — that would indicate a wrong dependency direction.

---

# Phase 2 — Veto chain, structural updates, validation extensions

**Goal:** Complete the runtime mutation surface — veto-based update propagation, structural removal, dry-run, diagnose. Finish the validation framework. The bulkhead is still the only component; this phase makes it production-grade with respect to runtime updates.

**Why this order:** The veto chain and structural updates are mostly orthogonal to which components exist. Doing them with one component (bulkhead) keeps the surface area small and the tests focused. Adding more components (phase 3) then inherits this work for free.

## Phase 2 work items

### 2.1 Implement the veto chain

Per ADR-028:

- The dispatcher in `inqudium-config` that routes patches according to lifecycle state.
- Cold path: skip veto chain, apply directly.
- Hot path: invoke listeners in registration order, then component-internal mutability check, then apply on full acceptance.
- Atomicity is per-component-patch (lösung A): a single veto rejects the whole component patch.
- `BuildReport` reports `VETOED` outcome with `VetoFinding` collection.

In `inqudium-imperative.bulkhead`:

- `BulkheadHotPhase` implements the component-internal mutability check. The check is conservative for now: accept simple field changes (limit increases/decreases that the strategy can handle in place), reject strategy-type changes if there are in-flight calls.

Tests:

- Cold component: listener vetoes are not consulted.
- Hot component: listener veto rejects the patch, snapshot unchanged, `VETOED` outcome in report.
- Hot component: listener accept + internal accept = patch applied.
- Hot component: internal veto rejects the patch even if listeners accepted.
- Multi-component update: veto on A doesn't affect patch to B.

### 2.2 Implement structural removal

Per ADR-026:

- `runtime.update(u -> u.imperative(im -> im.removeBulkhead("name")))` shuts down the named component.
- The component's hot phase drains in-flight calls per its shutdown semantics. For the bulkhead: stop accepting new acquires, wait for current permits to be released (with a configured timeout), then return permits to GC.
- The container removes the entry from its map.
- `RuntimeComponentRemovedEvent` is published.

Tests:

- Removed bulkhead: subsequent `runtime.imperative().findBulkhead(name)` returns empty Optional.
- In-flight calls during removal: complete normally; new acquires after `removeBulkhead` are rejected with `ComponentRemovedException`.
- External handle references after removal: methods return `ComponentRemovedException`.

### 2.3 Implement `dryRun`

`runtime.dryRun(updater)` runs the same validation and veto chain as `update(...)`, but does not apply the resulting patches. Returns the `BuildReport` that `update` would have returned.

Useful for CI/CD pipelines that want to validate proposed updates before applying.

### 2.4 Implement `diagnose`

Per ADR-027 class 4:

- `CrossComponentRule` SPI registration via `ServiceLoader`.
- `runtime.diagnose()` runs all registered cross-component rules against the current `InqConfigView`, collects `DiagnosticFinding` entries, returns a `DiagnosisReport`.
- A few built-in cross-component rules. Initially limited because most cross-component rules involve circuit breaker × retry × bulkhead interactions, which can't be tested until phase 3. Bulkhead-only rules: e.g., `MULTIPLE_BULKHEADS_NO_AGGREGATE_LIMIT` (warns if many bulkheads have generous individual limits but no global cap).

### 2.5 Move consistency rules to ServiceLoader

The consistency rules hardcoded in 1.8 are migrated to `ServiceLoader<ConsistencyRule<?>>` discovery per ADR-027. This is the foundation for application-specific rule contributions.

### 2.6 Listener registration API on handles

Per ADR-028:

- `BulkheadHandle.onChangeRequest(...)` returns an `AutoCloseable` for unregistration.
- Listeners survive snapshot updates but are tied to handle lifetime.
- After component removal, the handle is inert and its listeners are silently discarded.

Tests cover registration, vetoing, unregistration via the returned `AutoCloseable`, and listener cleanup on removal.

### 2.7 Lifecycle events for runtime topology

Per ADR-026 and ADR-028:

- `RuntimeComponentAddedEvent`, `RuntimeComponentRemovedEvent`, `RuntimeComponentPatchedEvent`, `ComponentBecameHotEvent`, `RuntimeComponentVetoedEvent` all published correctly.
- These live on the `InqRuntime` event publisher (a single, runtime-scoped publisher), distinct from per-component publishers.

### 2.8 Phase 2 acceptance criteria

- All phase 1 tests still pass.
- Veto-chain, removal, dryRun, diagnose all green with their own test suites.
- ServiceLoader-based rule loading works.
- The runtime is feature-complete for the bulkhead. Adding more components in phase 3 should not require any framework-level changes.

---

# Phase 3 — Component coverage and paradigm modules

**Goal:** Build out the remaining components (circuit breaker, retry, time limiter) using the patterns established in phases 1 and 2, and populate the existing-but-empty paradigm modules (`inqudium-reactive`, `inqudium-rxjava3`, `inqudium-kotlin`) at least to skeleton level.

**Why this order:** With the framework solid and the bulkhead proving the patterns, this phase is largely template work. Component-specific logic (state machine for circuit breaker, retry policies for retry) is the only genuinely new code; the lifecycle scaffolding, snapshot/patch types, DSL builders, and runtime integration are all replications of the bulkhead pattern.

**Note on existing module state:** The non-imperative paradigm modules (`inqudium-reactive`, `inqudium-rxjava3`, `inqudium-kotlin`) already exist in the build but are currently empty (no production code yet). Phase 3 fills them rather than creating them. The module names and their build configuration are already in place.

**Note on deprecation cleanup:** Each component migration in this phase (3.1 circuit breaker, 3.2 retry, 3.3 time limiter) is the appropriate moment to delete the category-B types that were deprecated in phase 1.10 because they were referenced by *that specific component*. Concretely:

- After 3.1 (circuit breaker migrated), search for category-B types that were deprecated solely because the circuit breaker referenced them. If the circuit breaker was the last reference, delete the deprecated type.
- Same pattern after 3.2 and 3.3.
- After all three components are migrated, ideally no `@Deprecated(forRemoval = true)` types from this refactoring session remain. If any do remain, they were referenced by something this session never touched — flag them for a follow-up.

## Phase 3 work items

### 3.1 Circuit breaker on the new architecture

The circuit breaker is the next-most-complex component after the bulkhead. Implementation order:

- `CircuitBreakerSnapshot` record with class-2 invariants (failure threshold in (0, 1], window size positive, etc.).
- `CircuitBreakerPatch`.
- `CircuitBreakerBuilder<P>` and `ImperativeCircuitBreakerBuilder` with presets (`protective`, `balanced`, `permissive` with circuit-breaker-appropriate defaults).
- `InqCircuitBreaker extends ImperativeLifecyclePhasedComponent<CircuitBreakerSnapshot>` plus `CircuitBreakerHotPhase` containing the state machine (CLOSED, OPEN, HALF_OPEN), the sliding window metrics, the failure recording.
- The cold-to-hot trigger is the first `execute` call (uniform with bulkhead per ADR-028).
- Component-internal mutability check: simple parameter changes accepted at any time; window-type changes (sliding window count vs. time-based) rejected if in-flight calls are present.
- Built-in consistency rules: `CIRCUITBREAKER_FAILURE_THRESHOLD_TOO_LOW_FOR_WINDOW` (per ADR-027 example), others as appropriate.
- Built-in cross-component rule: `CIRCUITBREAKER_INSIDE_RETRY` warning when retry wraps circuit breaker (or vice versa, depending on the topology — investigate before fixing the rule).
- Full test coverage for state transitions, recording, half-open probe behavior, snapshot updates affecting the live state machine.

### 3.2 Retry on the new architecture

Same pattern:

- `RetrySnapshot`, `RetryPatch`, `RetryBuilder<P>` / `ImperativeRetryBuilder`.
- Presets: `protective` (1 attempt, no retry — effectively a noop), `balanced` (3 attempts, exponential backoff), `permissive` (5 attempts, longer backoff).
- `InqRetry` plus `RetryHotPhase` containing the attempt counter, scheduler, backoff state.
- Component-internal mutability: backoff parameter changes accepted; max-attempts decreases below current in-flight retry counts rejected.
- Cross-component rule: `RETRY_BURST_CAN_FILL_BULKHEAD` per ADR-027 example.

### 3.3 Time limiter on the new architecture

Same pattern, simpler:

- `TimeLimiterSnapshot`, `TimeLimiterPatch`, builder, etc.
- One main parameter: `timeoutDuration`.
- Cross-component rule: `TIMELIMITER_SHORTER_THAN_TYPICAL_LATENCY` (heuristic based on observed metrics — phrase as warning when configurable threshold exceeded).

### 3.4 Reactive paradigm: `inqudium-reactive` skeleton

Module setup, depends on `inqudium-config` and Project Reactor.

- `ReactiveLifecyclePhasedComponent<S>` with the deferred-CAS pattern from ADR-029 (`Mono.defer(...)`).
- `Reactive` paradigm container interface in `inqudium-config`, implementation in `inqudium-reactive`.
- `ReactiveProvider` registered via `ServiceLoader`.
- One reference component: `ReactiveBulkhead` with the existing `AtomicNonBlockingBulkheadStrategy`. This proves the reactive paradigm end-to-end.

Other components (circuit breaker, retry, time limiter) follow as separate work items; track them but they are explicitly *out of scope* for the initial reactive integration. Each can be added in subsequent commits without blocking the rest of phase 3.

### 3.5 RxJava3 paradigm: `inqudium-rxjava3` skeleton

Identical structure to phase 3.4, but with RxJava3 types. Same reference component (RxJava3 bulkhead).

### 3.6 Kotlin paradigm: `inqudium-kotlin` skeleton

Identical structure, with `suspend fun`-based execute methods and `KotlinLifecyclePhasedComponent` using suspend points. Same reference component (Kotlin coroutines bulkhead).

### 3.7 YAML and JSON config adapters (optional, deferrable)

Per ADR-025, format adapters translate text into patches. Implement at least one (YAML is the most commonly requested):

- `inqudium-config-yaml` module.
- `YamlConfigSource.parse(InputStream): List<ComponentPatch<?>>`.
- Integration with `runtime.apply(patches)`.

This is a separate, low-priority work item and can be deferred beyond phase 3 if priorities require it. Note it here for traceability.

### 3.8 Phase 3 acceptance criteria

- All phase 1 and phase 2 tests still pass.
- Bulkhead, circuit breaker, retry, and time limiter all implemented in the imperative paradigm with full test coverage.
- Each of the three additional paradigms has at least the bulkhead working end-to-end.
- The cross-component diagnose rules involving multiple components actually trigger correctly when their conditions are met (testable now that the components exist).

---

## What to do when stuck

If during implementation you encounter:

- **An ADR ambiguity:** flag it, propose a resolution, ask before deciding. Do not silently invent.
- **A pattern from the existing code that doesn't fit:** the existing code is being replaced, not preserved. Default to the new pattern; only deviate if the new pattern genuinely doesn't fit, and explain why before deviating.
- **A test that can only pass with the old behavior:** the test is wrong for the new architecture. Either rewrite it to test the new behavior (preferred) or delete it if its concern is no longer relevant.
- **Performance regressions:** the snapshot/patch path is more allocation-heavy than the old direct-builder path. Some regression is expected and acceptable; >10% on hot paths is a flag for discussion.
- **Module structure decisions:** ADR-025 and ADR-026 specify the module layout. Stick to it. New types go in the module the ADRs name; if a type doesn't fit anywhere, that's a design issue worth flagging.

## What this document does not cover

- Detailed ADR content — read the ADRs.
- Code style conventions — defer to the project's existing style and `CLAUDE.md`.
- Test framework setup — JUnit 5 with the project's existing testing helpers and conventions.
- Git workflow — defer to the project's branching/PR conventions.
