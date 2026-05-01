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
