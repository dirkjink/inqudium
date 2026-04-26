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

ImperativeBulkhead inventory = runtime.imperative().bulkhead("inventory");
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

## Runtime updates

Limits adapt at runtime through the same DSL — call `runtime.update(...)` with a configurer that targets the bulkhead
by name:

```java
runtime.update(u -> u.imperative(im -> im
    .bulkhead("inventory", b -> b.maxConcurrentCalls(50))));
```

Untouched fields inherit from the live snapshot. A patch that calls `maxConcurrentCalls(50)` does not reset
`maxWaitDuration`, the events configuration, or any other field — the only change is the limit.

## Error code

| Code         | Exception                  | When                                         |
|--------------|----------------------------|----------------------------------------------|
| `INQ-BH-001` | `InqBulkheadFullException` | Call rejected — max concurrent calls reached |

---

## Configuration reference

| Parameter            | Type                  | Default                          | Description                                                                                |
|----------------------|-----------------------|----------------------------------|--------------------------------------------------------------------------------------------|
| `name`               | `String`              | (required, constructor argument) | Component identity used for lookup, events, and exceptions.                                |
| `maxConcurrentCalls` | `int`                 | 50                               | Maximum number of concurrent calls. Strictly positive.                                     |
| `maxWaitDuration`    | `Duration`            | 500&nbsp;ms                      | How long to wait for a permit. `Duration.ZERO` = fail immediately. Non-negative.           |
| `tags`               | `Set<String>`         | empty                            | Operational tags. Duplicates from the varargs setter are silently deduped.                 |
| `events`             | `BulkheadEventConfig` | `disabled()`                     | Per-call event flags. Opt-in so the hot path stays unweighted by default.                  |

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
