# CLAUDE.md

This file is read automatically by Claude Code when it starts in this repository. It captures project conventions, the module layout, and the current working context so Claude doesn't have to rediscover them on every session.

## Project overview

Inqudium is a multi-paradigm Java resilience library. It provides the same resilience elements — Circuit Breaker, Retry, Rate Limiter, Bulkhead, Time Limiter, Traffic Shaper — across four execution models:

- **Imperative** (Java, `Supplier<T>` / `Runnable` / `Function<T,R>`)
- **Kotlin coroutines** (`suspend` functions, `Flow`)
- **Project Reactor** (`Mono`, `Flux`)
- **RxJava 3** (`Single`, `Observable`, `Flowable`)

The architecture is documented in `docs/architecture.md` and detailed in the ADRs under `docs/adr/`.

## Repository layout

```
inqudium-core/           Contracts, configs, pure algorithms, events, SPI. JDK-only dependencies.
inqudium-imperative/     Imperative implementations (virtual-thread based).
inqudium-kotlin/         Coroutine implementations.
inqudium-reactor/        Project Reactor implementations.
inqudium-rxjava3/        RxJava 3 implementations.
inqudium-[bridge]/       Integration modules (micrometer, jfr, slf4j, spring-boot3).
docs/adr/                Architecture Decision Records.
docs/user-guide/         End-user documentation.
```

The kernel lives in `inqudium-core/src/main/java/eu/inqudium/core`. 

The root package of each module follows the convention 'eu' followed by the artifact ID of the Maven module, where the hyphens in the artifact ID are replaced by periods. For example, artifact ID 'inqudium-core' becomes the package name 'eu.inqudium.core'.

## Core design principles

These hold regardless of which module is being touched:

- **Pure algorithms in core.** No `Thread.sleep`, no blocking, no locks, no schedulers inside `inqudium-core`. Algorithms compute values; paradigm modules handle waiting and synchronization.
- **Virtual-thread ready.** No `synchronized` anywhere. `ReentrantLock` for locking, `LockSupport.parkNanos` for waiting. This is a hard constraint from ADR-008.
- **No Blocking** No blocking code in the core module
- **Injectable time.** Use `InqNanoTimeSource` for monotonic time (metrics, deadlines) and `InqClock` for wall-clock time (log timestamps, event metadata). Tests inject deterministic sources — never `Thread.sleep` or `System.nanoTime()` directly in tests.
- **Functional decoration as the primary API.** Elements decorate `Supplier<T>`, `Runnable`, `Function<T,R>`, `CompletionStage<T>`. Annotation-based `@InqShield` is a convenience layer on top, not a separate execution path.
- **First-registration-wins registries.** Registries never overwrite an existing instance; the provided config is ignored if the name is already present.

## Code-first, ADR-second

**The code is authoritative. ADRs must follow the code, not the other way around.**

Several ADRs have drifted from the implementation over time. Before editing an ADR, always inspect the current code and let it dictate what the ADR should say. An active inconsistency catalog lives in `docs/adr/_refactor-notes.md` (or equivalent path — create one if it isn't there yet) and can be used as the work plan for ADR revisions.

Known drift hotspots as of the last review:

- **ADR-022** (`InqCall` / call identity) — describes a `String callId` / `InqCall<T>` record that doesn't match the `long chainId` + `long callId` + `InternalExecutor` model actually in the code.
- **ADR-016** (sliding window) — the real interface is `FailureMetrics` with several strategy implementations, not a `SlidingWindow` with two implementations.
- **ADR-017, ADR-021, ADR-024** — reference a `CACHE` element that doesn't exist in `InqElementType`; `TRAFFIC_SHAPER` exists but isn't documented as an element in any ADR.
- **ADR-015** — the `InqRegistry` interface in code lacks the template methods (`addConfiguration`, `get(name, configName)`) and `remove`/`clear` that the ADR describes.
- **ADR-010** — forbids synchronous Callable + `Thread.interrupt`, but the imperative Time Limiter does exactly that via a virtual thread and `cancelOnTimeout`.
- **ADR-018** — the real `RetryConfig` and backoff strategies diverge significantly from the ADR; a second DSL `RetryConfig` record exists alongside the primary one.

When in doubt about any ADR statement, verify against the code before trusting it.

## Testing conventions

All tests are JUnit 5 with AssertJ. Any new test Claude writes must follow this format without exception:

- **Given / When / Then** structure, with comments marking each block.
  - If the case to be tested is complicated or unusual, explain it in the test by answering three questions: 
    - 1. What is to be tested? 
    - 2. How will the test case be deemed successful and why? 
    - 3. Why is it important to test this test case?

- **Method names** are full English sentences:
  - in java in snake_case. Example: `should_open_the_circuit_after_the_failure_threshold_is_reached`.
  - in kotlin use backticks. Example: \`should open the circuit after the failure threshold is reached\`.

- **Grouping** via `@Nested` inner classes for categories (e.g. a "State transitions" nested class, a "Thread safety" nested class).
  - Spring Boot test isolation caveat: 
    - When a test class uses an inner static @Configuration to scope its bean topology
      (a common pattern with @SpringBootTest), avoid wrapping the test methods in @Nested inner classes. Spring Boot's
      TestTypeExcludeFilter walks getEnclosingClassName() recursively and stops recognizing the inner @Configuration as
      test-internal once non-static @Nested test classes appear at the same nesting level. The result: that @Configuration
      leaks into the component scan of other @SpringBootTest classes, with cascading bean-conflict failures.
      Keep such test classes flat.
  
    - **Spring Boot context sharing across tests in the same class:** `@SpringBootTest` recycles the `ApplicationContext` between test methods within a single test class for performance. State changes made by one test method (e.g., a runtime strategy hot-swap) are visible to subsequent test methods in the same class. The standard pattern: use **disjoint resource names** per test method (e.g., a separate `@Bean InqElement` per test, named `aopHotSwap` vs. `aopRetune`), so each test exercises its own portion of the shared context without observing artefacts from prior tests. Combined with the `@Nested` caveat above, this means Spring Boot test classes are typically flat with method-level resource isolation, not nested with class-level isolation.
  
- **AssertJ only** (`assertThat(...)`). No JUnit `assertEquals`, no Hamcrest.
- **Deterministic time.** Inject `InqNanoTimeSource` or `InqClock` from an `AtomicLong`/`AtomicReference`. Never `Thread.sleep`.
- Tests should be thorough. Cover happy path, edge cases, error conditions, and concurrency where relevant.
- Tests are generally independent and isolated classes. In particular, they have no influence on other tests (in Spring Boot, there is often the case that Spring Boot captures too much information through classpath scanning).
- Do not use mock libraries

## Language conventions

- **All code, code comments, Javadoc, commit messages, PR descriptions, and test names are in English.** This is a non-negotiable project-wide rule — the codebase is an open-source artifact.
- **Conversational explanations to the user (in chat, not in code) are in German.** The user maintaining this project works in German. When Claude explains what it just did, why a design choice was made, or walks the user through a diff, use German prose.
- If Claude catches itself writing German in a code comment or a Javadoc block, that's a mistake — rewrite in English.

## Build and test

Maven multi-module project. Standard commands:

```
mvn verify                                      # build everything, run all tests
mvn -pl inqudium-core test                      # run core tests only
mvn -pl inqudium-imperative -am verify          # build imperative + its deps
mvn -pl inqudium-core test -Dtest=RetryConfigTest  # single test class
```

After any non-trivial change, run at least the affected module's `test` goal before suggesting a commit.

## Git workflow

Unless the user explicitly says otherwise, Claude Code should assume this flow for every non-trivial change:

1. Work on a dedicated feature branch — never on `main`. Create one
   at the start of the task with a descriptive name (`adr/fix-NNN-slug`,
   `fix/<area>-<slug>`, `feat/<slug>`, `docs/<slug>`).
2. Make the changes. Show the user a git diff or summary before
   committing, unless the change is trivial (typo fix, single-word edit).
3. On user approval, commit with a well-formed message (conventional
   prefix, subject line ≤72 chars, body explaining the why).
4. Push the branch.
5. Open a pull request against `main` with a meaningful title and
   a body that explains the change and references any related ADRs
   or issues. Use the `gh` CLI if available.
6. Report back to the user with the PR URL, so they can review and merge.

The user does the final merge. Do not merge PRs yourself.



## Element type reference

Quick lookup for the seven `InqElementType` values and their symbols (ADR-021):

| Symbol | Element          | Default pipeline order |
|--------|------------------|------------------------|
| TL     | TIME_LIMITER     | 100                    |
| TS     | TRAFFIC_SHAPER   | 200                    |
| RL     | RATE_LIMITER     | 300                    |
| BH     | BULKHEAD         | 400                    |
| CB     | CIRCUIT_BREAKER  | 500                    |
| RT     | RETRY            | 600                    |
| XX     | NO_ELEMENT       | 0                      |

Lower order value = more outward layer in the pipeline. Error codes follow `INQ-XX-NNN` where `XX` is the symbol. Code `000` is reserved for wrapped checked exceptions; `001-099` are active interventions; `100-199` are observability events.

## When unsure

Search the code first (`project_knowledge_search` has no equivalent here — use the filesystem and grep). The ADRs are a secondary reference. When the two disagree, the code wins.

## Approach for new tasks

- For smaller, manageable tasks, bug fixes can be started directly with the coding.
- If the task becomes unexpectedly extensive, it can lead to architectural breaks; in that case, create a Markdown document explaining the possible solutions, favoring one solution and explaining why.

## TODO discipline

A TODO comment is a debt the next person to read the code has to service. Justified TODOs are expensive enough to defer, or unsolved enough that "the right answer" isn't clear yet. They are not a polite way to skip work.

Before adding a TODO, check both gates:

1. **Is the solution path clear?** If yes, the TODO does not document an open question — it documents a known fix that wasn't done. Do the fix.
2. **Is the effort proportional?** If the fix is small (a few methods, a record, a Map type change), do it now. TODOs are for genuinely large detours that would derail the current task.

If neither gate is met, the change is not a TODO. It is a known cleanup that belongs in the current commit or in an immediately-following one. Reviewer feedback in particular should not be funneled into TODO comments unless one of the two gates is genuinely met.

When a TODO is genuinely warranted, it carries a phase tag (`TODO(1.9):`, `TODO(3.4):`) or a clear next-action description. "TODO: revisit" without specifics is not acceptable.