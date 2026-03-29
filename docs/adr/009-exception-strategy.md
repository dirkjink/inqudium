# ADR-009: Exception strategy

**Status:** Accepted  
**Date:** 2026-03-23 (updated 2026-03-29)  
**Deciders:** Core team

## Context

A resilience library wraps application calls and must decide how to signal failures — both failures from the protected downstream service and failures caused by the resilience elements themselves (circuit breaker open, rate limit exceeded, etc.).

There are three competing concerns:

1. **Transparency.** When a downstream call fails, the application should see the original exception — not a library-specific wrapper. A `ServiceUnavailableException` thrown by a REST client should arrive at the catch-site as a `ServiceUnavailableException`, regardless of how many resilience elements the call passed through.

2. **Distinguishability.** When a resilience element intervenes (rejects a call, exhausts retries), the application must be able to distinguish "the downstream service failed" from "the library blocked the call." These are fundamentally different situations requiring different handling.

3. **Decoupling.** The application should not become deeply coupled to library-specific exception types. If `catch (CallNotPermittedException e)` appears in controllers, service layers, and error handlers throughout the codebase, the resilience library has become an invasive dependency — the opposite of its purpose.

Additionally, exception typing is fragile in practice. Between the throw-site and the catch-site, exceptions are routinely wrapped by frameworks, proxies, and reflection machinery:

```
Origin:         TimeoutException
↓ Future.get()  ExecutionException(cause: TimeoutException)
↓ Spring AOP    UndeclaredThrowableException(cause: ...)
↓ JDK Proxy     InvocationTargetException(cause: ...)
```

At the catch-site, the original type may be buried several layers deep. Developers who write `catch (TimeoutException e)` rarely implement recursive cause-chain traversal — and those who do face edge cases like circular cause references and double-wrapping.

## Decision

### Principle: four exception categories with clear rules

Inqudium classifies every exception it creates or encounters into exactly one of four categories. Each category has a fixed exception type, a clear purpose, and a rule for when it applies.

| Category | Exception type | When | Element context |
|---|---|---|---|
| **Active intervention** | `InqException` subclass | Element prevents or alters a call | Always (name, type, error code) |
| **Checked exception wrapper** | `InqRuntimeException` | Downstream `Callable` throws checked exception during a call | Always (name, type) |
| **Precondition violation** | `IllegalArgumentException` | Invalid config parameter at build time | None (element does not exist yet) |
| **State violation** | `IllegalStateException` | Operation on frozen/invalid object | None (infrastructure error) |

The following subsections define each category in detail.

### Category 1: Active intervention — InqException

Inqudium throws an `InqException` subclass **only** when the resilience element itself prevents or alters the call. When the downstream call executes and fails, the original exception propagates unchanged.

| Situation | What Inqudium does | What the application sees |
|---|---|---|
| Call succeeds | Returns result | The result |
| Call fails, Circuit Breaker CLOSED | Records failure, propagates | **Original exception** |
| Call fails, Retry has attempts left | Retries, eventually propagates | **Last original exception** |
| Retry exhausted | All attempts failed | `InqRetryExhaustedException` wrapping last cause |
| Circuit Breaker OPEN | Call never made | `InqCallNotPermittedException` |
| Rate Limiter denied | Call never made | `InqRequestNotPermittedException` |
| Bulkhead full | Call never made | `InqBulkheadFullException` |
| TimeLimiter fires | Caller stops waiting | `InqTimeLimitExceededException` |

Every `InqException` carries:

- `elementName` — the instance name (e.g. `"paymentService"`)
- `elementType` — the element kind (e.g. `CIRCUIT_BREAKER`)
- `code` — a structured error code in `INQ-XX-NNN` format (ADR-021)

Subclasses add element-specific context:

- `InqCallNotPermittedException` → current state (OPEN / HALF_OPEN), failure rate
- `InqRequestNotPermittedException` → wait estimate until next permit
- `InqBulkheadFullException` → current concurrent call count, max permitted
- `InqRetryExhaustedException` → number of attempts, last cause
- `InqTimeLimitExceededException` → configured timeout, actual duration

### Category 2: Checked exception wrapper — InqRuntimeException

The functional decoration API (ADR-002) uses `Supplier<T>`, `Runnable`, and other functional interfaces that do not declare checked exceptions. When an element decorates a `Callable<T>` (which declares `throws Exception`), checked exceptions must be converted to unchecked exceptions.

A bare `RuntimeException` loses all Inqudium context — the catch-site cannot tell which element was involved or that Inqudium was involved at all. `InqRuntimeException` preserves this context:

```java
// Inside decorateCallable:
try {
    return callable.call();
} catch (RuntimeException re) {
    throw re;  // unchecked — propagate unchanged
} catch (Exception e) {
    throw new InqRuntimeException(name, elementType, e);  // checked — wrap with context
}
```

`InqRuntimeException` is distinct from `InqException`:

- `InqException` = "the element actively intervened" (circuit breaker opened, retries exhausted)
- `InqRuntimeException` = "the downstream call threw a checked exception, and the element wrapped it for API convenience"

This distinction matters because `InqRuntimeException` is not an intervention — it is a transparent wrapping. The original cause is always accessible via `getCause()`. The element context (`getElementName()`, `getElementType()`) tells the catch-site where the wrapping occurred.

`InqRuntimeException` is also used by `InqFailure.orElseThrow()` and `InqFailure.orElseThrowIfAbsent()` when the original exception is a checked exception that must be rethrown as unchecked. In this case, no element context is available (the utility operates outside an element), and the contextless constructor is used.

**Rule:** Every `new RuntimeException(cause)` inside an element implementation must be `new InqRuntimeException(name, elementType, cause)` instead. There must be zero bare `RuntimeException` instantiations in element code.

### Category 3: Precondition violation — IllegalArgumentException

Invalid configuration parameters are programming errors — they are detected at build time, not at call time:

```java
// CountBasedSlidingWindow constructor
if (size <= 0) throw new IllegalArgumentException("Window size must be positive, got: " + size);

// ExponentialBackoff constructor
if (multiplier < 1.0) throw new IllegalArgumentException("Multiplier must be >= 1.0, got: " + multiplier);

// InqTimeoutProfile builder
if (factor < 1.0) throw new IllegalArgumentException("Safety margin factor must be >= 1.0, got: " + factor);
```

These use `IllegalArgumentException` — the Java standard for precondition violations — because:

1. **No element context exists.** The element has not been created yet. There is no instance name, no element type, no error code. An `InqRuntimeException` with null context would be misleading.
2. **Developer expectation.** Every Java developer expects `IllegalArgumentException` for invalid arguments. Using a library-specific exception for this would be surprising and non-idiomatic.
3. **These are not resilience events.** A negative window size is a bug in the application code, not a runtime condition that the resilience library should handle.

**Rule:** Config builders and SPI constructors use `IllegalArgumentException` for invalid parameters. This is the only category where non-Inq exceptions are thrown.

### Category 4: State violation — IllegalStateException

Operations on objects in an invalid state use `IllegalStateException`:

```java
// Registry that has been frozen
if (frozen) throw new IllegalStateException("Registry is frozen — late registration rejected");
```

The same rationale as Category 3 applies: these are programming errors detected at infrastructure level, not resilience events, and the Java convention is clear.

**Rule:** Infrastructure classes (registries, ServiceLoader wrappers) use `IllegalStateException` for state violations.

### Exception hierarchy

```
RuntimeException
├── InqRuntimeException                                    — checked exception wrapper (Category 2)
│   ├── InqRuntimeException(name, type, cause)             — inside an element (has context)
│   └── InqRuntimeException(cause)                         — outside an element (no context)
│
├── InqException (abstract)                                — active intervention (Category 1)
│   ├── InqCallNotPermittedException      INQ-CB-001/002   — circuit breaker rejected
│   ├── InqRequestNotPermittedException   INQ-RL-001       — rate limiter denied
│   ├── InqBulkheadFullException          INQ-BH-001       — no concurrency permits
│   ├── InqTimeLimitExceededException     INQ-TL-001       — caller wait time exceeded
│   └── InqRetryExhaustedException        INQ-RT-001       — all retry attempts failed
│
├── IllegalArgumentException                               — precondition violation (Category 3)
└── IllegalStateException                                  — state violation (Category 4)
```

All Inqudium exceptions extend `RuntimeException` (unchecked). This is non-negotiable for the functional decoration API (ADR-002) — `Supplier.get()`, `Runnable.run()`, and `Function.apply()` do not declare checked exceptions.

### Cause-chain navigation utility

Because exception wrapping by frameworks is pervasive, Inqudium provides a utility that traverses the cause chain:

```java
InqFailure.find(exception)
    .ifCircuitBreakerOpen(info -> {
        // info.elementName(), info.state(), info.failureRate()
    })
    .ifRateLimited(info -> {
        // info.elementName(), info.waitEstimate()
    })
    .ifBulkheadFull(info -> {
        // info.elementName(), info.concurrentCalls()
    })
    .ifTimeLimitExceeded(info -> {
        // info.elementName(), info.configuredDuration(), info.actualDuration()
    })
    .ifRetryExhausted(info -> {
        // info.elementName(), info.attempts(), info.lastCause()
    })
    .orElseThrow(); // re-throw if no Inqudium intervention found
```

`InqFailure.find()` walks the entire cause chain recursively, handles circular references, and returns the first `InqException` it encounters — regardless of how many layers of `ExecutionException`, `InvocationTargetException`, or `UndeclaredThrowableException` surround it.

`orElseThrow()` and `orElseThrowIfAbsent()` re-throw the original exception. If the original is a checked exception, it is wrapped in `InqRuntimeException` (without element context, since `InqFailure` operates outside an element).

### What Inqudium does NOT do with exceptions

- **No wrapping of original exceptions.** If the downstream throws `ServiceUnavailableException`, the application sees `ServiceUnavailableException` — never `InqException(cause: ServiceUnavailableException)`. The only exception is `InqRetryExhaustedException`, which wraps the last cause because the application needs to know that retries were attempted.
- **No custom exception hierarchy that mirrors standard exceptions.** There is no `InqTimeoutException extends TimeoutException`. This would pollute catch-sites: every `catch (TimeoutException e)` would silently catch Inqudium timeouts too, blurring the line between "the service timed out" and "Inqudium stopped waiting."
- **No checked exceptions.** All Inqudium exceptions are unchecked. Checked exceptions cannot propagate through functional interfaces.
- **No bare RuntimeException.** Every `RuntimeException` that Inqudium creates is either an `InqException` subclass (active intervention) or an `InqRuntimeException` (checked exception wrapper with element context). There is no code path where a bare `new RuntimeException(cause)` is thrown from element code.

### Enforcement rules summary

| Rule | Applies to | Exception type |
|---|---|---|
| Element actively intervenes | Element implementations | `InqException` subclass with error code |
| Checked exception during a call | `decorateCallable`, `ExecutionException` unwrapping | `InqRuntimeException(name, type, cause)` |
| Checked exception in utility | `InqFailure.orElseThrow` | `InqRuntimeException(cause)` |
| Invalid config parameter | Config builders, SPI constructors | `IllegalArgumentException` |
| Invalid object state | Frozen registries | `IllegalStateException` |
| Everything else | — | **Not permitted.** No other exception types are created by Inqudium. |

## Consequences

**Positive:**
- Maximum transparency: original exceptions pass through untouched in the common case (call made, call failed).
- Minimal coupling: applications only encounter Inqudium exceptions when the library actively intervenes.
- Complete context: every exception Inqudium creates carries enough information to identify the source — element name and type for call-time exceptions, error code for interventions, descriptive message for config violations.
- No context loss: checked exceptions are wrapped in `InqRuntimeException` with element context, not in bare `RuntimeException`.
- Clear rules: four categories, each with a fixed exception type, a clear purpose, and a strict rule. No ambiguity.
- Framework-resilient: `InqFailure.find()` handles the real-world cause-chain wrapping that makes type-based catching unreliable.

**Negative:**
- `InqRetryExhaustedException` wrapping the last cause is a deviation from the "no wrapping" principle. Justified because the application needs to know retries were attempted — the retry count is not available on the original exception.
- `InqFailure.find()` is an opt-in utility. Developers who don't know about it will fall back to `catch (InqException e)` — which works but doesn't solve the cause-chain wrapping problem.
- Two unchecked exception base types (`InqException` for interventions, `InqRuntimeException` for wrapping) require developers to understand the distinction. The Javadoc makes this clear, and `InqRuntimeException.hasElementContext()` helps at the catch-site.

**Neutral:**
- The exception hierarchy lives in `inqudium-core`. All paradigm implementations throw the same exception types.
- The Retry element ignores all `InqException` subtypes by default (ADR-017) — retrying against an open Circuit Breaker or an exhausted Rate Limiter is pointless and would worsen the situation.
- `InqRuntimeException` does not carry an error code. It is not an intervention — it is a transparent wrapping. The original cause carries its own identity.
