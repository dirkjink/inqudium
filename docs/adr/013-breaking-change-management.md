# ADR-013: Breaking change management

**Status:** Accepted  
**Date:** 2026-03-23  
**Deciders:** Core team

## Context

A resilience library occupies a uniquely sensitive position in an application's dependency tree. Unlike a utility
library where a breaking change causes a compilation error (which is visible, immediate, and fixable before deployment),
a resilience library can introduce changes that **compile successfully but alter runtime behavior**.

### The three categories of breaking changes

**Category 1: API breaking changes** — A method is renamed, a class is moved, a parameter type changes. The compiler
catches these. The fix is mechanical. Semantic versioning (major version bump) communicates these clearly. This category
is well-understood and not the focus of this ADR.

**Category 2: Behavioral breaking changes** — The API surface is identical, the code compiles without modification, but
the system behaves differently in production:

- The sliding window calculation is corrected — the circuit breaker now opens at a slightly different failure rate for
  the same configuration.
- The backoff jitter algorithm is improved — retry intervals have a different distribution.
- The default value for `slidingWindowType` changes from `COUNT_BASED` to `TIME_BASED`.
- The rate limiter's token replenishment timing is fixed — under high concurrency, slightly more or fewer requests are
  permitted per second.
- The event emission order changes — a state transition event now fires before the call returns, not after.

These changes are improvements — bug fixes, better defaults, more correct behavior. But they alter how the system
behaves under load, and in a resilience context, altered behavior can cascade: a circuit breaker that opens 2% earlier
may trigger a fallback path that was previously untested.

**Category 3: Semantic breaking changes** — The behavior change is intentional and documented, but subtle enough that it
is easily missed in a changelog. For example: `InqRetryExhaustedException` now includes the attempt durations, causing
its `toString()` output to change — breaking log parsers that match on the exception message.

### Why semantic versioning alone is insufficient

Semantic versioning signals "there is a breaking change" (major bump) or "there is not" (minor/patch bump). It does not
help with Category 2, where the change is a bug fix or improvement that the maintainer considers non-breaking but the
consumer's production system depends on the exact previous behavior.

The Spring Boot approach of deprecation cycles and migration guides helps for API changes but does not address
behavioral changes that have no API surface to deprecate.

### What production teams need

A mechanism that allows:

1. **Upgrade the library version** without any behavioral change — the new version behaves exactly like the old one.
2. **Opt in to behavioral changes** individually — test each change in staging before enabling it in production.
3. **Opt in to all behavioral changes at once** — for teams that prefer to adopt everything at once after testing.
4. **Know what changed** — each behavioral change is documented with its flag, its old behavior, its new behavior, and
   its rationale.

## Decision

### Compatibility flags in `InqCompatibility`

Behavioral breaking changes are controlled by named boolean flags in a central `InqCompatibility` configuration. Each
flag has a clear name, a default value (old behavior), and documentation.

```java
var compatibility = InqCompatibility.builder()
    .flag(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE, true)   // new behavior
    .flag(InqFlag.BACKOFF_JITTER_UNIFORM_DISTRIBUTION, true) // new behavior
    .build();

var circuitBreaker = CircuitBreaker.of("paymentService",
    CircuitBreakerConfig.builder()
        .compatibility(compatibility)
        .failureRateThreshold(50)
        .build());
```

### Flag design

Each flag is an enum constant in `InqFlag`:

```java
public enum InqFlag {

    /**
     * Since: 0.3.0
     * Old behavior: Sliding window boundary check uses < (exclusive).
     *               A window of size 10 opens at call #11.
     * New behavior:  Sliding window boundary check uses <= (inclusive).
     *               A window of size 10 opens at call #10.
     * Default: false (old behavior preserved)
     */
    SLIDING_WINDOW_BOUNDARY_INCLUSIVE,

    /**
     * Since: 0.4.0
     * Old behavior: Backoff jitter uses a simple random offset.
     * New behavior:  Backoff jitter uses uniform distribution for
     *               more even spread across retry storms.
     * Default: false (old behavior preserved)
     */
    BACKOFF_JITTER_UNIFORM_DISTRIBUTION,

    // ... future flags
}
```

Each flag documents:

- **Since** — which version introduced the change
- **Old behavior** — what happens when the flag is `false` (default)
- **New behavior** — what happens when the flag is `true`
- **Default** — always `false` for at least one minor version after introduction

### Lifecycle of a flag

```
Version 0.3.0:  Flag introduced, default = false (old behavior)
                Changelog documents the flag, old vs. new behavior, migration guide.
                Startup log: "InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE is available.
                             Enable it to adopt the new sliding window boundary semantics."

Version 0.4.0:  Default changes to true (new behavior).
                Startup warning if explicitly set to false: "You are using legacy behavior
                for SLIDING_WINDOW_BOUNDARY_INCLUSIVE. This will be removed in 1.0.0."

Version 1.0.0:  Flag removed. New behavior is the only behavior.
                Code that references the removed flag gets a compilation error
                (enum constant deleted) — forcing acknowledgment.
```

The lifecycle guarantees:

- **At least one minor version** where the old behavior is the default — consumers can upgrade without surprises.
- **At least one minor version** where the new behavior is the default but the old is still available — consumers can
  opt out if needed.
- **At the next major version**, the flag is removed — no permanent compatibility debt.

### Bulk adoption

For teams that want to adopt all new behaviors at once:

```java
var compatibility = InqCompatibility.adoptAll();
```

This enables all flags that exist in the current version. It is the "I trust the library authors" shortcut for teams
with good staging environments.

The inverse:

```java
var compatibility = InqCompatibility.preserveAll();
```

This explicitly locks all flags to `false` — useful for production deployments where stability is paramount and
behavioral changes are adopted one by one after validation.

### ServiceLoader-based configuration: `InqCompatibilityOptions`

Flags can also be configured without code changes via a ServiceLoader-discoverable `InqCompatibilityOptions`
implementation:

```java
/**
 * SPI for providing compatibility flag defaults.
 * Discovered via ServiceLoader at startup.
 * Follows the conventions defined in ADR-014 (lazy discovery,
 * Comparable ordering, error isolation, singleton lifecycle).
 *
 * Multiple providers are merged in order: if a provider implements
 * Comparable<InqCompatibilityOptions>, providers are sorted before merging.
 * Later providers override earlier ones for the same flag.
 * Non-Comparable providers are merged after all Comparable providers,
 * in ServiceLoader discovery order (non-deterministic).
 */
public interface InqCompatibilityOptions {

    /**
     * Returns the flags this provider configures.
     * Only flags explicitly returned are set — absent flags retain their defaults.
     */
    Map<InqFlag, Boolean> flags();
}
```

A concrete implementation with explicit ordering:

```java
public class CompanyWideDefaults implements InqCompatibilityOptions,
                                            Comparable<InqCompatibilityOptions> {
    @Override
    public Map<InqFlag, Boolean> flags() {
        return Map.of(
            InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE, true,
            InqFlag.BACKOFF_JITTER_UNIFORM_DISTRIBUTION, false
        );
    }

    @Override
    public int compareTo(InqCompatibilityOptions other) {
        return 0; // lowest priority — applied first, can be overridden
    }
}

public class TeamSpecificOverrides implements InqCompatibilityOptions,
                                              Comparable<InqCompatibilityOptions> {
    @Override
    public Map<InqFlag, Boolean> flags() {
        return Map.of(
            InqFlag.BACKOFF_JITTER_UNIFORM_DISTRIBUTION, true  // override company default
        );
    }

    @Override
    public int compareTo(InqCompatibilityOptions other) {
        return 1; // higher priority — applied after CompanyWideDefaults
    }
}
```

The merge order is:

```
1. All Comparable providers, sorted by compareTo (ascending — lower value = applied first)
2. All non-Comparable providers, in ServiceLoader discovery order
3. Later providers override earlier providers for the same flag
```

This means: a provider with `compareTo` returning `0` forms the base, a provider returning `1` overrides it, and
non-Comparable providers override last. The pattern supports a layered configuration model — company-wide defaults →
team overrides → environment-specific adjustments — with deterministic ordering when `Comparable` is implemented.

### Resolution model: three layers with explicit merge strategy

Flag values can come from three sources. The resolution must be deterministic and predictable. The three layers, from
lowest to highest priority:

```
Layer 1 (lowest):   Built-in defaults         InqFlag enum defaults (always false)
Layer 2:            ServiceLoader              InqCompatibilityOptions providers
Layer 3 (highest):  Programmatic API           .compatibility(compatibility) on the element config
```

The critical question is what happens when both Layer 2 (ServiceLoader) and Layer 3 (programmatic API) set flags. There
are two possible strategies:

#### Strategy A: Programmatic replaces ServiceLoader entirely

If `.compatibility(compatibility)` is set on a config builder, the ServiceLoader flags are **completely ignored** for
that element. The programmatic configuration is the sole source of truth.

```java
// ServiceLoader sets: SLIDING_WINDOW=true, JITTER=true
// Programmatic sets: SLIDING_WINDOW=false

// Result with Strategy A:
//   SLIDING_WINDOW = false  (from programmatic)
//   JITTER = false          (default — ServiceLoader was ignored entirely)
```

**Advantage:** Simple mental model. If you configure programmatically, you own the full state.
**Disadvantage:** Setting one flag programmatically silently disables all ServiceLoader flags. Easy to accidentally lose
flags that were intentionally set in the ServiceLoader provider.

#### Strategy B: Programmatic overrides ServiceLoader per-flag (merge)

The layers are merged. ServiceLoader flags form the base, and programmatic flags override individual values on top:

```java
// ServiceLoader sets: SLIDING_WINDOW=true, JITTER=true
// Programmatic sets: SLIDING_WINDOW=false

// Result with Strategy B:
//   SLIDING_WINDOW = false  (programmatic overrides ServiceLoader)
//   JITTER = true           (ServiceLoader value preserved — not overridden)
```

**Advantage:** Additive. The ServiceLoader provides organization-wide defaults. Individual elements override specific
flags without losing the rest.
**Disadvantage:** Harder to reason about. The final state of a flag depends on whether someone else set it in a
ServiceLoader provider that may not be visible in the current codebase.

#### Decision: Strategy B (merge) as default, Strategy A available as opt-in

**Strategy B (merge)** is default because it matches the most common real-world pattern: an operations team provides
organization-wide compatibility defaults via a shared ServiceLoader JAR, and individual services override specific flags
where needed. Losing all ServiceLoader flags because one element sets one flag programmatically is a pit of failure.

Strategy A is available as an explicit opt-in for elements that need full control:

```java
var compatibility = InqCompatibility.builder()
    .ignoreServiceLoader()                                    // Strategy A: replace, don't merge
    .flag(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE, false)
    .build();
```

The resolution is then:

```
1. Start with built-in defaults (all false)
2. If ServiceLoader providers exist and ignoreServiceLoader is NOT set:
     Sort Comparable providers by compareTo, append non-Comparable providers
     Merge flags in order (later providers override earlier ones for the same flag)
3. If programmatic .compatibility() is set:
     Merge programmatic flags on top of current state (per-flag override)
     OR if ignoreServiceLoader: replace current state entirely with programmatic flags
4. Final state is locked — no further changes at runtime
```

### Startup logging (updated)

The startup log shows the resolved state **and its origin per flag**:

```
[Inqudium] Compatibility flags (resolved):
  ServiceLoader providers (sorted): CompanyWideDefaults [0], TeamSpecificOverrides [1]
  
  SLIDING_WINDOW_BOUNDARY_INCLUSIVE:      true  (ServiceLoader: CompanyWideDefaults)
  BACKOFF_JITTER_UNIFORM_DISTRIBUTION:   true  (ServiceLoader: TeamSpecificOverrides, overrides CompanyWideDefaults)
  
  Source precedence: defaults → ServiceLoader (sorted) → programmatic API
  2 of 2 flags adopted. Run with adopt-all: true to enable all new behaviors.
```

For elements using `ignoreServiceLoader()`:

```
[Inqudium] Compatibility flags for 'paymentService' (resolved, ServiceLoader ignored):
  SLIDING_WINDOW_BOUNDARY_INCLUSIVE:      false (programmatic)
  BACKOFF_JITTER_UNIFORM_DISTRIBUTION:   false (default)
```

This makes the resolution transparent — in post-incident analysis, you know exactly which layer determined each flag's
value.

### Spring Boot configuration

```yaml
inqudium:
  compatibility:
    adopt-all: false                                    # default
    flags:
      sliding-window-boundary-inclusive: true            # opt in to this one
      backoff-jitter-uniform-distribution: false         # keep old behavior
```

Or:

```yaml
inqudium:
  compatibility:
    adopt-all: true   # adopt everything at once
```

### Where flags are checked

Flags are checked at **configuration time**, not at call time. When a `CircuitBreakerConfig` is built, the compatibility
flags determine which algorithm implementation is selected. The selected implementation is fixed for the lifetime of the
element instance. There is no per-call branching — no `if (flag.isEnabled())` on the hot path.

```java
// Inside CircuitBreakerConfig.builder().build()
this.slidingWindow = compatibility.isEnabled(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE)
    ? new InclusiveSlidingWindow(windowSize)
    : new ExclusiveSlidingWindow(windowSize);  // legacy
```

This is critical for performance: the flag resolution happens once at startup, and the element runs without branching
overhead.

### What does NOT get a flag

- **API breaking changes** (renamed methods, removed classes) — these are communicated via semantic versioning and
  deprecation annotations. No flag needed — the compiler is the flag.
- **New features** — a new configuration option with a default value is additive, not breaking. No flag needed.
- **Security fixes** — if the old behavior is a security vulnerability, the fix is unconditional. No flag — the old
  behavior is simply removed.
- **Bug fixes that only affect incorrect usage** — if the old behavior violated the documented contract (e.g.,
  `failureRateThreshold(50)` opened at 60% due to a bug), the fix is unconditional. The old behavior was wrong, not
  alternative.

Flags are reserved for changes where **both the old and the new behavior are reasonable**, and the change affects
production behavior in a way that should be validated before adoption.

### Distinction: compatibility flags vs. configuration vs. feature toggles

`InqFlag` can be confused with two other mechanisms that look similar on the surface but serve fundamentally different
purposes. The distinction must be clear.

#### Compatibility flags (`InqFlag`) — version migration aid

| Aspect            | Compatibility flag                                                                           |
|-------------------|----------------------------------------------------------------------------------------------|
| **Purpose**       | Safe transition between library versions                                                     |
| **Lifetime**      | Temporary — introduced in version N, default-flipped in N+1, removed in N+2                  |
| **Scope**         | Library-internal algorithm choice (how the sliding window counts, how jitter is distributed) |
| **Who decides**   | Library authors introduce them; consumers adopt them on their schedule                       |
| **Resolved when** | Once at configuration time — immutable for the element's lifetime                            |
| **What changes**  | The implementation of a resilience algorithm. The API surface is identical.                  |
| **End state**     | The flag is deleted. The new behavior becomes the only behavior.                             |

A compatibility flag is a **migration tool**, not a permanent switch. Its existence signals: "We changed how this works
internally. Here is the old way and the new way. Adopt the new way when you are ready." Once everyone has adopted, the
flag and the old implementation are removed.

#### Configuration (`CircuitBreakerConfig`, `RetryConfig`, etc.) — user-defined behavior

| Aspect            | Configuration                                                       |
|-------------------|---------------------------------------------------------------------|
| **Purpose**       | Adapt resilience behavior to the application's requirements         |
| **Lifetime**      | Permanent — these options exist for the life of the library         |
| **Scope**         | What the element does (thresholds, durations, strategies, limits)   |
| **Who decides**   | The application developer, based on their service's characteristics |
| **Resolved when** | At element creation time — immutable for the element's lifetime     |
| **What changes**  | The element's behavior as designed and documented                   |
| **End state**     | The configuration option remains available indefinitely             |

`failureRateThreshold(50)` is configuration. It tells the Circuit Breaker what the user wants. It is not a migration
tool — there is no "old" or "new" threshold. The user sets it, and it stays.

The key difference: **configuration expresses intent** ("open at 50% failure rate"), **compatibility flags manage
transitions** ("use the corrected algorithm for computing that 50%").

#### Feature toggles (external systems) — runtime behavior control

| Aspect            | Feature toggle                                                                        |
|-------------------|---------------------------------------------------------------------------------------|
| **Purpose**       | Enable/disable application features at runtime, often per-user or per-environment     |
| **Lifetime**      | Variable — may be temporary (rollout) or permanent (entitlement)                      |
| **Scope**         | Application-level business logic (show new UI, enable premium feature)                |
| **Who decides**   | Product managers, operations, automated rollout systems                               |
| **Resolved when** | Per-request or per-session — can change at any time                                   |
| **What changes**  | Application behavior visible to the end user                                          |
| **End state**     | Depends on the toggle type — rollout toggles are removed, entitlement toggles persist |

Feature toggles (LaunchDarkly, Unleash, Split, or custom) operate at a fundamentally different level. They control *
*what the application does** — not how a resilience algorithm works internally.

Inqudium does **not** provide feature toggles and does not integrate with feature toggle systems. If an application
wants to dynamically enable or disable a resilience element based on a feature flag (e.g., "disable the rate limiter for
beta users"), that decision happens **outside** Inqudium — the application checks the feature flag and decides whether
to route through the resilience pipeline or bypass it.

```java
// Feature toggle integration is the application's responsibility
if (featureFlags.isEnabled("rate-limiting", userContext)) {
    return resilientPipeline.execute(() -> service.call(request));
} else {
    return service.call(request);
}
```

Inqudium's `InqFlag` is **not** a feature toggle system:

- It is not per-request. It is resolved once at startup.
- It is not dynamic. It cannot change at runtime.
- It is not externally managed. There is no dashboard, no API, no webhook.
- It is temporary. Every flag has a defined removal date.

|                          | Compatibility flag                   | Configuration         | Feature toggle   |
|--------------------------|--------------------------------------|-----------------------|------------------|
| Changes between versions | Yes (introduced → flipped → removed) | No (stable API)       | N/A (external)   |
| Changes at runtime       | No                                   | No                    | Yes              |
| Controlled by            | Library authors                      | Application developer | Product/ops team |
| Per-request resolution   | No                                   | No                    | Typically yes    |
| Temporary by design      | Yes                                  | No                    | Sometimes        |

### Relationship to event system (ADR-003)

When a flag changes behavior, the affected element emits a one-time `InqCompatibilityEvent` at creation time when
diagnostic events are enabled:

```java
public class InqCompatibilityEvent extends InqEvent {
    private final InqFlag flag;
    private final boolean enabled;
    private final String description;
}
```

This event is captured by all observability consumers (Micrometer, JFR, custom listeners) and provides an audit trail of
which compatibility choices are active in a running system. Useful for post-incident analysis: "Was the new sliding
window behavior active when the circuit breaker opened unexpectedly?"

## Consequences

**Positive:**

- Library upgrades are safe by default — no behavioral changes without explicit opt-in.
- Individual flags enable incremental adoption — test one change at a time in staging.
- `adoptAll()` and `preserveAll()` provide clear bulk strategies.
- ServiceLoader-based `InqCompatibilityOptions` enables flag management without code changes — swap a JAR or config file
  between environments.
- Three-layer resolution (defaults → ServiceLoader → programmatic) supports both organization-wide defaults and
  per-element overrides.
- Startup logging shows the resolved state **and the source per flag** — full transparency for post-incident analysis.
- Flag lifecycle (introduced → default-on → removed) prevents permanent compatibility debt.
- Configuration-time resolution means zero performance overhead on the hot path.
- Compatibility events provide an audit trail in production.

**Negative:**

- Every behavioral change requires defining a flag, documenting both behaviors, and maintaining the branching code until
  the flag is removed. This increases the cost of making behavioral changes — intentionally.
- The flag lifecycle adds complexity to the release process. Each minor release must decide which flags to introduce,
  which to flip to default-on, and which to remove.
- The merge strategy (Strategy B) means the final flag state depends on the combination of ServiceLoader providers and
  programmatic overrides. This is more powerful but harder to reason about than a simple replace. The startup log
  mitigates this by showing the origin of each flag's value.
- Consumers who never read changelogs will run with old behavior indefinitely (flags default to `false`). The startup
  log mitigates this but cannot force adoption.
- Multiple `InqCompatibilityOptions` providers via ServiceLoader are supported. Providers that implement `Comparable`
  are sorted deterministically; non-Comparable providers fall back to ServiceLoader discovery order. For fully
  deterministic resolution, all providers should implement `Comparable`.

**Neutral:**

- The flag mechanism lives in `inqudium-core` alongside the configuration infrastructure. All paradigm modules respect
  the same flags — a flag that changes sliding window semantics affects the imperative, Kotlin, Reactor, and RxJava
  implementations identically (because the sliding window algorithm is shared in core, per ADR-005).
- The number of active flags at any given time should be small (2-5). If the flag count grows large, it signals that the
  library is making too many behavioral changes per release cycle.
- `ignoreServiceLoader()` is available as an escape hatch for elements that need complete programmatic control. Its
  usage should be rare and deliberate.