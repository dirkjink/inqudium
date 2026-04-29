# ADR-015: Registry pattern

**Status:** Accepted  
**Date:** 2026-03-24
**Deciders:** Core team

## Context

Resilience elements have state (the Circuit Breaker's sliding window, the Rate Limiter's token bucket). Applications
invoke the same element repeatedly to benefit from this state. If an application creates a new Circuit Breaker instance
for every call, it effectively creates a sliding window of size 1 — the breaker will never open.

Elements must therefore be cached and retrieved. There are two common approaches:

**Approach A: Application-managed injection (Spring Beans)**

The application creates element instances via Spring `@Bean` definitions and injects them where needed:

```java
@Bean
public CircuitBreaker paymentBreaker() {
    return CircuitBreaker.of("paymentService", config);
}

@Service
public class PaymentService {
    @Autowired
    public PaymentService(CircuitBreaker paymentBreaker) { ... }
}
```

This works well in dependency-injection environments but requires significant boilerplate, especially when dealing with
dozens of backend services. It also requires the element to be created before it is first needed, even if it is never
called.

**Approach B: Registry pattern (Resilience4J style)**

The application creates a registry, configures default templates, and asks the registry for an element by name at the
point of use:

```java
public class PaymentService {
    private final CircuitBreaker cb;

    public PaymentService(CircuitBreakerRegistry registry) {
        this.cb = registry.circuitBreaker("paymentService"); // creates or returns existing
    }
}
```

This approach lazy-loads elements, reduces boilerplate, allows programmatic configuration on the fly, and works
perfectly in environments without dependency injection (e.g., pure Java applications or Spark/Hadoop jobs).

## Decision

We adopt the **Registry pattern** as the primary mechanism for managing element instances.

Each element type has its own registry: `CircuitBreakerRegistry`, `RetryRegistry`, `RateLimiterRegistry`,
`BulkheadRegistry`, `TimeLimiterRegistry`.

### Core responsibilities of a Registry

A registry exists to do four things:

1. **Store** instances of a specific element type.
2. **Retrieve** instances by name — always returning the same instance for the same name.
3. **Create** instances lazily on first request if they don't exist.
4. **Manage configurations** (templates) used to create those instances.

### The `InqRegistry` interface

All registries share a common contract defined in `inqudium-core`:

```java
public interface InqRegistry<E extends InqElement, C> {

    /**
     * Returns an existing element or creates a new one using the default config.
     */
    E get(String name);

    /**
     * Returns an existing element or creates a new one using the specified config.
     * If the element already exists, the provided config is ignored.
     */
    E get(String name, C config);

    /**
     * Returns an existing element or creates a new one using a named configuration template.
     * Throws an exception if the template does not exist.
     */
    E get(String name, String configName);

    /**
     * Registers a configuration template.
     */
    void addConfiguration(String configName, C config);

    /**
     * Returns the default configuration.
     */
    C getDefaultConfig();

    /**
     * Removes an element. Its internal state is discarded.
     * Returns true if it existed.
     */
    boolean remove(String name);

    /**
     * Removes all elements and templates.
     */
    void clear();
}
```

Type-specific registries (e.g. `CircuitBreakerRegistry`) extend this interface and provide type-specific convenience
methods (`circuitBreaker(...)` delegating to `get(...)`) to avoid casting.

### Thread safety and concurrency

The registry is backed by a `ConcurrentHashMap`. The `get()` methods use `computeIfAbsent()` to guarantee atomic
creation:

```java
public E get(String name, C config) {
    return elements.computeIfAbsent(name, k -> factory.create(k, config));
}
```

This guarantees that two threads calling `circuitBreaker("serviceA")` simultaneously will receive the exact same object
reference. The factory function is executed at most once per key.

### First-registration-wins semantics

If an element named "paymentService" already exists, and code calls `circuitBreaker("paymentService", differentConfig)`,
what happens?

The registry uses **first-registration-wins**. The existing element is returned, and the `differentConfig` is ignored.
The element's behavior is determined entirely by the configuration it was originally created with.

**Why?**

- **Safety.** Overwriting would change the configuration of an instance that may already be in use by other threads — a
  recipe for subtle concurrency bugs.
- **Spring Boot compatibility.** YAML-configured instances are created at startup. If application code later calls
  `circuitBreaker("paymentService", differentConfig)`, the YAML configuration takes precedence (it was registered
  first). This matches Spring's "external configuration wins" principle.

When a collision occurs and the provided configuration differs from the existing instance's configuration, the registry
emits a **warning event** through `InqEventPublisher` when diagnostic events are enabled:

```
[Inqudium] Registry warning: CircuitBreaker 'paymentService' already exists with
different configuration. The new configuration has been ignored.
```

This makes configuration bugs visible without failing the application at runtime.

### Configuration templates

Registries manage templates — named configurations that act as blueprints:

```java
var registry = CircuitBreakerRegistry.ofDefaults();

// Register a blueprint for all backend services
registry.addConfiguration("backend", CircuitBreakerConfig.builder()
    .failureRateThreshold(60)
    .build());

// Create instances using the blueprint
var userCb = registry.circuitBreaker("userService", "backend");
var orderCb = registry.circuitBreaker("orderService", "backend");
```

This centralizes configuration tuning. If the "backend" failure rate threshold needs to be adjusted, it is changed in
one place.

### Paradigm abstraction

The core registry implementation (`DefaultInqRegistry`) operates on `InqElement`. But users interact with
paradigm-specific APIs:

- `inqudium-circuitbreaker` provides `CircuitBreakerRegistry` (imperative)
- `inqudium-kotlin` provides `CoroutineCircuitBreakerRegistry`
- `inqudium-reactor` provides `ReactorCircuitBreakerRegistry`

All paradigm registries delegate to the same underlying `DefaultInqRegistry` logic, but they are parameterized with
their paradigm's specific element interface and factory function.

### No global singleton registry

Inqudium does **not** provide a global `CircuitBreakerRegistry.getInstance()`.

Applications create their own registry instances. In Spring Boot, the starter creates a single registry bean and injects
it. In plain Java, the developer calls `CircuitBreakerRegistry.ofDefaults()` and stores the reference.

**Why?** Global singletons cause state leakage between test executions, making parallel testing impossible without
complex `tearDown()` cleanup blocks. By forcing explicit registry creation, tests naturally isolate state.

### Integration with events (ADR-003)

The registry is not responsible for event routing. When the registry creates an element instance, the element creates
its own `InqEventPublisher`.

The registry does emit three lifecycle events of its own (via a registry-specific publisher):

- `RegistryEntryAddedEvent`
- `RegistryEntryRemovedEvent`
- `RegistryConfigurationIgnoredEvent` (the collision warning described above)

## Consequences

**Positive:**

- **Zero boilerplate.** Developers retrieve configured elements on demand at the call site.
- **Lazy loading.** Elements that are never called are never allocated.
- **Predictability.** The instance returned for a name is always the same object with the same configuration, regardless
  of call order.
- **First-registration-wins** prevents accidental configuration overwrite.
- **Thread-safe** instance creation via `ConcurrentHashMap.computeIfAbsent()`.
- **Template support** centralizes tuning for groups of similar services.
- **No global state.** Tests can instantiate their own registries and run in parallel without cross-talk.

**Negative:**

- Developers might mistakenly expect `get("name", newConfig)` to update an existing element's configuration. The warning
  event mitigates this, but it requires reading logs to discover.
- Applications with thousands of dynamically named elements (e.g. `circuitBreaker("user-" + userId)`) will cause an
  unbounded memory leak in the registry unless `remove()` is explicitly managed. (Inqudium elements are designed for
  service-level isolation, not per-user isolation).

**Neutral:**

- The registry is not a singleton. If an application creates two `CircuitBreakerRegistry` instances and registers "
  paymentService" in both, they are independent instances with independent state. This is by design but must be
  documented to prevent confusion.