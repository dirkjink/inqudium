# ADR-004: Native implementation per paradigm

**Status:** Accepted  
**Date:** 2026-03-22  
**Deciders:** Core team

## Context

Resilience4J and similar libraries follow an adapter pattern: resilience logic is implemented once in an imperative
core, and reactive support is provided through thin wrapper modules that bridge between the imperative internals and
reactive types (`Mono`, `Flux`, `Flow`, `Single`).

This approach has well-documented problems in production:

1. **Thread model mismatch.** An imperative circuit breaker that uses `synchronized` for state transitions pins virtual
   threads to the carrier thread (JEP 444). A coroutine wrapper around such a breaker forces the coroutine dispatcher
   into a blocking state that defeats the purpose of structured concurrency.

2. **Backpressure is bolted on, not native.** When a Reactor operator wraps an imperative rate limiter that blocks on
   `Semaphore.acquire()`, the blocking call does not participate in the reactive `Subscription.request(n)` protocol.
   The "backpressure" is actually thread blocking disguised as flow control.

3. **Cancellation semantics leak.** A `Mono` that wraps a blocking call cannot propagate cancellation into the blocking
   operation. When the downstream subscriber cancels, the blocking call continues to completion — wasting resources and
   producing results that are discarded.

4. **Testing asymmetry.** The imperative implementation is tested thoroughly. The reactive wrappers receive less
   rigorous testing because they are "just bridges." In practice, the bridge layer is where subtle bugs (lost signals,
   duplicate subscriptions, incorrect scheduler usage) accumulate.

## Decision

Every execution paradigm gets its own **native implementation** of each resilience element, built from the ground up on
that paradigm's concurrency primitives.

### Concurrency primitive mapping

| Concern         | Imperative (Java)             | Kotlin Coroutines           | Project Reactor           | RxJava 3                  |
|-----------------|-------------------------------|-----------------------------|---------------------------|---------------------------|
| State locking   | `ReentrantLock`               | `kotlinx.sync.Mutex`        | `Sinks.One<State>`        | `BehaviorSubject<State>`  |
| Timed waiting   | `LockSupport.parkNanos`       | `delay()`                   | `Mono.delay()`            | `Single.timer()`          |
| Concurrency cap | `j.u.c.Semaphore`             | `kotlinx.sync.Semaphore`    | `flatMap(maxConcurrency)` | `flatMap(maxConcurrency)` |
| Timeout         | `CompletableFuture.orTimeout` | `withTimeout()`             | `Mono.timeout()`          | `Single.timeout()`        |
| Backpressure    | Blocking queue                | `Flow` (suspending collect) | `Subscription.request(n)` | `Flowable.request(n)`     |

### What "native" means concretely

For the **Circuit Breaker** as an example:

- **Imperative:** State transitions are guarded by `ReentrantLock`. The OPEN→HALF_OPEN timeout is managed by a
  `ScheduledExecutorService`. State is stored in a volatile field behind the lock.
- **Kotlin:** State transitions are guarded by `Mutex`. The OPEN→HALF_OPEN timeout uses `delay()` inside a coroutine
  launched in the element's `CoroutineScope`. State is stored in a `MutableStateFlow`.
- **Reactor:** State transitions use `Sinks.One` to atomically publish the new state. The OPEN→HALF_OPEN timeout is a
  `Mono.delay()` that completes the transition. Upstream subscription is rejected with `CallNotPermittedException` when
  open — no blocking.
- **RxJava 3:** State transitions use `BehaviorSubject`. The OPEN→HALF_OPEN timeout is a `Single.timer()`. The
  transformer short-circuits the downstream `Single`/`Flowable` when the breaker is open.

### What is NOT native

Configuration, pure algorithms, and events live in `inqudium-core` and are shared across all paradigms (see ADR-005).
The native implementations call into these shared components for behavioral decisions — e.g. "should the breaker open
based on the current sliding window?" — but execute the actual state transition using their own concurrency primitives.

## Consequences

**Positive:**

- No thread model mismatch. Each paradigm uses the concurrency primitives it was designed for.
- Native backpressure. Reactive implementations participate in `request(n)` / `Flow` suspension natively — not through
  blocking bridges.
- Cancellation propagation. When a `Mono` subscriber cancels, the reactive circuit breaker actually stops. No wasted
  resources.
- Testable in isolation. Each paradigm's implementation is tested with that paradigm's test tools (`runTest` for
  coroutines, `StepVerifier` for Reactor, `TestObserver` for RxJava).
- Virtual-thread ready. The imperative layer avoids `synchronized` entirely (see ADR-008).

**Negative:**

- More code to write and maintain — roughly 4x the implementation effort compared to a single implementation + adapters.
- Risk of behavioral divergence between paradigms. Mitigated by shared behavioral contracts in core (see ADR-005) and
  cross-paradigm conformance tests.
- Higher bar for contributors — a new resilience element requires implementation in all four paradigms (though paradigm
  modules can lag behind the imperative module initially).

**Trade-off assessment:**
The maintenance cost is real but bounded. The resilience patterns (Circuit Breaker, Retry, Rate Limiter, Bulkhead, Time
Limiter, Cache) are stable and well-understood — they don't change frequently. The implementation effort is a one-time
cost; the correctness benefit is ongoing.
