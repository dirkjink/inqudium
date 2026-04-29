# ADR-020: Bulkhead design

**Status:** Accepted
**Date:** 2026-03-28
**Last updated:** 2026-04-24
**Deciders:** Core team

## Context

The Bulkhead isolates failures by limiting the number of concurrent calls to a downstream service. If a service slows
down, the bulkhead prevents the slowdown from consuming all available threads/connections in the calling application —
protecting unrelated services that share the same resources.

The name comes from ship construction: watertight compartments (bulkheads) prevent a hull breach from flooding the
entire ship.

### Algorithm choice

Two common bulkhead implementations exist:

**Thread-pool isolation** — Each downstream service gets its own thread pool. Calls execute on the pool's threads, not
the caller's thread. If the pool is exhausted, new calls are rejected. Used by Hystrix (Netflix). Provides strong
isolation because the caller's thread is never blocked — it submits to the pool and waits on a Future.

**Semaphore isolation** — A counter limits concurrent calls. The caller's thread executes the call directly. If the
counter is at its limit, the call is rejected (or waits). Used by Resilience4J.

### Why semaphore, not thread pool

Thread-pool isolation was designed for a world of expensive platform threads. Its purpose was to prevent one slow
service from consuming all threads in a shared `ExecutorService`. With virtual threads (Java 21+, ADR-008), this
rationale collapses:

- **Virtual threads are cheap.** Creating thousands of virtual threads is a lightweight operation. There is no shared
  thread pool to protect — each call gets its own virtual thread.
- **Context loss.** Thread-pool isolation moves execution to a different thread. `ThreadLocal` state (MDC, security
  context, transaction) does not propagate automatically. The context propagation SPI (ADR-011) mitigates this, but the
  overhead is unnecessary if the caller's thread can execute the call directly.
- **Overhead.** Submitting to a thread pool, waiting on a Future, and handling the result adds latency and allocation
  overhead compared to a direct call with a semaphore check.
- **Reactive incompatibility.** Thread-pool isolation fundamentally conflicts with reactive programming models (Reactor,
  RxJava) where execution should stay on the subscriber's scheduler. Injecting a thread pool breaks backpressure
  semantics.

Semaphore isolation is the right choice because:

- It works on the caller's thread — no context loss, no thread switch.
- It works identically for imperative, coroutine, and reactive paradigms.
- With virtual threads, the "protect the thread pool" argument for thread-pool isolation no longer applies.
- The concurrency limit is the only property that matters — and a counter (semaphore or atomic) provides exactly that.

### How Resilience4J implements bulkheading

Resilience4J offers **both** isolation strategies as separate implementations:

**`SemaphoreBulkhead`** — Uses `java.util.concurrent.Semaphore` directly. `tryAcquire(maxWaitDuration)` blocks the
caller's thread until a permit is available or the timeout expires. Configuration: `maxConcurrentCalls`,
`maxWaitDuration`. The Semaphore handles both counting and waiting natively.

**`ThreadPoolBulkhead`** — Wraps a `ThreadPoolExecutor` with configurable `coreThreadPoolSize`, `maxThreadPoolSize`,
`keepAliveDuration`, and `queueCapacity`. Calls are submitted to the pool and return `CompletionStage<T>` (not the raw
result). If the pool and queue are both full, the call is rejected.

**Key differences from Inqudium:**

| Aspect                    | Resilience4J                                                                   | Inqudium                                                                                 |
|---------------------------|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| Implementations           | Two: SemaphoreBulkhead + ThreadPoolBulkhead                                    | Counter-based only — semaphore (blocking) or atomic CAS (non-blocking) per paradigm      |
| Strategy split            | One implementation per type                                                    | Paradigm-typed contracts (`BlockingBulkheadStrategy` vs. `NonBlockingBulkheadStrategy`)  |
| Thread-pool option        | Yes — dedicated `ThreadPoolExecutor` per service                               | No — virtual threads make this unnecessary (ADR-008)                                     |
| Adaptive limits           | No                                                                             | Yes — pluggable `InqLimitAlgorithm` (AIMD, Vegas)                                        |
| Load shedding             | No                                                                             | Yes — `CoDelBulkheadStrategy` (sojourn-based drop)                                       |
| Reactive support          | SemaphoreBulkhead only — ThreadPoolBulkhead conflicts with reactive schedulers | First-class via `NonBlockingBulkheadStrategy`                                            |

**Why Inqudium drops the ThreadPoolBulkhead:**

Resilience4J's `ThreadPoolBulkhead` was designed for Java 8-17 where platform threads were scarce and expensive. It
solved a real problem: if a downstream service hangs, the calling application's shared `ForkJoinPool` or Tomcat thread
pool would fill up with blocked threads, starving all other services. A dedicated thread pool per service isolated this
failure.

With Java 21+ virtual threads (ADR-008), this problem disappears:

1. **Virtual threads are not pooled.** Each task gets a new virtual thread — there is no shared pool to exhaust. A
   hanging downstream service blocks virtual threads, but virtual threads cost kilobytes, not megabytes.
2. **The carrier thread pool is managed by the JVM.** Virtual threads yield their carrier thread when they block on I/O.
   A hanging downstream service does not consume carrier threads while waiting.
3. **The `ThreadPoolBulkhead` forces async return types.** Wrapping a synchronous call in `CompletionStage` adds
   complexity (exception handling, context propagation) that is unnecessary when the caller's thread can execute the
   call directly.

The counter-based bulkhead provides the same **concurrency limit** that the thread-pool bulkhead provides — it just
doesn't provide the **thread isolation**. In a virtual-thread world, thread isolation is no longer needed. The
concurrency limit is the property that matters.

For projects running on Java 8-17 without virtual threads, the bulkhead combined with a TimeLimiter (ADR-010) provides
equivalent protection: the TimeLimiter bounds the caller's wait time, and the bulkhead bounds the number of concurrent
calls. Together, they prevent thread exhaustion without a dedicated thread pool.

## Decision

### Strategy contracts — paradigm-typed by design

Bulkhead permit management is split into **two contracts** at the core level. The split is a deliberate API decision:
each paradigm picks the contract that fits its execution model. There is no "one strategy for all paradigms" — that
would either force blocking semantics on reactive/coroutine code (carrier-thread pinning) or force non-blocking
semantics on imperative code (no natural way to wait for a permit).

```java
public interface BulkheadStrategy {                  // shared base — lifecycle + introspection
    void release();
    void rollback();
    default void onCallComplete(long rttNanos, boolean isSuccess) { /* adaptive only */ }
    int availablePermits();
    int concurrentCalls();
    int maxConcurrentCalls();
}

public interface BlockingBulkheadStrategy extends BulkheadStrategy {
    RejectionContext tryAcquire(Duration timeout) throws InterruptedException;
}

public interface NonBlockingBulkheadStrategy extends BulkheadStrategy {
    RejectionContext tryAcquire();
}
```

The acquire signatures differ on purpose — `BlockingBulkheadStrategy.tryAcquire(Duration)` may park the calling thread
up to the timeout (and may throw `InterruptedException`); `NonBlockingBulkheadStrategy.tryAcquire()` returns
immediately with a yes/no decision and never throws.

**Acquire return convention.** Both methods return `null` on the happy path (no allocation when a permit is granted)
or a `RejectionContext` describing why the request was denied. This is a deliberate zero-allocation optimization: the
common case is "permit granted" and pays no object cost; the rejection case carries diagnostic data for downstream use
in exceptions and events.

**Strategies are stateful.** Unlike the pure-function model that earlier drafts of this ADR sketched, real strategies
own internal concurrency primitives — a `Semaphore` for `SemaphoreBulkheadStrategy`, an `AtomicInteger` (CAS loop) for
`AtomicNonBlockingBulkheadStrategy`, a `ReentrantLock` + `Condition` for the adaptive blocking variant. Strategy
instances are bound to a single bulkhead instance for that bulkhead's lifetime.

**Paradigm wiring.** A paradigm module accepts only the contract that fits:

| Paradigm                | Strategy contract                                            |
|-------------------------|--------------------------------------------------------------|
| Imperative              | `BlockingBulkheadStrategy`                                   |
| Reactive (Reactor/Rx3)  | `NonBlockingBulkheadStrategy`                                |
| Kotlin coroutines       | `NonBlockingBulkheadStrategy` (avoids carrier-thread pinning)|

Mixing is rejected by the compile-time contract: the imperative `ImperativeBulkhead` constructor takes a
`BlockingBulkheadStrategy` and would not accept a `NonBlockingBulkheadStrategy`, and vice versa.

#### Why the split is in `inqudium-core`, not in paradigm modules

Both contracts live in core because adaptive and CoDel strategies are paradigm-agnostic but tied to a specific
acquire shape. The `InqLimitAlgorithm` SPI (see "Adaptive bulkheads" below) drives a non-blocking adaptive variant
in core (`AdaptiveNonBlockingBulkheadStrategy`) and a blocking adaptive variant in the imperative module
(`AdaptiveBulkheadStrategy`). Keeping both contracts in core lets the algorithm SPI be defined once and consumed by
either family.

#### What "no blocking in core" means here

An earlier draft of this ADR claimed "no blocking in the core algorithm — ever". That overstates the rule. The
accurate version is:

- The core contracts `BulkheadStrategy` and `NonBlockingBulkheadStrategy` are non-blocking by definition.
- The `BlockingBulkheadStrategy` *contract* lives in core, but its *implementations* (Semaphore, Adaptive, CoDel) live
  in the paradigm module that needs them. A reactive paradigm never instantiates them.
- The pure-algorithm rule from CLAUDE.md (`no Thread.sleep`, `no synchronized`, `no schedulers in core`) holds for the
  core strategy implementations. `AtomicNonBlockingBulkheadStrategy` and `AdaptiveNonBlockingBulkheadStrategy` are
  CAS-only, lock-free, allocation-free on the happy path.

### `RejectionContext` and `RejectionReason`

`RejectionContext` is the canonical rejection-payload type:

```java
public record RejectionContext(
    RejectionReason reason,
    int limitAtDecision,        // limit enforced at decision time (current adaptive limit, not configured max)
    int activeCallsAtDecision,  // calls holding a permit at decision time
    long waitedNanos,           // time spent waiting before rejection (0 for non-blocking strategies)
    long sojournNanos           // CoDel post-lock queue wait (0 for non-CoDel strategies)
) { ... }
```

Captured **inside** the strategy's decision logic — within the CAS loop or the lock-guarded block — so every field
reflects the true state that caused the rejection. This eliminates the TOCTOU pitfall where a post-hoc call to
`concurrentCalls()` returns a value that has already changed between the rejection and the diagnostic read.

```java
public enum RejectionReason {
    CAPACITY_REACHED,         // limit met or exceeded at the moment of decision
    TIMEOUT_EXPIRED,          // blocking only — caller's wait expired without a permit becoming free
    CODEL_SOJOURN_EXCEEDED    // CoDel only — load shed despite a permit being available
}
```

The CoDel reason is operationally important: a `CODEL_SOJOURN_EXCEEDED` with low active calls is *expected behavior*
during sustained congestion, not a bug. Distinguishing it from `CAPACITY_REACHED` matters for incident triage.

`RejectionContext.toString()` produces human-readable summaries (`"CAPACITY_REACHED (10/10 concurrent calls, no
wait)"`, `"TIMEOUT_EXPIRED (10/10 concurrent calls, waited 500ms)"`, `"CODEL_SOJOURN_EXCEEDED (8/10 concurrent
calls, sojourn 1200ms, waited 1500ms)"`) suitable for exception messages and structured-log fields.

### Built-in strategies

#### Static (fixed-limit)

| Strategy                              | Module     | Acquire mechanism                              |
|---------------------------------------|------------|------------------------------------------------|
| `SemaphoreBulkheadStrategy`           | imperative | Fair `Semaphore.tryAcquire(timeout, NANOS)`    |
| `AtomicNonBlockingBulkheadStrategy`   | core       | CAS loop on a single `AtomicInteger`           |

`SemaphoreBulkheadStrategy` uses a *fair* semaphore (FIFO queue) and an `AtomicInteger` shadow counter for the
over-release guard (since `Semaphore.release()` does not check whether the caller holds a permit). The fair semaphore
means even `tryAcquire(Duration.ZERO)` respects the queue — a zero-timeout call may be rejected when the bulkhead has
free permits but other threads are already queued.

`AtomicNonBlockingBulkheadStrategy` uses a single `AtomicInteger` with `compareAndSet`. The acquire path is one
volatile read + one CAS; the release path is one CAS with a decrement-if-positive guard. No AQS infrastructure, no
queue, no fairness concept (irrelevant for instant decisions).

#### Adaptive

A pluggable `InqLimitAlgorithm` SPI computes a dynamic concurrency limit based on call outcomes:

```java
public interface InqLimitAlgorithm {
    int getLimit();                                                 // current dynamic limit
    void update(long rttNanos, boolean isSuccess, int inFlight);    // feedback after each completed call
}
```

Two implementations ship today:

- `AimdLimitAlgorithm` — Additive Increase / Multiplicative Decrease. Increases the limit on success, multiplicatively
  reduces it on failure or latency violation. Predictable, simple to reason about.
- `VegasLimitAlgorithm` — TCP Vegas-inspired. Uses RTT trends to detect queueing before failures appear. More
  responsive in latency-sensitive workloads.

Two adaptive strategies wrap the algorithm:

- `AdaptiveNonBlockingBulkheadStrategy` (core) — CAS loop reads the algorithm's current limit on each iteration. No
  locks, no signaling — limit changes are visible to concurrent acquirers via volatile reads inside the algorithm.
- `AdaptiveBulkheadStrategy` (imperative) — `Condition.awaitNanos(long)` under a `ReentrantLock`. The condition is
  signalled on release and on limit changes so parked threads re-evaluate.

**Feedback ordering matters.** The reactive/imperative facade must call `onCallComplete(rttNanos, isSuccess)` *before*
`release()` so the in-flight count passed to the algorithm still includes the completing call. Reversing the order
makes the algorithm see an artificially low in-flight count, suppressing limit increases when
`minUtilizationThreshold > 0`. Forgetting `onCallComplete` entirely silently degrades the adaptive strategy to a static
limiter.

To eliminate this hazard, adaptive strategies expose a `completeAndRelease(long rttNanos, boolean isSuccess)`
convenience method that performs both steps in the correct order with a `try/finally` guarantee. Facades should prefer
it over the two-step form.

**Over-limit transient state.** When the algorithm decreases the limit (e.g. 20 → 10) while 15 calls are in flight,
`activeCalls` (15) exceeds the new limit. The strategy does *not* forcibly revoke permits — it simply refuses new
acquisitions until the count drops below the new limit naturally. Both adaptive strategies follow this rule.

#### CoDel (controlled delay) load shedding

`CoDelBulkheadStrategy` (imperative) implements the CoDel queue-management algorithm. A permit is granted normally if
sojourn time (post-lock wait) stays below the target delay; if sojourn time exceeds the target for longer than one
interval, the strategy starts dropping requests *even when permits are available*, breaking the queueing-collapse
cycle.

Rejected requests carry `RejectionReason.CODEL_SOJOURN_EXCEEDED` plus a `BulkheadCodelRejectedTraceEvent` (TRACE
category) for diagnostics.

### Lifecycle methods: `release` vs. `rollback`

The two methods are mechanically identical (decrement the active count) but semantically distinct:

- `release()` — the business call ran (success or failure) and is now done.
- `rollback()` — the permit was acquired, but the *acquire-side telemetry* failed (an event publisher threw) and the
  business call never started. The rollback exists so the failed publish doesn't leak permits.

`ImperativeBulkhead` uses both: a normal completion calls `release()` from the `finally` block; a thrown
`BulkheadOnAcquireEvent` publish triggers `rollback()` plus an optional `BulkheadRollbackTraceEvent` (TRACE) before
the original exception propagates.

### Over-release safety

All strategies defend against double-release and release-without-acquire:

- `SemaphoreBulkheadStrategy` — the shadow `AtomicInteger.getAndUpdate(c -> c > 0 ? c - 1 : 0)` only releases the
  underlying semaphore when the shadow count was positive. A double-release becomes a no-op.
- `AtomicNonBlockingBulkheadStrategy` and `AdaptiveNonBlockingBulkheadStrategy` — CAS loop with a
  decrement-if-positive guard. Going below zero is impossible.

Permit leakage (forgetting to call `release` after a successful acquire) is still a fatal correctness bug — but it
must come from a paradigm-module bug, not from a misuse pattern that the strategies silently absorb.

### Configuration

Configuration is layered. The user-facing configuration for the imperative paradigm is `InqImperativeBulkheadConfig`,
which composes a core `InqBulkheadConfig` plus a `GeneralConfig`:

```java
public record InqBulkheadConfig(
    GeneralConfig general,
    InqElementCommonConfig common,
    int maxConcurrentCalls,
    BulkheadStrategy strategy,            // explicit strategy injection
    Duration maxWaitDuration,
    InqLimitAlgorithm limitAlgorithm,     // null for static strategies
    BulkheadEventConfig eventConfig
) implements InqElementConfig, ConfigExtension<InqBulkheadConfig> { ... }

public record InqImperativeBulkheadConfig(
    GeneralConfig general,
    InqBulkheadConfig bulkhead
) implements InqElementConfig, ConfigExtension<InqImperativeBulkheadConfig> { ... }
```

`InqImperativeBulkheadConfig.inference()` picks a strategy when none is set explicitly:

- If exactly one of `VegasLimitAlgorithmConfig` / `AimdLimitAlgorithmConfig` is present in the general config, the
  inference defers strategy creation to a follow-up step that builds the matching adaptive strategy.
- Otherwise it defaults to `new SemaphoreBulkheadStrategy(maxConcurrentCalls())`.

A separate DSL record `eu.inqudium.core.element.bulkhead.dsl.BulkheadConfig(name, maxConcurrentCalls, maxWaitDuration,
inqConfig)` exists for annotation-driven and DSL setups; it is a thin user-facing wrapper that resolves to one of the
records above.

Defaults:

| Parameter            | Default                          | Rationale                                                                                              |
|----------------------|----------------------------------|--------------------------------------------------------------------------------------------------------|
| `maxConcurrentCalls` | builder-defined                  | Set by the application — there is no global default at the strategy level; pick based on downstream capacity. |
| `maxWaitDuration`    | builder-defined                  | Common choice: `Duration.ZERO` (fail immediately when full).                                           |
| `eventConfig`        | `BulkheadEventConfig.standard()` | Only rejection events on; lifecycle and trace off. See ADR-003 for the framework rationale.            |
| `strategy`           | `SemaphoreBulkheadStrategy`      | Picked by `inference()` when no algorithm and no explicit strategy is configured.                      |

### Bulkhead facade — sync and async

The imperative facade is `ImperativeBulkhead<A, R>`, which implements both `InqDecorator` (sync `execute`) and
`InqAsyncDecorator` (async `executeAsync`). The same instance handles both call styles.

#### Synchronous path

```java
@Override
public R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next) {
    long startWait = eventConfig.isTraceEnabled() ? nanoTimeSource.now() : 0L;

    RejectionContext rejection;
    try {
        rejection = strategy.tryAcquire(maxWaitDuration);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        handleAcquireFailure(chainId, callId, startWait, null);
        throw new InqBulkheadInterruptedException(chainId, callId, name, ...);
    }
    if (rejection != null) {
        handleAcquireFailure(chainId, callId, startWait, rejection);
        throw new InqBulkheadFullException(chainId, callId, name, rejection, ...);
    }
    handleAcquireSuccess(chainId, callId, startWait);   // optional acquire/wait events; rollback on publish failure

    long startNanos = nanoTimeSource.now();
    Throwable businessError = null;
    try {
        return next.execute(chainId, callId, argument);
    } catch (Throwable t) {
        businessError = t;
        throw t;
    } finally {
        long rttNanos = nanoTimeSource.now() - startNanos;
        releaseAndReport(chainId, callId, rttNanos, businessError);  // onCallComplete -> release -> optional release event
    }
}
```

Notable points beyond a trivial acquire/execute/release:

- `chainId` and `callId` flow as primitive `long` values throughout (ADR-022). No string conversion on the hot path.
- The wait-time `nanoTime()` call only happens when TRACE is enabled — saving a native call on every happy-path acquire
  in the standard configuration.
- Publish failures during `BulkheadOnAcquireEvent` trigger `strategy.rollback()` so the failed publish does not leak
  the permit. An optional `BulkheadRollbackTraceEvent` records the rollback when TRACE is on.
- `releaseAndReport` calls `strategy.onCallComplete(rttNanos, isSuccess)` *before* `strategy.release()` (correct
  feedback ordering for adaptive strategies). Algorithm-hook failures are logged but never block the release.

#### Asynchronous path

```java
@Override
public CompletionStage<R> executeAsync(long chainId, long callId, A argument, InternalAsyncExecutor<A, R> next) { ... }
```

The async path is two-phase around-advice:

- **Start phase** (synchronous, on the calling thread): acquire via the same `BlockingBulkheadStrategy`. The caller
  experiences backpressure if the bulkhead is at capacity.
- **End phase** (asynchronous, on the completing thread): release plus optional release-event via `whenComplete()`.

Per ADR-023, the facade returns the **decorated copy** produced by `whenComplete()`, not the original
`CompletionStage`. Exceptions thrown inside `releaseAndReport` surface explicitly on the caller's future rather than
disappearing on a detached branch. Fast path: if the downstream stage is already a completed `CompletableFuture`, the
release callback runs inline and the original is returned — no callback is registered, so no two-object split.

### Wait behavior

When `maxWaitDuration > 0`, the *blocking* strategy implementation decides how to wait. The mechanism depends on the
strategy:

| Strategy                       | Wait mechanism                                                     |
|--------------------------------|--------------------------------------------------------------------|
| `SemaphoreBulkheadStrategy`    | `Semaphore.tryAcquire(timeout, TimeUnit.NANOSECONDS)` (fair queue) |
| `AdaptiveBulkheadStrategy`     | `Condition.awaitNanos(long)` under a `ReentrantLock`               |
| `CoDelBulkheadStrategy`        | `Condition.awaitNanos(long)` plus sojourn-time evaluation          |

When `maxWaitDuration == Duration.ZERO`, the same APIs are used with a zero timeout. The fair semaphore still respects
the FIFO queue — see the `SemaphoreBulkheadStrategy` Javadoc for the implication (a zero-timeout call may be rejected
even when permits are nominally available).

Non-blocking strategies do not wait by definition; they decide instantly. Whether a reactive facade then re-attempts
on a delayed signal is a paradigm-module concern — not part of the strategy contract.

### Interaction with TimeLimiter and orphaned calls (ADR-010)

When a TimeLimiter fires and the caller moves on, the orphaned call still holds a Bulkhead permit. The permit is
released when the orphaned call completes (success or failure) — the `finally` block runs regardless of whether the
caller is still waiting.

This means: under sustained timeouts, the Bulkhead may fill up with orphaned calls. This is by design — it is exactly
the "TimeLimiter + Bulkhead" mitigation pattern from ADR-010. The Bulkhead caps the number of orphaned calls. Once
full, new calls are rejected immediately, preventing unbounded resource consumption.

### Observability

The Bulkhead participates in the two-tier observability model defined in ADR-003. This ADR documents the
bulkhead-specific surface; refer to ADR-003 for the publisher contract, `InqEventPublisher` lifecycle, exporter
registry, and `publishTrace` / `isTraceEnabled` mechanics.

#### Tier 1: Polling-based metrics (always on, zero per-call overhead)

| Metric                                   | Source                          | Type    |
|------------------------------------------|---------------------------------|---------|
| `inqudium.bulkhead.concurrent.calls`     | `strategy.concurrentCalls()`    | Gauge   |
| `inqudium.bulkhead.available.permits`    | `strategy.availablePermits()`   | Gauge   |
| `inqudium.bulkhead.max.concurrent.calls` | `strategy.maxConcurrentCalls()` | Gauge   |
| `inqudium.bulkhead.rejections.total`     | `LongAdder` in rejection path   | Counter |

For adaptive strategies, `maxConcurrentCalls()` returns the algorithm's *current* limit, not the configured initial
value — so the gauge reflects adaptive movement. A Micrometer `MeterBinder` (in `inqudium-micrometer`) registers all
four once per bulkhead.

#### Tier 2: Diagnostic events

Per-call events are gated by `BulkheadEventConfig` plus the `BulkheadEventCategory` enum (`LIFECYCLE`, `REJECTION`,
`TRACE`):

| Event                                | Category    | When emitted                                                                                  |
|--------------------------------------|-------------|-----------------------------------------------------------------------------------------------|
| `BulkheadOnAcquireEvent`             | `LIFECYCLE` | Permit granted — includes `concurrentCalls` at acquisition                                    |
| `BulkheadOnReleaseEvent`             | `LIFECYCLE` | Permit released — includes `concurrentCalls` after release                                    |
| `BulkheadOnRejectEvent`              | `REJECTION` | Permit denied — includes the full `RejectionContext`                                          |
| `BulkheadWaitTraceEvent`             | `TRACE`     | Wait duration for an acquire attempt (success or failure)                                     |
| `BulkheadRollbackTraceEvent`         | `TRACE`     | Permit rolled back because the acquire-event publish threw                                    |
| `BulkheadCodelRejectedTraceEvent`    | `TRACE`     | CoDel-specific complement to `BulkheadOnRejectEvent` (sojourn-based drop)                     |
| `BulkheadLimitChangedTraceEvent`     | `TRACE`     | Adaptive algorithm changed the effective limit                                                |

Presets:

| Preset         | Categories enabled        | Happy-path cost |
|----------------|---------------------------|-----------------|
| `standard()`   | `REJECTION` only          | 0 B/op          |
| `diagnostic()` | All three categories      | ~80 B/op        |

`BulkheadEventConfig.of(category, ...)` enables an arbitrary subset. The lifecycle and trace gates are resolved into
plain `boolean` fields at construction, so the hot-path guard is one field read with no set lookup or virtual dispatch.
TRACE events are published via `eventPublisher.publishTrace(Supplier)` so the event object is only constructed when a
consumer or exporter is interested (see ADR-003).

### Exceptions

Two exception types are thrown by the bulkhead facade (ADR-009 codes `INQ-BH-NNN`, ADR-021 element symbol `BH`):

| Code         | Type                              | Cause                                                                          |
|--------------|-----------------------------------|--------------------------------------------------------------------------------|
| `INQ-BH-001` | `InqBulkheadFullException`        | Permit denied — reason in `getRejectionContext()` / `getRejectionReason()`     |
| `INQ-BH-002` | `InqBulkheadInterruptedException` | Caller thread interrupted while waiting for a permit (no rejection decision)   |

`InqBulkheadFullException`:

- Carries the immutable `RejectionContext` captured at decision time. `getRejectionReason()`, `getLimitAtDecision()`,
  and the rest are convenience accessors over that record.
- Overrides `fillInStackTrace()` to a no-op. A bulkhead rejection is a flow-control signal, not a programming error;
  `RejectionContext` already carries the diagnostic data, and stack-trace generation dominates the rejection path's
  cost under high rejection rates. Callers that need a stack trace should rethrow.

`InqBulkheadInterruptedException`:

- Restores the thread's interrupt flag (`Thread.currentThread().interrupt()`) before being thrown.
- Carries no `RejectionContext` — the strategy never made a rejection decision, so any post-hoc snapshot would
  misrepresent the cause.

Both exceptions extend `InqException` and therefore carry `chainId`, `callId`, `code`, `elementName`, and
`elementType` (ADR-009).

### Registry

Named bulkhead instances are managed by `BulkheadRegistry` (per ADR-015): `register`, `get(name)`, `get(name, config)`,
`find`, `getAllNames`, `getAll`. The registry follows the first-registration-wins rule from the registry contract.

## Consequences

**Positive:**

- The paradigm-typed strategy split makes incompatible wiring impossible at compile time — an imperative bulkhead
  cannot be built around a non-blocking strategy and vice versa.
- Counter-based isolation works identically across paradigms — no thread-pool conflicts with reactive or coroutine
  models.
- Virtual-thread friendly — no unnecessary thread switches, no platform thread waste.
- O(1) acquire/release on the static strategies; lock-free on both non-blocking variants.
- Adaptive strategies (AIMD, Vegas) plug in without changing the facade — the algorithm SPI is the single extension
  point.
- CoDel provides explicit load shedding without coupling to the rest of the bulkhead surface; rejection diagnostics
  distinguish CoDel drops from capacity-reached.
- `RejectionContext` captured inside the decision lock eliminates TOCTOU pitfalls in diagnostics — the values match
  the cause.
- Two-tier observability (ADR-003): polling gauges achieve 0 B/op on the happy path; diagnostic events provide
  per-call depth on demand. Standard mode is allocation-free.
- The acquire/release lifecycle integrates naturally with the TimeLimiter + Bulkhead mitigation pattern (ADR-010).

**Negative:**

- Counter-based isolation does not protect against caller-thread exhaustion in non-virtual-thread environments. If
  all in-flight caller threads are blocked on slow downstream calls, the application has stuck threads. Mitigation:
  use virtual threads (ADR-008) or combine with TimeLimiter (ADR-010).
- Adaptive feedback ordering (`onCallComplete` *before* `release`) is a correctness contract that paradigm modules must
  honour. The `completeAndRelease` helper exists to remove this footgun, but callers wiring the steps manually can
  still get it wrong.
- `BlockingBulkheadStrategy` implementations may park the caller's thread. With virtual threads this is cheap; on a
  pre-21 JVM running in a thread-pool it can starve the pool — the same hazard the TimeLimiter mitigates.
- Switching from `standard()` to `diagnostic()` event mode introduces ~80 B/op allocation overhead from lifecycle
  events. Acceptable for short troubleshooting sessions; not for always-on production telemetry.
- The fair `SemaphoreBulkheadStrategy` may reject zero-timeout requests even when permits are available, because the
  fair queue takes priority. This is the correct trade-off for FIFO fairness but is surprising — documented in the
  strategy's Javadoc.

**Neutral:**

- No thread-pool isolation option. Projects that require strict thread-pool isolation (e.g. for regulatory reasons)
  should use a dedicated `ExecutorService` outside Inqudium. The Bulkhead is a concurrency counter, not a thread-pool
  manager.
- `maxConcurrentCalls` does not weight individual calls. A bulkhead of N treats a lightweight read and a heavyweight
  batch operation as equal. Weighted bulkheads remain a possible future extension but are not in scope.
- Production metrics are delivered by polling gauges, not events. A Micrometer binder (or equivalent) is required for
  dashboard visibility — the event system alone does not provide continuous metrics in `standard()` mode.
