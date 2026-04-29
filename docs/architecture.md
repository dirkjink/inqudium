# Architecture of Inqudium

This document provides a high-level overview of Inqudium's internal architecture, linking to the relevant Architectural
Decision Records (ADRs) for detailed rationale.

## The big picture

Inqudium is a multi-paradigm resilience library. It provides the same algorithms (CircuitBreaker, RateLimiter, Retry,
Bulkhead, TimeLimiter) across four distinct execution models: Imperative, Kotlin Coroutines, Reactor, and RxJava 3.

```text
┌─────────────────────────────────────────────────────────┐
│                    User Application                     │
├───────────────┬───────────────┬───────────────┬─────────┤
│  Imperative   │   Coroutines  │    Reactor    │ RxJava  │
│   (Java)      │   (Kotlin)    │   (Java/Kt)   │ (Java)  │
├───────────────┼───────────────┼───────────────┼─────────┤
│    inqudium   │   inqudium    │    inqudium   │inqudium │
│    -core      │   -kotlin     │    -reactor   │-rxjava3 │
│  (functional) │ (suspending)  │ (publishers)  │(streams)│
├───────────────┴───────────────┴───────────────┴─────────┤
│                     inqudium-core                       │
│  Configs · Pure algorithms · Events · SPI contracts │
└─────────────────────────────────────────────────────────┘
```

## Module structure

Inqudium is a Maven multi-module project with fine-grained imperative modules (one per element) and one module per
reactive paradigm ([ADR-001](adr/001-modular-architecture.md)):

| Module                    | Purpose                                            |
|---------------------------|----------------------------------------------------|
| `inqudium-core`           | Contracts, configs, pure algorithms, events, SPI   |
| `inqudium-circuitbreaker` | Imperative CircuitBreaker (ReentrantLock-based)    |
| `inqudium-retry`          | Imperative Retry                                   |
| `inqudium-ratelimiter`    | Imperative RateLimiter                             |
| `inqudium-bulkhead`       | Imperative Bulkhead                                |
| `inqudium-timelimiter`    | Imperative TimeLimiter (CompletionStage-based)     |
| `inqudium-kotlin`         | Coroutine implementations (Mutex-based)            |
| `inqudium-reactor`        | Project Reactor implementations (Atomic operators) |
| `inqudium-rxjava3`        | RxJava 3 implementations (Atomic operators)        |
| `inqudium-[bridge]`       | Integration modules (micrometer, jfr, slf4j)       |

## Shared contracts vs native execution

To guarantee identical behavior across paradigms without sharing blocking code, Inqudium splits
responsibility ([ADR-005](adr/005-shared-contracts.md)):

1. **`inqudium-core` (The what)** — Defines configuration, data models, and pure functions (e.g., sliding window
   calculation, backoff math, rate limit bucket state). No locks, no thread pools, no wait/sleep.
2. **Paradigm modules (The how)** — Implement the functional decoration APIs ([ADR-002](adr/002-functional-api.md)) and
   handle synchronization.

Example: The CircuitBreaker state machine lives in core. When a call fails, the Kotlin module calls
`coreStateMachine.onError(...)`. The core computes the new state and returns "open". The Kotlin module then applies this
state change using a coroutine `Mutex`.

## Registry pattern

Elements are created and managed via registries ([ADR-015](adr/015-registry-pattern.md)):

```java
var registry = CircuitBreakerRegistry.ofDefaults();
var cb1 = registry.circuitBreaker("serviceA");
var cb2 = registry.circuitBreaker("serviceB");
```

Registries provide:

1. Thread-safe instantiation
2. Name-based lookup (returning the exact same instance)
3. Shared configuration templates
4. Explicit `remove()` and `reset()` lifecycle management

## Element design

### Functional API

Each element is a functional decorator that wraps an operation in a specific
paradigm ([ADR-002](adr/002-functional-api.md)):

- **Imperative**: `decorateSupplier(Supplier<T>)`
- **Kotlin**: `decorateSuspendFunction(suspend () -> T)`
- **Reactor**: `decorateMono(Mono<T>)`
- **RxJava**: `decorateSingle(Single<T>)`

### CircuitBreaker (ADR-016)

Uses a sliding window to track outcomes. Computes failure rate and slow call rate. Transitions to `OPEN` when thresholds
are exceeded.

- Pure immutable state updates (no lock contention during window math).
- Two window types: Count-based (N calls) and Time-based (N seconds).
- `InqClock` interface allows time travel testing.

### TimeLimiter (ADR-010)

Enforces a maximum duration for the caller's wait time.

- Does **not** use `Thread.interrupt()` due to JVM unsafety.
- Cooperative cancellation in reactive/coroutine paradigms.
- The underlying operation continues as an "orphaned" call.
- Provides `onOrphanedResult` completion callback for compensation logic.

### RateLimiter (ADR-019)

Token bucket algorithm limiting calls over time.

- Fixed bucket size, fixed refill rate.
- Pure function implementation (predictable math, no background timer threads).
- Supports wait durations (polling/delaying).

### Bulkhead (ADR-020)

Semaphore isolation limiting concurrent calls.

- No thread pools (unnecessary with Java 21 virtual threads, see ADR-008).
- Pure counter logic, paradigm handles waiting.
- Interacts explicitly with TimeLimiter: orphaned calls hold permits until completion.

### Retry (ADR-018)

Re-executes failed calls.

- Supports fixed, exponential, and randomized (jitter) backoff.
- Result/Exception predicates determine what is retried.
- Delay execution is paradigm-specific (`Thread.sleep`, `delay()`, `Mono.delay()`).

### Exceptions (ADR-009)

Inqudium uses a flat exception hierarchy extending `RuntimeException`.

- `InqException` (base)
    - `InqCallRejectedException` (CircuitBreaker open, RateLimiter/Bulkhead full)
    - `InqTimeLimitExceededException`
    - `InqRetryExhaustedException`
    - `InqConfigurationException` (setup errors)

Exceptions carry `elementName`, `elementType`, and often runtime state (e.g., failure rate) to aid debugging.

## Pipeline composition

Elements are composed into pipelines using `InqPipeline` ([ADR-017](adr/017-pipeline-composition-order.md)):

```java
var result = InqPipeline.of(circuitBreaker, retry, timeLimiter)
    .execute(() -> service.call());
```

Pipelines provide static factory orderings for correct composition:

- **TimeLimiter inside Retry**: Each attempt has a timeout.
- **CircuitBreaker outside Retry**: Sees each retry attempt individually for fast failure detection.
- **RateLimiter outside CircuitBreaker**: Rate limit is a global constraint, not per-attempt.

A Resilience4J-compatible order is available for migration.

## Event system

Two scopes ([ADR-003](adr/003-event-system.md)):

**Per-element publishers** — Created at element construction time. Consumers subscribe to one element and receive only
that element's events when diagnostic events are enabled. Thread-safe via `CopyOnWriteArrayList`.

**Global exporters** — Registered via `InqEventExporterRegistry` (programmatic or ServiceLoader). Receive all events
from all elements when diagnostic events are enabled. Export to Kafka, JFR, CloudEvents, etc. Support
`subscribedEventTypes()` filtering to avoid unnecessary serialization.

Every generated event carries a `callId` for end-to-end correlation across all elements in a pipeline.

## Context propagation

Framework-agnostic SPI ([ADR-011](adr/011-context-propagation.md)):

- The core defines `InqContextPropagator` but does not depend on SLF4J or OTel.
- Context automatically flows across execution boundaries (Future, Coroutine, Reactor).
- `inqudium-context-slf4j` bridges the SPI to MDC via ServiceLoader.

## Call identity propagation

Pipelines generate a single `callId` and propagate it through all composed elements via the `InqCall`
abstraction ([ADR-022](adr/022-call-identity-propagation.md)). This guarantees that all events emitted when diagnostic
events are enabled during a pipeline execution share the same correlation ID.

## Breaking change management

Behavioral changes are gated by `InqFlag` enum constants ([ADR-013](adr/013-breaking-change-management.md)). Three-layer
resolution: defaults → ServiceLoader → programmatic. `InqCompatibilityEvent` provides an audit trail of active flags at
element creation time.

## ServiceLoader conventions

Three SPIs use `ServiceLoader` ([ADR-014](adr/014-serviceloader-conventions.md)):

1. `InqEventExporter` — global event export
2. `InqContextPropagator` — context propagation bridges
3. `InqCompatibilityOptions` — code-free flag configuration

All follow the same conventions: lazy discovery on first access, Comparable ordering, error isolation (a failing
provider is logged and skipped), singleton lifecycle, frozen after first access.

## Virtual thread readiness

No `synchronized` anywhere in the library ([ADR-008](adr/008-virtual-thread-readiness.md)). All locking uses
`ReentrantLock`. All waiting uses `LockSupport.parkNanos`. This prevents carrier-thread pinning on Java 21+ virtual
threads.