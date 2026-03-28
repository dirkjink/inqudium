# Architecture

This document describes the internal architecture of Inqudium. It is intended for contributors, reviewers, and anyone who wants to understand *why* the library is structured the way it is. For *how to use* it, see the [User Guide](user-guide.md).

---

## Foundational principle: native per paradigm

Inqudium does not wrap one implementation in adapters for different paradigms. Instead, each paradigm (imperative Java, Kotlin Coroutines, Project Reactor, RxJava 3) gets its own native implementation built on top of a shared core ([ADR-004](adr/004-native-per-paradigm.md)).

```
┌─────────────────────────────────────────────────────┐
│                  inqudium-core                      │
│  Configs · Pure algorithms · Events · SPI contracts │
└───────────┬────────────┬──────────────┬─────────────┘
            │            │              │
   inqudium-         inqudium-    inqudium-      inqudium-
   circuit-          kotlin       reactor        rxjava3
   breaker
   (imperative)
```

The core module contains no threading primitives, no paradigm-specific code, and no external dependencies beyond the JDK. It is a pure SPI: configurations define *what*, behavioral contracts define *how* (as pure functions), and the paradigm module provides synchronization, scheduling, and execution ([ADR-005](adr/005-shared-contracts.md)).

## Module structure

Inqudium is a Maven multi-module project with fine-grained imperative modules (one per element) and one module per reactive paradigm ([ADR-001](adr/001-modular-architecture.md)):

| Module | Purpose |
|--------|---------|
| `inqudium-core` | Contracts, configs, pure algorithms, events, SPI |
| `inqudium-circuitbreaker` | Imperative CircuitBreaker (ReentrantLock-based) |
| `inqudium-retry` | Imperative Retry |
| `inqudium-ratelimiter` | Imperative RateLimiter |
| `inqudium-bulkhead` | Imperative Bulkhead |
| `inqudium-timelimiter` | Imperative TimeLimiter (CompletionStage-based) |
| `inqudium-kotlin` | Kotlin Coroutine implementations (Mutex, delay) |
| `inqudium-reactor` | Project Reactor implementations (Mono/Flux operators) |
| `inqudium-rxjava3` | RxJava 3 implementations (transformer, Flowable) |
| `inqudium-context-slf4j` | MDC context propagation bridge |
| `inqudium-spring-boot3` | Spring Boot auto-configuration, annotations |
| `inqudium-bom` | Bill of Materials for version alignment |

## Core design

### Pure algorithms

Every algorithm in the core (sliding windows, token bucket, backoff strategies, bulkhead counter) is a pure function: it takes state in and returns state out. No locks, no atomics, no blocking. The paradigm module provides synchronization:

| Paradigm | Synchronization |
|----------|----------------|
| Imperative | `ReentrantLock` (virtual-thread-safe, [ADR-008](adr/008-virtual-thread-readiness.md)) |
| Kotlin | `Mutex` (coroutine-aware) |
| Reactor | Atomic operators / `Mono.defer` |
| RxJava 3 | `AtomicReference` / `Single.defer` |

This separation has a crucial benefit: the core can be tested without concurrency. A `CountBasedSlidingWindow` test calls `record()` → checks `snapshot()` — no threads, no timing, no flakiness.

### Injectable time

All time-dependent algorithms use `InqClock` instead of `Instant.now()` ([ADR-016](adr/016-sliding-window-design.md)). In production, the system clock is used. In tests, time is controlled explicitly via an `AtomicReference<Instant>`. This eliminates `Thread.sleep()` from the entire test suite.

### Functional API

The primary interaction with Inqudium is through function decoration: `decorateSupplier`, `decorateRunnable`, `decorateCallable` ([ADR-002](adr/002-functional-api.md)). The pipeline composes decorators by nesting: the outermost decorator wraps the next, down to the original supplier.

## Element design

### Circuit breaker

Two sliding window implementations ([ADR-016](adr/016-sliding-window-design.md)):

**Count-based** — A circular buffer of size N. O(1) per call. Running counters for failures and slow calls are updated incrementally: when a new outcome evicts an old one, the old outcome's contribution is subtracted. No full-buffer scan.

**Time-based** — N one-second buckets. Bucket rotation is driven by `InqClock`: when time advances past the current bucket, the next bucket is cleared and becomes current. If the entire window duration has elapsed, all buckets are cleared in one pass.

### Retry and backoff

Three jitter algorithms ([ADR-018](adr/018-retry-behavior-backoff.md)):

- **Equal jitter**: `base/2 + random(0, base/2)`. Guaranteed minimum delay. Default.
- **Full jitter**: `random(0, base)`. Maximum spread.
- **Decorrelated jitter**: `random(initial, previous × 3)`. Stateful — each delay depends on the previous one. Best under high concurrency per AWS analysis.

The `RetryBehavior` evaluates exceptions in a defined order: max attempts → InqException exclusion → ignoreOn → retryOn → predicate → compute delay. The behavior returns `Optional<Duration>` — the paradigm module decides how to wait.

### Rate limiter

Token bucket algorithm ([ADR-019](adr/019-ratelimiter-design.md)). Tokens refill continuously based on elapsed time, avoiding the boundary problem of fixed-window counters (used by Resilience4J). The `bucketSize` parameter decouples burst tolerance from sustained rate.

### Bulkhead

Semaphore-based concurrency isolation ([ADR-020](adr/020-bulkhead-design.md)). No thread-pool isolation — virtual threads make it unnecessary. The acquire/release lifecycle requires explicit finally-based release. The paradigm module guarantees this pairing.

### Time limiter

Bounds the caller's wait time, not the operation's execution time ([ADR-010](adr/010-timelimiter-semantics.md)). No thread interrupts. Orphaned operations continue to completion and are observable via the `OrphanedCallHandler` callback. `InqTimeoutProfile` derives consistent timeouts from HTTP client values using RSS or worst-case methods ([ADR-012](adr/012-timeout-value-hierarchy.md)).

## Pipeline composition

The canonical element order ([ADR-017](adr/017-pipeline-composition-order.md)):

```
Cache → TimeLimiter → RateLimiter → Bulkhead → CircuitBreaker → Retry
(outermost)                                                (innermost)
```

Key properties of this order:

- **TimeLimiter outside Retry**: Bounds total caller wait time across all retries.
- **CircuitBreaker outside Retry**: Sees each retry attempt individually for fast failure detection.
- **RateLimiter outside CircuitBreaker**: Rate limit is a global constraint, not per-attempt.

A Resilience4J-compatible order is available for migration.

## Event system

Two scopes ([ADR-003](adr/003-event-system.md)):

**Per-element publishers** — Created at element construction time. Consumers subscribe to one element and receive only that element's events. Thread-safe via `CopyOnWriteArrayList`.

**Global exporters** — Registered via `InqEventExporterRegistry` (programmatic or ServiceLoader). Receive all events from all elements. Export to Kafka, JFR, CloudEvents, etc. Support `subscribedEventTypes()` filtering to avoid unnecessary serialization.

Every event carries a `callId` for end-to-end correlation across all elements in a pipeline.

## Context propagation

Framework-agnostic SPI ([ADR-011](adr/011-context-propagation.md)):

```
InqContextPropagator.capture() → InqContextSnapshot
InqContextPropagator.restore(snapshot) → InqContextScope
InqContextPropagator.enrich(callId, elementName, elementType)
InqContextScope.close() → restores previous context
```

Bridge modules (e.g., `inqudium-context-slf4j`) provide propagator implementations. The `InqContextPropagation` utility encapsulates the full lifecycle in a single line.

## Compatibility

Behavioral changes are gated by `InqFlag` enum constants ([ADR-013](adr/013-breaking-change-management.md)). Three-layer resolution: defaults → ServiceLoader → programmatic. `InqCompatibilityEvent` provides an audit trail of active flags at element creation time.

## Error codes

Every exception carries a structured error code following the `INQ-XX-NNN` format ([ADR-021](adr/021-error-codes.md)). The two-character element symbol (`CB`, `RT`, `RL`, `BH`, `TL`, `SY`) immediately identifies the element. Codes are stable across minor versions.

## ServiceLoader conventions

Three SPIs use `ServiceLoader` ([ADR-014](adr/014-serviceloader-conventions.md)):

1. `InqEventExporter` — global event export
2. `InqContextPropagator` — context propagation bridges
3. `InqCompatibilityOptions` — code-free flag configuration

All follow the same conventions: lazy discovery on first access, Comparable ordering, error isolation (a failing provider is logged and skipped), singleton lifecycle, frozen after first access.

## Virtual thread readiness

No `synchronized` anywhere in the library ([ADR-008](adr/008-virtual-thread-readiness.md)). All locking uses `ReentrantLock`. All waiting uses `LockSupport.parkNanos`. This prevents carrier-thread pinning on Java 21+ virtual threads.
