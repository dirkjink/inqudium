# ADR-011: Context propagation

**Status:** Accepted  
**Date:** 2026-03-23  
**Last updated:** 2026-03-23  
**Deciders:** Core team

## Context

Diagnostic and operational context — trace IDs, user IDs, request IDs, tenant identifiers — must propagate through resilience elements without being lost. In production, context entries are the primary mechanism for correlating log lines, traces, and metrics across layers and services.

The most common context carrier is SLF4J's MDC (`ThreadLocal<Map<String, String>>`). But MDC is not the only one:

- **OpenTelemetry Baggage** — key-value pairs propagated across service boundaries
- **Micrometer Observation Scope** — context for metrics and tracing
- **Spring Security Context** — authentication and authorization state
- **Custom application context** — tenant ID, feature flags, session state

All of these share the same fundamental problem: they are `ThreadLocal`-based and break when execution crosses thread boundaries — thread pools, virtual threads, coroutines, reactive chains.

A resilience library wraps application calls and frequently changes the execution context (TimeLimiter on a separate Future, Retry across attempts, Bulkhead on a different scheduler). If the library does not propagate context, it creates a blind spot in observability exactly when visibility matters most — during retries, timeouts, and circuit breaker state changes.

### Why the core must be context-system-agnostic

Binding the core to SLF4J's MDC would:

1. **Violate the zero-dependency principle.** `inqudium-core` depends only on the JDK (ADR-005). Adding `slf4j-api` as a runtime dependency breaks this contract.
2. **Exclude non-MDC context systems.** Applications using OpenTelemetry Baggage or custom context carriers would not benefit from propagation.
3. **Couple the SPI to a specific implementation.** MDC's `Map<String, String>` API constrains context to string key-value pairs. Other systems carry richer types.

## Decision

### A framework-agnostic context propagation SPI in `inqudium-core`

The core defines an `InqContextPropagator` SPI that abstracts context capture, restore, and cleanup. The SPI knows nothing about MDC, OpenTelemetry, or any specific context carrier.

```java
/**
 * SPI for propagating diagnostic context across execution boundaries.
 * Implementations bridge to specific context systems (MDC, OTel Baggage, etc.).
 *
 * Registered via InqContextPropagatorRegistry or Java ServiceLoader.
 */
public interface InqContextPropagator {

    /**
     * Captures the current context from the calling thread/scope.
     * Returns an opaque snapshot that can be restored later on a different thread.
     */
    InqContextSnapshot capture();

    /**
     * Restores a previously captured snapshot on the current thread/scope.
     * Returns a handle that must be closed to clean up (try-with-resources).
     */
    InqContextScope restore(InqContextSnapshot snapshot);

    /**
     * Optional: enriches the current context with Inqudium-specific entries.
     * Called after restore, before the protected call executes.
     */
    default void enrich(String callId, String elementName, InqElementType elementType) {
        // no-op by default — implementations add entries to their context system
    }
}
```

Supporting types:

```java
/**
 * Opaque snapshot of context state. Implementation-specific contents.
 */
public interface InqContextSnapshot {
    // marker interface — implementations carry their own state
}

/**
 * Scope handle returned by restore(). Implements AutoCloseable for
 * try-with-resources cleanup.
 */
public interface InqContextScope extends AutoCloseable {
    @Override
    void close(); // restores previous context, no exception
}
```

### How elements use the SPI

The capture/restore cycle is encapsulated in a core utility class. Element implementations never interact with individual propagators directly:

```java
// What element implementations write — one line
try (var scope = InqContextPropagation.activateFor(callId, elementName, elementType)) {
    return protectedCall.execute();
}
```

Internally, `InqContextPropagation.activateFor()` iterates over all registered propagators, captures, restores, enriches, and returns a composite scope that cleans up in reverse order on `close()`. If no propagators are registered, the method is a no-op with near-zero overhead.

### Paradigm-specific integration

The capture/restore cycle maps naturally to each paradigm's execution model:

| Paradigm | Where capture happens | Where restore happens |
|---|---|---|
| Imperative | Before `CompletableFuture.supplyAsync()` | First line inside the async task |
| Kotlin | Before `withContext()` / coroutine launch | Inside the coroutine, via a `CoroutineContext.Element` wrapping `InqContextPropagation` |
| Reactor | At `contextWrite()` time (subscription) | In each operator via `deferContextual()` |
| RxJava 3 | Before `subscribe()` | Via `RxJavaPlugins.setScheduleHandler()` wrapping |

Each paradigm module provides a bridge class that adapts the core SPI to the paradigm's native context mechanism. For example, the Kotlin module provides a `CoroutineContext.Element` that captures on creation and restores on every resumption:

```kotlin
class InqPropagationContext(
    private val callId: String,
    private val elementName: String,
    private val elementType: InqElementType
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<InqPropagationContext>

    private val snapshot = InqContextPropagation.captureAll()

    fun activate(): InqContextScope {
        return InqContextPropagation.restoreAndEnrich(snapshot, callId, elementName, elementType)
    }
}
```

### Registration

Propagators are registered in two ways:

**Programmatic (explicit):**
```java
InqContextPropagatorRegistry.register(new MdcContextPropagator());
InqContextPropagatorRegistry.register(new OtelBaggagePropagator());
```

**ServiceLoader (automatic):**
Implementations provide a `META-INF/services/eu.inqudium.core.context.InqContextPropagator` file. Inqudium discovers them at startup. This allows bridge modules to activate automatically when they are on the classpath — zero configuration. Discovery timing, ordering, error isolation, and lifecycle follow the conventions defined in ADR-014.

Multiple propagators can be registered simultaneously. They execute in registration order during capture and in reverse order during cleanup.

### Bridge modules

Each context system gets its own bridge module. Bridge modules depend on `inqudium-core` + their target library. No bridge module depends on another bridge module.

#### `inqudium-context-slf4j` — the MDC bridge

```java
public class MdcContextPropagator implements InqContextPropagator {

    @Override
    public InqContextSnapshot capture() {
        return new MdcSnapshot(MDC.getCopyOfContextMap());
    }

    @Override
    public InqContextScope restore(InqContextSnapshot snapshot) {
        var previous = MDC.getCopyOfContextMap();
        MDC.setContextMap(((MdcSnapshot) snapshot).entries());
        return () -> {
            if (previous != null) MDC.setContextMap(previous);
            else MDC.clear();
        };
    }

    @Override
    public void enrich(String callId, String elementName, InqElementType elementType) {
        MDC.put("inqudium.callId", callId);
        MDC.put("inqudium.elementName", elementName);
        MDC.put("inqudium.elementType", elementType.name());
    }

    private record MdcSnapshot(Map<String, String> entries) implements InqContextSnapshot {}
}
```

ServiceLoader registration (`META-INF/services/eu.inqudium.core.context.InqContextPropagator`):
```
eu.inqudium.context.slf4j.MdcContextPropagator
```

Adding `inqudium-context-slf4j` to the classpath is all that's needed. ServiceLoader discovers the propagator, and MDC flows through every resilience element automatically.

The MDC bridge additionally detects whether Reactor's automatic context propagation (`Hooks.enableAutomaticContextPropagation()`) is active and defers to it to avoid double-propagation.

#### Planned bridge modules

| Module                        | Propagator                    | Bridges to                    |
|-------------------------------|-------------------------------|-------------------------------|
| `inqudium-context-slf4j`      | `MdcContextPropagator`        | SLF4J MDC                     |
| `inqudium-context-otel`       | `OtelBaggagePropagator`       | OpenTelemetry Baggage         |
| `inqudium-context-micrometer` | `ObservationScopePropagator`  | Micrometer Observation Scope  |

### What the core enriches

When `enrich()` is called, the core passes three values:

| Key             | Value                          | Set by              |
|-----------------|--------------------------------|---------------------|
| `callId`        | The call's unique identifier (ADR-003) | Outermost element   |
| `elementName`   | Current element name           | Each element        |
| `elementType`   | Current element type (`InqElementType` enum) | Each element        |

The propagator decides how to store these. The MDC bridge stores them as `inqudium.callId`, `inqudium.elementName`, `inqudium.elementType`. An OpenTelemetry bridge might store them as Baggage entries. A custom propagator stores them wherever its context system lives.

The Retry element additionally passes `retryAttempt` through an extended enrichment — this is element-specific and handled by propagators that implement an optional `InqRetryAwareContextPropagator` sub-interface.

### Opt-out

Context propagation can be disabled globally or per-element:

```java
var config = CircuitBreakerConfig.builder()
    .contextPropagation(false)  // disable for this element
    .build();
```

```yaml
inqudium:
  context-propagation: false  # disable globally
```

When disabled, no capture/restore cycle runs. When no propagators are registered (no bridge module on classpath), the cycle is also a no-op.

## Consequences

**Positive:**
- `inqudium-core` remains free of SLF4J, OpenTelemetry, or any context framework dependency. The SPI is pure JDK interfaces.
- Multiple context systems coexist. An application using both MDC and OpenTelemetry Baggage registers both propagators — both are captured and restored at every boundary crossing.
- ServiceLoader auto-registration means zero configuration for the common case: add the bridge module to the classpath, and it works.
- The `InqContextPropagation.activateFor(...)` utility encapsulates the capture/restore pattern — element implementations are clean and cannot forget cleanup.
- `callId` from ADR-003 flows through the context system into MDC/OTel/custom loggers — bridging Inqudium events and application log lines.
- New context systems can be supported by adding a bridge module — no changes to core or existing elements.

**Negative:**
- An additional SPI abstraction layer. For projects that only use MDC, the indirection through `InqContextPropagator` → `MdcContextPropagator` adds conceptual complexity compared to direct `MDC.getCopyOfContextMap()`.
- Multiple propagators run sequentially at every boundary crossing. With three propagators and a pipeline of four elements, that is 12 capture/restore cycles per call. The overhead per cycle is ~100-200ns (map copy), so this is negligible for I/O-bound calls but measurable in microbenchmarks.
- The SPI must be stable — once bridge modules in the wild depend on `InqContextPropagator`, changing the interface is a breaking change.
- Each bridge module is a separate artifact to maintain and version.

**Neutral:**
- The Kotlin module depends on the core SPI, not on `kotlinx-coroutines-slf4j` directly. MDC propagation happens through the `inqudium-context-slf4j` bridge if present on the classpath. The Kotlin module does not need to know which context systems are active.
- If no bridge module is on the classpath, context propagation is silently inactive — no errors, no warnings, no overhead.
