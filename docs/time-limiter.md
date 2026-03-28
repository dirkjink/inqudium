# Time Limiter

The time limiter bounds the **caller's wait time**. It does not interrupt the downstream operation — orphaned operations continue to completion in the background.

## Quick start

```java
var config = TimeLimiterConfig.builder()
    .timeoutDuration(Duration.ofSeconds(5))
    .onOrphanedResult((ctx, result) ->
        log.warn("Orphaned result from {}: {}", ctx.elementName(), result))
    .onOrphanedError((ctx, error) ->
        log.error("Orphaned error from {}: {}", ctx.elementName(), error.getMessage()))
    .build();
```

## Orphaned calls

When the time limiter fires, the caller receives `InqTimeLimitExceededException` (`INQ-TL-001`). But the downstream operation may still be running. The `onOrphanedResult` and `onOrphanedError` handlers let you observe what happens to the orphaned call:

```java
.onOrphanedResult((ctx, result) -> {
    log.info("Call to {} completed {}ms after timeout with: {}",
        ctx.elementName(), ctx.actualDuration().toMillis(), result);
})
```

This is important for operations with side effects (database writes, payment processing). The timeout fires, but the write may still succeed.

## Timeout profile — deriving consistent timeouts

`InqTimeoutProfile` computes the TimeLimiter timeout and Circuit Breaker slow call threshold from your HTTP client timeouts. This prevents the common misconfiguration where the TimeLimiter fires before the HTTP client, or the slow call threshold is set arbitrarily:

```java
var profile = InqTimeoutProfile.builder()
    .connectTimeout(Duration.ofMillis(250))   // from your HTTP client
    .responseTimeout(Duration.ofSeconds(3))   // from your HTTP client
    .method(TimeoutCalculation.RSS)           // statistical tolerance analysis
    .safetyMarginFactor(1.2)                  // 20% above computed value
    .build();

var tlConfig = TimeLimiterConfig.builder()
    .timeoutDuration(profile.timeLimiterTimeout())
    .build();

var cbConfig = CircuitBreakerConfig.builder()
    .slowCallDurationThreshold(profile.slowCallDurationThreshold())
    .build();
```

RSS (Root Sum of Squares) produces tighter timeouts than simple addition because it assumes timeout components are statistically independent. Use `WORST_CASE` for a conservative upper bound.

## Error code

| Code | Exception | When |
|------|-----------|------|
| `INQ-TL-001` | `InqTimeLimitExceededException` | Caller wait time exceeded configured timeout |

---

## Configuration reference

### TimeLimiterConfig

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `timeoutDuration` | `Duration` | `5s` | Maximum time the caller waits. The operation may continue after this timeout. |
| `onOrphanedResult` | `BiConsumer<OrphanedCallContext, Object>` | `null` | Called when an orphaned call completes successfully. |
| `onOrphanedError` | `BiConsumer<OrphanedCallContext, Throwable>` | `null` | Called when an orphaned call fails. |
| `compatibility` | `InqCompatibility` | `ofDefaults()` | Behavioral change flags. |

**OrphanedCallContext fields:**

| Field | Type | Description |
|-------|------|-------------|
| `elementName` | `String` | The time limiter instance name. |
| `configuredDuration` | `Duration` | The configured timeout. |
| `actualDuration` | `Duration` | How long the operation actually took. |
| `callId` | `String` | The unique call identifier for correlation. |

**Full example:**

```java
TimeLimiterConfig.builder()
    .timeoutDuration(Duration.ofSeconds(3))
    .onOrphanedResult((ctx, result) ->
        log.warn("{}: orphaned call completed after {}ms",
            ctx.callId(), ctx.actualDuration().toMillis()))
    .onOrphanedError((ctx, error) ->
        log.error("{}: orphaned call failed: {}",
            ctx.callId(), error.getMessage()))
    .build();
```

### InqTimeoutProfile

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connectTimeout` | `Duration` | — | HTTP connect timeout (first component). |
| `responseTimeout` | `Duration` | — | HTTP response timeout (second component). |
| `additionalTimeout` | `Duration` | — | Extra components (TLS handshake, DNS, etc.). Repeatable. |
| `method` | `TimeoutCalculation` | `RSS` | `RSS` (statistical) or `WORST_CASE` (sum). |
| `safetyMarginFactor` | `double` | `1.2` | Factor applied to computed timeout. `1.2` = 20% headroom. |

**Output methods:**

| Method | Returns | Description |
|--------|---------|-------------|
| `timeLimiterTimeout()` | `Duration` | Recommended TimeLimiter timeout. |
| `slowCallDurationThreshold()` | `Duration` | Recommended CB slow call threshold (= timeLimiterTimeout). |

**Full example:**

```java
var profile = InqTimeoutProfile.builder()
    .connectTimeout(Duration.ofMillis(250))
    .responseTimeout(Duration.ofSeconds(3))
    .additionalTimeout(Duration.ofMillis(100))  // TLS handshake
    .method(TimeoutCalculation.RSS)
    .safetyMarginFactor(1.2)
    .build();
```
