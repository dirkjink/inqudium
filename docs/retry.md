# Retry

The retry element re-executes a failed operation with configurable delays between attempts. Inqudium separates the *decision* (should I retry?) from the *waiting* (how do I wait?) — the core computes the delay, the paradigm module executes the wait.

## Quick start

```java
var config = RetryConfig.builder()
    .maxAttempts(3)                       // initial call + 2 retries
    .initialInterval(Duration.ofMillis(500))
    .backoffStrategy(BackoffStrategy.exponentialWithEqualJitter())
    .maxInterval(Duration.ofSeconds(30))  // cap on computed delay
    .retryOn(IOException.class)           // only retry on I/O failures
    .ignoreOn(IllegalArgumentException.class) // never retry on bad input
    .build();
```

## Backoff strategies

Three built-in strategies control how the delay grows between retries:

**Fixed** — Every retry waits the same duration. Use for rate-limited APIs with fixed reset intervals.

```java
BackoffStrategy.fixed()
// Attempt 1: 500ms, Attempt 2: 500ms, Attempt 3: 500ms
```

**Exponential** — Each retry waits exponentially longer. Default multiplier is 2.0.

```java
BackoffStrategy.exponential()
// Attempt 1: 500ms, Attempt 2: 1s, Attempt 3: 2s, Attempt 4: 4s
```

**Exponential with jitter** — Adds randomization to prevent retry storms. Three jitter algorithms are available:

```java
// Equal jitter (recommended default) — guaranteed minimum of half the backoff
BackoffStrategy.exponentialWithEqualJitter()
// Attempt 2 base=1s → delay in [500ms, 1000ms]

// Full jitter — maximum spread, but near-zero delays possible
BackoffStrategy.exponentialWithFullJitter()
// Attempt 2 base=1s → delay in [0ms, 1000ms]

// Decorrelated jitter — each delay depends on the previous one
BackoffStrategy.exponentialWithDecorrelatedJitter()
// Best for preventing retry storms under high concurrency
```

## The jitter problem

When a downstream service recovers after an outage, all clients retry simultaneously — creating a "retry storm" that can re-overload the service. Jitter randomizes the delay so retries spread over time.

Equal jitter is the default because it balances two concerns: guaranteed minimum delay (no near-zero retries) and effective spread (no thundering herd). If you have specific requirements, full jitter provides maximum spread and decorrelated jitter provides the best high-concurrency behavior.

## Exception filtering

By default, all exceptions trigger a retry. You can restrict this:

```java
.retryOn(IOException.class)           // allowlist — only retry these
.ignoreOn(IllegalArgumentException.class) // blocklist — never retry these
.retryOnPredicate(ex -> ex.getMessage().contains("timeout")) // custom logic
```

Inqudium exceptions (`InqCallNotPermittedException`, `InqBulkheadFullException`, etc.) are **not retried by default**. Retrying against an open circuit breaker is pointless. To override this:

```java
.retryOnInqExceptions(true) // allow retrying InqException subclasses
```

## maxAttempts semantics

`maxAttempts` counts the total number of calls including the initial attempt. `maxAttempts(3)` means: initial call + 2 retries. This matches Resilience4J's convention.

When all attempts fail, `InqRetryExhaustedException` (error code `INQ-RT-001`) is thrown. It wraps the last cause:

```java
try {
    resilientCall.get();
} catch (InqRetryExhaustedException e) {
    log.error("Failed after {} attempts. Last error: {}",
        e.getAttempts(), e.getLastCause().getMessage());
}
```

## Error code

| Code | Exception | When |
|------|-----------|------|
| `INQ-RT-001` | `InqRetryExhaustedException` | All retry attempts exhausted |

---

## Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `maxAttempts` | `int` | `3` | Total attempts including the initial call. `3` = initial + 2 retries. |
| `initialInterval` | `Duration` | `500ms` | Base delay before the first retry. |
| `backoffStrategy` | `BackoffStrategy` | `exponentialWithEqualJitter()` | Strategy for computing retry delays. |
| `maxInterval` | `Duration` | `30s` | Upper cap on computed delay. Prevents exponential growth from producing absurd values. |
| `retryOn` | `Set<Class<? extends Throwable>>` | empty (= all) | Allowlist — if non-empty, only these exception types trigger a retry. |
| `ignoreOn` | `Set<Class<? extends Throwable>>` | empty | Blocklist — these exception types are never retried, even if in `retryOn`. |
| `retryOnInqExceptions` | `boolean` | `false` | Whether to retry on `InqException` subclasses. Default `false` because retrying against an open circuit breaker is pointless. |
| `retryOnPredicate` | `Predicate<Throwable>` | `null` | Custom predicate for retry decision. Checked after type filters. |
| `compatibility` | `InqCompatibility` | `ofDefaults()` | Behavioral change flags. |

**BackoffStrategy options:**

| Factory method | Formula | Notes |
|----------------|---------|-------|
| `BackoffStrategy.fixed()` | `delay = initialInterval` | Constant delay. |
| `BackoffStrategy.exponential()` | `initialInterval × 2^(attempt-1)` | Doubling. |
| `BackoffStrategy.exponential(1.5)` | `initialInterval × 1.5^(attempt-1)` | Custom multiplier. |
| `BackoffStrategy.exponentialWithEqualJitter()` | `base/2 + random(0, base/2)` | **Recommended default.** Minimum half the backoff. |
| `BackoffStrategy.exponentialWithFullJitter()` | `random(0, base)` | Maximum spread. Near-zero delays possible. |
| `BackoffStrategy.exponentialWithDecorrelatedJitter()` | `random(initial, prev×3)` | Stateful. Best under high concurrency. |

**Full example:**

```java
RetryConfig.builder()
    .maxAttempts(4)
    .initialInterval(Duration.ofMillis(200))
    .backoffStrategy(BackoffStrategy.exponentialWithEqualJitter())
    .maxInterval(Duration.ofSeconds(10))
    .retryOn(IOException.class)
    .retryOn(TimeoutException.class)
    .ignoreOn(IllegalArgumentException.class)
    .build();
```
