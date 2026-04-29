# ADR-002: Functional decoration API

**Status:** Accepted  
**Date:** 2026-03-22  
**Deciders:** Core team

## Context

Resilience patterns must be applied to existing code without requiring the protected logic to be aware of resilience
concerns. There are broadly three approaches:

1. **Annotation-driven (AOP)** — e.g. `@CircuitBreaker`. Requires a proxy framework (Spring AOP, AspectJ). Clean at the
   call site but opaque, hard to test in isolation, and framework-coupled.
2. **Inheritance-based** — e.g. extend a `ResilientService` base class. Inflexible, couples to a class hierarchy.
3. **Functional decoration** — wrap `Supplier<T>`, `Runnable`, `Function<T, R>`, or `CompletionStage<T>` with resilience
   behavior. Framework-agnostic, composable, testable.

## Decision

We adopt **functional decoration** as the primary API surface. Every element provides methods to decorate standard
functional interfaces:

```java
Supplier<T>        decorateSupplier(Supplier<T> supplier)
Runnable           decorateRunnable(Runnable runnable)
Function<T, R>     decorateFunction(Function<T, R> function)
Supplier<CompletionStage<T>> decorateCompletionStage(Supplier<CompletionStage<T>> supplier)
```

Annotation-driven resilience (`@InqShield`) is supported as a convenience layer in `inqudium-spring-boot3` but delegates
to the same functional decoration under the hood — it is not a separate execution path.

### Pipeline composition

For chaining multiple elements, `InqPipeline` provides a fluent API:

```java
Supplier<Result> resilient = InqPipeline
    .of(service::call)
    .shield(circuitBreaker)
    .shield(retry)
    .shield(rateLimiter)
    .decorate();
```

The pipeline applies elements in the order they are added (outer to inner). The first `.shield()` is the outermost
wrapper.

### Paradigm-specific decoration

Each paradigm module provides equivalent decoration for its native types:

- **Kotlin:** Extension functions on `suspend` lambdas and `Flow` operators
- **Reactor:** `transformDeferred` operators for `Mono`/`Flux`
- **RxJava 3:** `compose()` transformers for `Single`/`Observable`/`Flowable`

## Consequences

**Positive:**

- Zero framework dependency for the core API — works in Spring, Quarkus, Micronaut, plain Java, or any other runtime.
- Straightforward to test — decorate a lambda, call it, assert.
- Composable — elements can be chained in any order without special integration code.
- Transparent — the decoration stack is explicit at the call site, not hidden behind proxy magic.

**Negative:**

- Slightly more verbose than a single annotation at the call site.
- Consumers must manage element instances (creating, configuring, registering) — the Registry pattern (ADR-001) and
  Spring auto-configuration mitigate this.

**Neutral:**

- Annotation support via Spring AOP remains available as opt-in convenience but is not the primary API.
