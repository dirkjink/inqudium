# ADR-020: Bulkhead design

**Status:** Accepted  
**Date:** 2026-03-28  
**Deciders:** Core team

## Context

The Bulkhead isolates failures by limiting the number of concurrent calls to a downstream service. If a service slows down, the bulkhead prevents the slowdown from consuming all available threads/connections in the calling application — protecting unrelated services that share the same resources.

The name comes from ship construction: watertight compartments (bulkheads) prevent a hull breach from flooding the entire ship.

### Algorithm choice

Two common bulkhead implementations exist:

**Thread-pool isolation** — Each downstream service gets its own thread pool. Calls execute on the pool's threads, not the caller's thread. If the pool is exhausted, new calls are rejected. Used by Hystrix (Netflix). Provides strong isolation because the caller's thread is never blocked — it submits to the pool and waits on a Future.

**Semaphore isolation** — A counter limits concurrent calls. The caller's thread executes the call directly. If the counter is at its limit, the call is rejected (or waits). Used by Resilience4J.

### Why semaphore, not thread pool

Thread-pool isolation was designed for a world of expensive platform threads. Its purpose was to prevent one slow service from consuming all threads in a shared `ExecutorService`. With virtual threads (Java 21+, ADR-008), this rationale collapses:

- **Virtual threads are cheap.** Creating thousands of virtual threads is a lightweight operation. There is no shared thread pool to protect — each call gets its own virtual thread.
- **Context loss.** Thread-pool isolation moves execution to a different thread. `ThreadLocal` state (MDC, security context, transaction) does not propagate automatically. The context propagation SPI (ADR-011) mitigates this, but the overhead is unnecessary if the caller's thread can execute the call directly.
- **Overhead.** Submitting to a thread pool, waiting on a Future, and handling the result adds latency and allocation overhead compared to a direct call with a semaphore check.
- **Reactive incompatibility.** Thread-pool isolation fundamentally conflicts with reactive programming models (Reactor, RxJava) where execution should stay on the subscriber's scheduler. Injecting a thread pool breaks backpressure semantics.

Semaphore isolation is the right choice because:

- It works on the caller's thread — no context loss, no thread switch.
- It works identically for imperative, coroutine, and reactive paradigms.
- With virtual threads, the "protect the thread pool" argument for thread-pool isolation no longer applies.
- The concurrency limit is the only property that matters — and a semaphore (or counter) provides exactly that.

### How Resilience4J implements bulkheading

Resilience4J offers **both** isolation strategies as separate implementations:

**`SemaphoreBulkhead`** — Uses `java.util.concurrent.Semaphore` directly. `tryAcquire(maxWaitDuration)` blocks the caller's thread until a permit is available or the timeout expires. Configuration: `maxConcurrentCalls`, `maxWaitDuration`. The Semaphore handles both counting and waiting natively.

**`ThreadPoolBulkhead`** — Wraps a `ThreadPoolExecutor` with configurable `coreThreadPoolSize`, `maxThreadPoolSize`, `keepAliveDuration`, and `queueCapacity`. Calls are submitted to the pool and return `CompletionStage<T>` (not the raw result). If the pool and queue are both full, the call is rejected.

**Key differences from Inqudium:**

| Aspect | Resilience4J | Inqudium |
|---|---|---|
| Implementations | Two: SemaphoreBulkhead + ThreadPoolBulkhead | One: semaphore-based only |
| Thread safety | `java.util.concurrent.Semaphore` (blocking acquire) | Pure behavior function — caller synchronizes (paradigm-native) |
| Wait mechanism | `Semaphore.tryAcquire(timeout)` — blocks the calling thread | Paradigm-native: `parkNanos`, `delay()`, `Mono.delay()` |
| Thread-pool option | Yes — dedicated `ThreadPoolExecutor` per service | No — virtual threads make this unnecessary (ADR-008) |
| Return type (thread pool) | `CompletionStage<T>` (forced async) | N/A |
| Reactive support | SemaphoreBulkhead only — ThreadPoolBulkhead conflicts with reactive schedulers | Uniform across all paradigms |
| Purity | Embedded Semaphore state and blocking | Pure function — testable without concurrency primitives |

**Why Inqudium drops the ThreadPoolBulkhead:**

Resilience4J's `ThreadPoolBulkhead` was designed for Java 8-17 where platform threads were scarce and expensive. It solved a real problem: if a downstream service hangs, the calling application's shared `ForkJoinPool` or Tomcat thread pool would fill up with blocked threads, starving all other services. A dedicated thread pool per service isolated this failure.

With Java 21+ virtual threads (ADR-008), this problem disappears:

1. **Virtual threads are not pooled.** Each task gets a new virtual thread — there is no shared pool to exhaust. A hanging downstream service blocks virtual threads, but virtual threads cost kilobytes, not megabytes.
2. **The carrier thread pool is managed by the JVM.** Virtual threads yield their carrier thread when they block on I/O. A hanging downstream service does not consume carrier threads while waiting.
3. **The `ThreadPoolBulkhead` forces async return types.** Wrapping a synchronous call in `CompletionStage` adds complexity (exception handling, context propagation) that is unnecessary when the caller's thread can execute the call directly.

The semaphore-based bulkhead provides the same **concurrency limit** that the thread-pool bulkhead provides — it just doesn't provide the **thread isolation**. In a virtual-thread world, thread isolation is no longer needed. The concurrency limit is the property that matters.

For projects running on Java 8-17 without virtual threads, the semaphore bulkhead combined with a TimeLimiter (ADR-010) provides equivalent protection: the TimeLimiter bounds the caller's wait time, and the bulkhead bounds the number of concurrent calls. Together, they prevent thread exhaustion without a dedicated thread pool.

**Resilience4J's `Semaphore` vs. Inqudium's pure behavior:**

R4J's `SemaphoreBulkhead` uses `java.util.concurrent.Semaphore` directly — the Semaphore handles both counting and blocking internally. This works for imperative code but is problematic for reactive and coroutine paradigms:

- In Reactor, `Semaphore.tryAcquire(timeout)` would block the reactive scheduler thread — violating the non-blocking contract.
- In Kotlin, a blocking `tryAcquire` inside a coroutine causes carrier-thread pinning (ADR-008).

Inqudium separates the **decision** (is a permit available?) from the **waiting** (how to wait for one). The behavior function returns `BulkheadResult.denied()`, and the paradigm module decides how to wait using its native mechanism. No blocking in the core algorithm — ever.

## Decision

### BulkheadConfig

```java
public record BulkheadConfig(
    int maxConcurrentCalls,            // maximum number of concurrent calls permitted
    Duration maxWaitDuration,          // how long to wait for a permit (0 = fail immediately)
    InqCompatibility compatibility     // behavioral flags (ADR-013)
) {
    public static BulkheadConfig ofDefaults() {
        return BulkheadConfig.builder().build();
    }

    public static Builder builder() { ... }
}
```

Default values:

| Parameter | Default | Rationale |
|---|---|---|
| `maxConcurrentCalls` | 25 | Limits concurrent calls to a single downstream service. 25 is conservative for most services — high enough for normal throughput, low enough to prevent thread/connection exhaustion under failure. |
| `maxWaitDuration` | 0 (no wait) | Fail immediately when the bulkhead is full. Waiting is opt-in because it adds latency. |

### BulkheadBehavior

The behavioral contract manages a concurrency counter. Unlike the sliding window (ADR-016) or the token bucket (ADR-019), the bulkhead has **acquire** and **release** semantics — a permit is held for the duration of the call.

```java
public interface BulkheadBehavior {

    /**
     * Attempts to acquire a concurrency permit.
     *
     * @param state   current bulkhead state
     * @param config  bulkhead configuration
     * @return updated state with acquisition result
     *
     * Pure — no side effects. The caller decides how to wait if denied.
     */
    BulkheadResult tryAcquire(BulkheadState state, BulkheadConfig config);

    /**
     * Releases a previously acquired permit.
     * Must be called exactly once per successful tryAcquire, in a finally block.
     *
     * @param state  current bulkhead state
     * @return updated state with decremented concurrent call count
     */
    BulkheadState release(BulkheadState state);
}
```

Supporting types:

```java
public record BulkheadState(
    int concurrentCalls       // current number of in-flight calls
) {
    public static BulkheadState initial() {
        return new BulkheadState(0);
    }
}

public record BulkheadResult(
    boolean permitted,
    BulkheadState updatedState
) {
    public static BulkheadResult permitted(BulkheadState state) {
        return new BulkheadResult(true, state);
    }

    public static BulkheadResult denied(BulkheadState state) {
        return new BulkheadResult(false, state);
    }
}
```

### Acquire/release algorithm

```java
public class DefaultBulkheadBehavior implements BulkheadBehavior {

    @Override
    public BulkheadResult tryAcquire(BulkheadState state, BulkheadConfig config) {
        if (state.concurrentCalls() < config.maxConcurrentCalls()) {
            var newState = new BulkheadState(state.concurrentCalls() + 1);
            return BulkheadResult.permitted(newState);
        }
        return BulkheadResult.denied(state);
    }

    @Override
    public BulkheadState release(BulkheadState state) {
        int updated = Math.max(0, state.concurrentCalls() - 1);
        return new BulkheadState(updated);
    }
}
```

**Purity:** The algorithm is pure — it takes state in and returns state out. The paradigm module is responsible for synchronizing access to the shared state (ReentrantLock for imperative, Mutex for Kotlin, atomic operators for Reactor/RxJava).

### The acquire/release contract

Unlike other elements (Circuit Breaker, Retry, Rate Limiter) where the element wraps a single call, the Bulkhead has a **scoped lifecycle** — the permit is held from before the call until after the call completes (success or failure):

```java
// Imperative paradigm module (simplified)
public <T> T execute(Supplier<T> supplier) {
    var result = behavior.tryAcquire(state.get(), config);
    if (!result.permitted()) {
        throw new InqBulkheadFullException(name, state.get().concurrentCalls(), config.maxConcurrentCalls());
    }
    state.set(result.updatedState());
    try {
        return supplier.get();
    } finally {
        state.updateAndGet(s -> behavior.release(s));
    }
}
```

The `finally` block is critical — a permit must be released even if the call throws. Failure to release causes permit leakage, eventually exhausting the bulkhead permanently. Every paradigm module must guarantee release:

| Paradigm | Release guarantee |
|---|---|
| Imperative | `try/finally` block |
| Kotlin | `try/finally` inside coroutine, or `use {}` on a closeable scope |
| Reactor | `doFinally(signal -> release())` operator |
| RxJava 3 | `doFinally(() -> release())` operator |

### Waiting behavior

When the bulkhead is full and `maxWaitDuration > 0`, the caller waits for a permit to become available. The wait is a paradigm concern, not a behavior concern:

**`maxWaitDuration = 0` (default):** `InqBulkheadFullException` is thrown immediately (ADR-009).

**`maxWaitDuration > 0`:** The paradigm module polls or subscribes for permit availability:

| Paradigm | Wait mechanism |
|---|---|
| Imperative | `LockSupport.parkNanos()` + re-check loop with timeout (ADR-008) |
| Kotlin | `withTimeout(maxWaitDuration) { while (!tryAcquire()) delay(checkInterval) }` |
| Reactor | `Mono.defer(tryAcquire).repeatWhenEmpty(flux -> flux.delayElements(checkInterval)).timeout(maxWaitDuration)` |
| RxJava 3 | `Single.defer(tryAcquire).retryWhen(errors -> errors.delay(checkInterval)).timeout(maxWaitDuration)` |

### Interaction with TimeLimiter and orphaned calls (ADR-010)

When a TimeLimiter fires and the caller moves on, the orphaned call still holds a Bulkhead permit. The permit is released when the orphaned call completes (success or failure) — the `finally` block runs regardless of whether the caller is still waiting.

This means: under sustained timeouts, the Bulkhead may fill up with orphaned calls. This is by design — it is exactly the "TimeLimiter + Bulkhead" mitigation pattern from ADR-010. The Bulkhead caps the number of orphaned calls. Once full, new calls are rejected immediately, preventing unbounded resource consumption.

### Metrics

The Bulkhead emits events (ADR-003) for every acquisition and release:

- `BulkheadOnAcquireEvent` — permit acquired, `concurrentCalls` after acquisition
- `BulkheadOnRejectEvent` — permit denied, `concurrentCalls` at rejection time
- `BulkheadOnReleaseEvent` — permit released, `concurrentCalls` after release

The `concurrentCalls` field on each event enables real-time monitoring of bulkhead utilization. A Micrometer gauge can expose `current / max` as a saturation metric.

## Consequences

**Positive:**
- Semaphore isolation works identically across all paradigms — no thread-pool conflicts with reactive models.
- Virtual-thread friendly — no unnecessary thread switches, no platform thread waste.
- O(1) acquire/release — single counter increment/decrement.
- Pure behavioral contract — paradigm modules own the synchronization.
- The acquire/release lifecycle integrates naturally with the TimeLimiter + Bulkhead mitigation pattern (ADR-010).
- Metrics on every acquire/reject/release enable real-time saturation monitoring.

**Negative:**
- Semaphore isolation does not protect against caller-thread exhaustion in non-virtual-thread environments. If all 25 caller threads are blocked on slow downstream calls, the application has 25 stuck threads. Mitigation: use virtual threads (ADR-008) or combine with TimeLimiter (ADR-010).
- The polling wait mechanism (when `maxWaitDuration > 0`) adds a small check interval latency. The check interval is a trade-off between responsiveness and CPU usage.
- Permit leakage (failing to call `release()`) is a fatal correctness bug. Every paradigm module must guarantee `finally`-based release — this is enforced by behavioral contract tests.

**Neutral:**
- No thread-pool isolation option. If a project requires strict thread-pool isolation (e.g., for regulatory reasons), it should use a dedicated `ExecutorService` outside of Inqudium. The Bulkhead is a concurrency counter, not a thread pool manager.
- `maxConcurrentCalls` does not account for the weight of individual calls. A bulkhead of 25 treats a lightweight read and a heavyweight batch operation as equal. Weighted bulkheads are a potential future enhancement but not in scope for the initial release.
