# Error Handling

Inqudium throws its own exceptions only when an element actively intervenes. When the downstream call fails, the original exception propagates unchanged.

## Exception hierarchy

Every `InqException` carries a structured error code, element name, and element type:

| Code | Exception | When |
|------|-----------|------|
| `INQ-CB-001` | `InqCallNotPermittedException` | Circuit breaker is OPEN |
| `INQ-RT-001` | `InqRetryExhaustedException` | All retry attempts exhausted |
| `INQ-RL-001` | `InqRequestNotPermittedException` | Rate limiter denied |
| `INQ-BH-001` | `InqBulkheadFullException` | Bulkhead full |
| `INQ-TL-001` | `InqTimeLimitExceededException` | Timeout exceeded |

## Error codes

Error codes follow the format `INQ-XX-NNN` where `XX` is the two-character element symbol and `NNN` is a three-digit number. They are designed for log aggregation, alerting, and runbook linking:

```java
catch (InqException e) {
    metrics.increment("inqudium.error." + e.getCode());
    log.warn("{}: {}", e.getCode(), e.getMessage());
    // Kibana query: e.getCode() = "INQ-CB-001"
    // PagerDuty runbook: https://wiki/runbooks/INQ-CB-001
}
```

Error codes are stable across minor versions. Messages may change; codes will not.

## InqFailure — cause-chain navigation

In practice, Inqudium exceptions are wrapped by frameworks, proxies, and reflection:

```
ExecutionException
  └─ UndeclaredThrowableException
       └─ InvocationTargetException
            └─ InqCallNotPermittedException   ← this is what you need
```

`InqFailure.find()` traverses the entire cause chain (handling circular references) and provides a fluent API for handling:

```java
try {
    resilientCall.get();
} catch (Exception e) {
    InqFailure.find(e)
        .ifCircuitBreakerOpen(cb ->
            log.warn("Circuit breaker {} is open ({}% failure rate)",
                cb.getElementName(), cb.getFailureRate()))
        .ifRetryExhausted(rt ->
            log.error("Retries exhausted after {} attempts: {}",
                rt.getAttempts(), rt.getLastCause().getMessage()))
        .ifRateLimited(rl ->
            log.info("Rate limited — next permit in ~{}ms",
                rl.getWaitEstimate().toMillis()))
        .ifBulkheadFull(bh ->
            log.warn("Bulkhead {} is full ({}/{} concurrent)",
                bh.getElementName(), bh.getConcurrentCalls(), bh.getMaxConcurrentCalls()))
        .ifTimeLimitExceeded(tl ->
            log.warn("Timed out after {}ms (configured: {}ms)",
                tl.getActualDuration().toMillis(), tl.getConfiguredDuration().toMillis()))
        .orElseThrow(); // re-throw if not an Inqudium intervention
}
```

## Full error code reference

### Element codes

| Code | Exception | Description |
|------|-----------|-------------|
| `INQ-CB-001` | `InqCallNotPermittedException` | Call rejected — circuit breaker is OPEN |
| `INQ-CB-002` | `InqCallNotPermittedException` | Call rejected — HALF_OPEN probe limit reached |
| `INQ-RT-001` | `InqRetryExhaustedException` | All retry attempts exhausted |
| `INQ-RL-001` | `InqRequestNotPermittedException` | Request denied — no permits available |
| `INQ-BH-001` | `InqBulkheadFullException` | Call rejected — max concurrent calls reached |
| `INQ-TL-001` | `InqTimeLimitExceededException` | Caller wait time exceeded configured timeout |

### System codes

| Code | Type | Description |
|------|------|-------------|
| `INQ-SY-001` | `InqProviderErrorEvent` | ServiceLoader provider failed during construction |
| `INQ-SY-002` | `InqProviderErrorEvent` | ServiceLoader provider failed during execution |
| `INQ-SY-003` | log warning | Registry name collision (first-registration-wins) |
| `INQ-SY-004` | log warning | Pipeline anti-pattern detected |
| `INQ-SY-005` | `IllegalStateException` | Registry frozen — late registration rejected |
