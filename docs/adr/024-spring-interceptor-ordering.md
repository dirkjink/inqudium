# ADR-024: Spring interceptor ordering

**Status:** Proposed  
**Date:** 2026-04-13  
**Last updated:** 2026-04-13  
**Deciders:** Core team

## Context

### The single-interceptor architecture

ADR-017 establishes that Inqudium composes its resilience elements (TimeLimiter, TrafficShaper, RateLimiter, Bulkhead,
CircuitBreaker, Retry) into a single pipeline with deterministic internal ordering. In a Spring environment, this entire
pipeline is executed by **one** AOP interceptor — the `InqShieldAspect`. From Spring's perspective, it is a single
`@Around` advice. No external interceptor can insert itself between, say, CircuitBreaker and Retry, because those are
not separate proxy layers but function composition inside a single `proceed()` call.

This architecture is a deliberate deviation from Resilience4J's Spring integration, where each `@CircuitBreaker`,
`@Retry`, etc. is a separate `@Aspect` with its own `@Order`. In that model, Spring controls the nesting — and any
other `@Aspect` (transaction, security, caching) can interleave with the resilience elements. In Inqudium, the
resilience pipeline is atomic: the only ordering question is where the **pipeline as a whole** sits relative to other
Spring interceptors.

### The ordering problem

A typical service method may carry annotations from multiple frameworks:

```java
@PreAuthorize("hasRole('ADMIN')")
@Cacheable("products")
@InqCircuitBreaker("cb")
@InqRetry("rt")
@Transactional
public Product findById(Long id) {
    return remoteService.call(id);
}
```

Each annotation activates a different Spring interceptor. The order in which these interceptors execute fundamentally
changes the system's behavior:

**Cache before Inqudium (correct):** A cache hit returns immediately — no permit acquired, no rate token consumed, no
timeout timer started. The resilience pipeline is never entered.

**Cache after Inqudium (incorrect):** Every request — even cache hits — passes through the entire resilience pipeline.
A bulkhead permit is acquired and released for a cached value. A rate limiter token is consumed for data that was
already in memory. The resilience elements protect nothing and add latency.

**Transaction before Inqudium (incorrect):** A single transaction spans the entire retry sequence. If the first attempt
fails and rolls back, the second attempt reuses the same (now rolled-back) transaction context. Retried database writes
silently fail or produce inconsistent state.

**Transaction after Inqudium (correct):** Each retry attempt gets a fresh transaction. A failed attempt rolls back
cleanly, and the next attempt starts with a new transaction boundary.

These are not edge cases — they are the **default behavior** when interceptor priorities are not explicitly configured.
Spring's default priority for most interceptors is `Ordered.LOWEST_PRECEDENCE` (2147483647), which means the execution
order is undefined when multiple interceptors share the same priority.

### Why the framework should solve this, not the developer

Expecting every developer to correctly configure `@Order` for Security, Cache, Inqudium, and Transaction is
unrealistic. The interactions are subtle (Transaction inside Retry is correct, but Transaction inside CircuitBreaker
alone is debatable), the Spring documentation does not prescribe a universal ordering, and getting it wrong produces
bugs that are silent in testing and catastrophic in production.

A resilience framework that leaves this to the developer has failed at its job. The framework knows the correct
ordering — it should enforce it by default and warn when it detects deviations.

## Decision

### Canonical interceptor ordering

Inqudium defines a fixed set of interceptor priority constants that establish the correct nesting of the Inqudium
pipeline relative to common Spring interceptors:

```
[Outermost — runs first, completes last]
  Security    (100)    @PreAuthorize, @Secured, @RolesAllowed
  Validation  (200)    @Validated, method-level Bean Validation
  Cache       (300)    @Cacheable, @CacheResult
  Observability (400)  Tracing spans, metrics interceptors
  ── Inqudium (500) ── TimeLimiter → TrafficShaper → RateLimiter → Bulkhead → CB → Retry
  Transaction (600)    @Transactional
[Innermost — runs last, completes first]
```

These priorities are defined in a central constants class:

```java
public final class InqInterceptorOrder {

    private InqInterceptorOrder() {}

    /** Reject unauthorized calls before any resilience work. */
    public static final int SECURITY      = 100;

    /** Reject invalid input before any resilience work. */
    public static final int VALIDATION    = 200;

    /** Return cached results before entering the pipeline. */
    public static final int CACHE         = 300;

    /** Measure total duration including retries and shaping delays. */
    public static final int OBSERVABILITY = 400;

    /** The Inqudium resilience pipeline. */
    public static final int INQ_PIPELINE  = 500;

    /** Each retry attempt gets a fresh transaction boundary. */
    public static final int TRANSACTION   = 600;
}
```

The values are spaced by 100, consistent with the pipeline-internal ordering in ADR-017, leaving room for
application-specific interceptors at intermediate positions.

### Rationale for each position

**Security (100) — outermost**

An unauthorized request should be rejected immediately. There is no reason to acquire a bulkhead permit, start a
timeout timer, or consume a rate limiter token for a request that will be denied. Security checks are deterministic and
cheap — they do not benefit from retry or circuit-breaking.

**Validation (200) — after security, before cache**

Invalid input should be rejected before any work is performed. Like security, validation is deterministic — retrying an
invalid request produces the same result. Placing validation before cache ensures that invalid requests are never
served from the cache (a poisoned cache entry from a malformed request that somehow passed through).

**Cache (300) — after validation, before Inqudium**

ADR-017 establishes that cache is not a pipeline element — it replaces the method execution entirely on a hit. When
the cache interceptor runs before Inqudium, a cache hit short-circuits the entire resilience pipeline. No permit
acquired, no rate token consumed, no timeout started. This is the only correct position for read-through caching.

**Observability (400) — after cache, before Inqudium**

A tracing span or metrics interceptor at this position measures the **total** resilience-protected call duration,
including all retry attempts, shaping delays, and queuing time. This gives operators the caller-perceived latency. If
observability were inside the pipeline (after Inqudium), each retry would produce a separate span, losing the
end-to-end view.

Cache hits do not produce resilience-related observability data — they are measured by the cache interceptor's own
metrics. This is correct: a cache hit is not a "resilience event."

**Inqudium pipeline (500)**

The resilience pipeline itself. Internally ordered per ADR-017.

**Transaction (600) — innermost**

Each retry attempt must operate within its own transaction boundary. If the first attempt fails and the transaction
rolls back, the next attempt starts with a clean transaction. This is the most critical ordering constraint — getting
it wrong causes silent data corruption.

When `@Transactional` sits outside Inqudium (lower priority number), a single transaction spans all retry attempts. A
rollback on the first attempt leaves the subsequent attempts in an undefined transactional state. Some JPA providers
silently ignore writes after a rollback, others throw. Both are wrong.

### Spring Boot Auto-Configuration

The `inqudium-spring-boot-starter` automatically adjusts the priorities of detected Spring interceptors to match the
canonical ordering. No manual `@Order` configuration is required.

```java
@AutoConfiguration
@ConditionalOnClass(AbstractPipelineAspect.class)
public class InqInterceptorOrderAutoConfiguration {

    @Bean
    @ConditionalOnBean(BeanFactoryCacheOperationSourceAdvisor.class)
    static BeanPostProcessor cacheAdvisorOrderAdjuster() {
        return adjustAdvisorOrder(
                BeanFactoryCacheOperationSourceAdvisor.class,
                InqInterceptorOrder.CACHE);
    }

    @Bean
    @ConditionalOnBean(BeanFactoryTransactionAttributeSourceAdvisor.class)
    static BeanPostProcessor transactionAdvisorOrderAdjuster() {
        return adjustAdvisorOrder(
                BeanFactoryTransactionAttributeSourceAdvisor.class,
                InqInterceptorOrder.TRANSACTION);
    }

    // ... similar for Security, Validation advisors

    private static <T extends AbstractPointcutAdvisor> BeanPostProcessor adjustAdvisorOrder(
            Class<T> advisorType, int targetOrder) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (advisorType.isInstance(bean)) {
                    advisorType.cast(bean).setOrder(targetOrder);
                }
                return bean;
            }
        };
    }
}
```

The Inqudium aspect itself is annotated with `@Order(InqInterceptorOrder.INQ_PIPELINE)`:

```java
@Aspect
@Component
@Order(InqInterceptorOrder.INQ_PIPELINE)
public class InqShieldAspect extends AbstractPipelineAspect {
    // ...
}
```

### Startup validation

At application startup, Inqudium scans for methods that carry both Inqudium annotations and known framework
annotations, and validates the effective interceptor ordering. Warnings are emitted for detected misconfigurations:

**Cache after Inqudium:**

```
[Inqudium] WARNING: Method ProductService#findById is annotated with both @Cacheable and 
@InqCircuitBreaker, but the cache interceptor (order=2147483647) runs AFTER the Inqudium 
pipeline (order=500). Cache hits will unnecessarily pass through the resilience pipeline. 
Add inqudium-spring-boot-starter to auto-configure the ordering, or manually set the cache 
advisor order below 500.
```

**Transaction before Inqudium:**

```
[Inqudium] WARNING: Method OrderService#createOrder is annotated with both @Transactional 
and @InqRetry, but the transaction interceptor (order=100) runs BEFORE the Inqudium pipeline 
(order=500). All retry attempts will share a single transaction — a rollback on the first 
attempt will leave subsequent attempts in an undefined transactional state. Add 
inqudium-spring-boot-starter to auto-configure the ordering, or manually set the transaction 
advisor order above 500.
```

**Transaction before Inqudium without Retry (informational):**

```
[Inqudium] INFO: Method PaymentService#processPayment is annotated with both @Transactional 
and @InqCircuitBreaker (no @InqRetry present). Transaction runs before the Inqudium pipeline. 
This is acceptable when no retry is involved — the single transaction boundary covers the 
single attempt.
```

The validation distinguishes between hard misconfigurations (Transaction + Retry in wrong order) and soft ones
(Transaction + CircuitBreaker without Retry, which is debatable). Hard misconfigurations produce warnings; soft ones
produce info-level messages.

### Opting out

The auto-configuration respects explicit `@Order` annotations. If the developer has manually set an order on a Spring
advisor or aspect, the auto-configuration does not override it:

```java
// Developer explicitly wants Transaction outside Inqudium — auto-config does not interfere
@Bean
public PlatformTransactionManager transactionManager() { ... }

@Bean
public TransactionInterceptor transactionInterceptor() {
    // Explicit order — auto-config skips this advisor
    ...
}
```

The startup validation still runs and warns about the non-standard ordering, but does not prevent startup. The
developer made a conscious choice.

### Non-Spring environments

For CDI-based environments (MicroProfile, Quarkus), the interceptor priority is configured via the
`mp.fault.tolerance.interceptor.priority` property. Inqudium documents the recommended priority value relative to
standard CDI interceptors:

```properties
# Recommended: Inqudium after cache (PLATFORM_AFTER + 500), before transaction (PLATFORM_AFTER + 600)
mp.fault.tolerance.interceptor.priority=4500
```

For programmatic usage without any DI container, interceptor ordering is not relevant — the developer composes the
pipeline explicitly via the `InqPipeline` API (ADR-017), and external concerns (caching, transactions) are handled
by the application code surrounding the pipeline invocation.

## Consequences

**Positive:**

- The most common case (Spring Boot + Starter) requires zero configuration. Interceptor ordering is correct by
  default.
- The startup validation catches misconfigurations early — before they cause silent bugs in production.
- The `InqInterceptorOrder` constants provide a single source of truth for the intended ordering, usable by both the
  auto-configuration and by developers who need manual control.
- The single-interceptor architecture (ADR-017) reduces the ordering problem to one boundary per external interceptor,
  rather than N boundaries (one per resilience element).
- Explicit `@Order` overrides are respected — the framework does not fight the developer.

**Negative:**

- The auto-configuration modifies the ordering of interceptors that Inqudium does not own (`CacheInterceptor`,
  `TransactionInterceptor`). This could conflict with other frameworks that also adjust these priorities. The
  `@ConditionalOnBean` guards and opt-out mechanism mitigate this, but edge cases may exist.
- The startup scan adds to application startup time. For large codebases, the scan should be limited to Spring-managed
  beans with Inqudium annotations — not all classes on the classpath.
- CDI environments require manual property configuration. Auto-configuration is a Spring Boot feature; there is no
  equivalent for Quarkus or Open Liberty (though a Quarkus extension could be provided in the future).

**Neutral:**

- Not all interceptors listed in the canonical ordering are present in every application. A project without caching
  has no cache interceptor to reorder. The auto-configuration detects presence via `@ConditionalOnBean` and only
  adjusts what exists.
- The canonical ordering is a recommendation, not a hard constraint. Applications with unusual requirements (e.g.
  transaction-per-pipeline rather than transaction-per-attempt) can override it. The startup validation warns but does
  not block.
- This ADR covers Spring and CDI. Other frameworks (Micronaut, Vert.x) may require their own integration ADRs as
  Inqudium adoption expands.
