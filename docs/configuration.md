# Configuration Reference

Quick-reference hub for all Inqudium element configurations. Each element's full configuration documentation — including parameter tables, defaults, and examples — lives on its own page.

---

## Element configurations

| Element | Config class | Page | Key parameters |
|---------|-------------|------|----------------|
| Circuit Breaker | `CircuitBreakerConfig` | [circuit-breaker.md](circuit-breaker.md#configuration-reference) | `failureRateThreshold`, `slidingWindowSize`, `waitDurationInOpenState` |
| Retry | `RetryConfig` | [retry.md](retry.md#configuration-reference) | `maxAttempts`, `backoffStrategy`, `maxInterval`, `retryOn`/`ignoreOn` |
| Rate Limiter | `RateLimiterConfig` | [rate-limiter.md](rate-limiter.md#configuration-reference) | `limitForPeriod`, `limitRefreshPeriod`, `bucketSize` |
| Bulkhead | `BulkheadConfig` | [bulkhead.md](bulkhead.md#configuration-reference) | `maxConcurrentCalls`, `maxWaitDuration` |
| Time Limiter | `TimeLimiterConfig` | [time-limiter.md](time-limiter.md#configuration-reference) | `timeoutDuration`, `onOrphanedResult`/`onOrphanedError` |
| Timeout Profile | `InqTimeoutProfile` | [time-limiter.md](time-limiter.md#inqtimeoutprofile) | `connectTimeout`, `responseTimeout`, `method`, `safetyMarginFactor` |

---

## Common patterns

### All configs share these parameters

Every config implements `InqConfig` and provides:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `compatibility` | `InqCompatibility` | `ofDefaults()` | Behavioral change flags. See [Compatibility](compatibility.md). |

Configs that are time-dependent also provide:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `clock` | `InqClock` | `InqClock.system()` | Injectable time source. Override for [testing](testing.md). |

Applies to: `CircuitBreakerConfig`, `RateLimiterConfig`.

### Builder pattern

All configs use the same builder pattern:

```java
var config = CircuitBreakerConfig.builder()
    .failureRateThreshold(60)
    .slidingWindowSize(50)
    .build();  // immutable snapshot
```

Default configurations are available via `ofDefaults()`:

```java
var defaults = CircuitBreakerConfig.ofDefaults();
```

---

## Defaults at a glance

| Config | Parameter | Default |
|--------|-----------|---------|
| **CircuitBreaker** | failureRateThreshold | 50% |
| | slowCallRateThreshold | 100% (disabled) |
| | slowCallDurationThreshold | 60s |
| | slidingWindowType | COUNT_BASED |
| | slidingWindowSize | 100 |
| | minimumNumberOfCalls | 100 |
| | waitDurationInOpenState | 60s |
| | permittedCallsInHalfOpen | 10 |
| **Retry** | maxAttempts | 3 |
| | initialInterval | 500ms |
| | backoffStrategy | exponentialWithEqualJitter |
| | maxInterval | 30s |
| | retryOnInqExceptions | false |
| **RateLimiter** | limitForPeriod | 50 |
| | limitRefreshPeriod | 500ms |
| | bucketSize | = limitForPeriod |
| | timeoutDuration | 0 (no wait) |
| **Bulkhead** | maxConcurrentCalls | 25 |
| | maxWaitDuration | 0 (no wait) |
| **TimeLimiter** | timeoutDuration | 5s |
| **TimeoutProfile** | method | RSS |
| | safetyMarginFactor | 1.2 |

---

## Error codes

The full error code reference is in [error-handling.md](error-handling.md#full-error-code-reference).
