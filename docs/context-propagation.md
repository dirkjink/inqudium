# Context Propagation

When a resilience element executes a call on a different thread or scope (e.g., a TimeLimiter scheduling on a virtual thread), diagnostic context (MDC, OpenTelemetry Baggage) must propagate.

## SPI

`InqContextPropagator` is the SPI that bridge modules implement. The lifecycle is:

1. **Capture** — Snapshot the current context before the boundary.
2. **Restore** — Apply the snapshot on the new thread/scope.
3. **Enrich** — Add Inqudium-specific entries (callId, element name, element type).
4. **Close** — Restore the previous context when the protected call completes.

```java
try (var scope = InqContextPropagation.activateFor(callId, elementName, elementType)) {
    return protectedCall.execute();
} // scope.close() restores the previous context
```

## Bridge modules

Bridge modules provide propagator implementations. Adding the JAR to the classpath is sufficient — `ServiceLoader` handles registration.

| Module | Context system |
|--------|---------------|
| `inqudium-context-slf4j` | SLF4J MDC |
| `inqudium-context-otel` | OpenTelemetry Baggage (planned) |

## MDC entries

When `inqudium-context-slf4j` is on the classpath, every protected call has these MDC entries:

```
inq.callId      = a1b2c3d4-e5f6-...
inq.elementName = paymentService
inq.elementType = CIRCUIT_BREAKER
```

These propagate through async boundaries, enabling structured log correlation.
