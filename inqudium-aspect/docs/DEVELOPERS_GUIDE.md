# Inqudium Aspect — Developer's Guide

## Table of Contents

1. [Introduction](#introduction)
2. [Architecture Overview](#architecture-overview)
3. [Module Structure](#module-structure)
4. [Core Concepts](#core-concepts)
   - [The Wrapper Pipeline](#the-wrapper-pipeline)
   - [How AspectJ Weaving Bridges into the Pipeline](#how-aspectj-weaving-bridges-into-the-pipeline)
5. [Getting Started](#getting-started)
   - [Maven Dependency](#maven-dependency)
   - [A Minimal Aspect in Five Minutes](#a-minimal-aspect-in-five-minutes)
6. [Implementing Layer Providers](#implementing-layer-providers)
   - [Anatomy of an AspectLayerProvider](#anatomy-of-an-aspectlayerprovider)
   - [Layer Ordering](#layer-ordering)
   - [The LayerAction Contract](#the-layeraction-contract)
   - [Common Layer Patterns](#common-layer-patterns)
7. [Method-Specific Filtering with canHandle](#method-specific-filtering-with-canhandle)
8. [Building the Aspect](#building-the-aspect)
   - [Extending AbstractPipelineAspect](#extending-abstractpipelineaspect)
   - [The @Around Advice and Method Extraction](#the-around-advice-and-method-extraction)
   - [Exposing the Pipeline for Testing](#exposing-the-pipeline-for-testing)
9. [Chain Introspection via the Wrapper Interface](#chain-introspection-via-the-wrapper-interface)
10. [Asynchronous Pipelines](#asynchronous-pipelines)
    - [AsyncAspectLayerProvider](#asyncaspectlayerprovider)
    - [AbstractAsyncPipelineAspect](#abstractasyncpipelineastpect)
    - [Two-Phase Execution Semantics](#two-phase-execution-semantics)
11. [Complete Walkthrough: A Three-Layer Pipeline](#complete-walkthrough-a-three-layer-pipeline)
12. [Testing Strategies](#testing-strategies)
13. [Exception Handling](#exception-handling)
14. [Design Decisions and Trade-offs](#design-decisions-and-trade-offs)

---

## Introduction

The `inqudium-aspect` module bridges AspectJ's compile-time (or load-time) weaving
with the inqudium wrapper pipeline from `inqudium-core`. Instead of scattering
cross-cutting logic across hand-written interceptors, you declare reusable **layer
providers** — each encapsulating a single concern — and the module assembles them
into an immutable `JoinPointWrapper` chain at every advice invocation.

The result: composable, ordered, introspectable cross-cutting behavior that plugs
directly into the same infrastructure used by the rest of the inqudium framework.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  AspectJ Runtime                                                    │
│                                                                     │
│  @Around advice fires ──► ProceedingJoinPoint (pjp)                 │
│                              │                                      │
│                              │  pjp::proceed (= JoinPointExecutor)  │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  inqudium-aspect                                             │   │
│  │                                                              │   │
│  │  AbstractPipelineAspect                                      │   │
│  │    ├── layerProviders()  → List<AspectLayerProvider>         │   │
│  │    └── executeThrough(executor, method)                      │   │
│  │          │                                                   │   │
│  │          ▼                                                   │   │
│  │  AspectPipelineBuilder                                       │   │
│  │    ├── filter by canHandle(method)                           │   │
│  │    ├── sort by order()                                       │   │
│  │    └── buildChain(coreExecutor)                              │   │
│  │          │                                                   │   │
│  │          ▼                                                   │   │
│  │  ┌───────────────────────────────────────────────────────┐   │   │
│  │  │  inqudium-core                                        │   │   │
│  │  │                                                       │   │   │
│  │  │  JoinPointWrapper chain (immutable, thread-safe)      │   │   │
│  │  │    AUTHORIZATION ──► LOGGING ──► TIMING ──► core      │   │   │
│  │  │                                                       │   │   │
│  │  │  Wrapper interface: inner(), chainId(), callId(),     │   │   │
│  │  │                     layerDescription(), hierarchy     │   │   │
│  │  └───────────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

**Data flow**: AspectJ intercepts a method call → the `@Around` advice extracts the
`Method` and passes `pjp::proceed` to the pipeline → the builder filters and sorts
providers, constructs a `JoinPointWrapper` chain inside-out → `chain.proceed()`
traverses all layers and eventually calls the real method.

---

## Module Structure

```
inqudium-aspect/
├── pom.xml
└── src/
    ├── main/java/eu/inqudium/aspect/pipeline/
    │   ├── AspectLayerProvider.java           # Layer definition (sync)
    │   ├── AsyncAspectLayerProvider.java      # Layer definition (async)
    │   ├── AspectPipelineBuilder.java         # Chain assembly (sync)
    │   ├── AsyncAspectPipelineBuilder.java    # Chain assembly (async)
    │   ├── AbstractPipelineAspect.java        # Base class for sync aspects
    │   ├── AbstractAsyncPipelineAspect.java   # Base class for async aspects
    │   ├── package-info.java
    │   └── example/
    │       ├── Pipelined.java                 # Trigger annotation
    │       ├── GreetingService.java           # Target service
    │       ├── PipelinedAspect.java           # Concrete aspect
    │       ├── AuthorizationLayerProvider.java
    │       ├── LoggingLayerProvider.java
    │       └── TimingLayerProvider.java
    └── test/java/eu/inqudium/aspect/pipeline/
        ├── AspectPipelineBuilderTest.java
        ├── AbstractPipelineAspectTest.java
        ├── AsyncAspectPipelineBuilderTest.java
        ├── AbstractAsyncPipelineAspectTest.java
        └── example/
            └── PipelinedAspectTest.java
```

**Dependencies**:

| Module               | Purpose                                                  |
|----------------------|----------------------------------------------------------|
| `inqudium-core`      | `JoinPointWrapper`, `LayerAction`, `Wrapper`, chain IDs  |
| `inqudium-imperative`| `AsyncLayerAction`, `AsyncJoinPointWrapper` (async only) |
| `aspectjrt`          | AspectJ runtime annotations (`@Aspect`, `@Around`)       |
| `aspectjweaver`      | Compile-time or load-time weaving support                |

---

## Core Concepts

### The Wrapper Pipeline

The inqudium wrapper pipeline is a chain-of-responsibility pattern where each layer
has **around-semantics** — it receives a reference to the next step and decides
when, whether, and how to invoke it.

```java
// A LayerAction is the fundamental unit of behavior.
// It receives chainId, callId, the argument, and a reference to the next step.
LayerAction<Void, Object> timing = (chainId, callId, arg, next) -> {
    long start = System.nanoTime();
    try {
        return next.execute(chainId, callId, arg);  // proceed to next layer
    } finally {
        long elapsed = System.nanoTime() - start;
        metrics.record(elapsed);
    }
};
```

Key properties of the chain:

- **Immutable** — once built, the layer relationships are fixed.
- **Thread-safe** — the same chain can be invoked concurrently.
- **Introspectable** — every layer exposes `layerDescription()`, `chainId()`,
  `currentCallId()`, and can be traversed via `inner()`.
- **Zero-allocation IDs** — chain ID and call ID are primitive `long` values
  passed through the chain, never boxed.

### How AspectJ Weaving Bridges into the Pipeline

AspectJ's `@Around` advice gives you a `ProceedingJoinPoint` — essentially a handle
to the intercepted method. The `proceed()` method on that handle matches the
`JoinPointExecutor` functional interface from `inqudium-core`:

```java
@FunctionalInterface
public interface JoinPointExecutor<R> {
    R proceed() throws Throwable;
}
```

This means `pjp::proceed` can be passed directly as the terminal execution point
of a wrapper chain. The `inqudium-aspect` module automates the chain construction:

```
pjp::proceed  ──►  JoinPointWrapper chain  ──►  chain.proceed()
                    (built from providers)
```

---

## Getting Started

### Maven Dependency

Add `inqudium-aspect` to your module's POM. The `aspectj-maven-plugin` handles
compile-time weaving:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>inqudium-aspect</artifactId>
    <version>${project.version}</version>
</dependency>
```

The module's own POM already declares `aspectjrt`, `aspectjweaver`, `inqudium-core`,
and `inqudium-imperative` as transitive dependencies.

### A Minimal Aspect in Five Minutes

**Step 1** — Define a trigger annotation:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Resilient {}
```

**Step 2** — Implement a layer provider:

```java
public class LoggingLayerProvider implements AspectLayerProvider<Object> {

    @Override
    public String layerName() { return "LOGGING"; }

    @Override
    public int order() { return 10; }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            log.info("[chain={}, call={}] entering", chainId, callId);
            try {
                Object result = next.execute(chainId, callId, arg);
                log.info("[chain={}, call={}] success", chainId, callId);
                return result;
            } catch (Exception e) {
                log.error("[chain={}, call={}] failed: {}",
                          chainId, callId, e.getMessage());
                throw e;
            }
        };
    }
}
```

**Step 3** — Create the aspect:

```java
@Aspect
public class ResilienceAspect extends AbstractPipelineAspect {

    private final List<AspectLayerProvider<Object>> providers = List.of(
        new LoggingLayerProvider()
    );

    @Override
    protected List<AspectLayerProvider<Object>> layerProviders() {
        return providers;
    }

    @Around("@annotation(eu.example.Resilient)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return executeThrough(pjp::proceed, method);
    }
}
```

**Step 4** — Annotate your service method:

```java
public class OrderService {

    @Resilient
    public Order placeOrder(OrderRequest request) {
        // business logic
    }
}
```

That's it. Every call to `placeOrder()` now traverses the logging layer
automatically.

---

## Implementing Layer Providers

### Anatomy of an AspectLayerProvider

Every synchronous layer provider implements four contract methods:

```java
public interface AspectLayerProvider<R> {

    String layerName();                   // diagnostic name, e.g. "RETRY"
    int order();                          // priority (lower = outermost)
    LayerAction<Void, R> layerAction();   // the around-advice logic
    default boolean canHandle(Method m);  // method filter (default: true)
}
```

| Method         | Purpose                                                           |
|----------------|-------------------------------------------------------------------|
| `layerName()`  | Human-readable label shown in `toStringHierarchy()` and logs.     |
| `order()`      | Determines position in the chain. Lower values wrap outer.        |
| `layerAction()`| Returns the `LayerAction` — the actual around-advice logic.       |
| `canHandle()`  | Decides at build-time if this layer applies to the target method. |

### Layer Ordering

Layers are sorted by `order()` using a **stable sort**. Providers with equal order
values retain their registration order from the input list.

Recommended order ranges:

| Range     | Purpose                             | Examples                 |
|-----------|-------------------------------------|--------------------------|
| 0–9       | Infrastructure / context propagation| MDC, trace context       |
| 10–19     | Security / authorization             | Auth, rate limiting      |
| 20–29     | Observability                        | Logging, metrics         |
| 30–39     | Resilience                           | Circuit breaker, bulkhead|
| 40–49     | Retry / timeout                      | Retry, deadline          |
| 50–59     | Caching                              | Cache lookup/store       |

### The LayerAction Contract

A `LayerAction<A, R>` is a functional interface with full control over the
invocation:

```java
R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next);
```

The `next` parameter is the next step in the chain. You can:

| Action               | How                                              |
|----------------------|--------------------------------------------------|
| **Proceed normally** | `return next.execute(chainId, callId, arg);`     |
| **Short-circuit**    | Return a value without calling `next`             |
| **Retry**            | Call `next.execute(...)` multiple times           |
| **Transform result** | Call `next`, then modify the returned value       |
| **Handle exceptions**| Wrap the `next` call in try/catch                 |
| **Pre-process**      | Execute logic before calling `next`               |
| **Post-process**     | Execute logic after `next` returns                |

### Common Layer Patterns

#### Pre-processing (fire-and-forget logging)

```java
(chainId, callId, arg, next) -> {
    log.info("[chain={}, call={}] entering", chainId, callId);
    return next.execute(chainId, callId, arg);
}
```

#### Pre- and post-processing (timing)

```java
(chainId, callId, arg, next) -> {
    long start = System.nanoTime();
    try {
        return next.execute(chainId, callId, arg);
    } finally {
        metrics.record(System.nanoTime() - start);
    }
}
```

#### Exception handling (fallback)

```java
(chainId, callId, arg, next) -> {
    try {
        return next.execute(chainId, callId, arg);
    } catch (Exception e) {
        return fallbackValue;
    }
}
```

#### Conditional execution (caching)

```java
(chainId, callId, arg, next) -> {
    if (cache.containsKey(arg)) return cache.get(arg);
    Object result = next.execute(chainId, callId, arg);
    cache.put(arg, result);
    return result;
}
```

#### Retry with backoff

```java
(chainId, callId, arg, next) -> {
    int maxAttempts = 3;
    Exception lastException = null;
    for (int i = 0; i < maxAttempts; i++) {
        try {
            return next.execute(chainId, callId, arg);
        } catch (RuntimeException e) {
            lastException = e;
            Thread.sleep((long) Math.pow(2, i) * 100);
        }
    }
    throw lastException;
}
```

#### Authorization (short-circuit)

```java
(chainId, callId, arg, next) -> {
    if (!securityContext.isAuthorized()) {
        throw new SecurityException("Access denied");
    }
    return next.execute(chainId, callId, arg);
}
```

---

## Method-Specific Filtering with canHandle

Not every layer applies to every method. The `canHandle(Method)` method lets a
provider declare which methods it supports:

```java
public class TimingLayerProvider implements AspectLayerProvider<Object> {

    @Override
    public String layerName() { return "TIMING"; }

    @Override
    public int order() { return 30; }

    @Override
    public boolean canHandle(Method method) {
        // Only time methods explicitly annotated with @Pipelined
        return method.isAnnotationPresent(Pipelined.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            long start = System.nanoTime();
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                long elapsed = System.nanoTime() - start;
                System.out.printf("[chain=%d, call=%d] took %d µs%n",
                                  chainId, callId, elapsed / 1000);
            }
        };
    }
}
```

When the builder receives a `Method`, it filters first, then sorts:

```java
// Inside AspectPipelineBuilder:
builder.addProviders(providers, method);
// Equivalent to:
//   providers.stream()
//       .filter(p -> p.canHandle(method))
//       .sorted(Comparator.comparingInt(AspectLayerProvider::order))
//       .forEach(builder::addProvider);
```

Common `canHandle` patterns:

```java
// Only methods returning CompletionStage
method.getReturnType().isAssignableFrom(CompletionStage.class)

// Only methods in a specific interface
method.getDeclaringClass() == OrderRepository.class

// Only methods with a specific annotation
method.isAnnotationPresent(Idempotent.class)

// Only methods whose name matches a pattern
method.getName().startsWith("find")
```

The default implementation returns `true`, so providers that should apply
universally need no override.

---

## Building the Aspect

### Extending AbstractPipelineAspect

`AbstractPipelineAspect` handles the wiring between providers, builder, and
chain execution. A subclass only needs to implement `layerProviders()`:

```java
@Aspect
public class PipelinedAspect extends AbstractPipelineAspect {

    private final List<AspectLayerProvider<Object>> providers;

    public PipelinedAspect(List<AspectLayerProvider<Object>> providers) {
        this.providers = providers;
    }

    @Override
    protected List<AspectLayerProvider<Object>> layerProviders() {
        return providers;
    }

    // ...
}
```

The base class provides two execution paths:

| Method                                   | Description                        |
|------------------------------------------|------------------------------------|
| `executeThrough(executor)`               | Builds chain from all providers    |
| `executeThrough(executor, method)`       | Filters providers via `canHandle`  |
| `buildPipeline(executor)`                | Build without executing (diagnostics) |
| `buildPipeline(executor, method)`        | Build with filtering, no execution |

### The @Around Advice and Method Extraction

The `@Around` advice is the bridge between AspectJ and the pipeline. The key
steps are:

1. Extract the `Method` from the `ProceedingJoinPoint`.
2. Pass `pjp::proceed` as a `JoinPointExecutor` to the pipeline.

```java
@Around("@annotation(eu.inqudium.aspect.pipeline.example.Pipelined)")
public Object around(ProceedingJoinPoint pjp) throws Throwable {
    // Step 1: Extract the target method for canHandle filtering
    Method method = ((MethodSignature) pjp.getSignature()).getMethod();

    // Step 2: Execute through the pipeline
    // pjp::proceed matches JoinPointExecutor's functional interface
    return execute(pjp::proceed, method);
}
```

A fresh `JoinPointWrapper` chain is built for **every** advice invocation. This
ensures per-invocation isolation (each call gets its own call ID) while the
immutable layer definitions from the providers are safely shared.

### Exposing the Pipeline for Testing

Since `executeThrough` and `buildPipeline` are `protected`, a concrete aspect
should expose public delegation methods for testability:

```java
// Execute with method filtering
public Object execute(JoinPointExecutor<Object> coreExecutor, Method method)
        throws Throwable {
    return executeThrough(coreExecutor, method);
}

// Inspect without executing — for assertions on chain structure
public JoinPointWrapper<Object> inspectPipeline(
        JoinPointExecutor<Object> coreExecutor, Method method) {
    return buildPipeline(coreExecutor, method);
}
```

---

## Chain Introspection via the Wrapper Interface

Every `JoinPointWrapper` implements the `Wrapper<S>` interface, which provides
rich introspection capabilities:

```java
public interface Wrapper<S extends Wrapper<S>> {
    S inner();                    // next inner layer, or null
    long chainId();               // shared across all layers
    long currentCallId();         // increments per proceed()
    String layerDescription();    // e.g. "AUTHORIZATION"
    String toStringHierarchy();   // formatted tree output
}
```

### Walking the Chain

```java
JoinPointWrapper<Object> chain = aspect.inspectPipeline(() -> "dummy", method);

// Traverse outermost → innermost
List<String> layerNames = new ArrayList<>();
Wrapper<?> current = chain;
while (current != null) {
    layerNames.add(current.layerDescription());
    current = current.inner();
}
// Result: ["AUTHORIZATION", "LOGGING", "TIMING"]
```

### Verifying Shared Chain Identity

```java
long expectedChainId = chain.chainId();
Wrapper<?> current = chain;
while (current != null) {
    assert current.chainId() == expectedChainId;
    current = current.inner();
}
```

### Observing Call ID Progression

```java
assert chain.currentCallId() == 0;   // before any execution
chain.proceed();
assert chain.currentCallId() == 1;   // after first invocation
chain.proceed();
assert chain.currentCallId() == 2;   // after second invocation
```

### Printing the Hierarchy

```java
System.out.println(chain.toStringHierarchy());
```

Output:
```
Chain-ID: 42 (current call-ID: 0)
AUTHORIZATION
  └── LOGGING
    └── TIMING
```

### Verifying canHandle Filtering Effects

```java
Method greetMethod   = GreetingService.class.getMethod("greet", String.class);
Method farewellMethod = GreetingService.class.getMethod("farewell", String.class);

// greet() has @Pipelined → all three layers
JoinPointWrapper<Object> greetChain = aspect.inspectPipeline(() -> "g", greetMethod);
assert countLayers(greetChain) == 3;

// farewell() has no @Pipelined → TimingLayerProvider.canHandle returns false
JoinPointWrapper<Object> farewellChain = aspect.inspectPipeline(() -> "f", farewellMethod);
assert countLayers(farewellChain) == 2;  // AUTHORIZATION + LOGGING only
```

---

## Asynchronous Pipelines

For methods that return a `CompletionStage`, the module provides a full async
counterpart of every class.

### AsyncAspectLayerProvider

```java
public interface AsyncAspectLayerProvider<R> {

    String layerName();
    int order();
    AsyncLayerAction<Void, R> asyncLayerAction();
    default boolean canHandle(Method method) { return true; }
}
```

The `AsyncLayerAction` has a different return type — it produces a
`CompletionStage<R>` instead of a direct `R`:

```java
CompletionStage<R> executeAsync(long chainId, long callId, A argument,
                                InternalAsyncExecutor<A, R> next);
```

### AbstractAsyncPipelineAspect

```java
@Aspect
public class AsyncResilienceAspect extends AbstractAsyncPipelineAspect {

    private final List<AsyncAspectLayerProvider<Object>> providers = List.of(
        new AsyncBulkheadLayerProvider(),
        new AsyncTimingLayerProvider()
    );

    @Override
    protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
        return providers;
    }

    @Around("@annotation(AsyncResilient)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return executeThroughAsync(pjp::proceed, method);
    }
}
```

### Two-Phase Execution Semantics

Async layers have two distinct execution phases:

```
calling thread                              async completion
─────────────                               ────────────────
acquire()                    ┐
next.executeAsync(...)       │ start phase
                             │
  ┌──────────────────────────┘
  │
  │   ... async work happens ...
  │
  └──► whenComplete((r, e) ->
           release()         ┐
       )                     │ end phase
                             ┘
```

**Start phase** (synchronous): Code before `next.executeAsync()` runs on the
calling thread. Use this to acquire permits, start timers, or set context.

**End phase** (asynchronous): Code attached via `whenComplete()`, `thenApply()`,
etc. runs when the async operation completes. Use this to release permits, stop
timers, or record metrics.

Example — async bulkhead:

```java
public class AsyncBulkheadLayerProvider implements AsyncAspectLayerProvider<Object> {

    private final Semaphore permits;

    @Override public String layerName() { return "BULKHEAD"; }
    @Override public int order() { return 10; }

    @Override
    public AsyncLayerAction<Void, Object> asyncLayerAction() {
        return (chainId, callId, arg, next) -> {
            permits.acquire();                               // start phase
            CompletionStage<Object> stage;
            try {
                stage = next.executeAsync(chainId, callId, arg);
            } catch (Throwable t) {
                permits.release();                           // cleanup on sync failure
                throw t;
            }
            return stage.whenComplete((r, e) ->
                permits.release()                            // end phase
            );
        };
    }
}
```

> **ADR-023 reminder**: Always return the *decorated copy* produced by
> `whenComplete()`, not the original stage. This ensures that exceptions thrown
> inside the end-phase callback surface on the caller's future rather than
> disappearing on a detached branch.

---

## Complete Walkthrough: A Three-Layer Pipeline

This section walks through the full example included in the `example` package.

### Step 1 — The Trigger Annotation

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pipelined {}
```

### Step 2 — The Target Service

```java
public class GreetingService {

    @Pipelined
    public String greet(String name) {
        return "Hello, " + name + "!";
    }

    // No @Pipelined — TimingLayerProvider will NOT apply here
    public String farewell(String name) {
        return "Goodbye, " + name + "!";
    }
}
```

### Step 3 — Three Layer Providers

**AuthorizationLayerProvider** (order 10, outermost):

```java
public class AuthorizationLayerProvider implements AspectLayerProvider<Object> {

    @Override public String layerName() { return "AUTHORIZATION"; }
    @Override public int order() { return 10; }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            if (!isAuthorized()) {
                throw new SecurityException("Access denied");
            }
            return next.execute(chainId, callId, arg);
        };
    }
}
```

**LoggingLayerProvider** (order 20, middle):

```java
public class LoggingLayerProvider implements AspectLayerProvider<Object> {

    @Override public String layerName() { return "LOGGING"; }
    @Override public int order() { return 20; }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            log.info("[chain={}, call={}] entering", chainId, callId);
            try {
                Object result = next.execute(chainId, callId, arg);
                log.info("[chain={}, call={}] result={}", chainId, callId, result);
                return result;
            } catch (Exception e) {
                log.error("[chain={}, call={}] error={}", chainId, callId, e.getMessage());
                throw e;
            }
        };
    }
}
```

**TimingLayerProvider** (order 30, innermost, with `canHandle` filter):

```java
public class TimingLayerProvider implements AspectLayerProvider<Object> {

    @Override public String layerName() { return "TIMING"; }
    @Override public int order() { return 30; }

    @Override
    public boolean canHandle(Method method) {
        return method.isAnnotationPresent(Pipelined.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            long start = System.nanoTime();
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                long elapsed = System.nanoTime() - start;
                log.info("[chain={}, call={}] took {} µs",
                         chainId, callId, elapsed / 1000);
            }
        };
    }
}
```

### Step 4 — The Concrete Aspect

```java
@Aspect
public class PipelinedAspect extends AbstractPipelineAspect {

    private final List<AspectLayerProvider<Object>> providers;

    public PipelinedAspect(List<AspectLayerProvider<Object>> providers) {
        this.providers = providers;
    }

    @Override
    protected List<AspectLayerProvider<Object>> layerProviders() {
        return providers;
    }

    @Around("@annotation(eu.inqudium.aspect.pipeline.example.Pipelined)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return execute(pjp::proceed, method);
    }

    // Public delegation for testing
    public Object execute(JoinPointExecutor<Object> executor, Method method)
            throws Throwable {
        return executeThrough(executor, method);
    }

    public JoinPointWrapper<Object> inspectPipeline(
            JoinPointExecutor<Object> executor, Method method) {
        return buildPipeline(executor, method);
    }
}
```

### Step 5 — Call Flow Visualization

When `greetingService.greet("World")` is called:

```
  caller: greet("World")
    │
    ▼
  AspectJ @Around advice
    │  Method method = ((MethodSignature) pjp.getSignature()).getMethod();
    │  execute(pjp::proceed, method)
    │
    ▼
  AspectPipelineBuilder
    │  filter providers by canHandle(greet-Method) → all 3 pass
    │  sort by order: AUTH(10) → LOG(20) → TIMING(30)
    │  build inside-out: TIMING wraps core, LOG wraps TIMING, AUTH wraps LOG
    │
    ▼
  JoinPointWrapper chain:  AUTH → LOG → TIMING → pjp::proceed
    │
    │  chain.proceed()
    │
    ▼
  AUTHORIZATION layer
    │  ✓ authorized → next.execute(...)
    ▼
  LOGGING layer
    │  log "entering" → next.execute(...)
    ▼
  TIMING layer
    │  start timer → next.execute(...)
    ▼
  pjp::proceed → GreetingService.greet("World") → "Hello, World!"
    │
    │  (return value propagates back up)
    │
  TIMING layer  ← stop timer, record 42 µs
  LOGGING layer ← log "result=Hello, World!"
  AUTHORIZATION layer ← (no post-processing)
    │
    ▼
  caller receives "Hello, World!"
```

When `greetingService.farewell("World")` is called through the same aspect:

```
  AspectPipelineBuilder
    │  filter by canHandle(farewell-Method):
    │    AUTH.canHandle → true  (default)
    │    LOG.canHandle  → true  (default)
    │    TIMING.canHandle → false  (no @Pipelined annotation)
    │
    │  Only 2 layers: AUTH(10) → LOG(20)
    │
  Chain: AUTH → LOG → pjp::proceed
```

---

## Testing Strategies

### Testing Layers Without AspectJ

Since `JoinPointExecutor` is a functional interface, you can test layers
without any AspectJ weaving — just pass a lambda:

```java
@Test
void all_three_layers_execute_in_correct_order() throws Throwable {
    // Given
    List<String> trace = new ArrayList<>();
    PipelinedAspect aspect = new PipelinedAspect(List.of(
        new AuthorizationLayerProvider(trace, true),
        new LoggingLayerProvider(trace),
        new TimingLayerProvider(trace)
    ));

    // When — lambda stands in for pjp::proceed
    Object result = aspect.execute(() -> {
        trace.add("core");
        return "Hello!";
    });

    // Then
    assertThat(result).isEqualTo("Hello!");
    assertThat(trace).startsWith("auth:check", "auth:granted");
}
```

### Introspecting the Chain in Tests

```java
@Test
void non_pipelined_method_excludes_timing_layer() throws Exception {
    // Given
    Method farewellMethod = GreetingService.class.getMethod("farewell", String.class);
    PipelinedAspect aspect = new PipelinedAspect(List.of(
        new AuthorizationLayerProvider(trace, true),
        new LoggingLayerProvider(trace),
        new TimingLayerProvider(trace)
    ));

    // When
    JoinPointWrapper<Object> chain = aspect.inspectPipeline(() -> "dummy", farewellMethod);

    // Then — walk the chain via Wrapper.inner()
    List<String> layers = new ArrayList<>();
    Wrapper<?> current = chain;
    while (current != null) {
        layers.add(current.layerDescription());
        current = current.inner();
    }

    assertThat(layers).containsExactly("AUTHORIZATION", "LOGGING");
    assertThat(chain.toStringHierarchy()).doesNotContain("TIMING");
}
```

### Verifying Chain IDs and Call IDs

```java
@Test
void all_layers_share_the_same_chain_id() {
    JoinPointWrapper<Object> chain = aspect.inspectPipeline(() -> "x", method);

    long expectedChainId = chain.chainId();
    Wrapper<?> current = chain;
    while (current != null) {
        assertThat(current.chainId())
            .as("chainId of layer '%s'", current.layerDescription())
            .isEqualTo(expectedChainId);
        current = current.inner();
    }
}

@Test
void current_call_id_increments_after_each_execution() throws Throwable {
    JoinPointWrapper<Object> chain = aspect.inspectPipeline(() -> "result", method);

    chain.proceed();
    assertThat(chain.currentCallId()).isEqualTo(1);

    chain.proceed();
    assertThat(chain.currentCallId()).isEqualTo(2);
}
```

---

## Exception Handling

### Checked Exception Transport

`JoinPointExecutor.proceed()` declares `throws Throwable`, but the pipeline
chain uses `InternalExecutor` which does not declare checked exceptions. The
`JoinPointWrapper` solves this with a transport mechanism:

1. The core execution lambda catches checked exceptions from the delegate and
   wraps them in `CompletionException`.
2. `JoinPointWrapper.proceed()` catches `CompletionException` and re-throws
   the original cause.

```java
// Inside JoinPointWrapper:
@Override
public R proceed() throws Throwable {
    try {
        return initiateChain(null);
    } catch (CompletionException e) {
        throw e.getCause();  // unwrap and re-throw original
    }
}
```

This means callers see the exact same exception types they would see from
calling the method directly — the pipeline is transparent.

### Runtime Exceptions and Errors

Runtime exceptions (`RuntimeException` subclasses) and `Error` subclasses
propagate directly through the chain without wrapping. No special handling
is needed.

### Layer Exception Handling

Each layer can catch and handle exceptions independently:

```java
(chainId, callId, arg, next) -> {
    try {
        return next.execute(chainId, callId, arg);
    } catch (SpecificException e) {
        return fallbackValue;  // swallow and substitute
    }
    // Other exceptions propagate to outer layers
}
```

Outer layers see whatever the inner layer lets through. This creates a natural
exception filtering chain, similar to nested try-catch blocks.

---

## Design Decisions and Trade-offs

### Why a Fresh Chain Per Invocation?

A new `JoinPointWrapper` chain is built for every `@Around` advice invocation.
This costs a few object allocations per call, but guarantees:

- **Isolation**: Each invocation gets its own call ID. No shared mutable state
  between concurrent calls.
- **Correctness**: The `canHandle` filter is evaluated against the actual
  method being called, not a cached approximation.
- **Simplicity**: No cache invalidation logic, no lifecycle management.

For hot paths where allocation pressure matters, the `layerProviders()` list
itself can be pre-computed and cached — only the chain assembly is per-call.

### Why default canHandle Returns true?

Backward compatibility and convenience. Most layers apply universally. Only
layers with method-specific constraints need to override `canHandle`. The
default `true` means existing providers work without changes after the
method was added to the interface.

### Why Separate Sync and Async Hierarchies?

The sync path uses `LayerAction` + `InternalExecutor` (direct return values).
The async path uses `AsyncLayerAction` + `InternalAsyncExecutor`
(`CompletionStage` return values). These are fundamentally different execution
models — merging them into a single abstraction would add complexity to the
common (sync) case without benefiting it.

### Why inside-out Chain Construction?

The builder iterates layers in reverse order during `buildChain()`. The last
layer wraps the core executor, the second-to-last wraps that, and so on. This
produces a chain where the first registered (lowest-order) layer is outermost.

This approach means:
- Layer wiring is determined at construction time, not at execution time.
- No runtime `instanceof` checks on the hot path.
- The `nextStep` reference is pre-resolved in the constructor.

### Why Is the Aspect Constructor Injectable?

Accepting `List<AspectLayerProvider<Object>>` in the constructor makes the
aspect testable without AspectJ weaving. Tests can inject providers with trace
lists, mock authorization, or custom behavior — then call `execute()` directly
with a lambda standing in for `pjp::proceed`.
