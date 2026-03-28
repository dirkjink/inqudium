# Bulkhead

The bulkhead isolates failures by limiting the number of concurrent calls to a downstream service. If a service slows down, the bulkhead prevents it from consuming all available threads or connections — protecting unrelated services.

## Quick start

```java
var config = BulkheadConfig.builder()
    .maxConcurrentCalls(25)                // max 25 in-flight calls
    .maxWaitDuration(Duration.ZERO)        // fail immediately (default)
    .build();
```

## Acquire/release lifecycle

Unlike other elements, the bulkhead holds a permit for the *duration* of the call. Every successful acquire must be paired with exactly one release in a finally block. Failure to release causes permit leakage — the bulkhead will eventually fill up permanently.

The paradigm module guarantees this pairing:

| Paradigm | Guarantee |
|----------|-----------|
| Imperative | `try/finally` block |
| Kotlin | `try/finally` inside coroutine |
| Reactor | `doFinally(signal -> release())` |
| RxJava 3 | `doFinally(() -> release())` |

## Why semaphore, not thread pool

Resilience4J offers both `SemaphoreBulkhead` and `ThreadPoolBulkhead`. Inqudium only provides semaphore-based isolation because Java 21+ virtual threads make thread-pool isolation unnecessary. Virtual threads are cheap to create and do not share a limited pool — there is no shared resource to protect with a dedicated thread pool.

## Waiting behavior

By default (`maxWaitDuration = 0`), a denied call throws `InqBulkheadFullException` (`INQ-BH-001`) immediately. To wait for a permit:

```java
.maxWaitDuration(Duration.ofMillis(200)) // wait up to 200ms for a permit
```

## Error code

| Code | Exception | When |
|------|-----------|------|
| `INQ-BH-001` | `InqBulkheadFullException` | Call rejected — max concurrent calls reached |

---

## Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `maxConcurrentCalls` | `int` | `25` | Maximum number of concurrent calls. |
| `maxWaitDuration` | `Duration` | `0` (no wait) | How long to wait for a permit. `0` = fail immediately. |
| `compatibility` | `InqCompatibility` | `ofDefaults()` | Behavioral change flags. |

**Full example:**

```java
BulkheadConfig.builder()
    .maxConcurrentCalls(10)
    .maxWaitDuration(Duration.ofMillis(200))
    .build();
```
