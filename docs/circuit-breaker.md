# Circuit Breaker

The circuit breaker prevents cascading failures. When a downstream service starts failing, the breaker opens and rejects subsequent calls immediately — giving the service time to recover instead of drowning it with requests.

## State machine

The circuit breaker has three states:

**CLOSED** — Normal operation. Calls pass through to the downstream service. Outcomes (success/failure, duration) are recorded in a sliding window. If the failure rate or slow call rate exceeds the configured threshold, the breaker transitions to OPEN.

**OPEN** — Calls are rejected immediately with `InqCallNotPermittedException` (error code `INQ-CB-001`). No call reaches the downstream service. After `waitDurationInOpenState` elapses, the breaker transitions to HALF_OPEN.

**HALF_OPEN** — A limited number of probe calls are permitted. If they succeed, the breaker closes. If they fail, it reopens.

## Quick start

```java
var config = CircuitBreakerConfig.builder()
    .failureRateThreshold(50)             // open at 50% failures
    .slowCallRateThreshold(80)            // open at 80% slow calls
    .slowCallDurationThreshold(Duration.ofSeconds(3))  // "slow" = > 3s
    .slidingWindowType(SlidingWindowType.COUNT_BASED)   // or TIME_BASED
    .slidingWindowSize(100)               // last 100 calls (or 100 seconds)
    .minimumNumberOfCalls(20)             // need 20 calls before evaluating rates
    .waitDurationInOpenState(Duration.ofSeconds(60))    // stay open for 60s
    .permittedNumberOfCallsInHalfOpenState(10)          // 10 probe calls
    .build();
```

## Sliding windows

Two sliding window types are available:

**Count-based** (`SlidingWindowType.COUNT_BASED`) — A circular buffer that tracks the last N calls. O(1) per call, fixed memory. Use when you care about the last N calls regardless of how fast they arrive.

**Time-based** (`SlidingWindowType.TIME_BASED`) — Divides the window into 1-second buckets. Calls older than the window size (in seconds) are evicted. Use when you care about the failure rate over a time period.

Both windows track failures and slow calls independently. A call can be both successful and slow — slow call tracking is orthogonal to success/failure.

## Minimum calls threshold

The failure rate is not evaluated until `minimumNumberOfCalls` outcomes have been recorded. This prevents the breaker from opening on a single failure at startup:

```java
.minimumNumberOfCalls(20)  // need 20 calls before 50% means anything
```

## Error code

| Code | Exception | When |
|------|-----------|------|
| `INQ-CB-001` | `InqCallNotPermittedException` | Circuit breaker is OPEN or HALF_OPEN probe limit reached |

---

## Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `failureRateThreshold` | `float` | `50.0` | Failure rate percentage (0–100) that triggers OPEN. |
| `slowCallRateThreshold` | `float` | `100.0` | Slow call rate percentage (0–100) that triggers OPEN. Default 100 = disabled. |
| `slowCallDurationThreshold` | `Duration` | `60s` | Calls exceeding this duration are counted as "slow". |
| `slidingWindowType` | `SlidingWindowType` | `COUNT_BASED` | `COUNT_BASED` (circular buffer) or `TIME_BASED` (time buckets). |
| `slidingWindowSize` | `int` | `100` | Number of calls (count-based) or seconds (time-based) in the window. |
| `minimumNumberOfCalls` | `int` | `100` | Minimum calls before failure/slow rates are evaluated. |
| `waitDurationInOpenState` | `Duration` | `60s` | How long the breaker stays OPEN before transitioning to HALF_OPEN. |
| `permittedNumberOfCallsInHalfOpenState` | `int` | `10` | Number of probe calls allowed in HALF_OPEN. |
| `clock` | `InqClock` | `InqClock.system()` | Time source. Override for deterministic testing. |
| `compatibility` | `InqCompatibility` | `ofDefaults()` | Behavioral change flags. |

**Full example:**

```java
CircuitBreakerConfig.builder()
    .failureRateThreshold(60)
    .slowCallRateThreshold(80)
    .slowCallDurationThreshold(Duration.ofSeconds(3))
    .slidingWindowType(SlidingWindowType.COUNT_BASED)
    .slidingWindowSize(50)
    .minimumNumberOfCalls(10)
    .waitDurationInOpenState(Duration.ofSeconds(30))
    .permittedNumberOfCallsInHalfOpenState(5)
    .build();
```
