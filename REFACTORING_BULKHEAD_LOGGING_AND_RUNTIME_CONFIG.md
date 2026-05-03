# REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md

Plan for extending all five bulkhead-integration example modules with
practice-grade logging and a runtime-configuration-change demonstration.
The current example modules (delivered in the 5.A–5.H refactor cycle)
demonstrate the bulkhead's wiring through five integration styles, but
they show none of the operational concerns that real applications care
about: visibility into what is protected by what, visibility into what
events the bulkhead is producing, and runtime control over the
bulkhead's parameters as conditions change.

This refactor adds three orthogonal concerns to every example module:

1. **Practice-grade logging.** SLF4J + Logback configured per module.
   Each `OrderService` logs its own topology at construction time,
   subscribes to its own bulkhead's events, and emits log records at
   appropriate levels (Trace for acquire/release, Warn for reject,
   Error for rollback). Lifecycle events from the runtime are logged
   in a Main-side bootstrap hook, not in the service.

2. **Runtime-configuration-change demonstration.** A new `AdminService`
   exposes two methods (`startSellPromotion`, `endSellPromotion`) that
   patch the bulkhead from `balanced().maxConcurrentCalls(2)` to
   `permissive().maxConcurrentCalls(50)` and back. Demonstrates that
   the bulkhead's parameters can be tuned at runtime through the
   normal `runtime.update(...)` API, observed live by the running
   service.

3. **Library-side fill-in: a Spring-AOP introspection API.** While
   writing the topology logging in the AspectJ example, the existing
   `AbstractPipelineAspect.inspectPipeline(...)` API is exactly what's
   needed. The Spring-AOP equivalent (`InqShieldAspect`) lacks this
   API today — there is no public method that returns a
   `JoinPointWrapper`-style introspection handle for an annotated
   method. Without this, the Spring-Boot and Spring-Framework
   examples cannot show their topology with the same fidelity as the
   other three. This refactor adds the missing API to `inqudium-spring`
   as sub-step 6.B, before the dependent example modules in 6.E and
   6.F.

The plan rests on the design decisions taken with the maintainer
before this document was written:

1. **Logging stack.** SLF4J as the API; Logback as the implementation.
   Each example module brings its own `logback.xml` in
   `src/main/resources/`. The configuration is identical across
   modules to make output comparable when reading them in sequence.

2. **Logger naming.** Per-class loggers via the standard SLF4J idiom
   `LoggerFactory.getLogger(ClassName.class)`. The same logger handles
   both regular service messages and event-subscriber messages —
   no sub-loggers like `OrderService.events`.

3. **Topology logging.** Logged in `OrderService` at construction
   time. One log line per service method (so a service with four
   `@InqBulkhead`-annotated methods produces four lines). The
   `chainId` is NOT included in the topology log — it surfaces in
   bulkhead-event log lines instead, where it is naturally available.
   This avoids the need for active wrapper materialization at
   construction time.

4. **Bulkhead-event logging.** Subscribed and logged inside
   `OrderService` itself. Levels:
   - `BulkheadOnAcquireEvent`, `BulkheadOnReleaseEvent` → TRACE
   - `BulkheadOnRejectEvent` → WARN
   - `BulkheadRollbackTraceEvent` → ERROR
   The `BulkheadEventConfig` in `BulkheadConfig.newRuntime()` is
   configured to emit all of these (the standard preset only emits
   rejection events; the example needs them all to make logging
   observable in a tutorial setting).

5. **Lifecycle-event logging.** Subscribed in a Main-side bootstrap
   hook, NOT in the service. Lifecycle events come from the runtime,
   not from any single component. Levels:
   - `ComponentBecameHotEvent` → INFO
   - `RuntimeComponentAddedEvent` → INFO
   - `RuntimeComponentPatchedEvent` → INFO
   - `RuntimeComponentRemovedEvent` → INFO
   - `RuntimeComponentVetoedEvent` → WARN

6. **AdminService.** A non-bulkhead-protected service in every example
   module. Constructor takes the `InqRuntime` (idiomatic per style:
   `@Autowired` in Spring/Spring-Boot, `@Bean`-constructor-injection
   in plain Spring, direct constructor argument in function-based
   and proxy and AspectJ). Two methods:
   - `startSellPromotion()` — patches `orderBh` to
     `permissive().maxConcurrentCalls(50)`.
   - `endSellPromotion()` — patches it back to
     `balanced().maxConcurrentCalls(2)`.

7. **Main-side demonstration.** Three demo phases per module:
   a. Normal operation (saturation at 2 permits, third call rejected).
   b. After `startSellPromotion()` (no rejection at 5 concurrent calls).
   c. After `endSellPromotion()` (saturation at 2 again, third call
      rejected).

8. **Tests.** Two new tests per module, in a new `@Nested` group
   `RuntimeConfigChange`:
   - Test 1: full cycle (saturated at 2 → start promotion → 5 calls
     succeed → end promotion → saturated at 2 again).
   - Test 2: a follow-up assertion that `availablePermits()` reflects
     the patch live (e.g. permits jump from 2 to 50 immediately, then
     back to 2).

9. **Library-side API extension (6.B).** `InqShieldAspect` gets two
   public methods, signatures parallel to `AbstractPipelineAspect`:
   - `inspectPipeline(...)` returning a `JoinPointWrapper`-style
     introspection handle (cold-path API).
   - `getResolvedPipeline(Method)` returning the cached
     `ResolvedPipeline` (hot-path API).
   Verify by code inspection whether the existing
   `AbstractPipelineAspect` signatures translate one-to-one to
   `InqShieldAspect`'s internal structure, or whether the cache key
   shape differs and forces a deviation. If signatures cannot match
   exactly, document the deviation in the report and proceed.

10. **Module sequencing.** Same as the 5.A–5.H cycle:
    function → proxy → aspectj → spring-framework → spring-boot.
    The library-side sub-step 6.B comes before any module that
    depends on it (6.E and 6.F use the new API).

## Sub-steps

### 6.A: Plan document at the repository root

This document.

### 6.B: Library-side InqShieldAspect introspection API

File: `inqudium-spring/src/main/java/.../InqShieldAspect.java`

Add `inspectPipeline(...)` and `getResolvedPipeline(Method)` analogous
to `AbstractPipelineAspect`. Method signatures should be exactly
parallel where structurally possible. Plus tests in `inqudium-spring`'s
test tree pinning the new methods' shape.

This is a Library extension, not an example-module change — it lives
in `inqudium-spring`. The Spring-Framework and Spring-Boot example
modules in 6.F and 6.G use the new API.

If the cache key shape in `InqShieldAspect` differs from
`AbstractPipelineAspect`'s such that signatures cannot match, document
the deviation with reasoning and proceed.

### 6.C: Function-based example — logging + AdminService + tests

Module: `inqudium-bulkhead-integration-function/`

Add:
- `src/main/resources/logback.xml` with a configuration shared across
  all five modules.
- Bulkhead-event subscription in `OrderService`'s constructor.
- Topology logging in `OrderService`'s constructor.
- `AdminService` with `startSellPromotion()` and
  `endSellPromotion()`.
- Bootstrap-side lifecycle-event subscription in `Main`.
- Three-phase demo in `Main`.
- Two new tests in `OrderServiceFunctionExampleTest` under a
  `RuntimeConfigChange` `@Nested` group.

### 6.D: Proxy-based example — logging + AdminService + tests

Module: `inqudium-bulkhead-integration-proxy/`

Same shape as 6.C, adapted to the proxy idiom (interface +
implementation pattern; `OrderService` interface unchanged, the
`DefaultOrderService` implementation gains the constructor arguments
needed for event subscription and topology logging).

### 6.E: AspectJ example — logging + AdminService + tests

Module: `inqudium-bulkhead-integration-aspectj/`

Same shape as 6.C, but the `OrderService` is woven via CTW. The event
subscription happens in `OrderBulkheadAspect`'s constructor (or the
service's, but the aspect already owns the runtime — the natural fit
is the aspect). Topology logging via the existing `inspectPipeline`
on `AbstractPipelineAspect`.

If the topology-log site naturally fits the aspect rather than the
service, document the deviation from decision 3 and proceed. The
AspectJ idiom may make "topology logged in the service" awkward
because the service has no reference to the aspect at construction
time.

### 6.F: Spring-Framework example — logging + AdminService + tests

Module: `inqudium-bulkhead-integration-spring-framework/`

Same shape as 6.C. Uses 6.B's new `InqShieldAspect.inspectPipeline(...)`
API for the topology logging. Event subscription in the
`OrderService` constructor via Spring DI of `InqRuntime`.

### 6.G: Spring-Boot example — logging + AdminService + tests

Module: `inqudium-bulkhead-integration-spring-boot/`

Same shape as 6.F, with Spring Boot's auto-configuration handling the
DI plumbing. Uses 6.B's new API for topology logging.

### 6.H: Documentation closure

Tasks:
- Confirm all five example modules have parity (same logback.xml,
  same logging levels, same AdminService shape, same test structure).
- Add an entry to `ADR_DRIFT_NOTES.md` documenting the maintainer's
  intent for a unified introspection API that smooths over the
  technology-driven differences (a thin wrapper API for
  user-friendly access). Cite this refactor's 6.B as the local
  fix and frame the unified API as a future improvement.
- Repo-wide grep for stale references to the old structure.
- Delete `REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md` per its
  own document-lifecycle section.

## Sequencing rationale

6.A → 6.B → 6.C → 6.D → 6.E → 6.F → 6.G → 6.H.

6.B (library extension) comes before 6.F and 6.G (the modules that
depend on the new API). Among the example modules 6.C–6.G, the
sequence is function → proxy → aspectj → spring-framework → spring-boot,
matching 5.B–5.F's order. A reader can browse the modules in number
order and see the same complexity progression in two refactor cycles.

## Risk register

- Library API parity (6.B): the `InqShieldAspect` internal cache may
  differ from `AbstractPipelineAspect`'s in ways that prevent
  one-to-one signature parity. The plan asks for code inspection
  before writing the signatures and explicit documentation of any
  deviation.

- AspectJ topology-log location (6.E): the maintainer's design
  decision 3 (topology logged in `OrderService`) may not fit the
  AspectJ idiom cleanly because the service has no reference to the
  aspect at construction time. The plan permits relocating the
  topology log to `OrderBulkheadAspect` if the natural fit is there,
  with documentation in the report.

- Logback configuration parity (6.C onwards): the goal is identical
  configuration across all five modules. Subtle differences in
  Spring-Boot's logback handling (it picks up `logback-spring.xml`
  preferentially) may force an idiomatic adjustment in 6.G. Document
  any deviation.

- The runtime-config-change tests (6.C–6.G): patching from
  `balanced().maxConcurrentCalls(2)` to
  `permissive().maxConcurrentCalls(50)` may interact with
  strategy-hot-swap logic in unexpected ways if the two presets carry
  different `BulkheadStrategyConfig` defaults. The plan asks for
  verification that the patch is observed live without strategy
  re-materialization issues; if a strategy-related failure surfaces,
  pause and report.

## Completion log

- [ ] 6.A — Plan document
- [x] 6.B — InqShieldAspect introspection API (Library) (2026-05-02, PR #39)
- [x] 6.C — Function-based example (2026-05-02, PR #40)
- [ ] 6.D — Proxy-based example
- [ ] 6.E — AspectJ example
- [ ] 6.F — Spring-Framework example
- [ ] 6.G — Spring-Boot example
- [ ] 6.H — Documentation closure

## Document lifecycle

This document lives at the repository root for the duration of the
logging-and-runtime-config refactor. When all eight sub-steps are
complete and signed off, this document is deleted. The audit trail at
that point lives in Git history, the closed PRs, the new code in the
five example modules and `inqudium-spring`, and the updated
`ADR_DRIFT_NOTES.md`.
