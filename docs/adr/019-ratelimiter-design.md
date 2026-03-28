# ADR-019: Rate limiter design

**Status:** Accepted  
**Date:** 2026-03-28  
**Deciders:** Core team

## Context

The Rate Limiter controls the throughput of calls to a downstream service by enforcing a maximum number of calls within a time period. It prevents overloading a service that has known capacity limits — API rate limits, database connection caps, or contractual throughput agreements.

### Algorithm choice

Three common rate limiting algorithms exist:

**Fixed window counter** — Counts calls in fixed time windows (e.g., per second). Simple, but suffers from the boundary problem: 100 calls at the end of window N and 100 calls at the start of window N+1 produce 200 calls in a 1-second span, despite a 100/second limit.

**Sliding window log** — Records timestamps of every call and counts how many fall within the trailing window. Accurate but requires storing every timestamp — O(n) memory per element.

**Token bucket** — Tokens are added to a bucket at a fixed rate. Each call consumes one token. If no tokens are available, the call is denied or waits. Smooth, memory-efficient (O(1)), and naturally handles bursts (the bucket can accumulate tokens during idle periods).

### Why token bucket

The token bucket is the best fit for a resilience library:

- **Smoothing.** Unlike fixed windows, it distributes permits evenly over time.
- **Burst tolerance.** The bucket size determines how many calls can fire in a burst. A bucket of size 50 with a refill rate of 10/s allows a burst of 50 followed by a sustained 10/s.
- **O(1) memory.** Only two values: current token count and last refill timestamp.
- **Pure algorithm.** Token computation requires only the current time (injectable via `InqClock`, ADR-016) and simple arithmetic. No threads, no timers, no schedulers.

### How Resilience4J implements rate limiting

Resilience4J's `AtomicRateLimiter` uses a **fixed-window cycle model** with atomic CAS operations. It divides time into fixed-length cycles (configured as `limitRefreshPeriod`) and tracks available permissions per cycle:

```
cycle = currentNanos / cyclePeriodNanos
if (cycle > lastCycle):
    reset permissions to limitForPeriod
    lastCycle = cycle
permissions -= 1
```

The state is held in an `AtomicReference` and updated via `compareAndSet` for lock-free thread safety. If no permissions are available, the caller waits until the next cycle boundary (`nanosToWaitForPermission` is computed from time remaining in the current cycle).

**Key differences from Inqudium's token bucket:**

| Aspect | Resilience4J (fixed window cycle) | Inqudium (token bucket) |
|---|---|---|
| Algorithm | Fixed window with cycle reset | Token bucket with continuous refill |
| Boundary problem | Yes — all permissions reset at cycle boundary, allowing 2× burst at window edges | No — tokens refill proportionally to elapsed time |
| Burst control | No explicit burst parameter — burst is always `limitForPeriod` | Explicit `bucketSize` parameter, decoupled from refill rate |
| Token accumulation | Permissions do not accumulate across cycles | Tokens accumulate during idle periods up to `bucketSize` |
| Thread safety | `AtomicReference` + CAS (lock-free) | Pure function — caller provides synchronization (paradigm-native) |
| Purity | Embedded `System.nanoTime()` call | Injectable `InqClock` — fully testable |

**Why the token bucket is a better fit for Inqudium:**

The fixed-window boundary problem is the most significant issue. Consider a rate limit of 100 calls per second (cycle = 1s). If 100 calls arrive at t=0.99s (end of cycle N) and 100 calls arrive at t=1.01s (start of cycle N+1), all 200 are permitted — the effective rate is 200/s in a 20ms window. The token bucket avoids this because tokens refill continuously, not in bulk at cycle boundaries.

R4J's lock-free CAS approach is elegant for imperative code but does not translate to reactive or coroutine paradigms. Inqudium's pure-function approach lets each paradigm module use its native synchronization (ReentrantLock, Mutex, reactive operators) without CAS retry loops.

The explicit `bucketSize` parameter gives operators a knob that R4J does not expose: "allow a burst of 200 after idle periods, but sustain only 100/s." This is valuable for services that can handle occasional bursts but have a lower sustained capacity.

## Decision

### RateLimiterConfig

```java
public record RateLimiterConfig(
    int limitForPeriod,                 // number of permits per period
    Duration limitRefreshPeriod,        // period duration
    int bucketSize,                     // maximum token accumulation (burst capacity)
    Duration timeoutDuration,           // how long to wait for a permit (0 = fail immediately)
    InqClock clock,                     // injectable time source (ADR-016)
    InqCompatibility compatibility      // behavioral flags (ADR-013)
) {
    public static RateLimiterConfig ofDefaults() {
        return RateLimiterConfig.builder().build();
    }

    public static Builder builder() { ... }
}
```

Default values:

| Parameter | Default | Rationale |
|---|---|---|
| `limitForPeriod` | 50 | 50 calls per period. Conservative default for most APIs. |
| `limitRefreshPeriod` | 500ms | Refresh every 500ms → effective rate of 100 calls/second. Short period = smoother distribution. |
| `bucketSize` | `limitForPeriod` | Bucket holds one period's worth of tokens. No burst beyond one period. |
| `timeoutDuration` | 0 (no wait) | Fail immediately when no permit is available. Waiting is opt-in because it blocks the caller (or suspends the coroutine). |
| `clock` | `InqClock.system()` | Wall clock. Override for testing. |

### RateLimiterBehavior

The behavioral contract is a pure function that decides whether a permit is available and updates the token state:

```java
public interface RateLimiterBehavior {

    /**
     * Attempts to acquire a permit.
     *
     * @param state   current token bucket state
     * @param config  rate limiter configuration
     * @return updated state with acquisition result
     *
     * Pure — no side effects. The caller decides how to wait if the result indicates a delay.
     */
    PermitResult tryAcquire(TokenBucketState state, RateLimiterConfig config);
}
```

Supporting types:

```java
public record TokenBucketState(
    int availableTokens,
    Instant lastRefillTimestamp
) {
    public static TokenBucketState initial(RateLimiterConfig config) {
        return new TokenBucketState(config.bucketSize(), config.clock().instant());
    }
}

public record PermitResult(
    boolean permitted,
    Duration waitDuration,          // 0 if permitted, estimated wait if denied
    TokenBucketState updatedState   // state after this acquisition attempt
) {
    public static PermitResult permitted(TokenBucketState state) {
        return new PermitResult(true, Duration.ZERO, state);
    }

    public static PermitResult denied(Duration estimatedWait, TokenBucketState state) {
        return new PermitResult(false, estimatedWait, state);
    }
}
```

### Token bucket algorithm

```java
public class DefaultRateLimiterBehavior implements RateLimiterBehavior {

    @Override
    public PermitResult tryAcquire(TokenBucketState state, RateLimiterConfig config) {
        Instant now = config.clock().instant();

        // Refill tokens based on elapsed time
        Duration elapsed = Duration.between(state.lastRefillTimestamp(), now);
        long periodsElapsed = elapsed.toNanos() / config.limitRefreshPeriod().toNanos();

        int refilled = state.availableTokens() + (int) (periodsElapsed * config.limitForPeriod());
        int capped = Math.min(refilled, config.bucketSize());

        Instant lastRefill = periodsElapsed > 0
            ? state.lastRefillTimestamp().plus(config.limitRefreshPeriod().multipliedBy(periodsElapsed))
            : state.lastRefillTimestamp();

        // Try to acquire
        if (capped > 0) {
            var newState = new TokenBucketState(capped - 1, lastRefill);
            return PermitResult.permitted(newState);
        }

        // Denied — estimate wait time until next token
        Duration untilNextRefill = config.limitRefreshPeriod()
            .minus(Duration.between(lastRefill, now));
        if (untilNextRefill.isNegative()) untilNextRefill = Duration.ZERO;

        var newState = new TokenBucketState(0, lastRefill);
        return PermitResult.denied(untilNextRefill, newState);
    }
}
```

**Purity:** The algorithm uses `config.clock().instant()` for the current time — no `Instant.now()`, no `System.currentTimeMillis()`. In tests, time is controlled via `InqClock` (ADR-016).

**No blocking:** `tryAcquire` returns immediately with a `PermitResult`. If denied, `waitDuration` tells the caller how long to wait. The paradigm module decides how to wait: `LockSupport.parkNanos()` for imperative, `delay()` for Kotlin, `Mono.delay()` for Reactor.

### Waiting behavior

The `timeoutDuration` parameter controls whether the caller waits for a permit:

**`timeoutDuration = 0` (default):** If no permit is available, `InqRequestNotPermittedException` is thrown immediately (ADR-009). This is the safe default — no surprise blocking.

**`timeoutDuration > 0`:** The caller waits up to `timeoutDuration` for a permit to become available. The paradigm module implements the wait in its native way. If the timeout expires without a permit, `InqRequestNotPermittedException` is thrown.

The wait is **not** implemented in the behavior contract — it is a paradigm concern. The behavior returns `PermitResult.denied(waitDuration, ...)` and the paradigm module decides:

| Paradigm | Wait mechanism |
|---|---|
| Imperative | `LockSupport.parkNanos(waitDuration)`, then re-check (ADR-008) |
| Kotlin | `delay(waitDuration)`, then re-check |
| Reactor | `Mono.delay(waitDuration).then(recheck)` |
| RxJava 3 | `Single.timer(waitDuration).flatMap(recheck)` |

## Consequences

**Positive:**
- Token bucket provides smooth rate limiting with configurable burst tolerance.
- O(1) memory and computation per acquisition.
- Pure algorithm — injectable clock, no threading, testable.
- Wait behavior is optional (default: fail immediately) and paradigm-native when enabled.
- `PermitResult` carries `waitDuration` — the caller knows exactly how long to wait without guessing.

**Negative:**
- Token bucket allows bursts up to `bucketSize`. If a service cannot handle any bursts, `bucketSize` must be set to 1 — which effectively degrades to a strict rate limiter with no smoothing benefit.
- The `timeoutDuration` wait adds latency to the caller. In a pipeline where the Rate Limiter is outside the TimeLimiter (ADR-017), the wait counts against the TimeLimiter's budget.

**Neutral:**
- The algorithm does not enforce fairness. Under contention, the order in which concurrent callers receive permits depends on the paradigm's synchronization mechanism (ReentrantLock ordering for imperative, Mutex for Kotlin).
- `limitForPeriod` and `limitRefreshPeriod` together define the effective rate. 50 permits per 500ms = 100/s. The two-parameter design is more flexible than a single "requests per second" parameter.
