# Bulkhead

The bulkhead isolates failures by limiting the number of concurrent calls to a downstream service. If a service slows
down, the bulkhead prevents it from consuming all available threads or connections — protecting unrelated services.

## Quick start

Bulkheads are declared inside the `.imperative(...)` section of `Inqudium.configure()` and looked up by name on the
runtime container:

```java
InqRuntime runtime = Inqudium.configure()
    .imperative(im -> im
        .bulkhead("inventory", b -> b
            .maxConcurrentCalls(25)              // max 25 in-flight calls
            .maxWaitDuration(Duration.ZERO))     // fail immediately (default)
    )
    .build();

var inventory = runtime.imperative().bulkhead("inventory");
```

The component name is a method argument, not a setter — "forgot to set the name" is a compile-time problem now.

## Acquire/release lifecycle

Unlike other elements, the bulkhead holds a permit for the *duration* of the call. Every successful acquire must be
paired with exactly one release in a finally block. Failure to release causes permit leakage — the bulkhead will
eventually fill up permanently.

The paradigm module guarantees this pairing:

| Paradigm   | Guarantee                        |
|------------|----------------------------------|
| Imperative | `try/finally` block              |
| Kotlin     | `try/finally` inside coroutine   |
| Reactor    | `doFinally(signal -> release())` |
| RxJava 3   | `doFinally(() -> release())`     |

## Why semaphore, not thread pool

Resilience4J offers both `SemaphoreBulkhead` and `ThreadPoolBulkhead`. Inqudium only provides semaphore-based isolation
because Java 21+ virtual threads make thread-pool isolation unnecessary. Virtual threads are cheap to create and do not
share a limited pool — there is no shared resource to protect with a dedicated thread pool.

## Waiting behavior

By default (`maxWaitDuration = 0`), a denied call throws `InqBulkheadFullException` (`INQ-BH-001`) immediately. To wait
for a permit, set a non-zero duration:

```java
.bulkhead("inventory", b -> b
    .maxConcurrentCalls(10)
    .maxWaitDuration(Duration.ofMillis(200)))   // wait up to 200ms for a permit
```

`maxWaitDuration` applies to the three blocking [strategies](#strategies) — `semaphore`, `codel`, and the blocking
`adaptive` variant. The `adaptiveNonBlocking` strategy ignores the field entirely and fails fast on saturation.

## Presets

Three named presets cover the common starting points. They establish a baseline for `maxConcurrentCalls` and
`maxWaitDuration`; individual setters can refine the result afterwards (preset-then-customize order is required —
calling a preset after a setter throws `IllegalStateException`).

| Preset       | `maxConcurrentCalls` | `maxWaitDuration`  | Intent                                              |
|--------------|----------------------|--------------------|-----------------------------------------------------|
| `protective` | 10                   | `Duration.ZERO`    | Conservative limits, fail-fast — critical services. |
| `balanced`   | 50                   | 500&nbsp;ms        | Production default — reasonable headroom.           |
| `permissive` | 200                  | 5&nbsp;s           | Generous limits — elastic downstream services.      |

```java
.bulkhead("inventory", b -> b
    .balanced()                       // preset establishes the baseline
    .maxConcurrentCalls(75))          // refine specific fields afterwards
```

The presets are **orthogonal to the strategy choice** — a preset only sets `maxConcurrentCalls`, `maxWaitDuration`,
and a label, never the strategy. Combine the two freely: `b.balanced().codel(c -> ...)` produces a CoDel bulkhead
with the balanced capacity baseline. The two preset fields take effect on each [strategy](#strategies) as follows:

| Field                | Effect on the active strategy                                                                                                  |
|----------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `maxConcurrentCalls` | Hard permit cap on `semaphore` and `codel`. **Ignored** by both adaptive variants, which carry their own algorithmic limits.   |
| `maxWaitDuration`    | Park duration on `semaphore`, `codel`, and the blocking `adaptive` variant. **Ignored** by `adaptiveNonBlocking` (fail-fast).  |

For an adaptive strategy, the limit baseline lives inside the algorithm's `initialLimit` / `minLimit` / `maxLimit` —
the preset's `maxConcurrentCalls` is silently inoperative there. Pair an adaptive strategy with `permissive` (long
wait) or `balanced` (moderate wait); pair `adaptiveNonBlocking` with any preset, the wait baseline is moot either way.

## Per-call events

Per-call events (`BulkheadOnAcquireEvent`, `BulkheadOnReleaseEvent`, `BulkheadOnRejectEvent`,
`BulkheadWaitTraceEvent`) are opt-in. The default — `BulkheadEventConfig.disabled()` — keeps the hot path unweighted.
Subscribe to a specific bulkhead's events through its handle:

```java
.bulkhead("inventory", b -> b
    .balanced()
    .events(BulkheadEventConfig.allEnabled()))   // or pick individual flags

inventory.eventPublisher().onEvent(BulkheadOnAcquireEvent.class, event -> { /* ... */ });
```

## Strategies

A bulkhead picks the calls it admits with a *strategy*. Four strategies are available, selected via a setter on the
builder. The strategy setter is also a reset — a later setter on the same builder overrides any earlier strategy
choice (last-writer-wins). If no setter is called, the bulkhead runs with the semaphore strategy.

| Setter                       | Strategy                          | When                                                                            |
|------------------------------|-----------------------------------|---------------------------------------------------------------------------------|
| `b.semaphore()`              | Fixed permit count                | Default. Predictable downstream capacity, simple operational model.             |
| `b.codel(...)`               | Latency-aware drop policy         | Downstream slows down before it saturates; wait time is the more useful signal. |
| `b.adaptive(...)`            | Dynamic limit, blocking on full   | Right concurrency limit unknown or shifts with load.                            |
| `b.adaptiveNonBlocking(...)` | Dynamic limit, fail-fast on full  | Same as adaptive, but callers must not park.                                    |

### `semaphore()`

The default. The bulkhead holds `maxConcurrentCalls` permits; a call acquires one on entry and returns it on exit.
Calls that find no permit wait up to `maxWaitDuration`. The limit is fixed for the lifetime of the bulkhead unless
retuned via `runtime.update(...)` — see [Live tunability](#live-tunability) below.

```java
.bulkhead("inventory", b -> b
    .balanced()
    .semaphore())                         // explicit; equivalent to no strategy setter
```

**Best fit:** downstream services with a known and stable concurrency ceiling — a connection pool sized for a fixed
`N`, a service backed by a fixed-size thread pool, anything where operators want a single number to reason about. Also
the only strategy whose limit can be retuned at runtime without recreating the bulkhead.

**Avoid when:** downstream capacity shifts noticeably with load (autoscaling clusters, shared resource pools). A
static limit then either starves callers in peaks or overprovisions in valleys, and an adaptive strategy is the better
fit.

### `codel(...)`

CoDel ("controlled delay") measures the wait time of every call. When wait time stays above `targetDelay` for the
duration of `interval`, the strategy starts dropping queued calls. It reacts to latency symptoms rather than to a hard
concurrency cap. The strategy pairs a `targetDelay` (the budget above which queued calls become drop candidates) with
an `interval` (the consecutive-overshoot window required before drops start).

```java
.bulkhead("payments", b -> b
    .balanced()
    .codel(c -> c
        .targetDelay(Duration.ofMillis(50))    // latency budget for queued calls
        .interval(Duration.ofMillis(500))))    // consecutive-overshoot window before drops
```

**Best fit:** downstream services that slow down before they saturate — databases under load, services that queue
internally, anything where rising wait time is the earliest reliable signal of overload. Pairs naturally with a
non-zero `maxWaitDuration` so calls actually accumulate measurable wait.

**Avoid when:** the bulkhead runs in fail-fast mode (`maxWaitDuration = Duration.ZERO`) and calls never queue — CoDel
has nothing to observe and degrades to "always admit". Also a poor fit when the downstream's failure mode is hard
rejection rather than gradual latency growth: a fixed-size thread pool that rejects instantly leaves no latency signal
for CoDel to act on.

### `adaptive(...)`

Adaptive strategies grow and shrink the concurrency limit on the fly using RTT and error-rate signals from each call.
The algorithm choice (`aimd(...)` or `vegas(...)`) determines the update rule. The blocking variant parks callers
waiting for a permit for up to `maxWaitDuration`, like the semaphore strategy does.

The algorithm choice is mandatory — call exactly one of `.aimd(...)` or `.vegas(...)` inside the lambda. An empty
`.adaptive(a -> {})` is rejected at build time.

```java
.bulkhead("inventory", b -> b
    .balanced()
    .adaptive(a -> a.aimd(x -> x
        .initialLimit(50)
        .minLimit(5)
        .maxLimit(500))))
```

**Best fit:** downstream capacity is unknown up front or shifts noticeably with load — autoscaling backends, shared
infrastructure, services with variable response times. Callers can tolerate parking on saturation: background
processing, batch chains, hot paths with relaxed latency budgets.

**Avoid when:** callers must not park — use `adaptiveNonBlocking(...)` instead. Also unhelpful when downstream
behaviour is genuinely stable: tuning two algorithm thresholds buys nothing over a fixed semaphore limit there, and
the additional moving parts make incident analysis harder.

### `adaptiveNonBlocking(...)`

Same algorithm choice and dynamic-limit behaviour as `adaptive(...)`. The difference is the saturation policy: when
no permit is available, this strategy fails fast and `maxWaitDuration` is ignored.

```java
.bulkhead("inventory", b -> b
    .balanced()
    .adaptiveNonBlocking(a -> a.vegas(v -> v
        .initialLimit(50))))
```

**Best fit:** low-latency hot paths where a parking caller is worse than a rejected call. Async pipelines that
propagate back-pressure through rejection (paired with a retry or fallback) rather than through queueing.

**Avoid when:** callers can usefully ride out a short wait — a semaphore with a non-zero `maxWaitDuration` or the
blocking adaptive variant is more robust there. Also a poor fit when the calling code has no real rejection-handling
path: rejected calls then disappear into an exception instead of being retried or routed elsewhere.

### Adaptive algorithms

Both adaptive strategies accept one of two algorithms, picked by a setter inside the adaptive sub-builder. Defaults
match a `balanced` baseline, so an empty algorithm block (`.aimd(x -> {})` or `.vegas(v -> {})`) produces a usable
configuration.

#### `aimd(...)`

*Additive increase, multiplicative decrease.* Raises the limit by one when calls succeed and utilization stays above
`minUtilizationThreshold`; multiplies the limit by `backoffRatio` when the error rate crosses `errorRateThreshold`.
The default choice for most cases — robust when the downstream's error signal is more reliable than its latency
signal.

```java
.adaptive(a -> a.aimd(x -> x
    .initialLimit(50)
    .minLimit(5)
    .maxLimit(500)
    .backoffRatio(0.7)
    .errorRateThreshold(0.1)
    .minUtilizationThreshold(0.6)))
```

#### `vegas(...)`

RTT-based. Compares smoothed current RTT against a slowly-drifting baseline RTT and adjusts the limit when the ratio
drifts. More sensitive to latency change than AIMD; needs a stable baseline RTT to be meaningful, so it fits services
with a known and reasonably constant base latency.

```java
.adaptive(a -> a.vegas(v -> v
    .initialLimit(50)
    .smoothingTimeConstant(Duration.ofSeconds(1))
    .baselineDriftTimeConstant(Duration.ofSeconds(10))
    .errorRateThreshold(0.1)))
```

### Strategy defaults

The strategy sub-builders ship with a built-in `balanced` baseline. An empty sub-block produces a usable
configuration without any setter calls — `b.codel(c -> {})`, `b.adaptive(a -> a.aimd(x -> {}))`, and
`b.adaptive(a -> a.vegas(v -> {}))` all compile and run. The two algorithm sub-builders additionally expose the same
three named presets the top-level builder uses (`protective` / `balanced` / `permissive`); the names mean the same
thing on both layers, so no separate vocabulary has to be learned.

| Sub-block                          | Defaults applied                                                                                                                                                                                  |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `b.semaphore()`                    | No fields of its own — draws `maxConcurrentCalls` and `maxWaitDuration` from the top-level builder's [presets](#presets) or setters.                                                              |
| `b.codel(c -> {})`                 | `targetDelay = 50 ms`, `interval = 500 ms`.                                                                                                                                                       |
| `a.aimd(x -> x.protective())`      | `initialLimit = 20`, `minLimit = 1`, `maxLimit = 200`, `backoffRatio = 0.5`, `smoothingTimeConstant = 5 s`, `errorRateThreshold = 0.15`, `windowedIncrease = true`, `minUtilizationThreshold = 0.5`. |
| `a.aimd(x -> x.balanced())`        | `initialLimit = 50`, `minLimit = 5`, `maxLimit = 500`, `backoffRatio = 0.7`, `smoothingTimeConstant = 2 s`, `errorRateThreshold = 0.1`, `windowedIncrease = true`, `minUtilizationThreshold = 0.6`. Also the empty-lambda default. |
| `a.aimd(x -> x.permissive())`      | `initialLimit = 100`, `minLimit = 10`, `maxLimit = 1000`, `backoffRatio = 0.85`, `smoothingTimeConstant = 1 s`, `errorRateThreshold = 0.05`, `windowedIncrease = false`, `minUtilizationThreshold = 0.75`. |
| `a.vegas(v -> v.protective())`     | `initialLimit = 20`, `minLimit = 1`, `maxLimit = 200`, `smoothingTimeConstant = 2 s`, `baselineDriftTimeConstant = 30 s`, `errorRateSmoothingTimeConstant = 10 s`, `errorRateThreshold = 0.15`, `minUtilizationThreshold = 0.5`. |
| `a.vegas(v -> v.balanced())`       | `initialLimit = 50`, `minLimit = 5`, `maxLimit = 500`, `smoothingTimeConstant = 1 s`, `baselineDriftTimeConstant = 10 s`, `errorRateSmoothingTimeConstant = 5 s`, `errorRateThreshold = 0.1`, `minUtilizationThreshold = 0.6`. Also the empty-lambda default. |
| `a.vegas(v -> v.permissive())`     | `initialLimit = 100`, `minLimit = 10`, `maxLimit = 1000`, `smoothingTimeConstant = 500 ms`, `baselineDriftTimeConstant = 5 s`, `errorRateSmoothingTimeConstant = 3 s`, `errorRateThreshold = 0.05`, `minUtilizationThreshold = 0.75`. |

Top-level [presets](#presets) configure capacity (`maxConcurrentCalls`, `maxWaitDuration`); the algorithm sub-builders
configure the permit-management algorithm. The two layers compose freely — typical usage pairs a top-level preset with
the matching algorithm preset:

```java
.bulkhead("payments", b -> b
    .protective()
    .adaptive(a -> a.aimd(x -> x.protective())))
```

The same preset-then-customize discipline applies on both layers: a preset is a baseline and must come *before* any
per-field setter. Calling `.aimd(x -> x.maxLimit(150).protective())` throws `IllegalStateException` — refining works
the other way around: `.aimd(x -> x.protective().maxLimit(150))`.

## Live tunability

Limits and other fields adapt at runtime through the same DSL — call `runtime.update(...)` with a configurer that
targets the bulkhead by name:

```java
runtime.update(u -> u.imperative(im -> im
    .bulkhead("inventory", b -> b.maxConcurrentCalls(50))));
```

Untouched fields inherit from the live snapshot. A patch that calls `maxConcurrentCalls(50)` does not reset
`maxWaitDuration`, the events configuration, or any other field — the only change is the limit.

Which fields actually take effect at runtime depends on the active strategy:

| Field                                                       | Live-tunable          | Notes                                                                                                          |
|-------------------------------------------------------------|-----------------------|----------------------------------------------------------------------------------------------------------------|
| `maxConcurrentCalls`                                        | Semaphore only        | Other strategies veto the patch — they manage their own limit.                                                 |
| `maxWaitDuration`                                           | Yes                   | All strategies. Non-blocking strategies still accept the patch but ignore the value at execution time.         |
| `tags`                                                      | Yes                   | All strategies.                                                                                                |
| `events`                                                    | Yes                   | All strategies.                                                                                                |
| Strategy type (e.g. semaphore → CoDel)                      | Conditional           | Requires zero in-flight calls; vetoed otherwise.                                                               |
| Strategy sub-fields (CoDel `targetDelay`, AIMD `initialLimit`, …) | No               | The running strategy is not re-tuned in place — recreate the bulkhead to pick up new values.                   |

A strategy *type* swap — say from semaphore to CoDel — commits only when no permits are currently held. A patch that
arrives while calls are in flight is rejected with a veto explaining the in-flight count. Schedule swaps during low
load or in a maintenance window.

A patch that combines a strategy swap with a `maxConcurrentCalls` change is accepted as long as the post-patch
strategy is the semaphore — the field is evaluated against the strategy the bulkhead will run after the swap, not the
one it runs now.

## Error code

| Code         | Exception                  | When                                         |
|--------------|----------------------------|----------------------------------------------|
| `INQ-BH-001` | `InqBulkheadFullException` | Call rejected — max concurrent calls reached |

---

## Configuration reference

| Parameter            | Type                     | Default                          | Description                                                                                                                  |
|----------------------|--------------------------|----------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `name`               | `String`                 | (required, constructor argument) | Component identity used for lookup, events, and exceptions.                                                                  |
| `maxConcurrentCalls` | `int`                    | 50                               | Maximum number of concurrent calls. Strictly positive. Live-tunable on the semaphore strategy only.                          |
| `maxWaitDuration`    | `Duration`               | 500&nbsp;ms                      | How long to wait for a permit. `Duration.ZERO` = fail immediately. Non-negative.                                             |
| `tags`               | `Set<String>`            | empty                            | Operational tags. Duplicates from the varargs setter are silently deduped.                                                   |
| `events`             | `BulkheadEventConfig`    | `disabled()`                     | Per-call event flags. Opt-in so the hot path stays unweighted by default.                                                    |
| `strategy`           | `BulkheadStrategyConfig` | `SemaphoreStrategyConfig`        | Permit-management policy. Set via `b.semaphore()`, `b.codel(...)`, `b.adaptive(...)`, or `b.adaptiveNonBlocking(...)`.       |

**Full example:**

```java
InqRuntime runtime = Inqudium.configure()
    .imperative(im -> im
        .bulkhead("inventory", b -> b
            .maxConcurrentCalls(10)
            .maxWaitDuration(Duration.ofMillis(200))
            .tags("payment", "critical")
            .events(BulkheadEventConfig.allEnabled())))
    .build();
```
