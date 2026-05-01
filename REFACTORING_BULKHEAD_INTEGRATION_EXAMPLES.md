# REFACTORING_BULKHEAD_INTEGRATION_EXAMPLES.md

Plan for restructuring the integration-test territory around the imperative
bulkhead. The current `inqudium-bulkhead-integration-tests` module holds tests
only — no `src/main/java`. It functions as integrative protection but does not
serve as an example of how to use the imperative bulkhead in a real
application. This refactor splits the territory into:

- a parent `inqudium-bulkhead-integration` aggregating five
  example-application sub-modules (one per integration style), each fully
  self-contained, each demonstrating a complete tiny webshop scenario;
- a top-level `inqudium-bulkhead-library-tests` sibling that holds the
  library-end-to-end tests that survive from the old module — tests of
  bulkhead behaviour under realistic conditions, not user-application tests.

The plan rests on the design decisions taken before this document was
written, with the maintainer:

1. **Domain.** A small webshop. One service with two methods. The exact
   shape: every example module declares its own `OrderService` (or an
   equivalent name idiomatic to the integration style — but the same
   semantics) with two methods. Suggested:
   - `placeOrder(String item)` returning `String` — sync, demonstrates the
     happy-path sync execute through the bulkhead.
   - A second method that exercises a different bulkhead semantics
     (saturation, async, or timing) — suggested as
     `placeOrderHolding(CountDownLatch, CountDownLatch)` matching the
     existing test fixture's shape, so the saturation behaviour is
     demonstrable in tests without restructuring fixture machinery.

2. **One service, two methods, one strategy.** Semaphore strategy is
   sufficient for the demonstration. The five modules show the same domain
   semantics; they differ only in *how the bulkhead is wired into the
   application*.

3. **No common module.** Each example module is self-contained: it
   declares its own service, its own configuration code, its own
   demonstration of the integration style, and its own tests. Duplication
   across the five is accepted as the cost of self-containment. A reader
   opening any one module sees the complete example for that style without
   chasing references to a shared module.

4. **Idiomatic per integration style.** Each module shows what the wiring
   would naturally look like for someone choosing that style — Spring Boot
   shows `@SpringBootApplication`/`@Bean`/`@Service`/`@InqBulkhead`;
   AspectJ shows aspect declarations and pointcuts; function-based shows
   plain Java with `decorateSupplier(...)` and equivalent calls. The
   service's observable behaviour stays identical across all five, so a
   reader can confirm "yes, all five do the same thing" by reading the
   tests.

5. **Configuration is hardcoded via DSL.** No YAML, JSON, or other
   text-config format yet — those will arrive in a future refactor. When
   they do, each example module gets a parallel update.

6. **Modules are not installable, not deployable.** The
   `maven-install-plugin` and `maven-deploy-plugin` carry `<skip>true</skip>`
   in the parent POM, inherited by all sub-modules. Loading these from a
   repo would not make sense — they exist as in-repo demonstrations, not as
   library artifacts.

7. **Old `inqudium-bulkhead-integration-tests` module is renamed and
   relocated.** The existing tests in that module fall into two categories:
   integration-style-specific (Spring Boot, AspectJ wrapper, etc.) and
   library-end-to-end (lifecycle, concurrency, wrapper-family). The first
   category migrates into the matching new sub-modules. The second
   category stays together but in a renamed module:
   `inqudium-bulkhead-library-tests`, sitting at the top level as a
   sibling of `inqudium-bulkhead-integration`. The rename happens early
   (sub-step 5.A) so that all subsequent migrations land in the
   already-renamed module — no last-minute reshuffling.

8. **Module names.**
   - Parent: `inqudium-bulkhead-integration` (`<packaging>pom</packaging>`).
   - Sub-modules:
     `inqudium-bulkhead-integration-spring-framework`,
     `inqudium-bulkhead-integration-spring-boot`,
     `inqudium-bulkhead-integration-aspectj`,
     `inqudium-bulkhead-integration-proxy`,
     `inqudium-bulkhead-integration-function`.
   - Top-level sibling for renamed library tests:
     `inqudium-bulkhead-library-tests`.

9. **Sequencing: simple → complex.** Function-based first (no framework),
   then proxy (JDK dynamic proxies, no DI), then AspectJ (aspect machinery
   without DI), then Spring Framework (DI without auto-config), then
   Spring Boot (DI with auto-config). A reader can browse the modules in
   number order and watch the wiring complexity grow at each step.

## Sub-steps

### 5.A: Skeleton — parent, five empty sub-modules, library-tests rename

Establishes the new structure without yet populating any example. After
this sub-step, `mvn verify` is green on the new layout, the parent
aggregates five empty sub-modules, the old integration-tests module is
renamed to `inqudium-bulkhead-library-tests` at the top level, and all
existing tests still live in the renamed module unchanged.

**Tasks:**

1. Create `inqudium-bulkhead-integration/` at the repository top level.
   `pom.xml` declares `<packaging>pom</packaging>`, lists the five
   sub-modules, and configures `maven-install-plugin` and
   `maven-deploy-plugin` with `<skip>true</skip>` in `<build>` so all
   sub-modules inherit. Inherits the repo's parent POM as usual.
2. Create the five sub-module directories under
   `inqudium-bulkhead-integration/`, each with a minimal `pom.xml` that
   inherits the new parent and declares no `src/main/java`,
   `src/test/java`, or other content yet. They are stubs.
3. Rename the existing `inqudium-bulkhead-integration-tests/` directory to
   `inqudium-bulkhead-library-tests/`. Update the `<artifactId>` in its
   `pom.xml` to match. Update any references (the repo's root `pom.xml`
   `<modules>` list, any cross-module dependencies that name the old
   artifactId — search the whole repo with `grep`).
4. Add `inqudium-bulkhead-integration` and `inqudium-bulkhead-library-tests`
   to the root `pom.xml`'s `<modules>` list.
5. Verify the reactor builds: `mvn verify` runs the existing
   `inqudium-bulkhead-library-tests` (under its new name) plus all
   pre-existing modules; the five new sub-modules build but produce no
   tests.

**What 5.A does NOT do:**
- Does NOT migrate any test out of the renamed `inqudium-bulkhead-library-tests`.
  All existing tests stay there for now; later sub-steps move the
  integration-style-specific ones out.
- Does NOT add example application code to any sub-module.
- Does NOT touch any production code in `inqudium-imperative`,
  `inqudium-aspect`, `inqudium-spring`, etc.

**Verification gates for 5.A:**
- `mvn verify` reactor green.
- The five new sub-module directories exist with valid stub `pom.xml`s.
- `inqudium-bulkhead-integration-tests/` no longer exists; the renamed
  `inqudium-bulkhead-library-tests/` holds the same source files.
- Repository-wide `grep -rn "inqudium-bulkhead-integration-tests"` returns
  no matches outside historical references (Git history, ADRs that
  intentionally describe the old name as historical).
- `maven-install-plugin` and `maven-deploy-plugin` skip is in effect:
  `mvn install -pl inqudium-bulkhead-integration` (or any sub-module)
  produces no installed artifact in the local Maven repo.

### 5.B: Function-based example module + library-tests population

Smallest example module — no framework, no DI, no AspectJ. Service code
plus a tiny `main(String[])` method that exercises the service end-to-end
through bulkhead-decorated method calls. This module establishes the
example-application pattern that subsequent sub-modules adapt for their
respective integration style.

In parallel, this sub-step populates the renamed
`inqudium-bulkhead-library-tests` with the wrapper-family and lifecycle
tests that survived from the old module. They were already there after
5.A's rename — this sub-step's job is to confirm they still pass and to
state the rationale (in the module's `package-info.java` or `README.md`)
for why these tests live where they do.

**Tasks:**

1. **Function-based example application.** Create
   `inqudium-bulkhead-integration-function/src/main/java/...`:
   - An `OrderService` class with two methods (`placeOrder`,
     `placeOrderHolding`) matching the maintainer-defined shape.
   - A small `BulkheadConfig` class or method that builds an
     `InqRuntime` via `Inqudium.configure().imperative(...)` using the
     semaphore strategy. The DSL config is the canonical Inqudium pattern;
     a reader sees the configuration shape without framework noise.
   - A `Main` class with a `main(String[])` method that constructs the
     runtime, decorates the service methods via `decorateSupplier` (or
     equivalent function-based wrapper), runs a few sample calls, and
     prints the results. Demonstrates the shape "no framework, just Java
     functions wrapped through the bulkhead."
2. **Function-based example tests.** Tests in this sub-module test only
   the *example application*, not the library — patterns a real user
   would write. Suggested cases:
   - `place_order_succeeds_through_the_bulkhead`
   - `concurrent_calls_above_the_limit_are_rejected_with_InqBulkheadFullException`
   - The tests use the same `OrderService` from `src/main/java`,
     decorating it via the function-based pattern. Use the standard test
     conventions (Given/When/Then, AssertJ, snake_case, `@Nested` for
     categories, English comments).
3. **Library-tests population.** Confirm that
   `BulkheadConcurrentRemovalAndPatchTest`,
   `BulkheadWrapperFamilyTest`, `BulkheadWrapperLifecycleTest` still
   live in `inqudium-bulkhead-library-tests` and pass. Add a brief
   `package-info.java` to the test package explaining: "These tests
   exercise bulkhead library behaviour end-to-end under realistic
   conditions (concurrency, lifecycle transitions, wrapper-family
   compatibility). They are NOT examples of how to test a user's
   application — for that, see the example modules under
   `inqudium-bulkhead-integration/`. They live here so a future reader
   isn't tempted to copy them into their own test suite."

**What 5.B does NOT do:**
- Does NOT migrate any other test out of `inqudium-bulkhead-library-tests`.
  The Spring/AspectJ-specific tests stay there until their respective
  sub-modules (5.D, 5.E, 5.F) absorb them.
- Does NOT change the `Inqudium.configure()` DSL or any production code.

**Verification gates for 5.B:**
- `mvn verify` reactor green.
- `inqudium-bulkhead-integration-function/` has `src/main/java` with the
  three classes (Service, Config, Main) plus `src/test/java` with the new
  tests.
- The Main class runs cleanly when invoked directly (`mvn exec:java` or
  IDE-launch-equivalent) — verify by manual run if no automated test
  exercises it.
- The `package-info.java` in `inqudium-bulkhead-library-tests` is
  present.
- Test counts in `inqudium-bulkhead-library-tests` are unchanged from
  5.A; new test counts in the function-based module match the test list.

### 5.C: Proxy example module

JDK dynamic proxies via `InqProxyFactory`. Same `OrderService` shape, but
behind an interface that gets proxied. The two-method service requires an
interface (Java's dynamic proxies require interfaces), which is the small
shape adjustment this style imposes.

**Tasks:**

1. `OrderService` becomes an interface with the two methods. A
   `DefaultOrderService` (or similar implementation class) implements
   them.
2. `BulkheadConfig` builds the runtime. A `ProxyFactory` (or `Main`-level
   construction code) wraps the service implementation via
   `InqProxyFactory.of(name, bulkhead).protect(OrderService.class,
   defaultImpl)` to produce a proxied instance.
3. `Main` exercises the proxied service. A reader sees: "the service is
   a regular Java object, the proxy is a regular JDK Proxy, the bulkhead
   sits between them transparently."
4. Tests in this sub-module exercise the *proxied service* — not direct
   bulkhead calls. Pattern is: get a proxied instance, call its methods,
   observe bulkhead behaviour through the proxy.

**What 5.C does NOT do:**
- Does NOT touch other example modules.
- Does NOT migrate `BulkheadWrapperFamilyTest`'s proxy-related cases out
  of `inqudium-bulkhead-library-tests`. Those test the library
  (`InqProxyFactory`, `ProxyPipelineTerminal`); the new module's tests
  test the example application.

**Verification gates for 5.C:**
- `mvn verify` reactor green.
- The proxy module has `src/main/java` with interface + implementation +
  config + main, and `src/test/java` with example-app tests.
- The Main class runs cleanly when invoked directly.

### 5.D: AspectJ example module + AspectJ test migration

AspectJ aspect machinery without Spring. Most realistic for users who
want resilience advice but don't run a Spring container.

**Tasks:**

1. `OrderService` is a regular class (no Spring annotations). The
   bulkhead is applied via an aspect declaration that targets the service
   methods — pointcut + around advice that delegates to the bulkhead's
   pipeline-terminal.
2. The aspect uses `AspectPipelineTerminal.of(InqPipeline)` (or the
   nearest equivalent) to wrap the bulkhead. The configuration code shows
   the pipeline construction explicitly.
3. `Main` triggers the service; AspectJ either weaves at compile-time
   (preferred for the example, if the build supports it) or at load-time.
   Pick whichever is simplest for the example to demonstrate without
   requiring extensive build configuration. Pause and report if neither
   approach is clean for a self-contained example module.
4. Tests exercise the aspect-applied service end-to-end.
5. **Migrate `BulkheadAspectLifecycleTest` from `inqudium-bulkhead-library-tests`
   to this module.** That test is structurally an integration test of the
   AspectJ pipeline path against a real bulkhead — exactly what this
   module is for. Rename or restructure where needed so the test makes
   sense in its new home. Confirm it still passes.

**What 5.D does NOT do:**
- Does NOT migrate anything not specifically AspectJ-related. The
  wrapper-family tests stay in `inqudium-bulkhead-library-tests` even if
  some happen to mention AspectJ adjacency.
- Does NOT change the production AspectJ pipeline code.

**Verification gates for 5.D:**
- `mvn verify` reactor green.
- The AspectJ module is self-contained.
- `BulkheadAspectLifecycleTest` (or its renamed/restructured equivalent)
  lives in the new module and passes.
- `inqudium-bulkhead-library-tests` no longer contains
  `BulkheadAspectLifecycleTest`.

### 5.E: Spring Framework (no Boot) example module

Manually wired `ApplicationContext` — no auto-configuration. Shows that
Inqudium works with plain Spring DI, for users on legacy Spring or
non-Boot setups.

**Tasks:**

1. `@Configuration` class declaring beans for the runtime, the bulkhead
   handle (as `InqElement`), and the service.
2. The service uses `@InqBulkhead("...")` on its methods just like in
   the Spring Boot case — `InqShieldAspect` (or the equivalent aspect)
   gets registered as a bean and weaves behaviour at proxy creation.
3. `Main` builds the `AnnotationConfigApplicationContext` (or
   classic XML if a non-annotation example reads more like real
   legacy-Spring code — pick whichever is more illustrative; in 2026 the
   annotation form is much more common, so prefer it).
4. Tests use Spring's classic test support (`@ContextConfiguration` plus
   `SpringRunner`/JUnit Jupiter Spring extension) without
   `@SpringBootTest`. This is the differentiating choice from 5.F.

**What 5.E does NOT do:**
- Does NOT migrate the Spring-Boot tests; those are 5.F's territory.
- Does NOT use Spring Boot anywhere — `@SpringBootApplication`,
  `@SpringBootTest`, `application.properties`, auto-configuration
  starters: all absent.

**Verification gates for 5.E:**
- `mvn verify` reactor green.
- The Spring Framework module is self-contained.
- No Spring Boot dependencies in this module's POM.

### 5.F: Spring Boot example module + Spring-Boot test migration

The most familiar style for new Java applications today. Auto-config via
`@SpringBootApplication`, beans via `@Bean`, and the bulkhead reachable
through `@InqBulkhead` annotation on service methods.

**Tasks:**

1. `@SpringBootApplication`-annotated main class that defines `@Bean`
   methods for the runtime and the bulkhead handle, plus a `@Service`
   `OrderService`.
2. The example shows how Inqudium's auto-configuration discovers
   `InqElement` beans and wires them to `@InqBulkhead`-annotated methods.
3. `Main` is the standard `SpringApplication.run(Application.class,
   args)`.
4. **Migrate from `inqudium-bulkhead-library-tests`:**
   `BulkheadSpringBootIntegrationTest`,
   `BulkheadSpringBootHotSwapTest`,
   `BulkheadSpringBootShutdownTest`. Adapt each so it makes sense as part
   of a Spring Boot example module's test suite — they may need
   restructuring; the existing tests' inner `@SpringBootApplication` and
   `@Bean` declarations might collapse into the module's own
   `@SpringBootApplication`, removing per-test configuration boilerplate.
   Pause and report if a test's pre-existing in-class configuration
   doesn't merge cleanly.
5. After migration, `inqudium-bulkhead-library-tests` no longer has
   Spring-Boot-specific tests.

**What 5.F does NOT do:**
- Does NOT touch the auto-configuration code or `InqShieldAspect` itself.
  Those are library production code; this module exercises them.

**Verification gates for 5.F:**
- `mvn verify` reactor green.
- The Spring Boot module is self-contained.
- All three migrated tests pass in the new module.
- `inqudium-bulkhead-library-tests` no longer has the three migrated
  tests.

### 5.G: Final cleanup of `inqudium-bulkhead-library-tests`

After 5.A–5.F, `inqudium-bulkhead-library-tests` should hold exactly
the tests that are genuinely library-end-to-end and have no
integration-style-specific home. Confirm this state, audit residual
contents, ensure the `package-info.java` from 5.B accurately describes
the surviving content.

**Tasks:**

1. Audit `inqudium-bulkhead-library-tests`'s remaining tests. Each one
   should genuinely test library behaviour, not be an
   integration-specific test that should have moved.
2. Update `package-info.java` (added in 5.B) so its description matches
   what survives. If the surviving tests cover specific themes
   (lifecycle, concurrency, wrapper-family), name those themes
   explicitly.
3. If any surviving test would be more at home in a specific example
   module, pause and report — it might mean an earlier sub-step
   under-migrated.

**What 5.G does NOT do:**
- Does NOT move new tests into the library-tests module. Its scope is
  fixed.
- Does NOT modify production code.

**Verification gates for 5.G:**
- `mvn verify` reactor green.
- `inqudium-bulkhead-library-tests`'s test contents are documented in
  `package-info.java` and match what's in `src/test/java`.
- Any concerns about test placement are reported, not fixed silently.

### 5.H: TODO / doc closure

Update any documentation that references the old module structure. The
ADR audit is out of scope (deferred to its own refactor cycle), but
short-term references like the repo `README.md`, the `CLAUDE.md`
module-layout descriptions, or onboarding notes need to reflect the new
shape.

**Tasks:**

1. `grep -rn "inqudium-bulkhead-integration-tests"` repository-wide. For
   each match: keep if intentional historical reference (Git commit
   messages, old PR descriptions); update if it's a current docs
   reference.
2. Update `CLAUDE.md`'s module-layout description (if present) to list
   the new modules.
3. Add a note to `ADR_DRIFT_NOTES.md` (if any newly-discovered ADR
   drift surfaced during this refactor) for the future audit.
4. Delete `REFACTORING_BULKHEAD_INTEGRATION_EXAMPLES.md` from the
   repository root, per its document-lifecycle section.

**What 5.H does NOT do:**
- Does NOT touch ADRs. Documentation drift in ADRs goes to
  `ADR_DRIFT_NOTES.md`, not into this refactor.

**Verification gates for 5.H:**
- `grep -rn "inqudium-bulkhead-integration-tests"` returns only
  intentional historical references.
- `CLAUDE.md` describes the new module layout accurately.
- `REFACTORING_BULKHEAD_INTEGRATION_EXAMPLES.md` is deleted.

## Sequencing rationale

5.A → 5.B → 5.C → 5.D → 5.E → 5.F → 5.G → 5.H. Skeleton first so all
subsequent work has a stable home. Function-based first because it is
the simplest example and establishes the pattern. Each example module
adds one layer of integration complexity on top of the previous one.
Spring Boot last because it is the most layered (DI + auto-config +
annotation processing). Library-tests cleanup after all migrations are
complete. Doc closure last because it depends on the final shape being
known.

## Risk register

The plan's main load-bearing assumption is that each integration style
can produce a self-contained, idiomatic example without escaping into
shared infrastructure. The decision against a common module (decision 3)
puts pressure on each sub-module to fully express its style — if for
some integration style this proves to require infrastructure that's
genuinely shared (e.g. a fixture for hooking the runtime into the
service), pause and report; we may need to revise decision 3 mid-flight
or accept a small `inqudium-bulkhead-integration-common` exception.

The Spring-Boot-test migration in 5.F may surface that the existing
tests' in-class `@SpringBootApplication` and `@Bean` declarations don't
cleanly merge into the new module's own `@SpringBootApplication`. The
plan asks to pause and report — the alternative is keeping per-test
configuration as-is, which loses some of the benefit of having a real
example module.

The AspectJ weaving choice (compile-time vs. load-time) in 5.D may
require build-configuration work that's heavy for a small example
module. Pause and report if neither approach is clean for a
self-contained example.

## Completion log

- [ ] 5.A — Skeleton + library-tests rename
- [ ] 5.B — Function-based example + library-tests population
- [ ] 5.C — Proxy example
- [ ] 5.D — AspectJ example + AspectJ test migration
- [ ] 5.E — Spring Framework example
- [ ] 5.F — Spring Boot example + Spring Boot test migration
- [ ] 5.G — Library-tests cleanup
- [ ] 5.H — Doc closure

## Document lifecycle

This document lives at the repository root for the duration of the
example-modules refactor. When all eight sub-steps are complete and
signed off, this document is deleted. The audit trail at that point
lives in Git history, the closed PRs, the new module structure, and
the renamed `inqudium-bulkhead-library-tests` module.
