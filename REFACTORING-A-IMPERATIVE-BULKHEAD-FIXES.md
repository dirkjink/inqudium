# REFACTORING-A — Imperative bulkhead correctness fixes

Two narrowly-scoped correctness gaps in the imperative bulkhead's lifecycle
plumbing. Both are paradigm-internal (no new public API, no ADR), both have
clear fix shapes pre-specified in `TODO.md`, both ship with the tests that
should pin them.

This refactor exists because the gaps are real (silent no-op in one case,
unspecified semantics in the other) and because closing them before
REFACTORING-B (async variant) means the async path inherits a clean veto
chain and a complete strategy hot-swap surface.

## What this refactor does NOT do

- Does **not** introduce the asynchronous `InqBulkhead` variant
  (REFACTORING-B).
- Does **not** revise ADR-020 or any other ADR (REFACTORING-C).
- Does **not** extend the same fix to other paradigms (reactive, RxJava,
  coroutines). Each paradigm will adopt the same pattern when its own
  hot-phase work happens.
- Does **not** touch the deprecated `Bulkhead` / `ImperativeBulkhead` pair.

---

## Sub-steps

### A.1 — Same-type strategy rebuild on `BulkheadHotPhase`

**Source TODO entry:** `TODO.md` → "Strategy config tweaks without
strategy-type change".

**Goal.** A bulkhead patched with a new `BulkheadStrategyConfig` of the same
type but different field values must materialize the new fields. Today the
snapshot updates but the running strategy keeps its old config (silent
no-op).

**Tasks.**

1. Extend `BulkheadHotPhase` with a private field that remembers the
   `BulkheadStrategyConfig` from which the current `BulkheadStrategy` was
   last materialized.
2. In `strategyChanged(BulkheadSnapshot latest)`, after the existing
   type-identity check, compare the latest config against the remembered
   one with record `equals(...)`. Return `true` whenever the records
   differ.
3. In `swapStrategy(...)` (or the path that materializes a new strategy),
   update the remembered field on every successful swap or rebuild.
4. Confirm `BulkheadStrategyFactory.create(...)` already produces a fresh
   strategy from the latest snapshot regardless of type vs. field
   change — no factory change required.
5. The veto chain (`evaluate(...)` precondition `concurrentCalls() > 0`)
   already covers same-type rebuilds. Confirm by reading; do not modify.

**Out of scope.** Exposing the running strategy's config through a public
accessor. Removing the type-identity branch (it remains the fast path).
Touching the cold phase or the snapshot factory.

**Verification gates.**

- `mvn -pl inqudium-imperative -am verify` green.
- Four new tests in the imperative-bulkhead module covering:
    - CoDel(50ms,500ms) → CoDel(80ms,800ms) with zero in-flight calls:
      new field values observable in behaviour.
    - Same patch with one in-flight call: vetoed with
      `Source.COMPONENT_INTERNAL`, reason mentions concurrent count.
    - CoDel(50ms,500ms) → CoDel(50ms,500ms): no swap, `UNCHANGED` event,
      strategy reference unchanged.
    - Adaptive bulkhead patched with only the inner algorithm config
      changed (AIMD rates): strategy and algorithm rebuild.
- Test-count delta: +4 in `inqudium-imperative`. No deletions.
- Repository-wide grep: no new `TODO`/`FIXME` introduced.

**Report-form expectations.** Implementation session reports the touched
files, the test-count delta, and confirms which tests pin which scenario.
Flags any spec-vs-code surprise (e.g. a fifth strategy type that needed
similar handling) before silently extending scope.

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### A.2 — Listener throws → synthetic veto

**Source TODO entry:** `TODO.md` → "Listener that throws (instead of
returning a clean veto) — behaviour unspecified".

**Goal.** A listener registered via `bh.onChangeRequest(...)` that throws
a `RuntimeException` is treated as a veto with a synthetic reason naming
the thrown exception type. The patch is rejected for the affected
component but the rest of the cross-component update proceeds.

**Decision recorded.** Variant (b) from the TODO entry: synthetic veto.
Preserves the cross-component-atomicity contract from ADR-026; keeps the
bug visible (in `BuildReport.vetoFindings`); matches the
"listeners must provide a non-blank reason" discipline by synthesizing
one when the listener fails to.

**Tasks.**

1. In `UpdateDispatcher` (in `inqudium-config`) — wrap each listener
   invocation in a try/catch for `RuntimeException`.
2. On catch, synthesize a `ChangeDecision.veto(reason)` where the reason
   is `"listener " + listener.identifierOrName() + " threw " +
   ex.getClass().getSimpleName() + ": " + ex.getMessage()`.
3. Log the throw via `general().loggerFactory()` at `error` level with
   the original exception attached for stack trace.
4. The synthesized veto carries `Source.LISTENER` (existing source
   constant) so the `BuildReport.vetoFindings` entry routes consistently.

**Out of scope.** Catching `Error`/`Throwable` (only `RuntimeException`
per the contract — let `Error`s propagate). Adding a per-listener
toggle for the behaviour. Modifying the `ChangeDecision` record itself.

**Verification gates.**

- `mvn verify` green across the full reactor.
- Three new tests in `inqudium-config` covering:
    - Listener that throws → patch on its component reported as `VETOED`
      with `Source.LISTENER`, reason names exception type.
    - Multi-component update where only the listener-affected component is
      vetoed, others commit per cross-component-atomicity.
    - Throw is logged at `error` level (assert via captured logger
      factory).
- Test-count delta: +3 in `inqudium-config`.
- No change to existing veto-chain tests; if any break, the new
  semantics conflict with an unstated invariant — pause and report
  rather than silently update them.

**Report-form expectations.** Confirm whether existing veto-chain tests
needed adjustment. If yes, name each one and explain why the adjustment
is consistent with variant (b). Flag any paradigm-specific dispatcher
that does not route through `UpdateDispatcher` and would therefore
escape the new behaviour.

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

## Closure

When both sub-steps are merged and their TODO.md entries removed, this
document is deleted in a final closure commit. The audit trail at that
point lives in:

- the merged commits and PRs,
- the new tests as the behavioural pin,
- the absence of the corresponding entries from `TODO.md`.

No content from this document needs a permanent home in `CLAUDE.md` —
the conventions used here (remembered-config field, synthetic-veto
reason format) are paradigm-internal and self-documenting in the code.

## Phase A completion log

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <phase-level milestone> -->