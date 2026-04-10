# ADR-022: Call identity propagation

**Status:** Accepted  
**Date:** 2026-03-31
**Deciders:** Core team  
**Supersedes:** Initial ThreadLocal-based InqCallContext (removed)

## Context

Every call through an Inqudium pipeline passes through multiple resilience elements — a Circuit Breaker, a Retry, a Rate
Limiter, a Bulkhead, a Time Limiter — in sequence. For observability (ADR-003), all events emitted during a single call
when diagnostic events are enabled must share the same `callId`. This is the foundation of end-to-end correlation:
filtering by `callId` in Kibana, Grafana, or JFR reconstructs the complete lifecycle of one request across all elements.

### The problem

In the initial implementation, each element generated its own `callId` independently via `UUID.randomUUID().toString()`.
When three elements were composed in a pipeline, three different `callId` values appeared on the events:

```
Pipeline.execute()
  CircuitBreaker generates callId "aaa-111" → emits CB events with "aaa-111"
    Retry generates callId "bbb-222"        → emits Retry events with "bbb-222"
      RateLimiter generates callId "ccc-333" → emits RL events with "ccc-333"
```

The `callId` correlation — the core promise of ADR-003 — was broken. There was no mechanism to propagate a single
identity through the decoration chain.

### Requirements

1. **Shared identity in pipelines:** All elements in a pipeline must use the same `callId` for a given invocation.
2. **Independent identity standalone:** When an element is used outside a pipeline, it generates its own `callId`. No
   pipeline context is required for standalone usage.
3. **Cross-paradigm:** The solution must work for imperative `Supplier`, Kotlin `suspend () -> T`, Project Reactor
   `Mono/Flux`, and RxJava `Single/Flowable`.
4. **Testable:** The `callId` must be predictable in tests — no random UUIDs that make event assertions fragile.

### Rejected solution: ThreadLocal context

The obvious Java solution is a `ThreadLocal<String> callIdContext`. The pipeline generates the ID, sets it in the
ThreadLocal, and each element reads it:

```java
// Anti-pattern
InqCallContext.setCallId(UUID.randomUUID().toString());
try {
    return circuitBreaker.decorateSupplier(
        () -> retry.decorateSupplier(service::call).get()
    ).get();
} finally {
    InqCallContext.clear();
}
```

**Why we rejected it:**

- **Paradigm hostility.** ThreadLocal fails immediately in reactive streams and coroutines. Bridging ThreadLocal into
  `ContextView` (Reactor) or `CoroutineContext` (Kotlin) at every layer is complex, expensive, and fragile.
- **Async disconnect.** If a Retry element schedules its next attempt on a different thread, the ThreadLocal is lost
  unless explicitly captured and restored.
- **Hidden coupling.** The elements implicitly depend on state outside their method signatures.

## Decision

We introduce an explicit **call record** that travels through the decoration chain. Instead of decorators taking a
`Supplier<T>` and returning a `Supplier<T>`, they take an `InqCall<T>` and return an `InqCall<T>`.

### The `InqCall` abstraction

```java
/**
 * Represents a single execution passing through a resilience pipeline.
 * Carries the operation to execute and the context of the execution.
 */
public record InqCall<T>(
    String callId,
    Supplier<T> supplier
    // Future expansion: deadline, caller priority, etc.
) {
    /**
     * Creates a new call with a generated UUID.
     * Used by elements when operating standalone.
     */
    public static <T> InqCall<T> of(Supplier<T> supplier) {
        return new InqCall<>(InqCallIdGenerator.generate(), supplier);
    }

    /**
     * Creates a call with a specific ID.
     * Used by pipelines and tests.
     */
    public static <T> InqCall<T> of(String callId, Supplier<T> supplier) {
        return new InqCall<>(callId, supplier);
    }
}
```

The functional decoration API (ADR-002) changes from:

```java
// Old (implicit)
Supplier<T> decorateSupplier(Supplier<T> supplier);
```

To:

```java
// New (explicit)
InqCall<T> decorateCall(InqCall<T> call);

// Convenience overloads (delegates to decorateCall)
default Supplier<T> decorateSupplier(Supplier<T> supplier) {
    return decorateCall(InqCall.of(supplier)).supplier();
}
```

### How elements use `InqCall`

When a Circuit Breaker decorates a call, it reads the `callId` from the input, uses it for observability, and returns a
new `InqCall` containing the same `callId` and the wrapped supplier:

```java
@Override
public <T> InqCall<T> decorateCall(InqCall<T> call) {
    Supplier<T> wrapped = () -> {
        // Use call.callId() for events/MDC
        var context = InqContextPropagation.activateFor(call.callId(), ...);
        try (context) {
            // ... state machine logic ...
            return call.supplier().get();
        }
    };
    return new InqCall<>(call.callId(), wrapped);
}
```

### How pipelines use `InqCall`

The `InqPipeline` generates the `callId` once and pushes it through the chain:

```java
// Inside InqPipeline.execute(Supplier<T>)
String sharedCallId = InqCallIdGenerator.generate();

InqCall<T> call = InqCall.of(sharedCallId, originalSupplier);

for (InqDecorator decorator : decorators) {
    call = decorator.decorateCall(call);
}

return call.supplier().get();
```

Because every decorator preserves the `callId` it receives, the entire chain operates on `sharedCallId`.

### Paradigm translations

The imperative `InqCall<T>` maps cleanly to reactive and coroutine paradigms:

**Kotlin Coroutines:**

```kotlin
data class InqSuspendCall<T>(
    val callId: String,
    val action: suspend () -> T
)

fun <T> decorateSuspendCall(call: InqSuspendCall<T>): InqSuspendCall<T>
```

**Project Reactor:**
In Reactor, the `callId` does not wrap the `Mono` — it travels *inside* the Reactive Context, which is the native
propagation mechanism. The pipeline writes it once:

```java
// Reactor pipeline
Mono<T> decorated = mono;
for (ReactorDecorator decorator : decorators) {
    decorated = decorator.decorateMono(decorated);
}
return decorated.contextWrite(ctx -> ctx.put(CALL_ID_KEY, sharedCallId));
```

Elements read it during subscription:

```java
// Reactor element
return mono.deferContextual(ctx -> {
    String callId = ctx.getOrDefault(CALL_ID_KEY, InqCallIdGenerator.generate());
    // ... use callId ...
});
```

### The `InqDecorator` interface

To support pipeline composition without reflection or excessive type bounds, all imperative decorators implement a
common interface:

```java
public interface InqDecorator extends InqElement {
    <T> InqCall<T> decorateCall(InqCall<T> call);

    // Default convenience method for standalone usage
    default <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
        return decorateCall(InqCall.of(supplier)).supplier();
    }
}
```

This unifies `CircuitBreaker`, `Retry`, `RateLimiter`, and `Bulkhead` under a single composable type. (`TimeLimiter` is
an exception, as it requires `CompletionStage` semantics, see ADR-010. The pipeline handles this special case.)

## CallId Generator

The default ID generator (`InqCallIdGenerator`) provides UUID strings. However, if the application already has a
correlation ID (e.g., an OpenTelemetry Trace ID or a custom request ID), generating a new UUID is wasteful and breaks
correlation.

The pipeline configuration accepts an optional `CallIdSupplier`:

```java
var pipeline = InqPipeline.builder()
    .decorators(circuitBreaker, retry)
    .callIdSupplier(() -> MDC.get("request-id")) // Use existing ID
    .build();
```

If the supplier returns null, or if no supplier is configured, `InqCallIdGenerator` falls back to UUID generation.

## Testability

The explicit `InqCall` dramatically improves testability. To test an element's event emission with a known `callId`:

```java
var cb = CircuitBreaker.ofDefaults("test");
var call = InqCall.of("fixed-id-123", () -> "success");

cb.decorateCall(call).supplier().get();

assertThat(events).hasSize(1);
assertThat(events.get(0).getCallId()).isEqualTo("fixed-id-123");
```

No thread-local setup, no mock static generators. Just pure data in, data out.

## Why not `InqRequest`?

The name `InqCall` was chosen over `InqRequest` to avoid confusion with HTTP requests. A resilience element protects a
*method call* (which might be an HTTP request, a database query, or a local computation).

## Extensibility

The `InqCall` record is a natural carrier for other execution-scoped metadata that elements might need in the future.
For example, if we introduce a `Deadline` concept (where the timeout shrinks as the call passes through the pipeline),
`InqCall` can carry it:

```java
public record InqCall<T>(
    String callId,
    Supplier<T> supplier,
    Instant deadline  // added in future version
)
```

All existing decorators continue to work — they read `call.callId()` and `call.supplier()` as before. New decorators can
additionally read `call.deadline()`. No signature changes, no ThreadLocal additions, no migration.

## Consequences

**Positive:**

- The `callId` correlation promise (ADR-003) is now structurally guaranteed. There is no code path where two elements in
  the same pipeline can see different `callId` values.
- Works identically across all four paradigms — no thread-affinity assumptions, no reactive workarounds.
- Testable without setup: `InqCall.of("test-id", supplier)` → pass to any decorator → assert on events.
- Extensible: new context fields can be added to the record without API-breaking changes.
- The type hierarchy (`InqDecorator extends InqElement`) means elements are directly usable in pipelines — no wrapper or
  adapter needed.

**Negative:**

- Adds a small object allocation (`InqCall`) per decorator layer in imperative pipelines. JMH benchmarks show this is
  negligible (short-lived objects, escape-analyzed by C2), but it is non-zero.
- The `decorateSupplier` API is now a default interface method rather than the core abstraction. Authors of custom
  elements must implement `decorateCall`.

**Neutral:**

- The `InqCall` record is a core type that all paradigm modules will use. It must be stable — field additions are
  additive (new fields with defaults), field removals are breaking changes. This is the same stability contract as
  `InqEvent` and `InqConfig`.