# Inqudium User Guide

> Native resilience for every JVM paradigm.

This guide covers the core concepts of Inqudium. Each element and cross-cutting topic has its own dedicated page. Code examples target the imperative Java API; paradigm-specific guides for [Kotlin Coroutines](kotlin-coroutines.md), [Project Reactor](reactor.md), and [RxJava 3](rxjava3.md) are separate documents.

---

## Getting started

### Maven coordinates

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>inqudium-circuitbreaker</artifactId>
    <version>${inqudium.version}</version>
</dependency>
```

Or use the BOM to manage all module versions centrally:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>eu.inqudium</groupId>
            <artifactId>inqudium-bom</artifactId>
            <version>${inqudium.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### First example

```java
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;

var config = CircuitBreakerConfig.builder()
    .failureRateThreshold(50)
    .slidingWindowSize(10)
    .minimumNumberOfCalls(5)
    .build();
```

---

## Core concepts

### Elements

Every resilience mechanism in Inqudium is an **element**. There are six element types, each identified by a two-character symbol from the periodic table of resilience:

| Symbol | Element | What it does |
|--------|---------|--------------|
| **Cb** | [Circuit Breaker](circuit-breaker.md) | Prevents cascading failures by short-circuiting calls to failing services |
| **Rt** | [Retry](retry.md) | Re-executes a failed operation with configurable backoff |
| **Rl** | [Rate Limiter](rate-limiter.md) | Controls throughput by limiting calls per time period |
| **Bh** | [Bulkhead](bulkhead.md) | Isolates failures by limiting concurrent calls |
| **Tl** | [Time Limiter](time-limiter.md) | Bounds the caller's wait time (not execution time) |
| **Ca** | Cache | Stores successful results to reduce load (Phase 2) |

All elements implement `InqElement`, which provides a name, a type, and an event publisher.

### Configs are immutable

Every element is created from an immutable configuration object. Configurations are built using the builder pattern and cannot be modified after construction:

```java
var config = CircuitBreakerConfig.builder()
    .failureRateThreshold(60)
    .waitDurationInOpenState(Duration.ofSeconds(30))
    .build();
```

Calling `.build()` produces a frozen snapshot. To change the configuration, create a new config and a new element instance. See the [Configuration Reference](configuration.md) for all parameters.

### InqClock — injectable time

Every time-dependent algorithm in Inqudium uses `InqClock` instead of `Instant.now()`. In production, the default system clock is used. In tests, you inject a controllable clock:

```java
var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
InqClock testClock = time::get;

var config = CircuitBreakerConfig.builder()
    .clock(testClock)
    .build();

// Advance time by 30 seconds — no Thread.sleep(), no flakiness
time.set(time.get().plusSeconds(30));
```

This is the foundation for deterministic testing. See [Testing](testing.md) for patterns.

### Registry — named instances

Each element type has a registry that manages named instances. Registries are thread-safe (backed by `ConcurrentHashMap.computeIfAbsent`) and follow first-registration-wins semantics:

```java
var cb = registry.get("paymentService", config);

// Second call returns the same instance — config is ignored
var same = registry.get("paymentService", differentConfig);
assert cb == same;
```

---

## Element guides

Each element has its own page with detailed usage documentation and configuration reference:

| Element | Guide | What you'll learn |
|---------|-------|-------------------|
| Circuit Breaker | [circuit-breaker.md](circuit-breaker.md) | State machine, sliding windows, minimum calls threshold |
| Retry | [retry.md](retry.md) | Backoff strategies, jitter algorithms, exception filtering |
| Rate Limiter | [rate-limiter.md](rate-limiter.md) | Token bucket, burst tolerance, effective rate calculation |
| Bulkhead | [bulkhead.md](bulkhead.md) | Acquire/release lifecycle, semaphore vs. thread pool |
| Time Limiter | [time-limiter.md](time-limiter.md) | Orphaned calls, timeout profiles, RSS calculation |

---

## Cross-cutting topics

| Topic | Page | What you'll learn |
|-------|------|-------------------|
| Pipeline Composition | [pipeline.md](pipeline.md) | Element ordering, INQUDIUM vs. R4J order, anti-pattern detection |
| Error Handling | [error-handling.md](error-handling.md) | Exception hierarchy, error codes, InqFailure cause-chain navigation |
| Events & Observability | [events.md](events.md) | Per-element publishers, global exporters, ServiceLoader discovery |
| Context Propagation | [context-propagation.md](context-propagation.md) | SPI lifecycle, bridge modules, MDC entries |
| Compatibility Flags | [compatibility.md](compatibility.md) | Safe upgrades, flag resolution, lifecycle |
| Testing | [testing.md](testing.md) | Deterministic time, pure algorithm testing, assertion patterns |

---

## Further reading

| Document | Audience |
|----------|----------|
| [Configuration Reference](configuration.md) | Quick lookup of all parameters and defaults |
| [Architecture](architecture.md) | Contributors — why the library is structured this way |
| [Spring Boot Integration](spring-boot.md) | Spring users — auto-configuration, annotations |
| [Migration from Resilience4J](migration.md) | Teams switching from R4J — property mapping, behavioral differences |
