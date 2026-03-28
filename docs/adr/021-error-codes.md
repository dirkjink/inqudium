# ADR-021: Structured error codes

**Status:** Accepted  
**Date:** 2026-03-28  
**Deciders:** Core team

## Context

Exception messages are human-readable but unsuitable for structured diagnostics:

- They change between versions, breaking alert rules and monitoring dashboards.
- Substring matching in log aggregation tools is fragile — a rewording breaks the query.
- At 3 AM, a developer looking at a Kibana dashboard needs a code they can immediately search in the documentation, not a sentence they have to parse.

Libraries like Hibernate (HHH-NNNN), Spring (SPR-NNNN), and WildFly (WFLYEE-NNNN) demonstrate the value of stable error codes. However, flat sequential numbering (INQ-0001, INQ-0002) does not convey which element is involved — the developer must look up the number to find the context.

Inqudium has a unique advantage: every element already has a two-character symbol from the periodic-table branding (ADR element symbols in `InqElementType` Javadoc). These symbols can serve as the element discriminator in the error code.

## Decision

### Format

```
INQ-XX-NNN
```

Where:
- `INQ` — library prefix (stable, never changes)
- `XX` — two-character element symbol (uppercase)
- `NNN` — three-digit error number within the element (001–999)

### Element symbol mapping

| Symbol | Element | Example |
|--------|---------|---------|
| `CB` | Circuit Breaker | `INQ-CB-001` |
| `RT` | Retry | `INQ-RT-001` |
| `RL` | Rate Limiter | `INQ-RL-001` |
| `BH` | Bulkhead | `INQ-BH-001` |
| `TL` | Time Limiter | `INQ-TL-001` |
| `CA` | Cache | `INQ-CA-001` |
| `SY` | System (pipeline, registry, ServiceLoader, context) | `INQ-SY-001` |

The symbols match the element table symbols from the project branding, uppercased for readability in log output.

### Initial error code catalog

#### Circuit Breaker (CB)

| Code | Exception / Event | Description |
|------|-------------------|-------------|
| `INQ-CB-001` | `InqCallNotPermittedException` | Call rejected — circuit breaker is OPEN |
| `INQ-CB-002` | `InqCallNotPermittedException` | Call rejected — HALF_OPEN probe limit reached |

#### Retry (RT)

| Code | Exception / Event | Description |
|------|-------------------|-------------|
| `INQ-RT-001` | `InqRetryExhaustedException` | All retry attempts exhausted |

#### Rate Limiter (RL)

| Code | Exception / Event | Description |
|------|-------------------|-------------|
| `INQ-RL-001` | `InqRequestNotPermittedException` | Request denied — no permits available |

#### Bulkhead (BH)

| Code | Exception / Event | Description |
|------|-------------------|-------------|
| `INQ-BH-001` | `InqBulkheadFullException` | Call rejected — max concurrent calls reached |

#### Time Limiter (TL)

| Code | Exception / Event | Description |
|------|-------------------|-------------|
| `INQ-TL-001` | `InqTimeLimitExceededException` | Caller wait time exceeded configured timeout |

#### System (SY)

| Code | Exception / Event | Description |
|------|-------------------|-------------|
| `INQ-SY-001` | `InqProviderErrorEvent` | ServiceLoader provider failed during construction |
| `INQ-SY-002` | `InqProviderErrorEvent` | ServiceLoader provider failed during execution |
| `INQ-SY-003` | log warning | Registry name collision (first-registration-wins) |
| `INQ-SY-004` | log warning | Pipeline anti-pattern detected |
| `INQ-SY-005` | `IllegalStateException` | Registry frozen — late registration rejected |

### Message format

The error code is prepended to the existing message, separated by a colon:

```
INQ-CB-001: CircuitBreaker 'payment' is OPEN (failure rate: 75.5%)
INQ-RT-001: Retry 'orderService' exhausted after 3 attempts
INQ-BH-001: Bulkhead 'inventoryService' is full (25/25 concurrent calls)
INQ-TL-001: TimeLimiter 'paymentService' timed out after 3002ms (configured: 3000ms)
INQ-RL-001: RateLimiter 'apiGateway' denied request (next permit in ~250ms)
INQ-SY-001: ServiceLoader provider 'com.example.MyExporter' failed during construction: NullPointerException
```

### Implementation

Error codes are defined as `String` constants on `InqException` subclasses — not a separate enum. This keeps the code co-located with the exception and avoids a central registry class that would create an artificial dependency bottleneck.

```java
public class InqCallNotPermittedException extends InqException {

    /** Call rejected — circuit breaker is OPEN. */
    public static final String CODE = "INQ-CB-001";

    // ... constructor prepends CODE to message
}
```

The `getCode()` method is defined on `InqException` (abstract base) so that generic catch-site handling can extract the code without type-checking:

```java
catch (InqException e) {
    metrics.increment("inqudium.error." + e.getCode());
    log.warn("{}: {}", e.getCode(), e.getMessage());
}
```

### Stability contract

- **Error codes are stable across minor versions.** A code assigned in 0.2.0 will not be reassigned or removed until the next major version.
- **New codes may be added in any minor version.** The NNN numbering leaves room for 999 codes per element.
- **Messages may change freely.** The human-readable part after the code is not stable — only the code itself is a contract.
- **Codes are not localized.** `INQ-CB-001` is the same in every locale, every JVM, every log aggregation tool.

## Consequences

**Positive:**
- Googleable, greppable, dashboardable — `INQ-CB-001` works in Kibana queries, Prometheus alert rules, PagerDuty runbooks, and documentation search.
- Element identification at a glance — `CB` immediately tells you it's a Circuit Breaker without reading the message.
- Stable across versions — alert rules survive message rewordings.
- Documentation-linkable — each code gets a page in the docs with causes, diagnostics, and remediation.
- Consistent with established patterns (Hibernate, Spring, WildFly).

**Negative:**
- Minor message overhead — 12 characters prepended to every exception message. Negligible.
- Maintenance discipline required — every new exception or warning needs a code assignment. Mitigated by co-locating the code constant on the exception class itself (no central registry to forget).

**Neutral:**
- The `SY` prefix is new — it does not correspond to a periodic-table element. This is intentional: system-level codes are infrastructure, not resilience elements.
- The three-digit number allows 999 codes per element. If an element needs more than 999 distinct error codes, the element is doing too much.
