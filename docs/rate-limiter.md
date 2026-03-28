# Rate Limiter

The rate limiter controls throughput by limiting the number of calls within a time period. Inqudium uses a **token bucket** algorithm that provides smooth rate limiting with configurable burst tolerance.

## Quick start

```java
var config = RateLimiterConfig.builder()
    .limitForPeriod(50)                    // 50 permits per period
    .limitRefreshPeriod(Duration.ofMillis(500))  // refresh every 500ms → 100/s
    .bucketSize(50)                        // max token accumulation
    .timeoutDuration(Duration.ZERO)        // fail immediately (default)
    .build();
```

## Token bucket vs. fixed window

Resilience4J uses a fixed-window counter. Inqudium uses a token bucket. The difference matters at window boundaries: a fixed window allows 2× the rate limit in a short burst at the boundary. The token bucket refills continuously and avoids this problem.

The `bucketSize` parameter controls burst tolerance. During idle periods, tokens accumulate up to `bucketSize`. A service that was idle for 10 seconds with `bucketSize=200` and `limitForPeriod=50` can handle a burst of 200 calls before rate limiting kicks in. Set `bucketSize` equal to `limitForPeriod` (the default) for no burst tolerance.

## Waiting behavior

By default (`timeoutDuration = 0`), a denied request throws `InqRequestNotPermittedException` (`INQ-RL-001`) immediately. To wait for a permit:

```java
.timeoutDuration(Duration.ofMillis(500)) // wait up to 500ms for a permit
```

The wait mechanism is paradigm-native: `LockSupport.parkNanos` for imperative, `delay()` for Kotlin, `Mono.delay()` for Reactor.

## Effective rate calculation

The effective rate is `limitForPeriod / limitRefreshPeriod`:

| limitForPeriod | limitRefreshPeriod | Effective rate |
|---|---|---|
| 50 | 500ms | 100/s |
| 10 | 1s | 10/s |
| 100 | 100ms | 1000/s |

Shorter refresh periods produce smoother rate distribution.

## Error code

| Code | Exception | When |
|------|-----------|------|
| `INQ-RL-001` | `InqRequestNotPermittedException` | Request denied — no permits available |

---

## Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limitForPeriod` | `int` | `50` | Number of permits per period. |
| `limitRefreshPeriod` | `Duration` | `500ms` | Period duration. `50` per `500ms` = effective 100/s. |
| `bucketSize` | `int` | `= limitForPeriod` | Maximum token accumulation. Controls burst tolerance during idle periods. |
| `timeoutDuration` | `Duration` | `0` (no wait) | How long to wait for a permit. `0` = fail immediately. |
| `clock` | `InqClock` | `InqClock.system()` | Time source. Override for testing. |
| `compatibility` | `InqCompatibility` | `ofDefaults()` | Behavioral change flags. |

**Full example:**

```java
RateLimiterConfig.builder()
    .limitForPeriod(100)
    .limitRefreshPeriod(Duration.ofSeconds(1))
    .bucketSize(200)  // allow bursts of 200 after idle
    .timeoutDuration(Duration.ofMillis(500))
    .build();
```
