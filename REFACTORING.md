# Refactoring Specification: Configuration & Lifecycle Redesign

**Audience:** Claude Code, executing the refactoring work
**Authoritative sources:** ADR-025, ADR-026, ADR-027, ADR-028, ADR-029
**Scope:** Replace the existing configuration system with the snapshot/patch/runtime architecture, introduce the cold/hot lifecycle and veto-based update propagation, then propagate the new patterns to all components.

---

## How to use this document

This document is the **execution plan**. The ADRs are the **specifications**. Whenever this document refers to "as specified in ADR-NNN", you must read that ADR before implementing — this document does not duplicate ADR content; it sequences and disambiguates the work.

The work is split into **two phases**. Each phase ends with the project in a fully buildable state, with all tests passing. Do not start the next phase until the previous one is green. After Phase 2 closes, this document is deleted; subsequent refactor work begins under its own dedicated `REFACTORING.md`.

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

5. **Reminder for step 2.1 — lift `touchedFields()` to `ComponentPatch<S>`.** Step 1.4 added
   `touchedFields()` only on `BulkheadPatch`; the `ComponentPatch<S>` interface still exposes
   only `applyTo(base)`. The phase-2.1 update dispatcher needs to drive the veto chain through a
   typed but paradigm-agnostic `ComponentPatch<?>` reference and must therefore read touched
   fields without knowing the concrete patch type. Add the `Set<? extends ComponentField>
   touchedFields()` method to `ComponentPatch<S>` as part of the dispatcher work in 2.1, and have
   each per-component patch (Bulkhead now, other components when their own refactor pattern
   begins) implement it.

---

## Guiding principles

These apply throughout both phases:

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
 *             circuit breaker is migrated to the new architecture in a future refactor.
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

**Why this order:** The veto chain and structural updates are mostly orthogonal to which components exist. Doing them with one component (bulkhead) keeps the surface area small and the tests focused. Adding more components in subsequent refactors then inherits this work for free.

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
- A few built-in cross-component rules. Initially limited because most cross-component rules involve circuit breaker × retry × bulkhead interactions, which can't be tested until those components are migrated to the new architecture. Bulkhead-only rules: e.g., `MULTIPLE_BULKHEADS_NO_AGGREGATE_LIMIT` (warns if many bulkheads have generous individual limits but no global cap).

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

### 2.8 Per-runtime event registry isolation (ADR-required)

**Status:** open question, warrants its own ADR before implementation.

Phase 1.7 created `GeneralSnapshot.eventPublisher` using
`InqEventPublisher.create(name, type)`, which binds to the **global default**
`InqEventExporterRegistry` (`InqEventExporterRegistry.getDefault()`). Phase 1.9 ports
per-component publishers via `ComponentEventPublisherFactory`, whose default factory similarly
delegates to `InqEventPublisher.create(name, type)` and therefore also binds to the global
default registry.

Open question: should each `InqRuntime` instance have its own isolated
`InqEventExporterRegistry`, instead of sharing one global default? Implications reach beyond
the bulkhead:

- **Multi-runtime isolation.** Two `InqRuntime` instances in the same JVM (tests,
  multi-tenant) currently share exporter state. An isolated registry per runtime would let
  each runtime have its own exporter set without leaking events across them.
- **Migration cost for existing consumers.** Code that relies on the global registry to
  capture events from anywhere (the entire pre-refactor codebase) would need adjustment, plus
  a documented migration path for downstream applications.
- **Default behaviour change.** Whatever the default, it changes the operational contract
  for users who rely on global exporter binding today.
- **Bridges the runtime-scoped publisher and the per-component factory.** A consistent answer
  applies to both — the same registry-binding policy must hold for `GeneralSnapshot.eventPublisher`
  and the publishers created via the factory.

Out of scope for the configuration refactor as currently planned. Addressed in a dedicated ADR
(call it ADR-031 or whatever number is current) followed by implementation work in this step.
Until that ADR is accepted, both the runtime publisher and the per-component factory retain
their global-default binding.

The acceptance criteria for this step are: an ADR exists; its implementation has landed; both
publisher paths (runtime-scoped and component) honour the chosen isolation policy; existing
tests using `new InqEventExporterRegistry()` continue to work; a migration note for downstream
consumers is documented.

### 2.9 Bulkhead rollback-trace publishing on event-publish-during-acquire failure

**Status:** open follow-up from step 1.9.

The `BulkheadEventConfig.rollbackTrace` flag exists for parity with the pre-refactor
`ImperativeBulkhead` and travels through the snapshot/patch/builder triad like the other event
flags, but its publish path is **not** wired in `BulkheadHotPhase` — it covers the corner case
where an event publish itself fails after `tryAcquire` has succeeded:

```
acquire permit                      // ok, permit held
publish(BulkheadOnAcquireEvent)     // throws — listener / exporter blew up
                                    // permit is still held!
release permit                      // must roll back
publish(BulkheadRollbackTraceEvent) // tell observers we rolled back ...
                                    // ... but this publish could blow up too
```

What needs to happen:

- Catch the publish failure inside `BulkheadHotPhase.execute` (specifically around the
  `BulkheadOnAcquireEvent` publish — that is the only mid-acquire publish point).
- Release the permit, since the business call never started.
- Publish (or otherwise record) `BulkheadRollbackTraceEvent` if the flag is on. The
  publish-during-rollback can itself fail — re-publishing risks the same failure mode that
  triggered the rollback. Plausible designs:
  1. **Best-effort republish, then swallow.** Try `publisher.publish(rollback)`; if it throws,
     log via `LoggerFactory` and proceed. Simplest, but the rollback event may not reach
     subscribers if the publisher is the source of the failure.
  2. **Bypass the publisher entirely for rollback.** Log the rollback through the
     `LoggerFactory` from `GeneralSnapshot` — guaranteed delivery to operational tooling, but
     the event-bus path (and any exporters bound to it) is not informed.
  3. **Hybrid.** Publish if possible; log the rollback fact independently regardless of
     publish outcome. Belt-and-braces, but two records per rollback event clutters dashboards.

Tests that the eventual implementation must pin:

- An `onAcquire` subscriber that throws causes the permit to be released and (when
  `rollbackTrace` is on) `BulkheadRollbackTraceEvent` to be observable on the same publisher.
- The rollback path returns the original publish failure to the caller (the user's chain
  never ran), so `bulkhead.execute` rethrows the publish exception or wraps it in a
  framework-specific exception — the choice is part of this step's design work.
- A subscriber that throws on the rollback event itself does not corrupt the bulkhead's
  state — the permit was already released before the rollback publish was attempted.
- The `rollbackTrace` flag is opt-in like the other flags; with it off, the failure path
  releases the permit but does not publish.

The decision on (1) / (2) / (3) plus the rethrow contract belongs to this step. The
`BulkheadHotPhase` Javadoc references this REFACTORING.md item; nothing else in the codebase
flags it.

### 2.10 Strategy hot-swap and the new strategy-config DSL

**Status:** Implemented.

ADR-032 specifies how strategy configuration lives on `BulkheadSnapshot`, the DSL surface for
selecting a strategy, and the atomic hot-swap mechanism gated by zero in-flight calls.
Implementation followed the four sub-steps laid out in ADR-032's "Implementation notes":

- **A:** Snapshot field, sealed-type configs, default value.
- **B:** Strategy factory and hot-phase materialization.
- **C:** DSL sub-builders for each strategy.
- **D:** Hot-swap path with veto.

#### Migration of the deprecated configuration records

Step 1.10 deprecated several config records that lost their consumers when the old bulkhead
config stack was deleted:

- `eu.inqudium.core.element.bulkhead.config.AimdLimitAlgorithmConfig` (+ Builder)
- `eu.inqudium.core.element.bulkhead.config.VegasLimitAlgorithmConfig` (+ Builder)
- `eu.inqudium.imperative.bulkhead.config.CoDelBulkheadStrategyConfig` (+ Builder)

The corresponding strategy implementations (`AdaptiveBulkheadStrategy`,
`CoDelBulkheadStrategy`, `AdaptiveNonBlockingBulkheadStrategy`) are kept — they are the
strategy framework, paradigm-internal, and reused by the bulkhead via
`SemaphoreBulkheadStrategy`.

ADR-032 chose a clean break, not a name-preserving migration. The old records had constructor
signatures and field shapes built for the pre-refactor configuration system; the new records
use the snapshot architecture's compact-constructor invariant pattern. The new types live in
`eu.inqudium.config.snapshot.*` (algorithm configs and strategy configs alike).

The deprecated records were deleted as part of sub-step D's migration work.

### 2.11 Phase 2 acceptance criteria

Verify that the Phase 2 work is complete and the codebase is in a coherent state. This is a
verification step, not an implementation step — no new features, no behavioural changes.
Findings that require fixes feed into the later sub-steps in this section.

- All Phase 1 tests still pass.
- Veto-chain, structural removal, `dryRun`, `diagnose` all green with their own test suites.
- ServiceLoader-based consistency-rule loading works.
- Per-runtime event-registry isolation (ADR-031) works as specified.
- Rollback-trace publishing on event-publish-during-acquire failure works as specified.
- Strategy hot-swap (ADR-032) works for all four strategies, with the post-patch-state
  evaluation contract for live-tunability checks (ADR-028).
- Lifecycle events fire in the right situations: `RuntimeComponentAddedEvent`,
  `RuntimeComponentPatchedEvent`, `RuntimeComponentRemovedEvent`,
  `RuntimeComponentVetoedEvent`, `ComponentBecameHotEvent`. The patches-then-removals
  ordering is observable.
- Phase-reference cleanup in production Javadocs and ADRs is complete.

The acceptance criteria are formalized as a structured report: each criterion gets a status
(met / not met) and an anchor test class as evidence.

### 2.12 Imperative bulkhead pattern completion audit

A higher-level verification than 2.11. Where 2.11 checks the Phase 2 acceptance criteria,
2.12 checks whether the imperative bulkhead is complete *as a resilience pattern*.

- ADR-020 (bulkhead design) versus the current code: what is specified, what is implemented,
  what is missing?
- Strategy completeness: all four strategies (Semaphore, CoDel, Adaptive, AdaptiveNonBlocking)
  exposed through the DSL, materialized correctly, live-tunable where appropriate, vetoed
  where they cannot be tuned.
- Lifecycle completeness: cold and hot phases for every strategy, the cold-to-hot transition,
  structural removal with `ComponentRemovedException` on inert handles.
- Failure-mode coverage: what happens if strategy construction fails? If a listener throws?
  If `closeStrategy(...)` throws? If a hot-swap encounters an unexpected snapshot shape?
- Documentation status: README mentions the bulkhead, code samples reflect the current API,
  migration guide exists for users moving from the previous architecture.

**Status:** Implemented. Findings recorded in `AUDIT_FINDINGS.md`. Routing of findings
into subsequent sub-steps, `TODO.md`, the upcoming ADR audit, or "no action" was decided
at review time and is reflected in the sub-step bodies below.

### 2.13 BulkheadHotPhase: missing onCallComplete and adjacent execute-path fixes

A focused fix step for the central audit finding plus three adjacent issues. All four sit
in `BulkheadHotPhase.execute(...)` or related accessors and form a coherent block of
"finish the migration of the bulkhead's hot path from the legacy `ImperativeBulkhead`".

- **Missing `onCallComplete` feedback for adaptive algorithms** (kritisch). The
  `execute(...)` method calls `strategy.release()` directly in the `finally` block but
  does not first call `strategy.onCallComplete(rttNanos, isSuccess)`. ADR-020 specifies
  the `onCallComplete` → `release` ordering as required for adaptive strategies. Without
  it, `AdaptiveBulkheadStrategy` and `AdaptiveNonBlockingBulkheadStrategy` silently
  degrade to static limiters at their initial limit. Fix: measure RTT, call
  `onCallComplete` before `release`, log algorithm-update failures without blocking the
  release. The legacy `ImperativeBulkhead.releaseAndReport(...)` shows the correct
  pattern; that logic is what the new path needs.

- **`InqBulkheadFullException` and `InqBulkheadInterruptedException` constructed with
  `enableExceptionOptimization=false`** (wichtig). The exception-optimization flag is
  hardcoded to `false` rather than read from `general().enableExceptionOptimization()`.
  ADR-020 documents the optimization (no-op `fillInStackTrace`) as a deliberate
  performance feature on the rejection path. The legacy class wires it correctly; the
  new class needs the same wiring.

- **Cold-phase accessors return inconsistent value for adaptive strategies** (wichtig).
  `InqBulkhead.availablePermits()` and `concurrentCalls()` read `snapshot.maxConcurrentCalls()`
  in the cold state. This is correct for Semaphore and CoDel (both honour the snapshot's
  value), but `AdaptiveBulkheadStrategy` and `AdaptiveNonBlockingBulkheadStrategy` ignore
  `snapshot.maxConcurrentCalls()` and run on the algorithm's `initialLimit`. Result: a
  cold adaptive bulkhead reports a different "limit" than the same bulkhead reports
  immediately after warm-up. Decide between two fixes: (a) cold-phase accessors read the
  algorithm's `initialLimit` from the strategy config when the snapshot's strategy is
  adaptive, or (b) document the discontinuity as deliberate. Recommendation is (a) —
  observers should not see two different numbers for the same conceptual quantity.

- **`BulkheadHotPhase` class-level Javadoc claims "carries a SemaphoreBulkheadStrategy"**
  (nachrangig). The Javadoc was written before 2.10.B introduced the strategy factory.
  The constructor now delegates to `BulkheadStrategyFactory.create(...)` which produces
  one of four strategies. Update the class Javadoc and the `onSnapshotChange` Javadoc
  reference to reflect this — the strategy is no longer Semaphore-specific, the
  in-place re-tune branch is one of three paths through the snapshot handler.

Tests must verify, in addition to the existing test surface, that adaptive strategies
*actually adapt*: a test that drives an `AdaptiveBulkheadStrategy` through enough calls
to observe a limit change after `onCallComplete` feedback.

**Status:** Implemented. `BulkheadHotPhase.execute(...)` now samples RTT around the
downstream call, invokes `strategy.onCallComplete(rttNanos, isSuccess)` before
`strategy.release()` (ADR-020 ordering), and logs algorithm-update failures via
`general().loggerFactory()` without blocking the release. Both
`InqBulkheadFullException` and `InqBulkheadInterruptedException` are constructed with
`general().enableExceptionOptimization()`; the flag is now a record component on
`GeneralSnapshot` (default `true`) with a `GeneralSnapshotBuilder` setter.
`InqBulkhead.availablePermits()` reads the algorithm's `initialLimit` for adaptive
snapshots when cold via an exhaustive switch over `BulkheadStrategyConfig` and
`LimitAlgorithm`, eliminating the cold-to-hot discontinuity for adaptive bulkheads. The
class-level Javadoc on `BulkheadHotPhase` and the `onSnapshotChange` reference are
rewritten to be timeless. New pinning suite at
`BulkheadHotPhaseFeedbackTest` covers AIMD/Vegas limit growth (blocking +
non-blocking), `onCallComplete`-before-`release` ordering, algorithm-failure
isolation, the optimization flag's effect on suppression and on
`InqBulkheadInterruptedException` stack traces, and cold-phase-accessor consistency
across the four strategy variants.

### 2.14 Core module: residue of the old constructs

A focused sweep over `inqudium-core` to find anything that survived Phase 1 deletion or
Phase 2 evolution but is no longer reachable, no longer relevant, or no longer consistent
with the new architecture.

- Search for `@Deprecated` annotations: every remaining one needs a documented reason.
- Look for empty or near-empty packages from earlier phases.
- Look for classes referenced only from other deleted code (dead code one level up).
- Look for old configuration types whose successor is in `inqudium-config` but whose
  original is still around.
- Confirm the legacy `Bulkhead.java` and `ImperativeBulkhead.java` deprecation form is
  consistent and points at a clear future removal trigger.
- Clean up the phase-reference Javadoc on
  `SemaphoreBulkheadStrategy.adjustMaxConcurrent` (the comment still mentions
  "Phase 1" and "require drain" — neither is current).

### 2.15 User-guide update for the strategy DSL

The bulkhead user guide (`docs/user-guide/bulkhead/bulkhead.md`) was updated for the new
`Inqudium.configure()` DSL during Phase 1, but does not mention any of the four strategies,
the `strategy` field, the live-tunability rules, or the hot-swap preconditions.
Result: users reading the guide cannot discover that CoDel or the adaptive variants
exist as choices.

This step makes the strategy pattern visible in user-facing documentation. Coverage:

- The four strategy choices: when to pick each, what the trade-offs are.
- The DSL surface: `b.semaphore()`, `b.codel(...)`, `b.adaptive(...)`,
  `b.adaptiveNonBlocking(...)`, plus the algorithm sub-builders for the adaptive variants.
- Live-tunability matrix per strategy: what `runtime.update(...)` can change in place,
  what gets vetoed.
- Hot-swap preconditions: zero in-flight calls required for `STRATEGY` patches.
- The `strategy` field in the configuration reference table.

### 2.16 Function-wrapper and proxy-wrapper audit

The core module exposes wrapper mechanisms that pre-date the snapshot/patch architecture:
function wrappers and proxy wrappers used to apply resilience patterns to user code. These
wrappers must be checked against the current bulkhead architecture:

- Do they reach the bulkhead through the current handle API, or do they still go through
  pre-refactor accessors?
- Do they correctly handle the cold-to-hot transition (calling `execute` triggers it)?
- Do they correctly handle a hot-swapped strategy (the strategy reference is volatile, the
  wrapper must read it through the current API, not cache it)?
- Are the wrappers covered by tests that exercise them through a complete bulkhead, not
  just a mock?

### 2.17 AspectJ integration

The AspectJ integration module against the new bulkhead architecture.

- Aspect definitions, pointcuts, around-advice — still consistent with the current
  `InqBulkhead` handle?
- Lifecycle-phase compatibility: AspectJ aspects firing on hot bulkheads, on cold-to-hot
  transitions, on bulkheads that have been structurally removed.
- Test suite (if it exists) runs green; if it doesn't exist or doesn't exercise the new
  architecture's surface, this is the moment to add it.

### 2.18 Spring and Spring Boot integration

The Spring integration module and the Spring Boot starter against the new architecture.

- Auto-configuration produces `Inqudium.configure()`-shaped runtimes correctly.
- Bean definitions for `InqRuntime`, paradigm sections, individual components.
- Configuration properties (application.properties / application.yml) map to the new
  configuration shape.
- Lifecycle integration with Spring's own bean lifecycle: when does the runtime become
  hot? Is shutdown coordinated with Spring's context shutdown?
- Example application or integration tests demonstrating the typical usage pattern.

### 2.19 Bulkhead integration test module

A new module — `inqudium-bulkhead-integration-tests` — that exercises the bulkhead as a
*complete stack* across all integration variants.

The motivation is twofold. First, individual modules each have their own tests
(`inqudium-config` tests snapshots, `inqudium-imperative` tests bulkhead behaviour,
`inqudium-aspectj` tests aspects, `inqudium-spring` tests Spring configuration), but the
*combination* of these layers is currently nowhere exercised. A bulkhead used through
AspectJ, configured via Spring, with the DSL — that whole stack is not tested today.
Second, the test classes serve as readable, executable examples that demonstrate how
applications are expected to use the API. Well-named tests double as documentation.

Coverage:

- Bulkhead via DSL configuration, exercised through `bh.execute(...)`.
- Bulkhead via AspectJ aspect, exercised through user-method calls.
- Bulkhead via dynamic-proxy wrapper, exercised through proxy invocations.
- Bulkhead via Spring/Spring Boot autoconfiguration, exercised in a Spring context.
- Bulkhead receiving a runtime update during execution: the snapshot updates, observers
  see the new value, the live behaviour reflects the change (subject to the live-tunability
  rules).

Audit findings absorbed into this step's test coverage:

- Race between `markRemoved` and `onSnapshotChange` during hot-swap (concurrency test
  under load).
- Strategy construction failure on the cold-to-hot transition and on hot-swap (negative
  tests with a synthetically-failing strategy provider).

Tests are structured for readability: each test class addresses one variant, each test
method describes one user scenario. The naming should feel like a tutorial when read
top-to-bottom.

### Phase 2 closure

After 2.11 through 2.19 are complete and reviewed:

- `REFACTORING.md` is deleted. Its purpose is fulfilled.
- `AUDIT_FINDINGS.md` is deleted alongside.
- The imperative bulkhead is officially complete as a resilience pattern.
- The next document to be created — when work begins — is a new `REFACTORING.md` for the
  ADR audit (a separate, dedicated effort), and after that, separate `REFACTORING.md`
  documents for each subsequent resilience pattern (circuit breaker, retry, time limiter)
  in the imperative paradigm.

The principle for future work: one `REFACTORING.md` at a time, with a single focus. No
parallel refactor plans, no overlapping documents. Between documents, the codebase is the
truth; the ADRs describe the architecture; `IDEAS.md` and `TODO.md` hold open loops.

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
