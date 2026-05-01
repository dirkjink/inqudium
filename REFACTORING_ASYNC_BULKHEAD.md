# REFACTORING_ASYNC_BULKHEAD.md

Plan for closing the third entry of `TODO.md`: the new `InqBulkhead` covers only the
synchronous pipeline contract. This document plans the asynchronous extension.

The plan rests on six design decisions taken before this document was written, with the
maintainer:

1. **Class structure: shared.** `InqBulkhead<A,R>` will additionally implement
   `InqAsyncDecorator<A,R>`. One class, both paths. Mirrors the legacy
   `ImperativeBulkhead` shape and keeps a bulkhead's identity unitary in the runtime
   container — one name, one component, one set of listeners, one strategy instance
   shared by both paths.

2. **Permit semantics: acquire sync, release async.** Identical to the legacy class and
   to ADR-020's specification. The caller of `executeAsync(...)` either gets back a
   `CompletionStage` (acquire succeeded, downstream started) or sees an
   `InqBulkheadFullException` thrown synchronously (back-pressure semantics; the failure
   surfaces before any async work begins).

3. **Lifecycle trigger: unchanged.** `executeAsync(...)` is a synchronous method call
   that returns an eager-started `CompletionStage`. The cold-to-hot trigger fires on
   that method call, exactly as for sync `execute(...)`. ADR-029's existing CAS pattern
   in `ImperativeLifecyclePhasedComponent` is sufficient. The TODO entry's worry about
   "deferred-CAS" turned out to be a misalignment between `CompletionStage` (eager-by-
   default) and reactive `Mono` (lazy-by-default) semantics; under
   `CompletionStage` semantics, no deferral is involved.

4. **Strategy hot-swap under in-flight async: existing veto logic suffices.**
   `concurrentCalls()` increments on acquire and decrements on the release callback,
   regardless of which thread the callback runs on. ADR-032's `concurrentCalls() > 0
   → veto` rule applies uniformly to async-in-flight calls. No new veto code is needed.

5. **No new ADR.** The async path is the existing sync path plus a second method
   signature on the same class. ADR-020 already specifies the async semantics; we patch
   it where it currently still names the legacy `ImperativeBulkhead` as the
   implementation. ADR-029 gets a small note that its trigger applies to async too. If
   an unforeseen design conflict surfaces during implementation that warrants its own
   ADR, this plan upgrades — but the default is patches.

6. **Audit-2.18 and audit-2.19 follow-ups land here.** The three pipeline-integration
   bugs the TODO entry lists (HybridAspectPipelineTerminal CCE, AsyncElementLayerProvider
   compile-time rejection, InqShieldAspect async-dispatch CCE) all dissolve once
   `InqBulkhead implements InqAsyncDecorator`. We add regression tests pinning each
   site so the fix is structurally locked in.

7. **Legacy `ImperativeBulkhead` removal is OUT of scope.** Its removal becomes
   technically possible after this refactor, but the work of removing it (DSL paths,
   tests, downstream callers) is mechanical cleanup that belongs to a separate refactor
   cycle. This plan creates the precondition; another plan executes the removal.

---

## Sub-steps

The work splits into four sub-steps. Each ends in a coherent state where `mvn verify`
is green.

### 3.A: Implement `InqAsyncDecorator` on `InqBulkhead`, with `executeAsync(...)`

Carries the core implementation. After this sub-step, `InqBulkhead` accepts both
contracts at the type level and the runtime behaviour of the async path is correct,
but pipeline-integration consumers have not yet been adapted.

**Tasks:**

1. Declare `InqAsyncDecorator<A,R>` on `InqBulkhead<A,R>`'s implements list. Add the
   imports.
2. Implement `executeAsync(long chainId, long callId, A argument,
   InternalAsyncExecutor<A,R> next)` on `InqBulkhead`. The method delegates through to
   the hot phase via the lifecycle base class's existing dispatch mechanism — analogous
   to how `execute(...)` works today. The hot phase carries the actual logic.
3. Add `executeAsync(...)` to `BulkheadHotPhase` mirroring the existing `execute(...)`.
   The two-phase shape:
    - **Start phase** (sync, on caller thread): event setup if traced;
      `strategy.tryAcquire(maxWaitDuration)`; on rejection throw
      `InqBulkheadFullException` synchronously; on `InterruptedException` set the
      interrupt flag, throw `InqBulkheadInterruptedException`; on success publish the
      acquire event if configured.
    - **Downstream invocation:** call `next.executeAsync(...)`. If it throws
      synchronously during stage construction (rare but possible), release immediately
      and rethrow.
    - **End phase** (async, on stage-completion thread): attach a `whenComplete(...)`
      callback that releases the permit and publishes release/error events. Per ADR-023,
      return the *decorated copy* from `whenComplete(...)`, not the original stage.
      Fast-path optimization: if the returned stage is an already-completed
      `CompletableFuture`, run the release inline and return the original — no callback
      attachment necessary.
4. Remove the existing source-level TODO comment in `InqBulkhead.java` (line ~137) that
   flagged this gap. Replace it with a JavaDoc paragraph describing the async path,
   matching the style of the existing class JavaDoc.
5. Audit `InqAsyncDecorator`'s default methods (`decorateAsyncRunnable`,
   `decorateAsyncSupplier`, `decorateAsyncFunction`, etc., if they exist analogous to
   `InqDecorator`). Confirm they work transparently against an `InqBulkhead` that now
   implements the contract — they should, but the `<A,R>` parameter shape needs to be
   verified for the runtime-served path where both reduce to `Object`.

**What 3.A does NOT do:**
- Does NOT change `BulkheadStrategyFactory` or any strategy implementation. The
  strategies are paradigm-internal and indifferent to whether their permits are held by
  sync or async callers — `concurrentCalls()` accounting is identical either way.
- Does NOT touch `HybridAspectPipelineTerminal`, `AsyncElementLayerProvider`, or
  `InqShieldAspect`. Their cleanup is sub-step 3.B.
- Does NOT remove or modify the legacy `ImperativeBulkhead`.
- Does NOT add new fields to `BulkheadSnapshot` or `BulkheadPatch`. The async path
  reuses the existing snapshot fields.

**Tests landed in 3.A** (extending the existing test files for `InqBulkhead` /
`BulkheadHotPhase`):

- `executeAsync_acquires_synchronously_and_releases_on_stage_completion`
- `executeAsync_throws_synchronously_when_bulkhead_is_full`
- `executeAsync_releases_immediately_when_downstream_throws_during_stage_construction`
- `executeAsync_releases_on_failed_stage`
- `executeAsync_releases_on_cancelled_stage`
- `executeAsync_fast_path_returns_original_stage_when_already_completed`
- `executeAsync_publishes_acquire_and_release_events_when_traced`
- `concurrent_calls_count_matches_in_flight_async_acquires_minus_completions`
- `same_bulkhead_serves_both_sync_and_async_paths_through_one_strategy`

The last test is structurally important: it pins that an `InqBulkhead` mid-flight on
the sync path correctly accounts for an async acquire arriving on top, against the
same strategy. This is the property that justifies decision (1).

**Verification gates for 3.A:**
- `mvn verify` reactor green.
- A repository-wide grep confirms `InqBulkhead implements ... InqAsyncDecorator<A,R>`
  appears once on the class declaration.
- The source-level TODO comment in `InqBulkhead.java` is gone.
- New test count delta in the relevant test files matches the test list above.
- Pre-existing sync-path tests all still pass without modification.

### 3.B: Pin the audit-2.18 / audit-2.19 closures

The three audit findings the TODO entry references resolve structurally with 3.A.
This sub-step adds tests that pin each site, so the closure is locked in regardless
of future refactoring of those sites.

**Tasks:**

1. Add a regression test to `HybridAspectPipelineTerminal`'s test suite proving that
   constructing the terminal with a pipeline containing an `InqBulkhead` succeeds —
   the prior CCE on construction (audit 2.18 finding F-2.18-1) is gone. Cover both
   "pipeline contains only `InqBulkhead`" and "pipeline contains `InqBulkhead`
   alongside other elements" cases.
2. Add a compile-passing test (or, equivalently, a runtime test that exercises the
   path) for `AsyncElementLayerProvider` accepting an `InqBulkhead` (audit 2.18
   finding F-2.18-2). The fact that the code now compiles is a structural pin in its
   own right; a runtime test confirming the dispatch works through the provider is the
   behavioural pin.
3. Add a regression test to `InqShieldAspect`'s integration tests proving that an
   async-returning method intercepted with an `InqBulkhead` bean does NOT throw a
   `ClassCastException` at first invocation (audit 2.19 finding F-2.19-6). This is
   the user-visible failure mode the audit flagged.
4. Cross-check `inqudium-imperative`'s remaining audit-2.18 / audit-2.19 findings to
   confirm we have not missed a fourth site that the TODO entry under-counted. Report
   any additional sites found rather than fixing them silently.

**What 3.B does NOT do:**
- Does NOT modify `HybridAspectPipelineTerminal`, `AsyncElementLayerProvider`, or
  `InqShieldAspect` themselves. The fix is in 3.A; 3.B only adds tests.
- Does NOT introduce new test infrastructure. If a Spring Boot integration-test
  fixture is needed for the InqShieldAspect test, follow `CLAUDE.md`'s guidance on
  Spring Boot test isolation patterns.

**Verification gates for 3.B:**
- `mvn verify` reactor green.
- Each of the three audit findings (F-2.18-1, F-2.18-2, F-2.19-6) has a test method
  whose name or `@DisplayName` references the finding identifier so future readers can
  trace the pin.
- The sub-step report names any fourth site the cross-check surfaced.

### 3.C: Patch ADR-020 and ADR-029

ADR-020 currently specifies the async path with implementation references that name
the legacy `ImperativeBulkhead`. Update those references to point to the new
`InqBulkhead`. ADR-029 currently treats the cold-to-hot trigger as a sync-path concept;
add a small note that the trigger applies identically to the async path under
`CompletionStage` semantics, and reference the unified `InqBulkhead`.

**Tasks:**

1. **ADR-020:** Audit every reference to `ImperativeBulkhead` in the document. For
   each, decide:
    - Reference belongs to historical context (e.g. "the legacy implementation was…"):
      keep, possibly with a clarifying "(deprecated)" annotation.
    - Reference describes the *current* implementation: replace with `InqBulkhead`.
    - Reference describes the async-path implementation specifically: replace with
      `InqBulkhead` plus a sentence noting that the async path is now part of the same
      class.
2. **ADR-029:** Find the section discussing the cold-to-hot trigger ("first execute
   call"). Add a short paragraph clarifying that the trigger applies to
   `executeAsync(...)` invocations too — `CompletionStage`-returning calls are eager
   under our usage, so the method-call moment IS the trigger moment, identical to
   sync. If the document has a "per-paradigm differences" section, ensure the
   imperative-paradigm column does not imply async is missing.
3. Self-consistency sweep on both ADRs after the edits — same protocol as 1.B and 2.B.
   Drift unrelated to this refactor: pause and report, do not fix inline.

**What 3.C does NOT do:**
- Does NOT touch other ADRs unless a cross-reference from ADR-020 or ADR-029 has
  drifted. In that case: pause and report.
- Does NOT add an ADR. We decided against a new ADR; the patches reuse the existing
  homes.

**Verification gates for 3.C:**
- `mvn verify` reactor green (sanity check; doc-only changes).
- `grep -n "ImperativeBulkhead" docs/adr/020-*.md` returns only references that are
  intentionally about the legacy class.
- Both ADRs parse as well-formed Markdown.

### 3.D: Close the TODO entry

**Tasks:**

1. Delete the "Asynchronous variant of `InqBulkhead` is not implemented" entry from
   `TODO.md`, including its trailing `---` separator (same protocol as 1.B and 2.B).
2. Verify the resulting `TODO.md` structure: preamble unchanged, the user-guide entry
   becomes the only remaining item.

**Verification gates for 3.D:**
- `grep -n "Asynchronous variant" TODO.md` returns no matches.
- `grep -c "^---$" TODO.md` returns the expected count (one less than before).

---

## Sequencing rationale

3.A → 3.B → 3.C → 3.D is the natural order: the implementation lands first
(behaviour change), the audit-finding pins follow (regression protection), the
documentation catches up (so reading the ADRs reflects reality), and only then does
the TODO closure happen (so an interrupted refactor leaves the TODO open as a
recovery anchor).

3.A and 3.B together produce the meaningful behaviour change. 3.C and 3.D together
produce the closure.

Pause-points between sub-steps are review boundaries, as in the previous refactors.

---

## Risk register

The plan's main load-bearing assumption is that decision (3) — async lifecycle uses
the existing sync trigger — is correct. If during 3.A's implementation it turns out
that the existing trigger has a hidden assumption that breaks under async (for
example, a `PostCommitInitializable` that assumes the post-commit thread is the
caller's thread, which would not hold if a stage completion fires the trigger…
though decision 3 says the trigger fires on the method call, before the stage
completes, so this should not arise) — pause and reroute through a brief design
discussion before continuing.

The audit-2.18 / 2.19 cross-check in 3.B may surface a fourth site nobody currently
tracks. If so, the implementation session reports it in the sub-step write-up. The
review session decides whether the fourth site is in scope (add a test) or out of
scope (route to TODO.md or a follow-up audit).

The legacy `ImperativeBulkhead` continues to exist in parallel. Until it is removed
(separate future refactor), the codebase has two bulkhead implementations with
overlapping behaviour. This is acceptable because removal preconditions did not
exist before this refactor; now they do, and removal becomes a normal cleanup task
that can be scheduled when convenient.

---

## Completion log

- [ ] 3.A — InqBulkhead implements InqAsyncDecorator with executeAsync
- [ ] 3.B — Audit-2.18 and audit-2.19 regression pins
- [ ] 3.C — ADR-020 and ADR-029 patches
- [ ] 3.D — TODO entry closure

---

## Document lifecycle

This document lives at the repository root for the duration of the async-bulkhead
refactor. When all four sub-steps are complete and signed off, this document is
deleted along with its sibling `REFACTORING.md` (if one exists) at refactor end.
The audit trail at that point lives in the Git history, the closed PRs, the
patched ADRs, and the closed TODO.md entry.