# Pipeline Composition

Pipelines compose multiple resilience elements into a single cohesive unit. While you can nest elements manually, the
`InqPipeline` API provides named orderings, startup validation, and structural guarantees for context propagation.

## Creating a pipeline

The `InqPipeline` builder defines the composition:

```java
var pipeline = InqPipeline.of(circuitBreaker, retry, timeLimiter);
```

The order of elements in the `of()` method determines the nesting order. The first element is the outermost layer (
closest to the caller), and the last element is the innermost layer (closest to the network).

## Predefined orderings

The order in which elements are composed fundamentally changes their behavior. Inqudium provides a named factory method
for the recommended composition strategy:

```java
var pipeline = InqPipeline.standard(
    circuitBreaker,
    retry,
    timeLimiter,
    bulkhead
);
```

This order encodes best practices:

1. **Bulkhead (innermost):** Limits concurrent connections to the target service.
2. **TimeLimiter:** Bounds the wait time for the call. If it fires, the underlying call becomes an "orphaned" execution
   holding a bulkhead permit until it finishes.
3. **Retry:** Re-attempts failures (including timeouts from the TimeLimiter).
4. **CircuitBreaker (outermost):** Sees every individual attempt and its outcome. Opens quickly if the failure rate
   spikes, preventing new calls from even reaching the Retry or Bulkhead layers.

For teams migrating from Resilience4J, a compatible ordering is available:

```java
var pipeline = InqPipeline.resilience4jCompatible(
    bulkhead,
    timeLimiter,
    rateLimiter,
    circuitBreaker,
    retry
);
```

## Executing a pipeline

Once built, execute the pipeline using the paradigm-specific API:

### Imperative

```java
String result = pipeline.execute(() -> service.call());
```

### Kotlin Coroutines

```kotlin
val result = pipeline.executeSuspend { service.call() }
```

### Reactor

```java
Mono<String> result = pipeline.executeMono(Mono.defer(() -> service.call()));
```

## Call identity

The pipeline generates a unique `callId` (UUID) at each invocation. This ID propagates through all elements via
the [context propagation](context-propagation.md) system and appears on every [event](events.md) emitted during the call
when diagnostic events are enabled. Filtering by `callId` reconstructs the complete lifecycle of a single request.