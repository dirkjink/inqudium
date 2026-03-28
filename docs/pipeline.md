# Pipeline Composition

The pipeline composes multiple elements into a single decoration chain with explicit ordering.

## Basic usage

```java
Supplier<Result> resilient = InqPipeline
    .of(() -> paymentService.charge(order))
    .shield(circuitBreakerDecorator)
    .shield(retryDecorator)
    .shield(rateLimiterDecorator)
    .decorate();

Result result = resilient.get();
```

## Pipeline ordering

The order of `shield()` calls does not matter — the pipeline sorts elements according to the selected `PipelineOrder`. Two predefined orderings are available:

**INQUDIUM (default)** — The canonical order. Cache → TimeLimiter → RateLimiter → Bulkhead → CircuitBreaker → Retry. The outer elements fire first.

**RESILIENCE4J** — Compatible with Resilience4J's aspect ordering. Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead.

```java
InqPipeline.of(supplier)
    .order(PipelineOrder.RESILIENCE4J) // for migration from R4J
    .shield(cb).shield(retry).shield(rl)
    .decorate();
```

The key difference: in the INQUDIUM order, Retry is innermost (each retry sees a fresh circuit breaker check). In the RESILIENCE4J order, Retry is outermost (retries the entire pipeline).

## Custom ordering

```java
var order = PipelineOrder.custom(
    InqElementType.RATE_LIMITER,
    InqElementType.CIRCUIT_BREAKER,
    InqElementType.RETRY
);
```

## Anti-pattern detection

The pipeline emits warnings for known anti-patterns:

- **Retry outside CircuitBreaker** — Retry may attempt to call an open circuit breaker.
- **TimeLimiter inside Retry** — Each attempt gets a fresh timeout, but total wait is unbounded.
- **RateLimiter inside Retry** — Each retry consumes a rate limit permit.

These are warnings, not errors. Sometimes the "anti-pattern" is intentional.

## Call identity

The pipeline generates a unique `callId` (UUID) at each invocation. This ID propagates through all elements via the [context propagation](context-propagation.md) system and appears on every [event](events.md) emitted during the call. Filtering by `callId` reconstructs the complete lifecycle of a single request.
