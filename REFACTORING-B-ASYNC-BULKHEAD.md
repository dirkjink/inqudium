# REFACTORING-B — Asynchronous variant of `InqBulkhead`

Introduce the asynchronous variant of `InqBulkhead` so that the new
post-ADR-033 architecture covers both call styles. The synchronous form
already exists and is correct; the async form is missing entirely, and
its absence pins three audit findings (F-2.18-1, F-2.18-2, F-2.19-6) and
keeps the deprecated `Bulkhead` / `ImperativeBulkhead` pair as the only
working migration path for async-returning intercepted methods.

This refactor is substantial enough to warrant its own ADR. The cold/hot
lifecycle under deferred subscription is not the same problem the
synchronous form solved — the reactive paradigm's deferred-CAS pattern
(ADR-029) is the closer reference. That design must be settled in the
ADR before implementation begins.

## What this refactor does NOT do

- Does **not** remove `Bulkhead` / `ImperativeBulkhead`. Removal happens
  in a separate refactor cycle once the async variant has shipped and a
  deprecation window has elapsed.
- Does **not** add an async variant to other elements (CB, RT, RL, TL,
  TS). Each pattern's async path is its own decision.
- Does **not** introduce async support in other paradigms (reactive,
  RxJava, coroutines have native async forms already).
- Does **not** revise unrelated ADRs (REFACTORING-C handles the broader
  ADR audit).

---

## Sub-steps

### B.1 — ADR draft for the async `InqBulkhead`

**Goal.** A new ADR (next free number, expected `ADR-034` — confirm
against `docs/adr/` before assignment) that specifies:

- the `InqAsyncDecorator<Void, Object>` contract surface that the async
  bulkhead implements,
- the cold-to-hot transition semantics under deferred subscription
  (when does the cold→hot CAS fire if the first call is a
  `CompletionStage` chain that has not yet been subscribed to?),
- the two-phase around-advice contract: acquire on entry, release on
  stage completion (success or failure) rather than in a synchronous
  `finally`,
- the interaction with the existing veto chain and snapshot/patch
  lifecycle from ADR-025/-026/-028/-029/-032/-033,
- the relationship to the synchronous `InqBulkhead` (separate type vs.
  same type with two surfaces — decision required and recorded),
- the migration path from the deprecated `ImperativeBulkhead.executeAsync`.

**Tasks.**

1. Read ADR-020 (synchronous bulkhead), ADR-029 (reactive deferred CAS),
   the `InqAsyncDecorator` source, and the existing
   `ImperativeBulkhead.executeAsync` implementation.
2. Draft the ADR following the Status / Context / Decision /
   Consequences format used elsewhere in `docs/adr/`.
3. Sketch the public type shape (without yet implementing it). Decide
   the type identity question explicitly: is it `InqAsyncBulkhead` (new
   type) or does `InqBulkhead` gain a second contract (composite)?
4. Open a PR with the ADR alone; collect maintainer review before any
   code work begins.

**Out of scope.** Any code in `inqudium-imperative` or `inqudium-core`.
Tests. The aspect integration.

**Verification gates.**

- ADR file exists under `docs/adr/<NNN>-async-bulkhead.md` with all
  required sections filled.
- `mvn verify` green (no code change should mean no test impact).
- The ADR addresses each of the three audit findings (F-2.18-1,
  F-2.18-2, F-2.19-6) and states whether each is resolved by the async
  variant directly or requires an additional change.
- Cross-references to ADR-020, ADR-025, ADR-029 are present.

**Report-form expectations.** Implementation session reports: ADR
number assigned, the two key design decisions made (cold/hot trigger
semantics, type identity), and any open question that warrants
maintainer input before B.2 starts.

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### B.2 — Async surface in `inqudium-core`

**Goal.** The contracts and config surfaces in `inqudium-core` that the
async variant needs. No `inqudium-imperative` work yet.

**Tasks.** *(to be detailed after B.1 — the ADR's type-identity
decision determines whether this step adds a new contract type, extends
an existing one, or is empty if the existing `InqAsyncDecorator` already
suffices.)*

**Out of scope.** Anything paradigm-specific.

**Verification gates.**

- `mvn -pl inqudium-core test` green.
- New types are pure — no `Thread.sleep`, no blocking, no schedulers
  (CLAUDE.md core-purity rule).
- Test-count delta in `inqudium-core` matches the new contract surface
  (typically a small number — contract tests, not behaviour tests).

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### B.3 — Async implementation in `inqudium-imperative`

**Goal.** Materialize the async bulkhead in the imperative module per
the B.1 ADR and the B.2 contracts. Cold/hot lifecycle, two-phase
around-advice with stage-completion release, snapshot/patch wiring.

**Tasks.** *(to be detailed after B.1.)*

**Out of scope.** Aspect-module integration (B.4). Audit-finding
closure tests (B.5).

**Verification gates.**

- `mvn -pl inqudium-imperative -am verify` green.
- Behaviour tests cover: happy path, failure path with stage-completion
  release, veto chain interaction with concurrent calls in flight,
  cold→hot transition under deferred subscription, snapshot patch with
  in-flight async calls.
- Determinism: tests inject `InqNanoTimeSource` / `InqClock` from
  atomic refs (CLAUDE.md). No `Thread.sleep`.
- No `synchronized` introduced (CLAUDE.md ADR-008 constraint).

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### B.4 — Aspect-module integration

**Goal.** Update the three sites pinned by audits 2.18 and 2.19 so that
the async bulkhead works through the aspect integration:

- `HybridAspectPipelineTerminal.of(InqPipeline)` — eager validation
  accepts an async-capable bulkhead. Decide whether to keep eager
  validation (now satisfiable) or relax to lazy (separate question
  raised in audit 2.18).
- `AsyncElementLayerProvider` — type bound `<E extends InqElement &
  InqAsyncDecorator<Void, Object>>` accepts the new async bulkhead.
- `InqShieldAspect` async-method dispatch — the `InqAsyncDecorator`
  cast no longer fails for the new bulkhead.

**Tasks.** *(to be detailed after B.1/B.2.)*

**Out of scope.** Touching the synchronous dispatch path. Removing the
deprecated bulkhead bridge.

**Verification gates.**

- `mvn -pl inqudium-aspect-integration-tests -am verify` green.
- Three integration tests pin each previously-failing site against the
  new bulkhead.
- The deprecated `Bulkhead` continues to work through the aspect path
  (no regression on the migration bridge).

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### B.5 — Audit findings closure and TODO entry removal

**Goal.** Close audit findings F-2.18-1, F-2.18-2, F-2.19-6 with the
tests added in B.4 named explicitly as the pin. Remove the
"Asynchronous variant of `InqBulkhead` is not implemented" entry from
`TODO.md`.

**Tasks.**

1. Update each audit report (or its current resting place) noting the
   closing test name and the commit that introduced it. If the audit
   reports were already deleted at Phase 2 closure, this step is a
   note in the PR description only.
2. Remove the TODO entry. Note the audit-2.18/2.19 consequence
   subsection went with it — confirm those consequences are genuinely
   closed (not adjacent-pinned).
3. Update the code-level TODO comment at `InqBulkhead.java:137` —
   delete it now that the async variant exists.

**Out of scope.** Removing the deprecated `Bulkhead` /
`ImperativeBulkhead`. That is a separate decision.

**Verification gates.**

- `mvn verify` green.
- `grep -r "InqAsyncDecorator" inqudium-aspect inqudium-spring` shows
  no remaining unresolved cast or type-bound issues.
- `TODO.md` no longer contains the async-variant entry.
- `InqBulkhead.java` no longer contains the async-variant TODO comment.

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

## Closure

When all sub-steps are merged, this document is deleted. The async
variant is documented in:

- the new ADR (permanent),
- ADR-020 (updated cross-reference, falls under REFACTORING-C if not
  already done),
- the user guide (a new section to be added — task tracked as a B.5
  follow-up if not absorbed into B.4),
- the tests as the behavioural pin.

## Phase B completion log

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <phase-level milestone> -->