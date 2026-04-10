# ADR-014: ServiceLoader conventions

**Status:** Accepted  
**Date:** 2026-03-24
**Deciders:** Core team

## Context

Inqudium is designed to be extensible. Several core systems rely on the Java `ServiceLoader` mechanism to discover and
register implementations at runtime. This allows external modules to plug into Inqudium without explicit code-based
registration.

The primary SPIs that use ServiceLoader:

| SPI                       | Module          | Purpose                                            | ADR     |
|---------------------------|-----------------|----------------------------------------------------|---------|
| `InqContextPropagator`    | `inqudium-core` | Context capture/restore across thread boundaries   | ADR-011 |
| `InqCompatibilityOptions` | `inqudium-core` | Behavioral flag configuration without code changes | ADR-013 |
| `InqEventExporter`        | `inqudium-core` | Event export to external systems                   | ADR-003 |

Additional SPIs will follow as the project grows (e.g. custom `BackoffStrategy` implementations, registry backends,
configuration sources). Without a unified convention, each SPI will reinvent discovery, ordering, error handling, and
lifecycle rules — leading to inconsistent behavior and documentation.

### Problems with unspecified ServiceLoader usage

- **When are providers discovered?** At application startup? On first use?
- **What happens if a provider's constructor throws?** Does it fail the application? Is it silently skipped?
- **In what order do providers run?** If multiple `InqContextPropagator` implementations are on the classpath, which one
  runs first? Is the order deterministic?
- **What is the lifecycle?** Are providers singletons? Are they re-discovered on every call?

## Decision

All Inqudium SPIs that use `ServiceLoader` must adhere to the following five conventions.

### Convention 1: Lazy discovery, frozen after first access

Providers are **not** discovered at application startup. They are discovered and instantiated **the first time the SPI
is used**.

Example: `InqContextPropagator` providers are loaded the first time a resilience element decorates a call.
`InqEventExporter` providers are loaded the first time an event is published.

After the first access, the list of providers is **frozen** and cached for the lifetime of the application. No new
providers will be discovered.

**Why?**

- **Performance.** Avoids unnecessary class loading and instantiation at startup for SPIs that may never be used.
- **Predictability.** The set of active providers is stable after initialization. No risk of a new provider appearing
  mid-flight if a JAR is dynamically loaded.

### Convention 2: Deterministic ordering via `java.lang.Comparable`

If the order of execution matters, providers should implement `java.lang.Comparable<ProviderInterface>`.

After discovery, all providers that implement `Comparable` are sorted by their natural order. Providers that do not
implement `Comparable` are appended to the end of the list in the (non-deterministic) order they were returned by the
`ServiceLoader` iterator.

**Why?**

- `ServiceLoader` itself does not guarantee any order.
- `Comparable` is a standard JDK interface that allows providers to explicitly define their relative priority without
  needing a custom `@Order` annotation.
- The fallback to discovery order for non-Comparable providers maintains baseline behavior.

**Recommendation to SPI implementors:** Always implement `Comparable` when deploying multiple providers. Non-Comparable
ordering is a fallback, not a feature.

For SPIs with override semantics (like `InqCompatibilityOptions`), later providers override earlier ones for the same
key. For SPIs with sequential execution (like `InqContextPropagator` and `InqEventExporter`), all providers run in this
order.

### Convention 3: Error isolation and reporting

A misbehaving provider must **never** affect the core resilience logic.

**If a provider fails during instantiation (constructor throws):**

- The provider is **skipped** — it is not added to the provider list.
- A warning is logged via `System.Logger` (JDK Platform Logging, no SLF4J dependency in core).
- An `InqProviderErrorEvent` is emitted through `InqEventPublisher` if the event system is already initialized and
  diagnostic events are enabled. If not (because the event system itself depends on providers), the error is only
  logged.
- Other providers are not affected.

```
[Inqudium] WARNING: ServiceLoader provider com.example.BrokenPropagator failed to instantiate.
java.lang.RuntimeException: Failed to connect to config server
    at com.example.BrokenPropagator.<init>(...)
```

**If a provider's method throws during operation (e.g. `InqContextPropagator.capture()`
throws, `InqEventExporter.export()` throws):**

- The exception is **caught and contained** — it does not propagate to the calling element.
- A warning is logged (once per provider, not per invocation — to avoid log flooding).
- An `InqProviderErrorEvent` is emitted if diagnostic events are enabled.
- The operation continues with the remaining providers.
- The resilience element's behavior is **never** affected by a failing provider. A broken context propagator does not
  cause a Circuit Breaker call to fail.

### Convention 4: Singleton lifecycle

All discovered providers are treated as **singletons**. They are instantiated once, cached, and reused for the lifetime
of the application.

**Why?**

- Performance: avoids repeated instantiation on the hot path.
- State: allows providers to maintain their own internal state if needed (e.g., a buffer in an `InqEventExporter`).

### Convention 5: Zero-configuration activation

The presence of a bridge module's JAR on the classpath is sufficient to activate its functionality. No explicit
registration is required for the common case.

Example: Adding `inqudium-context-slf4j.jar` to the classpath is all that is needed to enable MDC propagation. The JAR
contains the `MdcContextPropagator` implementation and the corresponding `META-INF/services` file.

Programmatic registration is still supported for advanced use cases or environments where ServiceLoader is disabled:

```java
// Bypasses ServiceLoader entirely
InqContextPropagatorRegistry.set(new MdcContextPropagator());

// Adds a programmatic instance alongside discovered providers
InqEventExporterRegistry.register(new KafkaExporter(producer));
```

## Example: `InqEventExporter`

A user wants to export all Inqudium events to Kafka. They write a custom exporter:

```java
public class KafkaEventExporter implements InqEventExporter, Comparable<InqEventExporter> {
    // ... implementation ...

    @Override
    public int compareTo(InqEventExporter other) {
        return 10; // run after default exporters
    }
}
```

They create the file `META-INF/services/eu.inqudium.core.event.InqEventExporter` with the content:

```
com.mycompany.inq.KafkaEventExporter
```

When the first Inqudium event is published:

1. `InqEventExporterRegistry` uses `ServiceLoader` to find `KafkaEventExporter`.
2. It instantiates it (once). If the constructor throws, it logs a warning and skips.
3. It sorts all discovered exporters by their `compareTo` result.
4. It freezes the exporter list.
5. It iterates through the list, calling `export()` on each one. If `KafkaEventExporter.export()` throws, the exception
   is caught and logged, and the next exporter is called.

## Consequences

**Positive:**

- Consistent behavior across all current and future SPIs. A developer who understands how `InqContextPropagator`
  discovery works automatically knows how `InqEventExporter` and `InqCompatibilityOptions` work.
- Robustness: misbehaving providers are isolated and do not crash the application.
- Deterministic ordering when `Comparable` is used.
- Zero-config "it just works" experience for standard bridge modules.
- Frozen-after-first-access semantics prevent mid-flight changes to the provider set, eliminating a class of subtle race
  conditions.

**Negative:**

- The `Comparable<ProviderInterface>` contract is verbose. A custom annotation (`@Order(10)`) would be more concise but
  would require an annotation processor or reflection, adding complexity and potential startup overhead. `Comparable` is
  a pure JDK solution.
- The `System.Logger` usage for provider errors (before the event system is initialized) means these early errors are
  only visible in JVM platform logs, not in SLF4J/Logback. This is acceptable because it only affects the bootstrap
  phase.

**Neutral:**

- The conventions are enforced by a shared `InqServiceLoader` utility class in `inqudium-core` that all SPI registries
  delegate to. This ensures consistency without code duplication.