# REFACTORING-C — ADR audit and module-structure cleanup

A single coherent pass over the architecture documents and the matching
build/module structure. Three TODO entries are settled together because
they need a consistent voice across the ADRs and because two of them
share an underlying decision (registry semantics) that is also the
prerequisite for closing a fourth Spring-aspect entry.

This refactor presupposes REFACTORING-A (small bulkhead fixes) is
done. It does **not** require REFACTORING-B (async variant) to be done
first, but should not start until B.1 (the async ADR draft) is at
least settled — REFACTORING-C touches ADR-020 and the async variant's
ADR will cross-reference it, so the rewrite needs to know what the
async ADR will say.

## What this refactor does NOT do

- Does **not** touch ADRs unrelated to the four TODO entries below.
  Other ADR-drift uncovered during the audit goes into a follow-up
  audit report under `docs/audit/`, not into scope creep.
- Does **not** introduce new resilience patterns or paradigms.
- Does **not** rewrite ADRs that the code has not yet stabilized
  around (the project is pre-alpha; some ADRs are intentionally
  ahead of the implementation and shouldn't be rewritten backwards).

---

## Sub-steps

### C.1 — ADR-020 Configuration and Registry sections rewrite

**Source TODO entry:** `TODO.md` → "ADR-020 Configuration and Registry
sections describe pre-Phase-1 architecture".

**Goal.** Rewrite the two affected sections of ADR-020 against the
post-Phase-2 architecture. Other sections of ADR-020 (strategy
contract, exception-optimization performance discussion) survive
intact.

**Tasks.**

1. Read the current `BulkheadSnapshot`, the `Inqudium.configure()...
   imperative(...)` DSL chain, and the `Imperative.bulkhead(name)`
   runtime lookup. Confirm what "the configuration surface today
   actually is" before writing.
2. Rewrite the Configuration section: snapshots, patches, the DSL,
   strategy DSL helpers (`semaphore(...)`, `codel(...)`,
   `adaptive(...)`).
3. Rewrite the Registry section: runtime registry, first-registration-
   wins semantics, `Imperative.bulkhead(name)` lookup. Cross-reference
   the registry-replacement decision from C.3 if it lands first.
4. Add cross-references to ADR-025/-026/-028/-029/-032/-033 where
   they add detail.
5. Confirm the strategy-contract and exception-optimization sections
   still match the code; if they have drifted, flag (do not silently
   fix in this sub-step — route to a follow-up).

**Out of scope.** Rewriting other ADRs. Adding new ADR sections
(this is a fix-in-place, not an extension).

**Verification gates.**

- `mvn verify` green (no code change expected).
- The rewritten sections contain no references to deprecated types
  (`InqBulkheadConfig`, the standalone-registry approach).
- Code samples in the rewritten sections compile against current
  code (manual check or copy-and-paste into a scratch test).

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### C.2 — SLF4J relocation: `inqudium-core` becomes JDK-only

**Source TODO entries:** `TODO.md` →
- "`inqudium-core` declares SLF4J as a compile-scope dependency",
- "`inqudium-context-slf4j` is effectively empty".

**Decision recorded.** Variant (a) from the TODO entry: move the
SLF4J bridge classes to `inqudium-context-slf4j`, leave only the
`LoggerFactory` abstraction in `inqudium-core`. This satisfies the
"JDK-only core" architectural commitment in `CLAUDE.md` and ADR-005,
and it gives `inqudium-context-slf4j` its first real content.

**Tasks.**

1. Move `eu.inqudium.core.log.Slf4jLoggerFactory` and the
   `Slf4j*Action` helpers from `inqudium-core` to
   `inqudium-context-slf4j`. Adjust package to
   `eu.inqudium.context.slf4j` per the project's package-naming rule
   in `CLAUDE.md`.
2. In `inqudium-core/pom.xml`: remove `org.slf4j:slf4j-api` and
   `ch.qos.logback:logback-classic`.
3. In `inqudium-context-slf4j/pom.xml`: add `slf4j-api` at compile
   scope, `logback-classic` at provided/test scope as appropriate.
4. Update SPI registration files (`META-INF/services/...`) to reflect
   the new locations.
5. Confirm no other module (`inqudium-imperative`, etc.) was relying
   on the transitive SLF4J pull-through. If yes, add explicit
   compile/provided dependencies where they belong.
6. Update `CLAUDE.md` if any wording about `inqudium-core`'s
   dependencies needs sharpening — likely already accurate ("JDK-only").

**Out of scope.** Adding new logging framework adapters. Changing the
`LoggerFactory` abstraction itself.

**Verification gates.**

- `mvn verify` green.
- `mvn -pl inqudium-core dependency:tree` shows no SLF4J in scope.
- `grep -r "org.slf4j" inqudium-core/src` returns nothing.
- The Slf4j bridge classes work end-to-end via integration test
  (`inqudium-context-slf4j` should grow such a test if it lacks one).
- Test-count delta: net positive (new bridge tests in
  `inqudium-context-slf4j`, possible deletions in `inqudium-core`
  for tests that move with the classes).

**Report-form expectations.** Implementation session reports the
moved classes, the consumer modules that needed explicit
dependencies, and any module whose dependency-tree changed
unexpectedly.

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### C.3 — Registry-replacement semantics decision

**Source TODO entry:** `TODO.md` → "Spring aspect cache is not
invalidated when an `InqElement` bean is replaced" — the underlying
architectural question.

**Goal.** Decide whether element-replacement-via-registry is a
supported operation in Inqudium's architecture, and if so through
which API. Record the decision as a new ADR or as an extension to
ADR-015 (which currently describes the registry without a replacement
contract).

**Tasks.**

1. Survey all places in the codebase that read elements from the
   registry: aspect module, Spring integration, user-guide examples,
   tests.
2. Survey the implications of each direction:
    - **Replacement supported** → registry exposes a change-listener
      API, the snapshot/listener pattern from the bulkhead runtime
      extends to element identity, the aspect cache becomes a
      listener subscriber.
    - **Replacement unsupported** → first-registration-wins is
      elevated to a documented contract, the user-guide states so,
      and the aspect cache adds an identity-comparison guard with
      a one-time WARN log on mismatch.
3. Write the decision as ADR-extension or new ADR. Update
   `CLAUDE.md`'s "first-registration-wins registries" line to link
   to it.

**Out of scope.** Implementing the Spring aspect cache fix (C.4).
Touching the runtime registry's first-registration-wins behaviour
itself (the decision documents the existing behaviour formally; it
does not change it).

**Verification gates.**

- New or updated ADR exists.
- `CLAUDE.md` cross-references the ADR.
- `mvn verify` green (decision step, no behaviour change).

**Report-form expectations.** Implementation session reports the
decision direction taken and which sites in the survey are affected
by the implementation step (C.4).

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

### C.4 — Spring aspect cache invalidation per the C.3 decision

**Source TODO entry:** `TODO.md` → "Spring aspect cache is not
invalidated when an `InqElement` bean is replaced" — the
implementation step.

**Goal.** `InqShieldAspect`'s per-method element cache behaves
correctly under whatever replacement contract C.3 settled on.

**Tasks.** *(specific tasks depend on C.3's outcome — both
directions are sketched below)*

If C.3 decided **replacement supported**:
1. Subscribe `InqShieldAspect` to the registry change-listener API.
2. On listener fire, invalidate the affected cache entries.
3. Concurrency: invalidation must be safe under concurrent intercept
   calls. The cache is per-method, so per-entry CAS or volatile
   replacement is the simplest pattern.
4. Test: replace an `InqElement` bean, intercept the same method
   again, observe the new element wired through.

If C.3 decided **replacement unsupported**:
1. On cache hit, compare the cached element's identity against the
   current registry lookup as a guard. Mismatch → one-time WARN
   log + use the new lookup result for this call.
2. Update the user-guide to state explicitly that bean replacement
   after first interception is undefined behaviour.
3. Test: bean replacement triggers the WARN log; subsequent calls
   use the new element (per the lenient-recovery shape of the
   guard).

**Out of scope.** Reworking the cache architecture beyond the C.3
contract. Adding caches to other paradigms' aspect equivalents.

**Verification gates.**

- `mvn -pl inqudium-spring -am verify` green.
- Test pins the chosen behaviour from C.3.
- TODO entry removed.

**Completion log.**

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <topic> -->

---

## Closure

When all four sub-steps are merged and their TODO.md entries removed,
this document is deleted. Permanent destinations:

- ADR-020 (rewritten sections, permanent),
- ADR for registry-replacement semantics (permanent),
- `inqudium-context-slf4j` (real content, permanent),
- `CLAUDE.md` updates (permanent),
- the new tests as the behavioural pin.

If the audit surfaces drift in ADRs not listed here, the finding goes
to a `docs/audit/c-adr-audit-followup.md` report, not into scope
creep on this refactor.

## Phase C completion log

<!-- Entries: - [x] YYYY-MM-DD HH:MM — <phase-level milestone> -->