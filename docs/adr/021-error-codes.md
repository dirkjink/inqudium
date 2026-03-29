# ADR-021: Structured error codes

**Status:** Accepted  
**Date:** 2026-03-28 (updated 2026-03-29)  
**Deciders:** Core team

## Context

Exception messages are human-readable but unsuitable for structured diagnostics:

- They change between versions, breaking alert rules and monitoring dashboards.
- Substring matching in log aggregation tools is fragile — a rewording breaks the query.
- At 3 AM, a developer looking at a Kibana dashboard needs a code they can immediately search in the documentation, not a sentence they have to parse.

Libraries like Hibernate (HHH-NNNN), Spring (SPR-NNNN), and WildFly (WFLYEE-NNNN) demonstrate the value of stable error codes. However, flat sequential numbering (INQ-0001, INQ-0002) does not convey which element is involved — the developer must look up the number to find the context.

Inqudium has a unique advantage: every element already has a two-character symbol from the periodic-table branding. These symbols are carried as fields on the `InqElementType` enum and serve as the element discriminator in the error code.

## Decision

### Format

```
INQ-XX-NNN
```

Where:
- `INQ` — library prefix (stable, never changes)
- `XX` — two-character element symbol (uppercase)
- `NNN` — three-digit error number within the element (000–999)

### Number ranges

| Range | Meaning |
|-------|---------|
| `000` | **Reserved:** wrapped downstream checked exception (see below) |
| `001–099` | Exception codes — active resilience interventions |
| `100–199` | Event codes — observability events |
| `200–999` | Reserved for future use |

### Reserved code `000` — wrapped checked exceptions

Error codes ending in `000` indicate that a downstream call threw a **checked exception** which the element wrapped in `InqRuntimeException` for API convenience (ADR-009, Category 2). The `000` suffix explicitly signals that this is **not** an active intervention — it is a transparent wrapping.

```
INQ-CB-000: Checked exception in CIRCUIT_BREAKER 'paymentService': java.io.IOException: connection refused
INQ-RT-000: Checked exception in RETRY 'orderService': javax.naming.NamingException: lookup failed
INQ-XX-000: connection refused   (InqFailure.orElseThrow — no element context)
```

The `000` code is never manually assigned. It is generated automatically by `InqRuntimeException`:

- **Inside an element:** `InqRuntimeException(name, elementType, cause)` → code is `elementType.errorCode(0)`, e.g. `INQ-CB-000`
- **Outside an element:** `InqRuntimeException(cause)` → code is `INQ-XX-000` (system-level wrapping, no element context)

This design ensures that:
1. Every `InqException` — including `InqRuntimeException` — has a structured error code.
2. Monitoring dashboards can distinguish interventions (`001+`) from wrappings (`000`) with a simple suffix check.
3. A Kibana query for `INQ-CB-*` captures both circuit breaker interventions and checked exceptions that occurred inside a circuit breaker.

### Element symbol mapping

Symbols are fields on the `InqElementType` enum, accessible via `symbol()`:

| Symbol | Element | `InqElementType` constant |
|--------|---------|---------------------------|
| `CB` | Circuit Breaker | `CIRCUIT_BREAKER` |
| `RT` | Retry | `RETRY` |
| `RL` | Rate Limiter | `RATE_LIMITER` |
| `BH` | Bulkhead | `BULKHEAD` |
| `TL` | Time Limiter | `TIME_LIMITER` |
| `CA` | Cache | `CACHE` |
| `XX` | No element (pipeline, registry, ServiceLoader, context) | `NO_ELEMENT` |

### Error code catalog

#### Reserved wrapping codes (000)

| Code | Exception | Description |
|------|-----------|-------------|
| `INQ-CB-000` | `InqRuntimeException` | Checked exception wrapped inside Circuit Breaker |
| `INQ-RT-000` | `InqRuntimeException` | Checked exception wrapped inside Retry |
| `INQ-RL-000` | `InqRuntimeException` | Checked exception wrapped inside Rate Limiter |
| `INQ-BH-000` | `InqRuntimeException` | Checked exception wrapped inside Bulkhead |
| `INQ-TL-000` | `InqRuntimeException` | Checked exception wrapped inside Time Limiter |
| `INQ-XX-000` | `InqRuntimeException` | Checked exception wrapped outside any element (e.g. `InqFailure.orElseThrow`) |

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

#### No Element (XX)

| Code | Exception / Event | Description |
|------|-------------------|-------------|
| `INQ-XX-000` | `InqRuntimeException` | Checked exception wrapped outside any element |
| `INQ-XX-001` | `InqProviderErrorEvent` | ServiceLoader provider failed during construction |
| `INQ-XX-002` | `InqProviderErrorEvent` | ServiceLoader provider failed during execution |
| `INQ-XX-003` | log warning | Registry name collision (first-registration-wins) |
| `INQ-XX-004` | log warning | Pipeline anti-pattern detected |
| `INQ-XX-005` | `IllegalStateException` | Registry frozen — late registration rejected |

### Message format

The error code is prepended to the existing message, separated by a colon:

```
INQ-CB-001: CircuitBreaker 'payment' is OPEN (failure rate: 75.5%)
INQ-RT-001: Retry 'orderService' exhausted after 3 attempts
INQ-BH-001: Bulkhead 'inventoryService' is full (25/25 concurrent calls)
INQ-TL-001: TimeLimiter 'paymentService' timed out after 3002ms (configured: 3000ms)
INQ-RL-001: RateLimiter 'apiGateway' denied request (next permit in ~250ms)
INQ-CB-000: Checked exception in CIRCUIT_BREAKER 'paymentService': java.io.IOException: connection refused
INQ-XX-000: connection refused
INQ-XX-001: ServiceLoader provider 'com.example.MyExporter' failed during construction: NullPointerException
```

### Implementation

#### InqElementType — symbol and error code generation

The two-character symbol and the error code format are defined on the `InqElementType` enum:

```java
public enum InqElementType {

    CIRCUIT_BREAKER("CB"),
    RETRY("RT"),
    RATE_LIMITER("RL"),
    BULKHEAD("BH"),
    TIME_LIMITER("TL"),
    CACHE("CA");

    private final String symbol;

    InqElementType(String symbol) { this.symbol = symbol; }

    /** Returns the two-character element symbol (e.g. "CB", "RT"). */
    public String symbol() { return symbol; }

    /** Generates a structured error code: INQ-XX-NNN. */
    public String errorCode(int number) {
        return String.format(Locale.ROOT, "INQ-%s-%03d", symbol, number);
    }
}
```

This centralizes the format in one place. Exception classes reference `InqElementType.errorCode(N)` instead of hardcoding strings:

```java
public class InqCallNotPermittedException extends InqException {

    /** Call rejected — circuit breaker is OPEN. */
    public static final String CODE = InqElementType.CIRCUIT_BREAKER.errorCode(1);
    // → "INQ-CB-001"
}

public class InqRuntimeException extends InqException {

    // Inside an element:
    public InqRuntimeException(String name, InqElementType type, Throwable cause) {
        super(type.errorCode(0), name, type, ...);
        // → "INQ-CB-000" for CIRCUIT_BREAKER
    }

    // Outside an element (InqFailure):
    public InqRuntimeException(Throwable cause) {
        super("INQ-XX-000", null, null, ...);
    }
}
```

#### Benefits of `InqElementType.errorCode()`

1. **Single source of truth.** The format `INQ-XX-NNN` is defined once. If the format ever changes, only `errorCode()` needs updating.
2. **No hardcoded strings.** Exception classes reference `InqElementType.CIRCUIT_BREAKER.errorCode(1)` — a typo in the symbol is impossible because the enum constant enforces correctness.
3. **Discoverable.** `InqElementType.CIRCUIT_BREAKER.errorCode(0)` reads as "the error code for a Circuit Breaker with number 0" — self-documenting.
4. **Testable.** `assertThat(InqElementType.CIRCUIT_BREAKER.errorCode(1)).isEqualTo("INQ-CB-001")` — trivial to test.

### Stability contract

- **Error codes are stable across minor versions.** A code assigned in 0.2.0 will not be reassigned or removed until the next major version.
- **Code `000` is permanently reserved** for wrapped checked exceptions across all elements. It will never be reassigned to an active intervention.
- **New codes may be added in any minor version.** The NNN numbering leaves room for 999 codes per element.
- **Messages may change freely.** The human-readable part after the code is not stable — only the code itself is a contract.
- **Codes are not localized.** `INQ-CB-001` is the same in every locale, every JVM, every log aggregation tool.
- **Element symbols are stable.** The two-character symbols on `InqElementType` will not change. They are part of the error code contract.

## Consequences

**Positive:**
- Googleable, greppable, dashboardable — `INQ-CB-001` works in Kibana queries, Prometheus alert rules, PagerDuty runbooks, and documentation search.
- Element identification at a glance — `CB` immediately tells you it's a Circuit Breaker without reading the message.
- Intervention vs. wrapping at a glance — `000` suffix means wrapping, `001+` means active intervention.
- Stable across versions — alert rules survive message rewordings.
- Documentation-linkable — each code gets a page in the docs with causes, diagnostics, and remediation.
- Consistent with established patterns (Hibernate, Spring, WildFly).
- No hardcoded strings — all codes derived from `InqElementType.errorCode(N)`.

**Negative:**
- Minor message overhead — 12 characters prepended to every exception message. Negligible.
- Maintenance discipline required — every new exception or warning needs a code assignment. Mitigated by co-locating the code constant on the exception class and deriving it from `InqElementType`.

**Neutral:**
- The three-digit number allows 999 codes per element. If an element needs more than 999 distinct error codes, the element is doing too much.
