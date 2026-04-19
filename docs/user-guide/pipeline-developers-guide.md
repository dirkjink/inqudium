# Inqudium Developer Guide

## Overview

Inqudium is a resilience pipeline framework for Java that wraps method calls in configurable layers of protection — circuit breakers, retries, bulkheads, rate limiters, and time limiters. It provides three integration styles: programmatic API, annotation-driven, and Spring/Spring Boot auto-configuration.

```
┌─────────────────────────────────────────────────────┐
│                    Inqudium Stack                    │
├──────────────────┬──────────────────────────────────┤
│  inqudium-spring │  inqudium-spring-boot            │
│  (plain Spring)  │  (auto-configuration)            │
├──────────────────┴──────────────────────────────────┤
│  inqudium-annotation-support (scanner + factory)    │
├──────────────────┬──────────────────────────────────┤
│  inqudium-core   │  inqudium-imperative             │
│  (sync pipeline) │  (async pipeline)                │
├──────────────────┴──────────────────────────────────┤
│  inqudium-annotations (7 annotations, zero deps)    │
└─────────────────────────────────────────────────────┘
```

---

## Part 1 — InqPipeline API

### Building a Pipeline

`InqPipeline` is the central composition unit. Elements are added via `shield()` and automatically sorted by a `PipelineOrdering`:

```java
InqPipeline pipeline = InqPipeline.builder()
        .shield(circuitBreaker)   // InqElement
        .shield(retry)            // InqElement
        .shield(bulkhead)         // InqElement
        .build();                 // default: standard ordering
```

### Pipeline Orderings

Three ordering strategies control which element wraps which:

| Strategy | Order (outermost → innermost) | Use case |
|----------|-------------------------------|----------|
| **Standard** (default) | TL → TS → BH → RL → CB → RT | Inqudium recommended |
| **Resilience4J** | RT → CB → RL → TL → BH → TS | Compatibility with R4J |
| **Custom** | User-defined `EnumMap` | Full control |

```java
// Resilience4J ordering
InqPipeline r4j = InqPipeline.builder()
        .shield(cb).shield(rt).shield(bh)
        .order(PipelineOrdering.resilience4j())
        .build();

// Custom ordering
EnumMap<InqElementType, Integer> map = new EnumMap<>(InqElementType.class);
map.put(InqElementType.BULKHEAD, 100);
map.put(InqElementType.CIRCUIT_BREAKER, 200);
map.put(InqElementType.RETRY, 300);

InqPipeline custom = InqPipeline.builder()
        .shield(cb).shield(rt).shield(bh)
        .order(PipelineOrdering.custom(map))
        .build();
```

### Executing Through the Pipeline

Use one of the six terminals to execute code through the pipeline:

```java
// Sync via functions
String result = SyncPipelineTerminal.of(pipeline)
        .execute(() -> remoteService.call());

// Async via functions
CompletionStage<String> stage = AsyncPipelineTerminal.of(pipeline)
        .executeAsync(() -> remoteService.callAsync());
```

### Terminal Landscape

```
                      Functions          Proxy            AspectJ
                   ┌──────────────┬────────────────┬──────────────────┐
  Sync             │ SyncPipeline │ ProxyPipeline   │ AspectPipeline   │
                   │ Terminal     │ Terminal        │ Terminal         │
                   ├──────────────┼────────────────┼──────────────────┤
  Async            │ AsyncPipeline│       —        │       —          │
                   │ Terminal     │                │                  │
                   ├──────────────┼────────────────┼──────────────────┤
  Hybrid           │      —      │ HybridProxy    │ HybridAspect     │
  (sync+async)     │              │ PipelineTerminal│ PipelineTerminal │
                   └──────────────┴────────────────┴──────────────────┘
```

Hybrid terminals dispatch per-method: `CompletionStage` return type → async chain, everything else → sync chain. The decision is cached per `Method` in a `ConcurrentHashMap`.

### Proxy Terminal Example

```java
// Protect an interface via JDK dynamic proxy
PaymentService protected = ProxyPipelineTerminal.of(pipeline)
        .protect(PaymentService.class, realPaymentService);

// All interface methods now route through the pipeline
protected.processPayment(request);  // → BH → CB → RT → real call
```

---

## Part 2 — Annotations

### Seven Annotations

All annotations live in `inqudium-annotations` (zero dependencies):

| Annotation | Element Type | Attributes |
|------------|-------------|------------|
| `@InqCircuitBreaker("name")` | CIRCUIT_BREAKER | `value`, `fallbackMethod` |
| `@InqRetry("name")` | RETRY | `value`, `fallbackMethod` |
| `@InqBulkhead("name")` | BULKHEAD | `value`, `fallbackMethod` |
| `@InqRateLimiter("name")` | RATE_LIMITER | `value`, `fallbackMethod` |
| `@InqTimeLimiter("name")` | TIME_LIMITER | `value`, `fallbackMethod` |
| `@InqTrafficShaper("name")` | TRAFFIC_SHAPER | `value`, `fallbackMethod` |
| `@InqShield` | (meta) | `order` (INQUDIUM / RESILIENCE4J / CUSTOM) |

All annotations support both `@Target({METHOD, TYPE})`:

```java
// METHOD-level — protects one method
@InqCircuitBreaker("paymentCb")
@InqRetry("paymentRt")
public PaymentResult process(PaymentRequest req) { ... }

// TYPE-level — protects all public methods
@InqCircuitBreaker("inventoryCb")
public class InventoryService { ... }
```

### TYPE + METHOD Merge Rules

When both TYPE and METHOD level annotations are present:

1. METHOD annotations **override** TYPE for the **same element type** (e.g. `@InqCircuitBreaker` on method replaces `@InqCircuitBreaker` on class)
2. METHOD annotations **add to** TYPE for **different element types** (e.g. `@InqBulkhead` on method combined with `@InqCircuitBreaker` on class)
3. TYPE annotations are **inherited** by unannotated methods

```java
@InqCircuitBreaker("globalCb")   // TYPE: CB
@InqRetry("globalRt")            // TYPE: RT
public class ShippingService {

    // Inherits: globalCb + globalRt
    public String estimate() { ... }

    // Overrides CB: specialCb (not globalCb) + inherits globalRt
    @InqCircuitBreaker("specialCb")
    public String ship() { ... }

    // Adds BH: globalCb + globalRt + orderBh
    @InqBulkhead("orderBh")
    public String track() { ... }
}
```

### Ordering via @InqShield

```java
@InqShield(order = "RESILIENCE4J")
@InqCircuitBreaker("cb")
@InqRetry("rt")
@InqBulkhead("bh")
public String riskyCall() { ... }
// R4J order: RT → CB → BH (instead of standard BH → CB → RT)
```

---

## Part 3 — InqElementRegistry

The registry is a thread-safe name → element lookup:

```java
// Builder API (immutable after build)
InqElementRegistry registry = InqElementRegistry.builder()
        .register("paymentCb", circuitBreaker)
        .register("paymentRt", retry)
        .build();

// Mutable API (for auto-configuration)
InqElementRegistry registry = InqElementRegistry.create();
registry.register("paymentCb", circuitBreaker);

// Typed lookup
CircuitBreaker cb = registry.get("paymentCb", CircuitBreaker.class);
```

---

## Part 4 — Spring Integration

### Plain Spring (no Boot)

Add `inqudium-spring` and configure manually:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>inqudium-spring</artifactId>
</dependency>
```

```java
@Configuration
@EnableAspectJAutoProxy
public class ResilienceConfig {

    @Bean
    public InqElementRegistry inqElementRegistry() {
        return InqElementRegistry.builder()
                .register("paymentCb", CircuitBreaker.of(cbConfig))
                .register("paymentRt", Retry.of(rtConfig))
                .build();
    }

    @Bean
    public InqShieldAspect inqShieldAspect(InqElementRegistry registry) {
        return new InqShieldAspect(registry);
    }
}
```

### Spring Boot (auto-configuration)

Add `inqudium-spring-boot` — everything is automatic:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>inqudium-spring-boot</artifactId>
</dependency>
```

```java
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker paymentCb() {
        return CircuitBreaker.of(config);
    }

    @Bean
    public Retry paymentRt() {
        return Retry.of(config);
    }
}
```

`InqAutoConfiguration` discovers all `InqElement` beans, registers them by `getName()`, and creates the `InqShieldAspect`. Override the auto-configured registry with your own `@Bean`:

```java
@Bean
public InqElementRegistry customRegistry() {
    return InqElementRegistry.builder()
            .register("paymentCb", myCustomCb)
            .build();
}
```

### Module Dependency

```
inqudium-spring-boot ──depends on──▶ inqudium-spring
       │                                    │
  @AutoConfiguration                  InqShieldAspect
  InqAutoConfiguration                (the @Aspect)
  (bean discovery)
```

Both modules coexist safely: `@ConditionalOnMissingBean` prevents double-creation of the registry and aspect.

### Sync / Async Dispatch

`InqShieldAspect` dispatches automatically based on return type:

| Return Type | Chain |
|-------------|-------|
| `CompletionStage<T>` | Async chain (`InqAsyncDecorator.executeAsync()`) |
| Everything else | Sync chain (`InqDecorator.execute()`) |

The dispatch decision is cached per `Method`.

---

## Part 5 — Anti-Pattern Validation

`PipelineValidator` detects common ordering mistakes:

```java
InqPipeline pipeline = InqPipeline.builder()
        .shield(rt).shield(cb).shield(bh)
        .order(PipelineOrdering.custom(myCustomOrder))
        .build();

// Log warnings
ValidationResult result = PipelineValidator.validate(pipeline);
result.warnings().forEach(w -> log.warn("Pipeline: {}", w));

// Or fail fast at startup
result.throwIfWarnings();
```

### Known Anti-Patterns

| Anti-Pattern | Problem | Standard ordering avoids it? |
|---|---|---|
| **Retry outside CircuitBreaker** | CB doesn't see individual retry attempts — failure rate is based on the final outcome after all retries | ✅ Yes |
| **TimeLimiter inside Retry** | Each attempt has its own timeout, but total wait time across all retries is unbounded | ✅ Yes |
| **Bulkhead inside Retry** | Each retry attempt acquires a new permit — retries can exhaust bulkhead capacity | ✅ Yes |
| **RateLimiter inside Retry** | Each retry consumes a token — retries drain the bucket faster than expected | ✅ Yes |

The standard ordering avoids all four anti-patterns by design. Validation is most useful for `CUSTOM` orderings.

Note: the **Resilience4J ordering** intentionally places Retry outside CircuitBreaker. This is a design choice (not a bug) — R4J's circuit breaker records the final result after retries. `PipelineValidator` will emit a warning for this, which can be suppressed if the R4J behavior is desired.

---

## Module Overview

| Module | Description | Dependencies |
|--------|-------------|--------------|
| `inqudium-annotations` | 7 annotations, zero deps | — |
| `inqudium-core` | Sync pipeline, InqElement, InqElementRegistry, PipelineValidator | — |
| `inqudium-imperative` | Async pipeline (`CompletionStage`) | core |
| `inqudium-annotation-support` | InqAnnotationScanner, PipelineFactory | core, annotations |
| `inqudium-aspect` | AspectJ (compile-time weaving) terminals | core, imperative |
| `inqudium-spring` | `InqShieldAspect` for Spring AOP | annotation-support, imperative |
| `inqudium-spring-boot` | `InqAutoConfiguration` | spring |
