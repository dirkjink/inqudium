# ADR-015: Registry pattern

**Status:** Accepted  
**Date:** 2026-03-23  
**Deciders:** Core team

## Context

Resilience elements are named instances — "paymentService", "orderService", "inventoryCheck". The same Circuit Breaker instance must be shared across all code paths that protect the same downstream service. If each call site creates its own instance, the sliding window is fragmented, the failure rate is diluted, and the breaker never opens.

The Registry is the mechanism that ensures: one name → one instance → one shared state.

### What the registry must do

1. **Store** named element instances with their configurations.
2. **Retrieve** instances by name — always returning the same instance for the same name.
3. **Create on demand** — optionally create an instance with a default configuration when a name is requested that doesn't exist yet.
4. **Integrate with Spring Boot** — the auto-configuration creates instances from YAML properties and registers them; the `@InqShield` annotation resolves instances by name from the registry.
5. **Support the R4J compatibility layer** — ADR-006 requires wrapping `InqRegistry` with Resilience4J's `CircuitBreakerRegistry` API.

### Design questions

**Thread safety.** Multiple threads may request the same name concurrently — the first request creates the instance, subsequent requests must return the same one. The classic double-checked locking problem.

**Name collision.** What happens when code registers an instance under a name that already exists? This can happen legitimately (Spring auto-config creates from YAML, then application code creates programmatically with the same name) or accidentally (typo, copy-paste).

**Default configurations.** In Spring Boot, YAML properties define configurations per instance name. But code may request an instance name that has no YAML entry. Should the registry create it with a global default, refuse, or throw?

**Scoping.** Is there one global registry per element type, or can registries be scoped (e.g., per tenant in a multi-tenant application)?

## Decision

### One registry per element type

Each element type has its own registry:

```java
CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
// etc.
```

Registries are **not** global singletons. The application creates and owns them. In Spring Boot, the auto-configuration creates one registry per element type and makes it a bean. In non-Spring applications, the developer creates registries explicitly.

### Thread-safe via `ConcurrentHashMap.computeIfAbsent`

The registry uses `ConcurrentHashMap<String, T>` internally. Instance retrieval uses `computeIfAbsent`, which guarantees:

- If the name exists, the existing instance is returned.
- If the name does not exist, the creation function runs exactly once, even under concurrent access.
- No explicit locking needed — `ConcurrentHashMap` handles it.

```java
public class CircuitBreakerRegistry {
    private final ConcurrentHashMap<String, CircuitBreaker> instances = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig defaultConfig;

    public CircuitBreaker circuitBreaker(String name) {
        return instances.computeIfAbsent(name,
            n -> CircuitBreaker.of(n, defaultConfig));
    }

    public CircuitBreaker circuitBreaker(String name, CircuitBreakerConfig config) {
        return instances.computeIfAbsent(name,
            n -> CircuitBreaker.of(n, config));
    }
}
```

**Why not `ReentrantLock`?** ADR-008 mandates `ReentrantLock` over `synchronized` for element internals (state machines, where virtual threads may block while holding the lock). The registry is different — `computeIfAbsent` on `ConcurrentHashMap` is non-blocking for the common case (name exists, return cached instance). The creation function runs at most once per name and is brief (no I/O, no blocking). `ReentrantLock` would add complexity without benefit here.

### Name collision: first registration wins

If `circuitBreaker("paymentService", configA)` is called and "paymentService" already exists (created with `configB`), the **existing instance is returned unchanged**. The new configuration is ignored.

This is the `computeIfAbsent` semantic — "if absent, compute; if present, return existing." It is intentionally not `computeIfPresent` or `put` (which would overwrite).

**Rationale:**
- **Predictability.** The instance returned for a name is always the same object with the same configuration, regardless of call order.
- **Safety.** Overwriting would change the configuration of an instance that may already be in use by other threads — a recipe for subtle concurrency bugs.
- **Spring Boot compatibility.** YAML-configured instances are created at startup. If application code later calls `circuitBreaker("paymentService", differentConfig)`, the YAML configuration takes precedence (it was registered first). This matches Spring's "external configuration wins" principle.

When a collision occurs and the provided configuration differs from the existing instance's configuration, the registry emits a **warning event** through `InqEventPublisher`:

```
[Inqudium] Registry warning: CircuitBreaker 'paymentService' already exists with 
failureRateThreshold=50. Ignoring new registration with failureRateThreshold=60. 
The existing instance is returned.
```

### Explicit registration for pre-configuration

For cases where the creation order matters — e.g., registering instances with specific configurations before any call site accesses them:

```java
var registry = CircuitBreakerRegistry.of(defaultConfig);

// Pre-register with specific configs
registry.register("paymentService", CircuitBreakerConfig.builder()
    .failureRateThreshold(50)
    .build());

registry.register("orderService", CircuitBreakerConfig.builder()
    .failureRateThreshold(30)
    .build());

// Later, at any call site:
var cb = registry.circuitBreaker("paymentService"); // returns pre-registered instance
var cb2 = registry.circuitBreaker("unknownService"); // created with defaultConfig
```

`register()` uses the same `computeIfAbsent` semantic — it does not overwrite existing instances.

### Default configuration

Every registry is created with a **default configuration** that applies to instances created on demand (names not explicitly pre-registered):

```java
// Global default
var registry = CircuitBreakerRegistry.ofDefaults(); // uses CircuitBreakerConfig.ofDefaults()

// Custom default
var registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.builder()
    .failureRateThreshold(60)
    .slidingWindowSize(20)
    .build());
```

When `circuitBreaker("unknownName")` is called and "unknownName" is not pre-registered, the instance is created with the registry's default configuration.

There is no "refuse unknown names" mode. If an application wants strict control over which names are allowed, it should pre-register all expected names and use a custom wrapper that rejects unknown names.

### Enumeration and inspection

The registry supports querying its contents:

```java
registry.getAllNames();                    // Set<String>
registry.find("paymentService");           // Optional<CircuitBreaker>
registry.getAll();                         // Map<String, CircuitBreaker> (unmodifiable)
registry.getDefaultConfig();               // CircuitBreakerConfig
```

These methods are useful for actuator endpoints (ADR-006: `/actuator/inqudium`), monitoring dashboards, and testing.

### Scoping

Registries are **not** globally scoped by design. The application controls how many registries exist and where they are used. For multi-tenant applications, a `Map<TenantId, CircuitBreakerRegistry>` is a simple and explicit pattern — no framework magic needed.

### Registry in `inqudium-core`

The base registry contract lives in core:

```java
public interface InqRegistry<E extends InqElement, C extends InqConfig> {
    E get(String name);
    E get(String name, C config);
    void register(String name, C config);
    Optional<E> find(String name);
    Set<String> getAllNames();
    Map<String, E> getAll();
    C getDefaultConfig();
}
```

Each imperative element module provides its concrete registry (`CircuitBreakerRegistry`, `RetryRegistry`, etc.). Paradigm modules provide their own registries (`CoroutineCircuitBreakerRegistry`, `ReactiveCircuitBreakerRegistry`, etc.) with the same contract.

## Consequences

**Positive:**
- One name, one instance, one state — guaranteed by `ConcurrentHashMap.computeIfAbsent`.
- No explicit locking — the common case (name exists) is a single hash lookup.
- First-registration-wins prevents accidental configuration overwrite.
- Collision warnings make misconfiguration visible without failing.
- Default configurations reduce boilerplate — only non-standard instances need explicit registration.
- The registry contract in core ensures all paradigm modules offer the same lookup semantics.

**Negative:**
- First-registration-wins means that if YAML configuration and programmatic configuration disagree, whichever was registered first wins. This requires the startup order to be well-defined — in Spring Boot, YAML is processed before user `@Bean` methods, so YAML wins. Outside Spring, the developer must ensure the correct registration order.
- No built-in support for removing or replacing instances. Once registered, an instance exists for the JVM's lifetime. This is intentional — hot-swapping a Circuit Breaker whose state machine is mid-transition would be dangerous. For testing, create a new registry per test.

**Neutral:**
- The registry is not a singleton. If an application creates two `CircuitBreakerRegistry` instances and registers "paymentService" in both, they are independent instances with independent state. This is by design but must be documented to prevent confusion.
