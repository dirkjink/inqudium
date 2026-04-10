# ADR-017: Pipeline composition order

**Status:** Accepted  
**Date:** 2026-03-23  
**Last updated:** 2026-03-23  
**Deciders:** Core team

## Context

ADR-002 defines the functional decoration API — elements wrap `Supplier<T>` in layers. ADR-003 establishes that
cross-element communication happens through the pipeline (exception propagation), not through events. But the order in
which elements are composed fundamentally changes the system's behavior.

### The order problem

Consider two configurations of the same three elements:

**Configuration A: Retry outside Circuit Breaker**

```
Call → Retry → CircuitBreaker → HTTP Client
```

- The Circuit Breaker sees every individual attempt. Three retry attempts = three calls recorded in the sliding window.
- If the first attempt fails and the breaker opens, the second and third attempts are rejected immediately — no wasted
  calls to the downstream service.
- The Retry sees `InqCallNotPermittedException` from the breaker as a failure and may retry it — which is usually
  wrong (retrying against an open breaker is pointless).

**Configuration B: Retry inside Circuit Breaker**

```
Call → CircuitBreaker → Retry → HTTP Client
```

- The Circuit Breaker sees only the final outcome of the retry sequence. Three attempts that all fail = one failure in
  the sliding window.
- The breaker opens more slowly (it counts retried sequences, not individual attempts).
- The Retry never sees `InqCallNotPermittedException` — the breaker is the outer layer and rejects before the retry
  logic runs.

Neither configuration is universally "correct" — they serve different use cases. But the default recommendation must be
clear, because most developers will not think about the ordering implications.

### Deviation from Resilience4J's order

Resilience4J documents the following aspect order:

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( Function ) ) ) ) )
```

Reading from inside out — the call passes through Bulkhead first, then TimeLimiter, then RateLimiter, then
CircuitBreaker, then Retry. The outermost layer is Retry.

Inqudium's canonical order **deliberately deviates** from this. The key differences and their rationale:

**TimeLimiter position:** Resilience4J places TimeLimiter inside RateLimiter and CircuitBreaker. This means the
TimeLimiter only bounds individual call duration — not total pipeline time including retries. If the TimeLimiter is at
3s and Retry has 3 attempts, the total caller wait time can reach 9s. In Inqudium, TimeLimiter sits outside Retry by
default, bounding the total caller wait time (ADR-010).

**RateLimiter position:** Resilience4J places RateLimiter outside TimeLimiter but inside CircuitBreaker. This means
retried calls don't consume additional rate limit permits (good), but the rate limit is not visible to the Circuit
Breaker (the breaker doesn't know about rate limiting). In Inqudium, RateLimiter sits outside CircuitBreaker — the rate
limit is the first filter after cache, preventing excessive load from even reaching the breaker's sliding window.

**Bulkhead position:** Resilience4J places Bulkhead as the innermost element. This means the concurrency limit only
applies to the actual call, not to the retry sequence. In Inqudium, Bulkhead sits outside CircuitBreaker — concurrency
is bounded at the pipeline level, not just the call level.

| Element        | Resilience4J position | Inqudium position      | Rationale for deviation                                                                |
|----------------|-----------------------|------------------------|----------------------------------------------------------------------------------------|
| Retry          | Outermost             | Innermost              | R4J: retries the whole pipeline. Inq: retries only the call, breaker sees each attempt |
| CircuitBreaker | Inside Retry          | Outside Retry          | Inq: faster failure detection, each attempt counted individually                       |
| RateLimiter    | Inside CircuitBreaker | Outside CircuitBreaker | Inq: rate limit is a global constraint, should gate before breaker                     |
| TimeLimiter    | Inside RateLimiter    | Outside Retry          | Inq: bounds total caller wait time including retries (ADR-010)                         |
| Bulkhead       | Innermost             | Outside CircuitBreaker | Inq: concurrency bounded at pipeline level, not just call level                        |

## Decision

### The purpose of the Pipeline API

The Pipeline API exists to solve a specific problem: **composing multiple resilience elements into a single decoration
chain with explicit, predictable ordering.**

Without a pipeline, the developer chains decorations manually:

```java
Supplier<R> resilient = retry.decorateSupplier(
    circuitBreaker.decorateSupplier(
        rateLimiter.decorateSupplier(
            () -> service.call()
        )
    )
);
```

This is inside-out, hard to read, and the ordering is implicit in the nesting. The Pipeline API makes the ordering
explicit, readable, and validates it:

```java
Supplier<R> resilient = InqPipeline
    .of(() -> service.call())
    .shield(circuitBreaker)
    .shield(retry)
    .decorate();
```

But beyond readability, the Pipeline API provides:

1. **Predefined orderings** — named compositions that encode best practices.
2. **Startup validation** — warnings for known anti-patterns.
3. **callId propagation** — the pipeline generates the `callId` (ADR-003) and passes it through all elements.
4. **Context propagation** — the pipeline orchestrates `InqContextPropagation` (ADR-011) across all elements.

### Predefined orderings

The Pipeline API provides named orderings as factory methods. Each encoding represents a proven composition strategy:

#### `PipelineOrder.INQUDIUM` — the Inqudium canonical order (default)

```
Call → Cache → TimeLimiter → RateLimiter → Bulkhead → CircuitBreaker → Retry → [call]
       ①        ②             ③             ④           ⑤                ⑥
```

**① Cache** — If a cached result exists, return immediately. No other element is invoked.

**② TimeLimiter** — Bounds total caller wait time including retries. If 3 retries each take 3s, the TimeLimiter at 8s
cuts the total short (ADR-010).

**③ Rate Limiter** — Controls the rate at which calls enter the pipeline. Outside Retry: retries don't consume
additional permits. Outside CircuitBreaker: rate limit gates before the breaker's sliding window.

**④ Bulkhead** — Limits concurrent calls at the pipeline level. Even calls the breaker passes through are bounded.

**⑤ Circuit Breaker** — Records each individual attempt in the sliding window. Opens fast because it sees every retry
attempt as a separate call.

**⑥ Retry** — Innermost. Retries the actual call. The breaker counts each attempt. The TimeLimiter bounds the total
time. The RateLimiter doesn't penalize retries.

#### `PipelineOrder.RESILIENCE4J` — Resilience4J compatible order

```
Call → Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → [call]
```

For projects migrating from Resilience4J (ADR-006) that want behavioral parity with their existing configuration. The
Retry is outermost: it retries the entire pipeline including the CircuitBreaker check. The TimeLimiter bounds each
individual attempt, not the total time.

#### `PipelineOrder.custom(...)` — fully custom order

```java
var order = PipelineOrder.custom(
    InqElementType.RETRY,
    InqElementType.CIRCUIT_BREAKER,
    InqElementType.RATE_LIMITER,
    InqElementType.TIME_LIMITER,
    InqElementType.BULKHEAD
);
```

For applications with specific ordering requirements that neither predefined order satisfies.

### Pipeline API with ordering

```java
// Default: Inqudium canonical order
var resilient = InqPipeline
    .of(() -> service.call())
    .order(PipelineOrder.INQUDIUM)           // default, can be omitted
    .shield(circuitBreaker)
    .shield(retry)
    .shield(rateLimiter)
    .decorate();

// Resilience4J compatible order
var resilient = InqPipeline
    .of(() -> service.call())
    .order(PipelineOrder.RESILIENCE4J)
    .shield(circuitBreaker)
    .shield(retry)
    .shield(rateLimiter)
    .decorate();

// Custom order
var resilient = InqPipeline
    .of(() -> service.call())
    .order(PipelineOrder.custom(
        InqElementType.CIRCUIT_BREAKER,
        InqElementType.RETRY,
        InqElementType.RATE_LIMITER))
    .shield(circuitBreaker)
    .shield(retry)
    .shield(rateLimiter)
    .decorate();
```

When `.order()` is set, the `shield()` calls can be in **any order** — the pipeline sorts them according to the
specified ordering. Elements not present in the ordering are appended at the end in `shield()` order.

When `.order()` is not set, `PipelineOrder.INQUDIUM` is the default.

### Startup validation for common anti-patterns

When elements are composed into a pipeline, Inqudium checks for known problematic orderings and emits warnings:

**Retry outside Circuit Breaker (in custom order):**

```
[Inqudium] Pipeline warning: Retry 'paymentRetry' is outside CircuitBreaker 'paymentCb'.
Retry may attempt to retry against an open circuit breaker, receiving 
InqCallNotPermittedException on each attempt. Consider configuring Retry to 
not retry on InqCallNotPermittedException, or move Retry inside CircuitBreaker.
```

**TimeLimiter inside Retry (in custom or R4J order):**

```
[Inqudium] Pipeline warning: TimeLimiter 'paymentTl' is inside Retry 'paymentRetry'.
Each retry attempt gets a fresh timeout, but total caller wait time is unbounded 
(up to timeout × maxAttempts = 3s × 3 = 9s). This matches Resilience4J behavior.
```

Note: the R4J-compatible order triggers the same warnings as a custom order would. The warnings are informational, not
errors — the developer chose this order intentionally when selecting `PipelineOrder.RESILIENCE4J`.

### `@InqShield` with stacked element annotations

#### Why not a heterogeneous annotation array

The ideal design would be `@InqShield({@InqCircuitBreaker("cb"), @InqRetry("rt")})` — a single `value` attribute
accepting an array of mixed element annotations. However, Java annotations have a fundamental limitation: **an
annotation attribute can only hold arrays of a single annotation type.** `Annotation[] value()` is not declarable. There
is no polymorphism in Java's annotation type system.

Workarounds like a generic `@InqElement(type = CIRCUIT_BREAKER, name = "cb")` lose the specialized annotation types and
their extensible attributes. Typed attributes like `circuitBreakers = @InqCircuitBreaker("cb")` create redundant naming
and meaningless arrays (there is no use case for two Circuit Breakers on the same method).

#### The solution: stacked annotations on the method

Each element is a standalone method-level annotation. `@InqShield` controls only the ordering — it carries no element
references:

```java
@InqShield
@InqCircuitBreaker("paymentCb")
@InqRetry("paymentRetry")
@InqTimeLimiter("paymentTl")
public PaymentResult processPayment(PaymentRequest request) { ... }
```

The `@InqShield` annotation:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InqShield {

    /**
     * The pipeline ordering to use.
     * INQUDIUM:      Elements are sorted into Inqudium canonical order.
     * RESILIENCE4J:  Elements are sorted into R4J-compatible order.
     * CUSTOM:        Elements are applied in annotation declaration order on the method.
     */
    PipelineOrder order() default PipelineOrder.INQUDIUM;
}
```

Each element annotation:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InqCircuitBreaker {
    String value();   // instance name resolved from registry
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InqRetry {
    String value();
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InqRateLimiter {
    String value();
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InqBulkhead {
    String value();
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InqTimeLimiter {
    String value();
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InqCache {
    String value();
}
```

#### How the AOP aspect resolves elements

The `InqShieldAspect` scans the method for all Inqudium element annotations, collects them, and builds the pipeline
according to the `@InqShield.order()`. Element names are resolved from the corresponding registries (ADR-015):

```java
// Simplified — inside InqShieldAspect
var method = joinPoint.getMethod();
var order = method.getAnnotation(InqShield.class).order();

// Collect all Inqudium element annotations present on the method
var elements = InqAnnotationScanner.scan(method);
// Returns: [{CIRCUIT_BREAKER, "paymentCb"}, {RETRY, "paymentRetry"}, {TIME_LIMITER, "paymentTl"}]

// Sort according to the selected order
var sorted = order.sort(elements);

// Build pipeline
var pipeline = InqPipeline.of(() -> joinPoint.proceed());
for (var element : sorted) {
    pipeline = pipeline.shield(registry.resolve(element));
}
return pipeline.decorate().get();
```

#### Ordering modes

**`PipelineOrder.INQUDIUM` (default — implicit when `@InqShield` is absent):** Elements are sorted into canonical order
regardless of their declaration position on the method. `@InqShield` is not required — the presence of any Inqudium
element annotation on a method activates the aspect with canonical order:

```java
// Simplest form — no @InqShield needed for canonical order:
@InqCircuitBreaker("cb")
@InqRetry("rt")
// Produces: CircuitBreaker → Retry (Inqudium canonical)

// Equivalent — explicit @InqShield with default order:
@InqShield
@InqCircuitBreaker("cb")
@InqRetry("rt")

// Also equivalent — declaration order is irrelevant:
@InqRetry("rt")
@InqCircuitBreaker("cb")
// Still produces: CircuitBreaker → Retry (Inqudium canonical)
```

**`PipelineOrder.RESILIENCE4J`:** Elements are sorted into R4J-compatible order. `@InqShield` is required to specify the
non-default order:

```java
@InqShield(order = PipelineOrder.RESILIENCE4J)
@InqCircuitBreaker("cb")
@InqRetry("rt")
// Produces: Retry → CircuitBreaker (R4J order)
```

**`PipelineOrder.CUSTOM`:** Elements are applied in the order they are declared on the method. Java preserves annotation
declaration order since Java 8 (annotations are stored in declaration order in the class file). `@InqShield` is
required:

```java
@InqShield(order = PipelineOrder.CUSTOM)
@InqRetry("rt")                    // outermost
@InqCircuitBreaker("cb")           // middle
@InqRateLimiter("rl")              // innermost
// Produces: Retry → CircuitBreaker → RateLimiter
```

#### Element annotations are independent of `@InqShield`

A critical design property: each element annotation is a standalone `@Target(ElementType.METHOD)` annotation. This
means:

- **`@InqShield` does not reference the element annotations.** Adding a new element (e.g. `@InqFallback`) requires
  creating one new annotation class — `@InqShield` is not modified.
- **Each element annotation can grow its own attributes** without affecting any other annotation:

```java
@InqRetry(value = "paymentRetry", fallbackMethod = "onPaymentFailure")
@InqCircuitBreaker(value = "paymentCb", fallbackMethod = "onCircuitOpen")
```

- **Custom user-defined elements** can participate by implementing a marker annotation (`@InqCustomElement`) that the
  aspect scanner recognizes.

#### When `@InqShield` is needed vs. optional

`@InqShield` is **optional** for the default case. The presence of any Inqudium element annotation on a method activates
the AOP aspect with `PipelineOrder.INQUDIUM` (canonical order).

`@InqShield` is **required** only when the developer wants to change the ordering:

| Scenario                   | `@InqShield` needed?                                               |
|----------------------------|--------------------------------------------------------------------|
| Canonical order (default)  | No — element annotations alone are sufficient                      |
| Canonical order (explicit) | Optional — `@InqShield` without attributes is equivalent to absent |
| Resilience4J order         | Yes — `@InqShield(order = PipelineOrder.RESILIENCE4J)`             |
| Custom order               | Yes — `@InqShield(order = PipelineOrder.CUSTOM)`                   |

This design means the most common case — canonical order with a few elements — is the simplest:

```java
@InqCircuitBreaker("paymentCb")
@InqRetry("paymentRetry")
public PaymentResult processPayment(PaymentRequest request) { ... }
```

No boilerplate, no ceremony. The intent is immediately clear.

### Retry should not retry on `InqException` by default

Regardless of pipeline order, the Retry element should be configured to **not retry** on Inqudium's own rejection
exceptions (ADR-009). This is the **default behavior** — Retry ignores all `InqException` subtypes unless explicitly
overridden:

```java
// Default: retries on application exceptions, ignores Inqudium rejections
var retry = Retry.of("paymentRetry", RetryConfig.builder()
    .maxAttempts(3)
    .build());
// InqCallNotPermittedException, InqRequestNotPermittedException, 
// InqBulkheadFullException are ignored by default

// Override: retry on specific Inqudium exceptions (rare, must be explicit)
var retry = Retry.of("paymentRetry", RetryConfig.builder()
    .maxAttempts(3)
    .retryOnInqExceptions(true)   // opt-in, not default
    .build());
```

### Documentation: decision matrix

The user guide includes a decision matrix for each element pair:

| Outer          | Inner          | Effect                                                | When to use                                            |
|----------------|----------------|-------------------------------------------------------|--------------------------------------------------------|
| CircuitBreaker | Retry          | Breaker counts each attempt, opens fast               | Inqudium default — fastest failure detection           |
| Retry          | CircuitBreaker | Breaker counts retry sequences, opens slow            | R4J default — retries the whole sequence as a unit     |
| TimeLimiter    | Retry          | Total time bounded across all attempts                | Inqudium default — guaranteed max wait                 |
| Retry          | TimeLimiter    | Each attempt bounded, total time = timeout × attempts | R4J default — each attempt gets its own budget         |
| RateLimiter    | Retry          | Retries don't consume rate limit permits              | Inqudium default — retries shouldn't penalize the rate |
| Retry          | RateLimiter    | Each retry consumes a permit                          | When retries should be rate-limited                    |
| Bulkhead       | CircuitBreaker | Concurrency bounded even when breaker is closed       | Inqudium default — consistent concurrency control      |
| CircuitBreaker | Bulkhead       | Bulkhead only active when breaker allows calls        | R4J default — breaker gates concurrency                |

The matrix explicitly shows which order each predefined `PipelineOrder` uses, helping developers understand what they're
choosing.

## Consequences

**Positive:**

- Predefined orderings (`INQUDIUM`, `RESILIENCE4J`, `CUSTOM`) eliminate guesswork. The developer chooses a strategy, not
  individual positions.
- The deviation from Resilience4J is explicit and justified — not hidden. Migration projects can choose
  `PipelineOrder.RESILIENCE4J` for behavioral parity.
- Stacked element annotations are fully decoupled from `@InqShield`: adding a new element never modifies the container
  annotation.
- No redundant naming — `@InqCircuitBreaker("cb")` is self-explanatory, no `circuitBreakers = @InqCircuitBreaker("cb")`.
- Each element annotation can grow its own attributes independently (e.g. `fallbackMethod`).
- Startup warnings catch anti-patterns regardless of which ordering is selected.
- Retry's default exclusion of `InqException` prevents the most common pipeline interaction bug.
- The decision matrix helps developers make informed choices.

**Negative:**

- Three ordering modes add conceptual complexity. Most projects will use `INQUDIUM` implicitly and never think about
  it — but the other modes must be documented and tested.
- `PipelineOrder.CUSTOM` relies on Java preserving annotation declaration order in the class file. This is guaranteed by
  the JLS since Java 8, but may surprise developers who assume annotations are unordered.
- The aspect scans every method for Inqudium element annotations. In large codebases, this scan must be efficient —
  limited to Spring-managed beans, not all classes.

**Neutral:**

- Not all six elements are used in every pipeline. A pipeline with only CircuitBreaker + Retry is perfectly valid and
  common. The predefined orders determine the relative position of whichever elements are present.
- The predefined orderings can be extended in future versions (e.g. `PipelineOrder.HIGH_THROUGHPUT` with a
  bulkhead-first strategy) without breaking existing configurations.
