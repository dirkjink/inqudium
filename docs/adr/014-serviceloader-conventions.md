# ADR-014: ServiceLoader conventions

**Status:** Accepted  
**Date:** 2026-03-23  
**Deciders:** Core team

## Context

Inqudium uses the Java `ServiceLoader` mechanism as the primary way for external modules and user code to extend the library without compile-time dependencies. As of this ADR, three SPIs rely on ServiceLoader:

| SPI | Module | Purpose | ADR |
|---|---|---|---|
| `InqContextPropagator` | `inqudium-core` | Context capture/restore across thread boundaries | ADR-011 |
| `InqCompatibilityOptions` | `inqudium-core` | Behavioral flag configuration without code changes | ADR-013 |
| `InqEventExporter` | `inqudium-core` | Event export to external systems | ADR-003 |

Additional SPIs will follow as the project grows (e.g. custom `BackoffStrategy` implementations, registry backends, configuration sources). Without a unified convention, each SPI will reinvent discovery, ordering, error handling, and lifecycle rules — leading to inconsistent behavior and documentation.

### Problems with unspecified ServiceLoader usage

**Discovery order is non-deterministic.** The `ServiceLoader` specification does not guarantee the order in which providers are discovered. Different JVM implementations, classpath orderings, and module paths produce different iteration orders. If multiple providers compete (e.g. two `InqContextPropagator` implementations), the effective behavior depends on the deployment environment.

**Error handling is undefined.** What happens when a provider's constructor throws? When its method throws? When the `META-INF/services` file references a class that doesn't exist on the classpath? Each SPI would need its own error policy.

**Lifecycle is unclear.** Are providers instantiated once at startup (singletons) or per-use? Can they be registered and deregistered at runtime? Do they have a shutdown hook?

**Interaction with programmatic registration.** All three SPIs also support programmatic registration (`InqContextPropagatorRegistry.register(...)`, etc.). The relationship between ServiceLoader-discovered providers and programmatically registered ones must be consistent.

## Decision

### All Inqudium SPIs follow a single set of ServiceLoader conventions

These conventions apply to every SPI interface in `inqudium-core` that is discoverable via ServiceLoader. Individual ADRs may add SPI-specific behavior (e.g. the merge strategy in ADR-013) but must not contradict these conventions.

### Convention 1: Discovery happens once at first access

ServiceLoader discovery runs **lazily on first access**, not eagerly at application startup. The results are cached for the lifetime of the JVM. There is no re-scanning, no hot-reloading, no runtime re-discovery.

```java
// First call triggers ServiceLoader.load() + caching
var propagators = InqContextPropagatorRegistry.getPropagators();

// Subsequent calls return the cached list — no re-discovery
var same = InqContextPropagatorRegistry.getPropagators();
```

**Rationale:** Eager discovery at class-load time causes problems in environments with complex classloader hierarchies (application servers, OSGi containers, test frameworks). Lazy discovery on first access gives the application time to configure its classloaders. Caching ensures predictable behavior after the first access — no mid-flight changes to the provider set.

### Convention 2: Ordering via `Comparable`

When multiple providers of the same SPI are discovered, their order determines which one takes precedence (for override semantics) or which runs first (for sequential execution).

The ordering rule:

1. Providers that implement `Comparable<T>` (where `T` is the SPI interface) are sorted by `compareTo`, ascending. Lower values are applied first.
2. Providers that do **not** implement `Comparable` are appended after all `Comparable` providers, in ServiceLoader discovery order (non-deterministic).
3. Programmatically registered providers are appended after all ServiceLoader-discovered providers, in registration order.

```
Effective order:
  [Comparable providers, sorted]  →  [non-Comparable providers, discovery order]  →  [programmatic, registration order]
```

For SPIs with override semantics (like `InqCompatibilityOptions`), later providers override earlier ones for the same key. For SPIs with sequential execution (like `InqContextPropagator` and `InqEventExporter`), all providers run in this order.

**Recommendation to SPI implementors:** Always implement `Comparable` when deploying multiple providers. Non-Comparable ordering is a fallback, not a feature.

### Convention 3: Error isolation

A failing provider must **never** break the application or other providers. The error policy depends on the SPI's phase:

#### Construction errors (provider instantiation)

If a provider's no-arg constructor throws an exception or the class cannot be loaded:

- The provider is **skipped** — it is not added to the provider list.
- A warning is logged via `System.Logger` (JDK Platform Logging, no SLF4J dependency in core).
- An `InqProviderErrorEvent` is emitted through `InqEventPublisher` if the event system is already initialized. If not (because the event system itself depends on providers), the error is only logged.
- Other providers are not affected.

```
[Inqudium] WARNING: Failed to instantiate InqContextPropagator: 
  com.example.BrokenPropagator — java.lang.NoClassDefFoundError: io/opentelemetry/api/baggage/Baggage
  Provider skipped. Other propagators will function normally.
```

#### Execution errors (method invocation)

If a provider's method throws during operation (e.g. `InqContextPropagator.capture()` throws, `InqEventExporter.export()` throws):

- The exception is **caught and contained** — it does not propagate to the calling element.
- A warning is logged (once per provider, not per invocation — to avoid log flooding).
- An `InqProviderErrorEvent` is emitted.
- The operation continues with the remaining providers.
- The resilience element's behavior is **never** affected by a failing provider. A broken context propagator does not cause a Circuit Breaker call to fail.

### Convention 4: Singleton lifecycle

Each ServiceLoader-discovered provider is instantiated **exactly once** via its public no-arg constructor. The instance is reused for all subsequent calls. Providers must be **thread-safe** — they are called concurrently from multiple elements on multiple threads.

There is no `close()`, `shutdown()`, or `destroy()` lifecycle callback. Providers that hold resources (connections, buffers) must manage their own lifecycle externally — Inqudium does not own the provider's resource lifecycle.

**Rationale:** Resilience elements are configured at startup and remain active for the application's lifetime. Provider lifecycle hooks would add complexity without serving a clear use case — the provider is needed as long as the application runs.

### Convention 5: Programmatic registration supplements ServiceLoader

Every SPI registry supports both ServiceLoader discovery and programmatic registration:

```java
// ServiceLoader — automatic, classpath-based
// (discovered on first access)

// Programmatic — explicit, code-based
InqContextPropagatorRegistry.register(new CustomPropagator());
InqEventExporterRegistry.register(new KafkaExporter(producer));
```

Programmatic registrations are **appended after** ServiceLoader-discovered providers. They do not replace ServiceLoader providers — both coexist. This is consistent with the merge strategy in ADR-013: ServiceLoader provides the base, programmatic registration adds on top.

Programmatic registration must happen **before first access** to the provider list. Registrations after first access throw `IllegalStateException` — the provider set is frozen after discovery.

```java
// OK — register before any element uses the registry
InqContextPropagatorRegistry.register(new CustomPropagator());
var cb = CircuitBreaker.of("test", config); // triggers first access

// FAIL — too late, provider set is frozen
InqContextPropagatorRegistry.register(new AnotherPropagator()); // throws IllegalStateException
```

**Rationale:** Allowing registration after discovery would mean the provider set can change mid-flight — elements created before the registration see a different set than elements created after. This is a source of subtle, environment-dependent bugs.

### Convention 6: `META-INF/services` file naming

The services file follows the standard Java ServiceLoader convention:

```
META-INF/services/<fully-qualified-SPI-interface-name>
```

For Inqudium's SPIs:

```
META-INF/services/eu.inqudium.core.context.InqContextPropagator
META-INF/services/eu.inqudium.core.compatibility.InqCompatibilityOptions
META-INF/services/eu.inqudium.core.event.InqEventExporter
```

Each line in the file is a fully qualified class name of a provider implementation. Empty lines and lines starting with `#` are ignored (standard ServiceLoader behavior).

### Convention 7: JPMS integration

In a modular application (JPMS), ServiceLoader discovery requires the provider module to declare:

```java
module com.mycompany.inqudium.extensions {
    requires inqudium.core;
    provides eu.inqudium.core.context.InqContextPropagator
        with com.mycompany.MdcContextPropagator;
}
```

Inqudium's own bridge modules (e.g. `inqudium-context-slf4j`) ship with the correct `provides` directive in their `module-info.java`. Third-party providers must add their own.

On the classpath (non-modular), `META-INF/services` files work as usual — no `module-info.java` needed.

### Summary table

| Convention | Rule |
|---|---|
| Discovery timing | Lazy on first access, cached for JVM lifetime |
| Ordering | `Comparable` providers sorted first, then non-Comparable, then programmatic |
| Construction error | Provider skipped, warning logged, others unaffected |
| Execution error | Exception caught, warning logged (once), element unaffected |
| Lifecycle | Singleton, no shutdown hook, must be thread-safe |
| Programmatic registration | Supplements ServiceLoader, must happen before first access |
| Services file | Standard `META-INF/services/<SPI-interface>` |
| JPMS | `provides ... with ...` in `module-info.java` |

## Consequences

**Positive:**
- Consistent behavior across all current and future SPIs. A developer who understands how `InqContextPropagator` discovery works automatically knows how `InqEventExporter` and `InqCompatibilityOptions` work.
- Error isolation guarantees that a broken provider (missing class, runtime exception) never crashes the application or degrades resilience behavior.
- Deterministic ordering via `Comparable` eliminates environment-dependent behavior for providers that opt in.
- Frozen-after-first-access semantics prevent mid-flight changes to the provider set, eliminating a class of subtle race conditions.
- JPMS integration is documented upfront — modular applications know exactly what to declare.

**Negative:**
- The frozen-after-first-access rule means providers cannot be added after the first element is created. In test scenarios where elements are created in `@BeforeEach`, custom providers must be registered even earlier (e.g. `@BeforeAll` or a test extension). This is documented in `inqudium-test`.
- No hot-reloading of providers. Applications that need to change provider behavior at runtime must do so through the provider's own configuration (e.g. a feature flag inside the provider), not by swapping provider instances.
- The `System.Logger` usage for provider errors (before the event system is initialized) means these early errors are only visible in JVM platform logs, not in SLF4J/Logback. This is acceptable because it only affects the bootstrap phase.

**Neutral:**
- This ADR does not introduce new code — it codifies conventions that the existing SPIs already follow (or should follow). Existing ADRs (003, 011, 013) reference these conventions.
- Future SPIs must reference this ADR and document any deviations (which should be exceptional and justified).
