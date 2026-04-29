# ADR-025: Configuration architecture

**Status:** Accepted
**Date:** 2026-04-26
**Deciders:** Core team

## Context

The framework provides resilience components — bulkheads, circuit breakers, retries, time limiters, and so on —
and supports four execution paradigms: imperative, Reactor-based reactive, RxJava3-based reactive, and Kotlin
coroutines. A configuration system for this surface must satisfy four requirements simultaneously:

1. **Fluent DSL.** Configuration is written in code, with a discoverable, IDE-friendly API. No
   annotation-driven assembly, no XML, no separate configuration grammar to learn.
2. **Read-only access at runtime.** The configured state of every component must be queryable as a stable,
   thread-safe read view, suitable for monitoring, structured logging, and operational diagnostics.
3. **Runtime mutability.** Components can be reconfigured while the application is running. Operational
   concerns — raise a bulkhead's concurrency limit during a traffic spike, lower a circuit breaker's
   threshold for a misbehaving downstream — must have an in-band answer that does not require restart.
4. **Coherent surface across components and paradigms.** Components share common configuration vocabulary
   (name, observability, tags). The four paradigms must each have access to all components but with
   paradigm-typed APIs that prevent misconfiguration. The result must feel "of one piece" to the developer —
   verbs and structure should be uniform regardless of which component or paradigm is being configured.

These requirements are individually unremarkable but combine into a non-trivial design problem. A naïve fluent
builder solves (1) but not (3): builders typically produce sealed objects. A mutable configuration object
solves (3) but not (2): mutable state is unsafe for concurrent reads without locking, which adds overhead on
the hot path. Maintaining a separate "live view" alongside immutable builders solves (2) and (3) but at the
cost of two parallel data models — a classic source of drift.

The design must also accommodate non-DSL configuration sources. YAML, JSON, HOCON, Spring
`@ConfigurationProperties`, and platform-specific systems (Kubernetes ConfigMaps, AWS Parameter Store) all
need a path that does not duplicate the DSL's validation, defaulting, and structural rules.

Finally, the design must locate code in modules in a way that respects build-time isolation. The
`inqudium-core` runtime kernel should not depend on the configuration framework. Paradigm modules should not
pull each other in. Format adapters should be optional dependencies. But the DSL surface must be uniform
regardless of which modules are present at compile time.

## Decision

The configuration system is built around a single conceptual core: **the DSL produces patches; patches apply to
snapshots; snapshots are held by atomic live containers**. Initialization, runtime updates, and format adapters
(YAML, JSON, ...) all converge on the same patch-and-apply mechanism. There is no second data model.

### Three layers

**Layer 1 — Snapshots.** Per component, an immutable `record` capturing the full configuration state. Snapshots
are value objects: thread-safe by virtue of immutability, comparable by `equals`, serializable, and the canonical
input to runtime components. Compact constructors enforce snapshot-internal invariants (see ADR-027).

```java
public record BulkheadSnapshot(
    String name,
    ParadigmTag paradigm,
    int maxConcurrentCalls,
    Duration maxWaitDuration,
    AdaptiveLimitSnapshot adaptiveLimit,    // nullable; sealed alternative type for AIMD/Vegas/null
    ObservabilitySnapshot observability,
    Set<String> tags
) implements ComponentSnapshot {
    public BulkheadSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(paradigm, "paradigm");
        Objects.requireNonNull(maxWaitDuration, "maxWaitDuration");
        if (maxConcurrentCalls <= 0) {
            throw new IllegalArgumentException(
                "maxConcurrentCalls must be positive, got: " + maxConcurrentCalls);
        }
        if (maxWaitDuration.isNegative()) {
            throw new IllegalArgumentException(
                "maxWaitDuration must not be negative, got: " + maxWaitDuration);
        }
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
```

**Layer 2 — Live containers.** Per component instance, a thin holder owning an `AtomicReference<Snapshot>` plus
a list of change subscribers. Reads are a single volatile load. Writes are atomic snapshot replacement, performed
via `compareAndSet` retry loops, followed by subscriber dispatch. Subscribers are how the runtime components
(strategies, breakers, retry executors) react to live updates — for example, the bulkhead's adaptive strategy
adjusts its semaphore permits when the snapshot's `maxConcurrentCalls` changes.

```java
public final class LiveBulkhead {
    private final AtomicReference<BulkheadSnapshot> ref;
    private final CopyOnWriteArrayList<Consumer<BulkheadSnapshot>> listeners;

    public BulkheadSnapshot snapshot() {
        return ref.get();
    }

    void apply(BulkheadPatch patch) {
        BulkheadSnapshot prev, next;
        do {
            prev = ref.get();
            next = patch.applyTo(prev);
        } while (!ref.compareAndSet(prev, next));
        for (Consumer<BulkheadSnapshot> listener : listeners) {
            listener.accept(next);
        }
    }

    public AutoCloseable subscribe(Consumer<BulkheadSnapshot> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
```

**Layer 3 — DSL.** The DSL produces `Patch` objects. A patch carries a bitset of "touched" fields plus the new
values. Applying a patch to a snapshot is a per-field choice: if the field is touched, the new value wins;
otherwise the previous value is retained. Initialization applies patches to a system-default snapshot and
validates completeness (see ADR-027). Updates apply patches to the current snapshot and skip completeness checks.

```java
final class BulkheadPatch implements ComponentPatch<BulkheadSnapshot> {
    private final BitSet touched = new BitSet(8);
    private String name;
    private Integer maxConcurrentCalls;
    private Duration maxWaitDuration;
    private AdaptiveLimitSnapshot adaptiveLimit;
    private ObservabilitySnapshot observability;
    private Set<String> tags;

    void touchName(String value) {
        touched.set(Field.NAME);
        this.name = value;
    }
    // ... other touch methods

    @Override
    public BulkheadSnapshot applyTo(BulkheadSnapshot base) {
        return new BulkheadSnapshot(
            touched.get(Field.NAME)             ? name             : base.name(),
            base.paradigm(),                    // paradigm is structural, never patched
            touched.get(Field.MAX_CONCURRENT)   ? maxConcurrentCalls : base.maxConcurrentCalls(),
            touched.get(Field.MAX_WAIT)         ? maxWaitDuration  : base.maxWaitDuration(),
            touched.get(Field.ADAPTIVE)         ? adaptiveLimit    : base.adaptiveLimit(),
            touched.get(Field.OBSERVABILITY)    ? observability    : base.observability(),
            touched.get(Field.TAGS)             ? tags             : base.tags()
        );
    }
}
```

The unification is the key insight: **initialization and update are the same operation with different starting
snapshots.** Initialization starts from a system-default snapshot and demands completeness; update starts from
the current snapshot and tolerates partial coverage. One DSL, one patch type, two modes.

### The DSL surface

A single top-level builder organized by paradigm sections:

```java
InqRuntime runtime = Inqudium.configure()
    .general(g -> g
        .clock(systemClock)
        .observability(o -> o.standard())
    )
    .imperative(im -> im
        .bulkhead("inventory", b -> b
            .balanced()                              // preset as baseline
            .maxConcurrentCalls(15)                  // override
            .observability(o -> o.diagnostic())
        )
        .circuitBreaker("payments", cb -> cb
            .protective()
            .failureThreshold(0.5)
        )
        .retry("notifications", r -> r
            .maxAttempts(3)
            .exponentialBackoff(Duration.ofMillis(100))
        )
    )
    .reactive(rx -> rx
        .bulkhead("recommendations", b -> b
            .permissive()
            .maxConcurrentCalls(200)
        )
    )
    .build();
```

Three deliberate properties shape the surface:

**The component name is a method argument, not a setter.** `bulkhead("inventory", b -> ...)`. This makes the name
mandatory by signature, eliminates "forgot to set the name" failures, and identifies the component for the patch
machinery without ambiguity.

**Common cross-component vocabulary uses identical method names.** `b.observability(...)` works on every
component builder with the same semantics and the same sub-builder type. `b.tags("payment", "critical")` works
identically. This is what gives the DSL the "of one piece" feel — verbs are uniform across components.

**Paradigm is a section, not a suffix.** The `.imperative(...)`, `.reactive(...)`, `.rxjava3(...)`,
`.coroutines(...)` blocks are sections that group all components belonging to that paradigm. This permits
paradigm-specific extensions on the component builders inside each section: an
`ImperativeBulkheadBuilder extends BulkheadBuilder<Imperative>` can offer `.codel(...)` and `.aimd(...)`, while
`ReactiveBulkheadBuilder extends BulkheadBuilder<Reactive>` cannot — the methods don't exist on that type.
Misconfiguration is a compile-time error, not a runtime check.

The lookup key for a component is `(name, paradigm)`. The same name in two paradigm sections produces two
distinct components, which is the correct semantics: an imperative bulkhead and a reactive bulkhead with the
same logical purpose are separate runtime objects.

### Runtime updates

Updates use the same DSL, called via `runtime.update(...)`:

```java
runtime.update(u -> u
    .imperative(im -> im
        .bulkhead("inventory", b -> b
            .maxConcurrentCalls(25)              // patches existing component
        )
        .bulkhead("newOne", b -> b               // adds new component
            .balanced()
        )
        .removeBulkhead("legacy")                // explicit structural removal
    )
);
```

The semantics are:

- A `.bulkhead(name, ...)` call against an existing name *patches* that component — only touched fields change.
- A `.bulkhead(name, ...)` call against a new name *adds* that component, applying the patch to a default
  snapshot. All mandatory fields must be supplied (validation as in init).
- An explicit `.removeBulkhead(name)` call (or analogue per component type) shuts down and removes a component.
  In-flight calls drain or are aborted per component-specific shutdown semantics; this is a paradigm-module
  concern (see ADR-026).

Updates are applied atomically per component but not transactionally across components. If component A's update
succeeds and component B's update fails (e.g., validation error), A's update is committed and B's is rejected.
The `runtime.update(...)` call returns a `BuildReport` (see ADR-027) summarizing what was applied, what was
rejected, and any warnings raised. Callers needing all-or-nothing semantics should validate first via
`runtime.dryRun(updater)` and apply only on clean validation.

The handling of updates against components that are already actively serving calls — including the cold/hot
lifecycle distinction, listener-based veto propagation, and the corresponding `BuildReport` outcome extensions
— is specified in ADR-028.

### Format adapters and patches

The patch mechanism is the single integration point for non-DSL configuration sources. A YAML adapter parses
text and produces patches; the same patches feed `runtime.apply(...)` (a slightly lower-level entry point than
`update`, intended for adapter use):

```java
// In inqudium-config-yaml
public final class YamlConfigSource {
    public static List<ComponentPatch<?>> parse(InputStream yaml) { ... }
}

// Application code
List<ComponentPatch<?>> patches = YamlConfigSource.parse(stream);
runtime.apply(patches);
```

This means YAML, JSON, HOCON, Spring-Properties configurations all flow through the same validation, the same
consistency rules, the same listener notifications as DSL calls. Format adapters do not duplicate logic — they
translate text into the canonical patch format and stop.

### Module structure

The configuration system is split across modules to keep `inqudium-core` focused on the runtime kernel:

| Module                     | Contains                                                                              | Depends on            |
|----------------------------|---------------------------------------------------------------------------------------|-----------------------|
| `inqudium-core`            | Events, time, exceptions, logging, common utilities. No configuration code.           | —                     |
| `inqudium-config`          | Snapshot records, patches, live containers, DSL framework, all builder *interfaces*   | core                  |
|                            | (including paradigm-specific subinterfaces), validation framework (ADR-027), runtime  |                       |
|                            | interfaces (ADR-026), `ParadigmProvider` SPI.                                         |                       |
| `inqudium-imperative`      | Implementations of imperative builder interfaces, `ImperativeBulkhead`,               | config                |
|                            | `ImperativeCircuitBreaker`, strategies, ServiceLoader registration.                   |                       |
| `inqudium-reactive`        | Reactor-based implementations.                                                        | config                |
| `inqudium-rxjava3`         | RxJava3-based implementations.                                                        | config                |
| `inqudium-kotlin`          | Kotlin coroutines implementations.                                                    | config                |
| `inqudium-config-yaml`     | YAML → patch translation.                                                             | config                |
| `inqudium-config-json`     | JSON → patch translation.                                                             | config                |
| `inqudium-config-spring`   | Spring `@ConfigurationProperties` → patch translation.                                | config, spring-boot   |

The unusual decision is that **paradigm-specific builder interfaces live in `inqudium-config`, not in the
paradigm modules.** The reasoning: the DSL must be writable when only `inqudium-config` is on the compile
classpath. If `ImperativeBulkheadBuilder` lived in `inqudium-imperative`, every consumer would need that module
on its compile path even if it never used imperative components — and the "of one piece" feel would break,
because the available builder methods would depend on which modules are present.

The implementations of those interfaces are in the paradigm modules. They are loaded via `ServiceLoader` at
runtime, not referenced from `inqudium-config` directly. If the imperative module is missing on the runtime
classpath, calling `.imperative(...)` raises a clear, actionable error:

```
ParadigmUnavailableException: The 'imperative' paradigm requires module
'inqudium-imperative' on the classpath. Add it as a dependency or remove
.imperative(...) sections from your configuration.
```

The SPI surface is small:

```java
// In inqudium-config
public interface ParadigmProvider {
    ParadigmTag paradigm();
    ParadigmBuilderFactory builderFactory();
    ParadigmContainerFactory containerFactory();
}

public interface ParadigmBuilderFactory {
    ImperativeBuilder createImperativeBuilder(...);    // each provider implements its own paradigm's method
    // ... or via a dispatch mechanism per-paradigm
}
```

The exact shape of the SPI is implementation detail; what matters for this ADR is that the paradigm modules are
loaded by `ServiceLoader` (sorted per ADR-014 if multiple are present, though normally only one per paradigm
exists), and the DSL surface in `inqudium-config` does not statically reference them.

### Defaults and presets

Each component builder offers three named presets that establish baseline values:

| Preset       | Intent                                                                          |
|--------------|---------------------------------------------------------------------------------|
| `protective` | Conservative limits, fail-fast, low overhead. Critical downstream services.     |
| `balanced`   | Production default. Reasonable headroom, moderate wait, observability standard. |
| `permissive` | Generous limits, longer wait. Elastic downstream services.                      |

The semantics of the preset values are component-specific (e.g., `bulkhead.balanced()` sets
`maxConcurrentCalls=50, maxWaitDuration=500ms`; `circuitBreaker.balanced()` sets failure-rate threshold and
window size differently). Concrete values per component are documented in the component's own ADR (ADR-020 for
bulkhead, etc.) — this ADR specifies only the *naming convention* and the *preset-then-customize discipline*
(ADR-027).

Presets are not the only way to configure; every preset value is also reachable via individual setters. A user
who wants full explicit control sets every relevant field manually. Validation will accept this provided all
mandatory fields are set.

### What this ADR does not specify

Several closely related concerns have their own ADRs:

- **Component instances and lookup** — see ADR-026 (`InqRuntime` and the registry replacement).
- **Validation strategy** — see ADR-027 (the four validation classes and when each runs).
- **Component-specific configuration semantics** — see the per-component ADR (e.g., ADR-020 for bulkhead). This
  ADR's bulkhead snippets are illustrative, not normative for that component.

## Consequences

**Positive:**

- One configuration surface for initialization, runtime updates, and format adapters. No duplicated validation,
  no second data model, no DSL/builder fork.
- Snapshots are records — automatically thread-safe, automatically serializable by mainstream libraries
  (Jackson, Moshi, Gson) without configuration.
- `AtomicReference`-based live containers permit lock-free reads on the hot path. Components that subscribe to
  changes get a single notification on every snapshot replacement.
- The `(name, paradigm)` lookup key resolves the "same logical bulkhead in two paradigms" question cleanly: it's
  two distinct components, and the configuration shows that.
- The patch mechanism makes runtime updates feel exactly like initialization. The mental model is uniform.
- Format adapters (YAML, JSON, ...) are reduced to "translate text to patches" — they cannot diverge from the
  DSL because there is no DSL-only validation path.
- Module boundaries are linear (`core ← config ← paradigm-modules`), no circular dependencies, no shared mutable
  state across modules.
- The "DSL is paradigm-uniform" property holds even when individual paradigm modules are missing — the API
  compiles, the methods exist, only the runtime fails (with a clear error) if a paradigm is invoked without its
  implementation.

**Negative:**

- The patch mechanism has more machinery than a plain builder: bitsets, touch flags, `applyTo` methods. Each
  component must produce both a snapshot record and a patch class. Code generation (annotation processor) could
  reduce the boilerplate but is out of scope for this ADR.
- Step-builder enforcement of method ordering (the current `MandatoryStep → TopicHub → Buildable` chain) is
  lost. Lambda-DSLs cannot impose call order between method invocations on the same builder. The
  preset-then-customize discipline (ADR-027) compensates partially but not fully.
- Cross-paradigm consistency views require a common interface (`BulkheadView` extracted from
  `BulkheadSnapshot`). One additional type per component — modest, but real.
- ServiceLoader-based paradigm loading produces runtime errors (rather than compile-time errors) if a paradigm
  module is missing. The error message is clear, but the failure is not at the earliest possible moment.
- Adding a new paradigm requires changes in `inqudium-config` (a new top-level method, new builder interfaces).
  Paradigms are not pure plug-ins.

**Neutral:**

- Records as DTOs make later schema evolution constrained: adding a field is breaking for direct constructors,
  though tolerable via builders. Versioned snapshot records (e.g., `BulkheadSnapshotV2`) are a possible
  evolution path; out of scope for this ADR.
- The `inqudium-config` module is large by design: it owns all snapshot records, all builder interfaces, the
  DSL framework, the validation framework, and the runtime interfaces. This is intentional consolidation,
  trading module size for cohesion.
