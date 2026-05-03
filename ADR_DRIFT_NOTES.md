# ADR_DRIFT_NOTES.md

Working notes for the future ADR-audit refactor. This file is a collection
point for ADR drift observations surfaced during other refactor work — places
where an ADR's text has fallen out of sync with the current code, with
another ADR, or with itself. The observations are recorded here rather than
fixed inline because:

- ADR↔ADR drift is suspected: fixing one ADR in isolation may worsen
  inconsistency with another ADR that has drifted in a different direction.
- A coherent fix requires reading all ADRs cross-referentially in one pass,
  not piecewise during unrelated work.

Each entry names the ADR, the location, and the observed drift. Entries are
NOT actionable individually — they are inputs to the planning document of
the future ADR-audit refactor (`REFACTORING_ADR_AUDIT.md` when that work
starts).

## Lifecycle

This file lives at the repository root until the ADR-audit refactor begins.
At that point its content becomes input to the audit's planning document
and the file is deleted. New entries are appended at the bottom of the
"Observations" section as drift surfaces during ongoing work.

## Observations

### ADR-032 — stale cross-reference in implementation-plan narrative

**Location:** `docs/adr/032-bulkhead-strategy-hot-swap.md`, around line 446
(the bullet under "Implementation notes").

**The drift:** The bullet ends with "Sub-step C closes that." but sub-step C
in the original implementation plan was "DSL sub-builders"; the hot-swap
path was actually wired in sub-step D. Stale cross-reference.

**Surfaced in:** Sub-step 1.B review (PR closing the same-type strategy
rebuild bug fix).

**Severity:** Cosmetic — the implementation-plan narrative is historical at
this point and does not drive any current decision. But it misleads a reader
trying to trace the implementation history through the ADR.

---

### ADR-029 — duplication between Snapshot subscription section and concrete-component example

**Location:** `docs/adr/029-component-lifecycle-implementation-pattern.md`,
around line 361+ (the "Snapshot subscription" section).

**The drift:** After sub-step 3.C's follow-up patches updated the concrete-
component example to match current code, the `afterCommit(...)` hook is now
shown twice — once on the hot-phase side in the example, once on the cold-
phase side in the Snapshot subscription section. Not factually wrong, but
mildly redundant.

**Surfaced in:** Sub-step 3.C follow-up patches.

**Severity:** Style — not a correctness issue, but the document reads as if
two sections describe the same hook from slightly different angles without
acknowledging that they do.

---

### ADR-020 — Configuration and Registry sections describe pre-Phase-1 architecture

**Location:** `docs/adr/020-bulkhead-design.md`, sections "Configuration"
and "Registry".

**The drift:** Both sections describe the legacy `InqBulkheadConfig` record
and the original builder DSL / standalone-registry approach. Both pre-date
Phase 1 and the snapshot/patch architecture (ADR-025 onwards). The
bulkhead's actual configuration surface today is `BulkheadSnapshot` plus
`Inqudium.configure()...imperative(...)`; registry semantics moved to
`Imperative.bulkhead(name)` lookup on the runtime.

**Surfaced in:** TODO.md entry "ADR-020 Configuration and Registry sections
describe pre-Phase-1 architecture" (pre-existed this notes file).

**Severity:** Substantive — readers consulting ADR-020 for the canonical
bulkhead-pattern reference get a description that matches removed or
deprecated code. The "what really runs" is documented across ADR-025/-026/
-028/-029/-032/-033, but ADR-020 still presents the pre-refactor model as
if current.

**Cross-references for the audit:** When fixing ADR-020 Configuration and
Registry, ensure the rewritten sections do not contradict ADR-025 (config
architecture), ADR-026 (runtime and registry), or ADR-033 (type-parameter
migration affecting the imperative bulkhead's signature).

---

### `inqudium-core` SLF4J dependency contradicts CLAUDE.md / ADR-005 "JDK-only" framing

**Location:** `inqudium-core/pom.xml` declares `org.slf4j:slf4j-api` at
`<scope>compile</scope>`. CLAUDE.md and ADR-005 describe `inqudium-core` as
JDK-only.

**The drift:** Module-graph reality contradicts documented architectural
commitment. SLF4J is transitively pulled into every consumer of
`inqudium-core` whether they want logging or not.

**Surfaced in:** TODO.md entry "`inqudium-core` declares SLF4J as a
compile-scope dependency" (pre-existed this notes file).

**Severity:** Substantive — the architectural commitment ("a resilience
library that does not impose a logging framework") is silently broken.

**Companion to:** The empty `inqudium-context-slf4j` module (next entry).
Same architectural decision settles both.

---

### `inqudium-context-slf4j` is effectively empty

**Location:** `inqudium-context-slf4j/` — module exists in the multi-module
build but contains no Java sources, only an SPI marker file.

**The drift:** The module's intended role is unclear from the code. Likely
intended as the home for the SLF4J bridge code that currently lives in
`inqudium-core` (companion entry above).

**Surfaced in:** TODO.md entry "`inqudium-context-slf4j` is effectively
empty" (pre-existed this notes file).

**Severity:** Substantive — either a stub waiting for content or a leftover
to be deleted. Either way confusing for readers of the build structure.

**Companion to:** The SLF4J-in-core entry above. The same decision settles
both: either `inqudium-core` becomes truly JDK-only and this module gains
the bridge code, or `inqudium-core` keeps SLF4J and this module is deleted.

---

### `InqShieldAspect` per-method element cache has no invalidation hook

**Location:** `inqudium-spring/src/main/java/eu/inqudium/spring/InqShieldAspect.java`.

**The drift:** Architectural — not an ADR drift in the strict sense, but
related: the registry-semantics question ("is `InqElement` bean replacement
a supported operation?") is not answered by any current ADR. The aspect's
cache assumes stable bean topology; the registry's first-registration-wins
principle (CLAUDE.md) is consistent with that, but neither documents
replacement explicitly.

**Surfaced in:** TODO.md entry "Spring aspect cache is not invalidated when
an `InqElement` bean is replaced" (pre-existed this notes file).

**Severity:** Latent bug — silent staleness if a user does replace a bean.
Not on the critical path; library is pre-alpha.

**For the audit:** Decide what registry replacement semantics are. Then the
aspect's cache treatment follows: either listener-based invalidation (if
replacement is supported), or documented undefined-behaviour-on-replacement
(if not).

---

### Naming cleanup for proxy factory classes

**Location:** `inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/InqAsyncProxyFactory.java`
(and its sync sibling `inqudium-core/.../InqProxyFactory.java`).

**The drift:** After the proxy-consolidation refactor (PR series ending in
PR #49), the public proxy-factory API is `InqProxyFactory` (sync) and
`InqAsyncProxyFactory` (hybrid sync+async). The "Async" name is a historical
artifact: the class supports hybrid dispatch (sync methods route through one
extension, async methods through another), not async-only behavior. A future
refactor could rename it to `InqHybridProxyFactory` or similar to make the
hybrid nature explicit. This is a user-facing rename with no behavioral
impact; it requires updating consumers (which now exist) and is therefore
its own follow-up.

**Surfaced in:** REFACTORING_PROXY_CONSOLIDATION.md, "What this refactor
does NOT do" → "Naming cleanup".

**Severity:** Cosmetic / API ergonomics — no correctness impact, but the
class name misleads readers about the dispatch model.

---

### Unified introspection API across proxy / aspect-based mechanisms

**Location:** `inqudium-core/src/main/java/eu/inqudium/core/pipeline/Wrapper.java`
(proxy side) and `inqudium-spring/src/main/java/eu/inqudium/spring/InqShieldAspect.java`'s
`ResolvedPipelineState` (aspect side, from refactor 6.B).

**The drift:** After the proxy-consolidation refactor (PR series ending in
PR #49), every proxy produced by the JDK-dynamic-proxy machinery is
`Wrapper`-conforming with stable `chainId` and `layerDescription`. The
remaining divergence is between proxy-based introspection (`Wrapper`) and
aspect-based introspection (`InqShieldAspect`'s `ResolvedPipelineState`
from 6.B). A future refactor could harmonize the two surfaces into a
technology-spanning API — perhaps a thin wrapper type that both
proxy-`Wrapper` and aspect-`ResolvedPipelineState` adapt to, exposing
`chainId` and the topology in a uniform shape.

**Surfaced in:** REFACTORING_PROXY_CONSOLIDATION.md, "What this refactor
does NOT do" → "Unified introspection API".

**Severity:** Substantive — diagnostics consumers (the topology log lines
6.D introduces, future Micrometer / JFR bridges) currently need two code
paths to introspect a chain, depending on whether the chain was assembled
proxy-side or aspect-side. A unified surface would let those consumers
work uniformly.
