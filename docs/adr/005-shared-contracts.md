# ADR-005: Shared contracts in inqudium-core

**Status:** Accepted  
**Date:** 2026-03-22  
**Deciders:** Core team

## Context

ADR-004 establishes that each paradigm gets its own native implementation. This creates a risk: four independent
implementations of each element could diverge in behavior. A circuit breaker that opens at 50% failure rate in the
imperative module but at 51% in the Kotlin module would be a critical bug.

We need a mechanism that guarantees behavioral equivalence across paradigms without sharing execution logic.

## Decision

`inqudium-core` serves as the **Service Provider Interface (SPI)** that all paradigm modules implement against. It
contains exactly four categories of shared code — nothing else.

### 1. Configuration (immutable, builder pattern)

Config objects are defined once and used by all paradigms:

```java
CircuitBreakerConfig config = CircuitBreakerConfig.builder()
    .failureRateThreshold(50)
    .waitDurationInOpenState(Duration.ofSeconds(30))
    .slidingWindowSize(10)
    .slidingWindowType(COUNT_BASED)
    .permittedNumberOfCallsInHalfOpenState(3)
    .build();
```

A `CircuitBreakerConfig` created for the imperative `CircuitBreaker.of(config)` is the same object passed to
`CoroutineCircuitBreaker.of(config)`. No conversion, no mapping, no paradigm-specific config variants.

Config objects are:

- **Immutable** after construction via the builder
- **Paradigm-agnostic** — no references to threads, schedulers, dispatchers, or reactive types
- **Validated at build time** — the builder rejects invalid combinations (e.g. negative thresholds)

### 2. Pure algorithms (no side effects, no threading)

Algorithmic logic that all paradigms share:

- **SlidingWindow** — Computes failure rate from a circular buffer of outcomes. Pure function: input is the buffer state
  and the new outcome, output is the updated buffer and the current failure rate. No locks, no atomics — the caller is
  responsible for synchronization in its paradigm's model. Two implementations: count-based (circular buffer) and
  time-based (time buckets), both driven by an injectable `InqClock` for testability (see ADR-016).
- **BackoffStrategy** — Computes the Duration for the next retry. `FixedBackoff`, `ExponentialBackoff`,
  `RandomizedBackoff` (jitter decorator). Pure function: input is attempt number and config, output is a `Duration`. The
  caller decides how to wait (Thread.sleep vs delay() vs Mono.delay()).
- **TokenBucket** — Computes whether a permit is available and updates the bucket state. Pure function: the caller
  decides how to synchronize access and how to wait for a permit.

The critical property: **these algorithms never call sleep, delay, await, lock, or any blocking/suspending operation.**
They compute values. The paradigm module decides how to act on those values.

### 3. Behavioral contracts (interfaces)

Each element has a behavioral contract that defines the decision logic without prescribing execution:

```java
public interface CircuitBreakerBehavior {
    boolean isCallPermitted(CircuitBreakerState currentState);
    CircuitBreakerState onSuccess(CircuitBreakerState currentState, long durationNanos);
    CircuitBreakerState onError(CircuitBreakerState currentState, long durationNanos, Throwable throwable);
    boolean shouldTransitionToHalfOpen(CircuitBreakerState currentState, Instant now);
}
```

The default implementation in core encodes the state machine logic: when to open, when to transition to half-open, when
to close. Paradigm modules delegate to this behavior for decisions and then execute the state transition with their own
concurrency primitives.

### 4. Event types (see ADR-003)

All event classes live in core. Paradigm modules instantiate and emit them when diagnostic events are enabled.

### What core does NOT contain

- No `InqPipeline` implementation — each paradigm provides its own pipeline builder
- No decorator implementations — decoration is paradigm-specific
- No thread management, scheduler references, or lock primitives
- No runtime dependencies beyond the JDK

## Consequences

**Positive:**

- **Behavioral equivalence by construction.** All paradigms use the same sliding window, the same backoff computation,
  and the same state machine logic. A circuit breaker configured identically behaves identically across Java, Kotlin,
  Reactor, and RxJava.
- **Single source of truth for configs.** No risk of config parameters being interpreted differently across paradigms.
- **Testable in isolation.** Pure algorithms are trivially unit-tested — no mocking of threads, schedulers, or reactive
  contexts required.
- **Minimal core dependency.** Core has zero runtime dependencies beyond the JDK. Every paradigm module pulls in only
  its own reactive library.

**Negative:**

- Behavioral contracts must be designed carefully — too prescriptive, and they constrain paradigm-specific
  optimizations; too loose, and implementations can diverge.
- Pure algorithm APIs must be ergonomic enough that paradigm implementors use them instead of reimplementing the logic.

**Verification:**
Cross-paradigm conformance tests (in `inqudium-test`) will exercise each element through the same scenario matrix and
assert identical behavioral outcomes. The execution path differs; the result must not.