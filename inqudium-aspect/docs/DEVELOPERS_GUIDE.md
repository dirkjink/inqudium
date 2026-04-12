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
   - [Accessing the Aspect Singleton at Runtime](#accessing-the-aspect-singleton-at-runtime)
9. [Pipeline Caching with ResolvedPipeline](#pipeline-caching-with-resolvedpipeline)
   - [How It Works](#how-it-works)
   - [Parallel to SyncDispatchExtension](#parallel-to-syncdispatchextension)
   - [Per-Call Cost Comparison](#per-call-cost-comparison)
   - [Diagnostics on the Cached Pipeline](#diagnostics-on-the-cached-pipeline)
10. [Chain Introspection via the Wrapper Interface](#chain-introspection-via-the-wrapper-interface)
11. [Asynchronous Pipelines](#asynchronous-pipelines)
    - [AsyncAspectLayerProvider](#asyncaspectlayerprovider)
    - [AbstractAsyncPipelineAspect](#abstractasyncpipelineastpect)
    - [Two-Phase Execution Semantics](#two-phase-execution-semantics)
12. [Complete Walkthrough: A Three-Layer Pipeline](#complete-walkthrough-a-three-layer-pipeline)
13. [Testing Strategies](#testing-strategies)
14. [Exception Handling](#exception-handling)
15. [Design Decisions and Trade-offs](#design-decisions-and-trade-offs)
    - [Why Cache Per Method, Not Build Per Invocation?](#why-cache-per-method-not-build-per-invocation)
    - [Hot Path vs. Cold Path](#hot-path-vs-cold-path)

---

## Introduction

The `inqudium-aspect` module bridges AspectJ's compile-time (or load-time) weaving
with the inqudium wrapper pipeline from `inqudium-core`. Instead of scattering
cross-cutting logic across hand-written interceptors, you declare reusable **layer
providers** — each encapsulating a single concern — and the module assembles them
into a pre-composed pipeline that is resolved once per method and cached for all
subsequent invocations.

The pipeline caching follows the same pattern as `SyncDispatchExtension` in the
proxy module: the layer chain is composed into a single
`Function<InternalExecutor, InternalExecutor>` at resolution time, and each
invocation only creates a terminal lambda for `pjp::proceed` before traversing
the pre-built chain.

The result: composable, ordered, introspectable cross-cutting behavior with
near-zero per-invocation overhead — no filtering, no sorting, no wrapper-object
allocation on the hot path.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│  AspectJ Runtime                                                     │
│                                                                      │
│  @Around advice fires ──► ProceedingJoinPoint (pjp)                 │
│                              │                                       │
│                              │  pjp::proceed (= JoinPointExecutor)  │
│                              ▼                                       │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │  inqudium-aspect                                              │    │
│  │                                                               │    │
│  │  AbstractPipelineAspect                                       │    │
│  │    ├── layerProviders()  → List<AspectLayerProvider>          │    │
│  │    └── executeThrough(executor, method)                       │    │
│  │          │                                                    │    │
│  │          ▼                                                    │    │
│  │  ConcurrentHashMap<Method, ResolvedPipeline>                  │    │
│  │    │                                                          │    │
│  │    ├── cache miss (first call for this Method):               │    │
│  │    │     filter by canHandle(method)                          │    │
│  │    │     sort by order()                                      │    │
│  │    │     compose chainFactory (Function<Terminal, Chain>)     │    │
│  │    │     store ResolvedPipeline in cache                      │    │
│  │    │                                                          │    │
│  │    └── cache hit (every subsequent call):                     │    │
│  │          ResolvedPipeline.execute(pjp::proceed)               │    │
│  │            │                                                  │    │
│  │            ├── create terminal lambda (1 allocation)          │    │
│  │            ├── chainFactory.apply(terminal)                   │    │
│  │            └── chain.execute(chainId, callId, null)           │    │
│  │                                                               │    │
│  │  Pre-composed chain (no wrapper objects):                     │    │
│  │    AUTH.execute → LOG.execute → TIMING.execute → terminal     │    │
│  └──────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**Data flow (first call)**: AspectJ intercepts a method call → the `@Around` advice
extracts the `Method` and passes `pjp::proceed` to `executeThrough` → the
`ResolvedPipeline` is resolved (providers filtered, sorted, chain factory composed)
and cached → the terminal executor is created and passed through the pre-composed
chain.

**Data flow (subsequent calls)**: The cached `ResolvedPipeline` is retrieved from the
`ConcurrentHashMap` → only the terminal executor is created → the pre-composed chain
factory applies the terminal and traverses all layers. No filtering, sorting, or
object allocation beyond the terminal lambda.

---

## Module Structure

```
inqudium-aspect/
├── pom.xml
└── src/
    ├── main/java/eu/inqudium/aspect/pipeline/
    │   ├── AspectLayerProvider.java           # Layer definition (sync)
    │   ├── AsyncAspectLayerProvider.java      # Layer definition (async)
    │   ├── AspectPipelineBuilder.java         # Chain assembly (sync, cold path)
    │   ├── AsyncAspectPipelineBuilder.java    # Chain assembly (async)
    │   ├── ResolvedPipeline.java              # Pre-composed chain (sync, hot path)
    │   ├── AsyncResolvedPipeline.java         # Pre-composed chain (async, hot path)
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

Add `inqudium-aspect` to your **application** module's POM:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>inqudium-aspect</artifactId>
    <version>${project.version}</version>
</dependency>
```

The module declares `aspectjrt`, `aspectjweaver`, `inqudium-core`, and
`inqudium-imperative` as transitive dependencies.

**Compile-time weaving** must be configured in the **application module**
(not in `inqudium-aspect` itself — it is a library). Add the
`aspectj-maven-plugin` to your application's POM:

```xml
<plugin>
    <groupId>dev.aspectj</groupId>
    <artifactId>aspectj-maven-plugin</artifactId>
    <version>1.14.1</version>
    <configuration>
        <complianceLevel>${maven.compiler.release}</complianceLevel>
        <source>${maven.compiler.release}</source>
        <target>${maven.compiler.release}</target>
        <showWeaveInfo>true</showWeaveInfo>
        <aspectLibraries>
            <!-- Tell the weaver where to find the @Aspect classes -->
            <aspectLibrary>
                <groupId>eu.inqudium</groupId>
                <artifactId>inqudium-aspect</artifactId>
            </aspectLibrary>
        </aspectLibraries>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>
                <goal>test-compile</goal>
            </goals>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>org.aspectj</groupId>
            <artifactId>aspectjtools</artifactId>
            <version>1.9.22.1</version>
        </dependency>
    </dependencies>
</plugin>
```

> **Why not in the library?** The library module provides the `@Aspect` classes
> and the pipeline infrastructure. CTW is the application's responsibility —
> it decides which classes get woven. This also means the library's own unit
> tests run without weaving, testing the pipeline logic in isolation via
> `aspect.execute()` with injected providers.

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

> **Important: AspectJ requires a no-arg constructor.** AspectJ instantiates
> singleton aspects via `aspectOf()`, which calls the no-arg constructor. If
> your aspect accepts providers via a constructor parameter (e.g. for testing),
> you must also provide a no-arg constructor:
> ```java
> // Required by AspectJ's singleton instantiation
> public ResilienceAspect() {
>     this(List.of(new LoggingLayerProvider()));
> }
>
> // Used by tests for injectable providers
> public ResilienceAspect(List<AspectLayerProvider<Object>> providers) {
>     this.providers = providers;
> }
> ```
> The field-initializer pattern shown above (`providers = List.of(...)`) avoids
> this issue entirely because Java runs field initializers during the default
> constructor.

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

    // AspectJ singleton — wires the production layer stack
    public PipelinedAspect() {
        this(List.of(
            new AuthorizationLayerProvider(),
            new LoggingLayerProvider(),
            new TimingLayerProvider()
        ));
    }

    // Test constructor — injectable providers with trace recording
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

The base class provides two execution paths — a cached hot path and a cold
path for introspection:

| Method                                   | Path   | Description                                    |
|------------------------------------------|--------|------------------------------------------------|
| `executeThrough(executor, method)`       | Hot    | Uses cached `ResolvedPipeline` per method      |
| `executeThrough(executor)`               | Cold   | Builds fresh chain from all providers          |
| `resolvedPipeline(method)`               | Hot    | Returns cached pipeline for diagnostics        |
| `buildPipeline(executor)`                | Cold   | Full `JoinPointWrapper` chain (introspection)  |
| `buildPipeline(executor, method)`        | Cold   | Filtered `JoinPointWrapper` chain              |

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

When `executeThrough(executor, method)` is called, the `ResolvedPipeline` for
that method is retrieved from a `ConcurrentHashMap` (or resolved and cached on
first access). Only the terminal executor (`pjp::proceed`) is created per call —
the chain structure itself is pre-composed and reused.

### Exposing the Pipeline for Testing

Since `executeThrough`, `buildPipeline`, and `resolvedPipeline` are `protected`,
a concrete aspect should expose public delegation methods for testability:

```java
// Execute with method filtering (hot path — uses cached ResolvedPipeline)
public Object execute(JoinPointExecutor<Object> coreExecutor, Method method)
        throws Throwable {
    return executeThrough(coreExecutor, method);
}

// Inspect without executing — full Wrapper introspection (cold path)
public JoinPointWrapper<Object> inspectPipeline(
        JoinPointExecutor<Object> coreExecutor, Method method) {
    return buildPipeline(coreExecutor, method);
}

// Access the cached, pre-composed pipeline for diagnostics
public ResolvedPipeline getResolvedPipeline(Method method) {
    return resolvedPipeline(method);
}
```

### Accessing the Aspect Singleton at Runtime

AspectJ manages aspect lifecycle internally. By default every `@Aspect` class
is a **singleton** — one instance per class loader. AspectJ creates that
instance the first time the aspect's advice is triggered and retains it for
the lifetime of the class loader. There is no public constructor call you
control; the instance simply *appears* when weaving kicks in.

To obtain a reference to that singleton — for diagnostics, pipeline
inspection, or dependency injection — use the
**`org.aspectj.lang.Aspects`** utility class. It is part of `aspectjrt`
(already on your classpath via the `inqudium-aspect` transitive dependency)
and works identically for compile-time weaving (CTW) and load-time weaving
(LTW).

#### The `Aspects` Utility

`org.aspectj.lang.Aspects` provides two static methods per instantiation
model:

```java
import org.aspectj.lang.Aspects;

// Retrieve the singleton instance — throws NoAspectBoundException if
// the aspect has not been instantiated yet (i.e. no advice has fired).
PipelinedAspect aspect = Aspects.aspectOf(PipelinedAspect.class);

// Check whether the singleton exists without risking an exception.
boolean bound = Aspects.hasAspect(PipelinedAspect.class);
```

Under the hood, `Aspects.aspectOf(Class)` locates the `aspectOf()` method
that the AspectJ weaver injects into every concrete aspect class, and
invokes it via reflection. The result is the same object that the weaver
uses internally — there is no second instance, no copy.

#### When Is the Singleton Available?

The singleton is created lazily by the weaver on first advice execution.
This means:

- **After weaving + first matched join point:** `Aspects.aspectOf(...)` returns
  the instance. This is the normal production case.
- **Before any advice fires:** `Aspects.aspectOf(...)` throws
  `NoAspectBoundException`. Guard with `Aspects.hasAspect(...)` if your
  access point might run before any woven method is called.
- **Without weaving (plain `javac`, no LTW agent):** The `aspectOf()` method
  does not exist on the class. `Aspects.aspectOf(...)` throws
  `NoAspectBoundException`. This is expected in unit tests that test
  the pipeline logic without weaving — use the test constructor instead
  (see [Extending AbstractPipelineAspect](#extending-abstractpipelineaspect)).

#### Practical Use Cases

**Runtime diagnostics** — inspect the live pipeline of a running application:

```java
if (Aspects.hasAspect(PipelinedAspect.class)) {
    PipelinedAspect aspect = Aspects.aspectOf(PipelinedAspect.class);
    Method method = OrderService.class.getMethod("placeOrder", OrderRequest.class);

    ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);
    System.out.println(pipeline.toStringHierarchy());
    // Chain-ID: 42 (current call-ID: 137)
    // AUTHORIZATION
    //   └── LOGGING
    //     └── TIMING
}
```

**Exposing diagnostics via a health endpoint:**

```java
@RestController
public class PipelineDiagnosticsController {

    @GetMapping("/debug/pipeline/{methodName}")
    public String pipelineInfo(@PathVariable String methodName) throws Exception {
        if (!Aspects.hasAspect(PipelinedAspect.class)) {
            return "Aspect not bound — weaving may not be active.";
        }

        PipelinedAspect aspect = Aspects.aspectOf(PipelinedAspect.class);
        Method method = GreetingService.class.getMethod(methodName, String.class);
        ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

        return "Layers: " + pipeline.layerNames()
             + "\nDepth: " + pipeline.depth()
             + "\nCalls: " + pipeline.currentCallId()
             + "\n\n" + pipeline.toStringHierarchy();
    }
}
```

**Spring integration** — register the woven singleton as a Spring bean so
it can be injected into other components:

```xml
<bean id="pipelinedAspect"
      class="eu.inqudium.aspect.pipeline.example.PipelinedAspect"
      factory-method="aspectOf" />
```

Or with Java configuration:

```java
@Configuration
public class AspectConfig {

    @Bean
    @ConditionalOnClass(name = "org.aspectj.lang.Aspects")
    public PipelinedAspect pipelinedAspect() {
        return Aspects.aspectOf(PipelinedAspect.class);
    }
}
```

This allows injecting the live aspect into any Spring-managed component for
monitoring or configuration — while the aspect itself continues to be
managed by the AspectJ runtime.

**Integration tests with weaving** — when running with the `aspectj-maven-plugin`
or the LTW agent, the full woven aspect is available:

```java
@Test
void woven_aspect_singleton_has_expected_layer_count() throws Exception {
    // Given — the woven singleton (requires CTW or LTW to be active)
    assertThat(Aspects.hasAspect(PipelinedAspect.class)).isTrue();
    PipelinedAspect aspect = Aspects.aspectOf(PipelinedAspect.class);

    // When
    Method method = GreetingService.class.getMethod("greet", String.class);
    ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

    // Then
    assertThat(pipeline.depth()).isEqualTo(3);
    assertThat(pipeline.layerNames())
            .containsExactly("AUTHORIZATION", "LOGGING", "TIMING");
}
```

#### `Aspects.aspectOf` vs. Direct `MyAspect.aspectOf()`

AspectJ weaves a `public static MyAspect aspectOf()` method directly into
every concrete aspect class. This method is callable at runtime but **not
visible at compile time** when using `javac` — only the AspectJ compiler
(`ajc`) makes it visible during compilation. `Aspects.aspectOf(Class)` is
the portable alternative: it is a regular Java API call, compiles with any
Java compiler, and resolves the woven method internally via reflection.

| Approach | Compiler | Availability | Error mode |
|---|---|---|---|
| `MyAspect.aspectOf()` | `ajc` only | Compile-time visible | Compile error with `javac` |
| `Aspects.aspectOf(MyAspect.class)` | Any (`javac`, `ajc`) | Always compiles | `NoAspectBoundException` at runtime if not woven |

**Recommendation:** Always use `Aspects.aspectOf(Class)` in application code
and tests. It works with every compiler and every weaving strategy. Reserve
direct `MyAspect.aspectOf()` for native `.aj` aspect files compiled with
`ajc`.

---

## Pipeline Caching with ResolvedPipeline

### How It Works

`ResolvedPipeline` is the centerpiece of the hot-path optimization. It
pre-composes the entire layer chain into a single reusable function at
resolution time, then caches it per `Method` in the `AbstractPipelineAspect`.

```java
// Resolution (once per Method — on first call):
ResolvedPipeline pipeline = ResolvedPipeline.resolve(providers, method);
//   1. Filter providers by canHandle(method)
//   2. Sort by order()
//   3. Extract LayerActions
//   4. Compose into: Function<InternalExecutor, InternalExecutor>

// Execution (every subsequent call — hot path):
Object result = pipeline.execute(pjp::proceed);
//   1. Create terminal lambda (captures pjp::proceed)
//   2. chainFactory.apply(terminal) — applies pre-composed chain
//   3. chain.execute(chainId, callId, null) — traverses all layers
```

The `chainFactory` is a nested function that contains all `LayerAction`s.
For a three-layer pipeline `[AUTH(10), LOG(20), TIMING(30)]`, the factory
produces:

```
chainFactory.apply(terminal)  =
    (cid, callId, arg) -> AUTH.execute(cid, callId, arg,
        (cid2, callId2, arg2) -> LOG.execute(cid2, callId2, arg2,
            (cid3, callId3, arg3) -> TIMING.execute(cid3, callId3, arg3,
                terminal)))
```

No `JoinPointWrapper` objects, no `AbstractBaseWrapper` chains, no
`AtomicLong` in wrapper constructors — just nested lambda calls.

### Parallel to SyncDispatchExtension

The design directly mirrors the proxy module's `SyncDispatchExtension`:

| Proxy module                                  | Aspect module                                  |
|-----------------------------------------------|-------------------------------------------------|
| `SyncDispatchExtension.nextStepFactory`       | `ResolvedPipeline.chainFactory`                |
| `Function<InternalExecutor, InternalExecutor>`| `Function<InternalExecutor, InternalExecutor>` |
| Composed once in `linkInner()`                | Composed once in `resolve()`                   |
| `buildTerminal(method, args, target)` per call| Terminal lambda with `pjp::proceed` per call   |
| `executeChain(chainId, callId, terminal)`     | `chainFactory.apply(terminal).execute(...)`    |
| `MethodHandleCache` — shared across stack     | `ConcurrentHashMap<Method, ResolvedPipeline>`  |
| One cache per proxy stack                     | One cache per aspect instance                  |

Both approaches share the same insight: the layer chain structure is
**deterministic** for a given method signature and does not need to be
rebuilt on every call. Only the terminal (what to invoke at the end) changes.

The async pipeline follows the identical pattern: `AsyncResolvedPipeline`
pre-composes `AsyncLayerAction` chains into a
`Function<InternalAsyncExecutor, InternalAsyncExecutor>`, cached per method
in `AbstractAsyncPipelineAspect`.

### Per-Call Cost Comparison

| Step                          | Without caching         | With ResolvedPipeline  |
|-------------------------------|-------------------------|------------------------|
| Filter providers              | Every call              | Once per Method        |
| Sort providers                | Every call              | Once per Method        |
| Extract LayerActions          | Every call              | Once per Method        |
| Compose chain                 | N wrapper objects       | 0 objects (pre-composed)|
| Terminal executor             | 1 lambda                | 1 lambda               |
| Chain/call IDs                | AtomicLong in wrappers  | AtomicLong in pipeline |
| HashMap lookup                | —                       | 1 `get()` (fast path)  |

### Diagnostics on the Cached Pipeline

The cached `ResolvedPipeline` provides its own diagnostic methods, independent
of the `Wrapper` interface:

```java
// Access the cached pipeline
ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

// Layer information
pipeline.layerNames();       // ["AUTHORIZATION", "LOGGING", "TIMING"]
pipeline.depth();            // 3

// ID tracking
pipeline.chainId();          // globally unique, from CHAIN_ID_COUNTER
pipeline.currentCallId();    // increments with each execute()

// Hierarchy visualization
System.out.println(pipeline.toStringHierarchy());
// Output:
// Chain-ID: 42 (current call-ID: 7)
// AUTHORIZATION
//   └── LOGGING
//     └── TIMING
```

For full `Wrapper` introspection (`inner()`, type-safe chain traversal), use
`buildPipeline()` on the cold path — this constructs a `JoinPointWrapper` chain
that implements the complete `Wrapper<S>` interface.

---

## Chain Introspection via the Wrapper Interface

> **Note:** The `Wrapper` interface provides full chain traversal via `inner()`
> and is available on the **cold path** (`buildPipeline()`). For lightweight
> diagnostics on the **hot path**, use `ResolvedPipeline.layerNames()`,
> `.depth()`, and `.toStringHierarchy()` — see
> [Diagnostics on the Cached Pipeline](#diagnostics-on-the-cached-pipeline).

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

Uses the same caching strategy as the synchronous counterpart: an
`AsyncResolvedPipeline` is resolved once per `Method` and cached in a
`ConcurrentHashMap`. The `AsyncLayerAction` chain is pre-composed into a
`Function<InternalAsyncExecutor, InternalAsyncExecutor>` — only the terminal
executor is created per call.

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
        return executeThroughAsync(pjp::proceed, method);  // cached hot path
    }
}
```

The base class provides the same hot/cold path split as the sync version:

| Method                                            | Path   | Description                                      |
|---------------------------------------------------|--------|--------------------------------------------------|
| `executeThroughAsync(executor, method)`            | Hot    | Uses cached `AsyncResolvedPipeline` per method   |
| `executeThroughAsync(executor)`                    | Cold   | Builds fresh `AsyncJoinPointWrapper` chain       |
| `resolvedAsyncPipeline(method)`                    | Hot    | Returns cached pipeline for diagnostics          |
| `buildAsyncPipeline(executor)`                     | Cold   | Full `Wrapper` introspection chain               |
| `buildAsyncPipeline(executor, method)`             | Cold   | Filtered `Wrapper` introspection chain           |

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

    // AspectJ singleton — wires the production layer stack.
    // PipelinedAspect.aspectOf() returns this instance for diagnostics.
    public PipelinedAspect() {
        this(List.of(
            new AuthorizationLayerProvider(),
            new LoggingLayerProvider(),
            new TimingLayerProvider()
        ));
    }

    // Test constructor — injectable providers with trace recording
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

    // Cold path — full Wrapper introspection
    public JoinPointWrapper<Object> inspectPipeline(
            JoinPointExecutor<Object> executor, Method method) {
        return buildPipeline(executor, method);
    }

    // Hot path — access the cached, pre-composed pipeline
    public ResolvedPipeline getResolvedPipeline(Method method) {
        return resolvedPipeline(method);
    }
}
```

### Step 5 — Call Flow Visualization

**First call** to `greetingService.greet("World")` — pipeline is resolved and cached:

```
  caller: greet("World")
    │
    ▼
  AspectJ @Around advice
    │  Method method = ((MethodSignature) pjp.getSignature()).getMethod();
    │  execute(pjp::proceed, method)
    │
    ▼
  AbstractPipelineAspect.executeThrough(executor, method)
    │  ConcurrentHashMap: cache miss for greet-Method
    │
    ▼
  ResolvedPipeline.resolve(providers, method)        ← happens ONCE
    │  filter providers by canHandle(greet-Method) → all 3 pass
    │  sort by order: AUTH(10) → LOG(20) → TIMING(30)
    │  compose chainFactory inside-out:
    │    terminal → AUTH( LOG( TIMING( terminal ) ) )
    │  store in cache
    │
    ▼
  ResolvedPipeline.execute(pjp::proceed)
    │  create terminal lambda (captures pjp::proceed)
    │  chainFactory.apply(terminal)
    │  chain.execute(chainId, callId, null)
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
  terminal → pjp::proceed → GreetingService.greet("World") → "Hello, World!"
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

**Subsequent calls** to `greetingService.greet(...)` — pipeline is reused:

```
  caller: greet("Alice")
    │
    ▼
  AspectJ @Around advice
    │  execute(pjp::proceed, method)
    │
    ▼
  AbstractPipelineAspect.executeThrough(executor, method)
    │  ConcurrentHashMap: cache HIT for greet-Method
    │  (no filtering, no sorting, no chain composition)
    │
    ▼
  ResolvedPipeline.execute(pjp::proceed)
    │  create terminal lambda                ← only per-call allocation
    │  chainFactory.apply(terminal)          ← reuse pre-composed chain
    │  chain.execute(chainId, callId, null)
    │
    ▼
  AUTH → LOG → TIMING → terminal → greet("Alice") → "Hello, Alice!"
```

**`farewell("World")`** — first call resolves a different cached pipeline:

```
  ResolvedPipeline.resolve(providers, farewell-Method)
    │  filter by canHandle(farewell-Method):
    │    AUTH.canHandle → true  (default)
    │    LOG.canHandle  → true  (default)
    │    TIMING.canHandle → false  (no @Pipelined annotation)
    │
    │  compose chainFactory: terminal → AUTH( LOG( terminal ) )
    │  store in cache (separate entry from greet)
    │
  Subsequent farewell() calls reuse this 2-layer pipeline.
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
chain uses `InternalExecutor` which does not declare checked exceptions. Both
`JoinPointWrapper` (cold path) and `ResolvedPipeline` (hot path) solve this
with the same transport mechanism:

1. The terminal executor catches checked exceptions from the delegate and
   wraps them in `CompletionException`.
2. The entry point (`proceed()` or `execute()`) catches `CompletionException`
   and re-throws the original cause.

```java
// Inside ResolvedPipeline (hot path):
public Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
    InternalExecutor<Void, Object> terminal = (cid, caid, arg) -> {
        try {
            return coreExecutor.proceed();
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw new CompletionException(t); }
    };
    try {
        return chainFactory.apply(terminal).execute(chainId, callId, null);
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

### Why Cache Per Method, Not Build Per Invocation?

The original `AspectPipelineBuilder` approach built a full `JoinPointWrapper`
chain on every `@Around` advice invocation — filtering, sorting, allocating N
wrapper objects. This was wasteful: the layer structure is **deterministic per
Method**. Only the terminal executor (`pjp::proceed`) changes between calls.

`ResolvedPipeline` applies the same insight as `SyncDispatchExtension` in the
proxy module: pre-compose the chain structure once, then reuse it. The cache
key is `Method` because `canHandle(Method)` can produce different layer sets
for different methods (e.g. `greet()` gets 3 layers, `farewell()` gets 2).

The per-call cost is reduced to:
- One `ConcurrentHashMap.get()` (fast path, no locking after initial population)
- One terminal lambda creation (captures `pjp::proceed`)
- One `AtomicLong.incrementAndGet()` for the call ID
- The chain traversal itself (nested lambda calls, no object allocation)

The `buildPipeline()` methods remain available for cold-path introspection
when full `Wrapper` interface support (`inner()`, type-safe traversal) is needed.

### Hot Path vs. Cold Path

The aspect base class now has a clear separation:

| Path | Method | Purpose | Allocations per call |
|------|--------|---------|---------------------|
| **Hot** | `executeThrough(executor, method)` | Production execution | 1 lambda |
| **Cold** | `buildPipeline(executor, method)` | Testing / introspection | N wrapper objects |

The hot path uses `ResolvedPipeline` (no wrapper objects, pre-composed chain
factory). The cold path uses `AspectPipelineBuilder` + `JoinPointWrapper` (full
`Wrapper` interface with `inner()`, `chainId()`, `toStringHierarchy()`). Both
paths produce identical execution behavior — only the overhead differs.

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

Both `AspectPipelineBuilder.buildChain()` and `ResolvedPipeline.resolve()`
iterate layers in reverse order. The last layer wraps the core executor (or
terminal), the second-to-last wraps that, and so on. This produces a chain
where the first registered (lowest-order) layer is outermost.

For `ResolvedPipeline`, this means the `chainFactory` is built as:

```java
// Start with identity (terminal passes through)
Function<InternalExecutor, InternalExecutor> factory = Function.identity();

// Walk in reverse — innermost layer wraps terminal first
for (int i = actions.size() - 1; i >= 0; i--) {
    LayerAction action = actions.get(i);
    Function<InternalExecutor, InternalExecutor> outer = factory;
    factory = terminal -> {
        InternalExecutor next = outer.apply(terminal);
        return (cid, callId, arg) -> action.execute(cid, callId, arg, next);
    };
}
```

At execution time, `chainFactory.apply(terminal)` resolves the entire chain
in one pass — no runtime `instanceof` checks, no pre-resolved `nextStep`
fields, just nested lambda invocations.

### Why Is the Aspect Constructor Injectable?

Accepting `List<AspectLayerProvider<Object>>` in the constructor makes the
aspect testable without AspectJ weaving. Tests can inject providers with trace
lists, mock authorization, or custom behavior — then call `execute()` directly
with a lambda standing in for `pjp::proceed`.

**No-arg constructor requirement:** AspectJ's singleton instantiation model
(`aspectOf()`) requires a no-arg constructor. If your aspect only has a
parameterized constructor, AspectJ throws `NoAspectBoundException` at
runtime. The no-arg constructor should wire the **production** layer stack —
this makes the singleton immediately usable for both execution and diagnostics:

```java
// AspectJ calls this via aspectOf() — wires the production layer stack.
// PipelinedAspect.aspectOf().getResolvedPipeline(method) works out of the box.
public MyAspect() {
    this(List.of(new LoggingLayerProvider(), new TimingLayerProvider()));
}

// Tests call this with injectable trace-enabled providers
public MyAspect(List<AspectLayerProvider<Object>> providers) {
    this.providers = providers;
}
```

Alternatively, use the field-initializer pattern to avoid the issue entirely:

```java
@Aspect
public class MyAspect extends AbstractPipelineAspect {
    // Field initializer runs during the implicit no-arg constructor
    private final List<AspectLayerProvider<Object>> providers = List.of(
        new LoggingLayerProvider(),
        new TimingLayerProvider()
    );

    @Override
    protected List<AspectLayerProvider<Object>> layerProviders() {
        return providers;
    }
}
```
