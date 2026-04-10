# ADR-006: Resilience4J drop-in compatibility

**Status:** Accepted  
**Date:** 2026-03-22  
**Deciders:** Core team

## Context

Resilience4J is the de facto standard for fault tolerance in Spring Boot applications. Thousands of production systems
use its annotations (`@CircuitBreaker`, `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter`), its YAML configuration (
`resilience4j.*` properties), and its Micrometer metrics (exposed under `resilience4j.*` metric names). Grafana
dashboards, Prometheus alerting rules, and Datadog monitors are built around these metric names.

Migrating away from Resilience4J requires changing annotations, rewriting YAML, and updating every dashboard. This
migration cost prevents adoption of alternatives — even when the alternative is technically superior.

## Decision

We provide `inqudium-compat-resilience4j`, a compatibility module (ADR-001) that makes migration a **dependency swap
with zero code changes**.

### Four compatibility layers

**1. Annotation compatibility**

An AOP interceptor recognizes the five Resilience4J annotations and routes them to Inqudium's native elements:

- `@io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker` → Inqudium Circuit Breaker
- `@io.github.resilience4j.retry.annotation.Retry` → Inqudium Retry
- `@io.github.resilience4j.ratelimiter.annotation.RateLimiter` → Inqudium Rate Limiter
- `@io.github.resilience4j.bulkhead.annotation.Bulkhead` → Inqudium Bulkhead
- `@io.github.resilience4j.timelimiter.annotation.TimeLimiter` → Inqudium Time Limiter

The interceptor reads the `name`, `fallbackMethod`, and other annotation attributes and maps them to equivalent Inqudium
configuration.

**2. Property compatibility**

A property mapper reads `resilience4j.*` YAML/properties and maps them to `InqConfig` equivalents:

```yaml
# This existing configuration continues to work unchanged
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        sliding-window-size: 10
```

Every Resilience4J property is mapped to its Inqudium equivalent. Properties that have no Inqudium equivalent (if any)
are logged as warnings at startup.

**3. Metric name compatibility**

Micrometer metrics are exposed under the original `resilience4j.*` names in addition to the native `inqudium.*` names.
This is implemented as a metric name alias — both names point to the same underlying meter. Existing Grafana dashboards
and Prometheus alerts continue working without changes.

The alias can be disabled via configuration once the consumer has updated their dashboards:

```yaml
inqudium:
  compat:
    resilience4j:
      metrics:
        alias-enabled: true  # default, set to false after dashboard migration
```

**4. Registry API compatibility**

Code that programmatically accesses `CircuitBreakerRegistry.of(...)` through the Resilience4J API receives an adapter
that delegates to `InqRegistry`. This covers edge cases where consumers interact with the registry directly rather than
through annotations.

### Migration path

```
Step 1: Swap dependency              → Application starts unchanged
Step 2: Migrate annotations          → @CircuitBreaker → @InqShield (incremental)
Step 3: Migrate YAML                 → resilience4j.* → inqudium.* (incremental)
Step 4: Update dashboards            → Disable metric aliases
Step 5: Remove compat dependency     → Clean break
```

Each step is independent. A project can stay at step 1 indefinitely.

### What the compat module does NOT do

- It does not re-implement Resilience4J. It maps to Inqudium's own engine.
- It does not support Resilience4J's internal extension points (custom `CircuitBreakerStateMachineFactory` etc.).
- It does not provide runtime co-existence with actual Resilience4J on the classpath — one or the other, not both.

For projects that want Resilience4J's element ordering during migration, `PipelineOrder.RESILIENCE4J` is available as a
pipeline composition strategy (ADR-017). This preserves the behavioral semantics of R4J's aspect ordering while running
on Inqudium's engine.

## Consequences

**Positive:**

- Zero-friction adoption path. The cost of trying Inqudium in an existing project is exactly one line in the build file.
- Existing operational infrastructure (dashboards, alerts) continues to work throughout the migration.
- Incremental migration reduces risk — teams can migrate annotation by annotation, service by service.

**Negative:**

- Maintenance burden: the compat module must track Resilience4J's annotation and property API as new versions are
  released.
- Potential confusion: two sets of annotations and two sets of property keys work simultaneously during migration.
- The compat module cannot achieve 100% behavioral parity with Resilience4J for every edge case — differences must be
  documented.

**Risk mitigation:**

- The compat module has its own comprehensive test suite that verifies Resilience4J annotations trigger the correct
  Inqudium behavior.
- Property mapping fidelity tests compare R4J YAML parsing against Inqudium config output for every property.
- A migration guide documents known behavioral differences.
