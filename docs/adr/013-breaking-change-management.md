# ADR-013: Breaking change management

**Status:** Accepted  
**Date:** 2026-03-23  
**Deciders:** Core team

## Context

A resilience library occupies a uniquely sensitive position in an application's dependency tree. Unlike a utility library where a breaking change causes a compilation error (which is visible, immediate, and fixable before deployment), a resilience library can introduce changes that **compile successfully but alter runtime behavior**.

### The three categories of breaking changes

**Category 1: API breaking changes** — A method is renamed, a class is moved, a parameter type changes. The compiler catches these. The fix is mechanical. Semantic versioning (major version bump) communicates these clearly. This category is well-understood and not the focus of this ADR.

**Category 2: Behavioral breaking changes** — The API surface is identical, the code compiles without modification, but the system behaves differently in production:

- The sliding window calculation is corrected — the circuit breaker now opens at a slightly different failure rate for the same configuration.
- The backoff jitter algorithm is improved — retry intervals have a different distribution.
- The default value for `slidingWindowType` changes from `COUNT_BASED` to `TIME_BASED`.

Tests pass. Staging may pass. But production behavior changes — and in a resilience library, that can mean unexpected circuit breaker openings, different retry patterns, or altered bulkhead behavior under load.

**Category 3: Semantic breaking changes** — The behavior is correct, but observable side effects change: `toString()` output changes (breaking log parsers), event ordering shifts (breaking monitoring assertions), metrics keys are renamed. These are subtle enough to survive code review but impactful enough to break production observability.

## Decision

Behavioral and semantic breaking changes are gated behind named **compatibility flags**. A flag controls which implementation variant is active — old behavior (default until explicitly changed) or new behavior (opt-in). Flags are resolved once at configuration build time and are immutable for the lifetime of the element.

### The `InqCompatibility` mechanism

Each behavioral breaking change gets a named constant in the `InqFlag` enum. Each constant documents in its Javadoc: since which version it was introduced, the old behavior, the new behavior, and the default.

```java
InqCompatibility.preserveAll()   // All flags off — maximum stability across upgrades
InqCompatibility.adoptAll()      // All flags on — after staging validation

// Or individually:
InqCompatibility.builder()
    .flag(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE, true)
    .flag(InqFlag.RETRY_BACKOFF_FULL_JITTER, false)
    .build()
```

`InqCompatibility` is passed to the element registry at startup and applies globally to all elements created from that registry. Per-element overrides are not supported — compatibility is a deployment-level decision, not a per-element tuning parameter.

### Abgrenzung zu Konfiguration und Feature-Toggles

**Abgrenzung zur Konfiguration**

Reguläre Konfigurationswerte wie `failureRateThreshold`, `slidingWindowSize` oder `waitDurationInOpenState` sind **Domänenparameter**: Sie steuern, *was* Inqudium mit den Laufzeitdaten macht, und sind dauerhaft Teil des Element-Designs. Der Nutzer wählt sie bewusst anhand fachlicher Anforderungen und pflegt sie langfristig.

Compatibility-Flags sind **Migrationsparameter**: Sie steuern, *wie* eine interne Algorithmus-Implementierung vorübergehend zwischen altem und neuem Verhalten wählt. Sie existieren ausschließlich innerhalb eines definierten Versionsfensters und haben ein garantiertes Entfernungsdatum. Der Nutzer trifft keine fachliche Entscheidung — er entscheidet nur über den *Zeitpunkt* der Übernahme einer Änderung.

| | Konfiguration | Compatibility-Flag |
|---|---|---|
| Zweck | Domänensteuerung | Migrationssteuerung |
| Lebensdauer | Dauerhaft | Bis zum nächsten Major |
| Entscheidungsträger | Fachlich motiviert | Upgrade-Zeitplan |
| Anzahl | Viele, kontextspezifisch | Klein (2–5 aktiv) |

**Abgrenzung zu Feature-Toggles**

Feature-Toggles (im Sinne von Continuous Delivery) dienen dazu, neues Produktverhalten schrittweise für Nutzer auszurollen — oft per Request, per User-Segment oder per Environment, gesteuert durch externe Systeme wie LaunchDarkly. Sie sind ein operatives Werkzeug.

Compatibility-Flags unterscheiden sich in drei wesentlichen Punkten:

1. **Auflösungszeitpunkt:** Flags werden einmalig beim Aufbau der Konfiguration (`builder().build()`) aufgelöst und sind danach für die Lebensdauer des Elements unveränderlich. Es gibt kein Laufzeit-Branching und keine externe Abhängigkeit.

2. **Steuerung:** Sie werden im Anwendungscode konfiguriert (`InqCompatibility.preserveAll()`), nicht durch ein externes Toggle-System. Ein Wechsel erfordert eine Code-Änderung und ein Redeploy — bewusst, weil es sich um eine Upgrade-Entscheidung handelt, nicht um ein operatives Kill-Switch.

3. **Lebensende:** Feature-Toggles können dauerhaft bestehen bleiben (z. B. Kill-Switches). Compatibility-Flags haben ein **garantiertes Entfernungsdatum**: Sie werden spätestens beim nächsten Major entfernt, und die Enum-Konstante wird gelöscht — damit der Compiler alle verbliebenen Verwendungen zwingt, aufgeräumt zu werden.

### Flag lifecycle

Each flag passes through three version stages before removal:

| Version | Default | Status |
|---|---|---|
| 0.3.0 | `false` (old behavior) | Introduced — new behavior is opt-in |
| 0.4.0 | `true` (new behavior) | New behavior is default — old behavior is opt-out |
| 1.0.0 | — | Flag removed — `InqFlag` constant deleted |

At the removal version, the `InqFlag` constant is deleted from the enum. Any application that still references it gets a compilation error — forcing the developer to consciously clean up the flag reference. This is intentional: there must be no silent perpetuation of old behavior past the major version boundary.

Guarantees:
- At least one minor version to test the new behavior (opt-in phase).
- At least one minor version to opt out if staging reveals problems (opt-out phase).
- Removed at the next major — no permanent compatibility debt.

### Performance: zero hot-path overhead

Flags are resolved **once, at configuration build time**. The builder selects the algorithm implementation at that point. The selected implementation is fixed for the lifetime of the element instance. There is no per-call branching — no `if (flag.isEnabled())` on the hot path.

```java
// Inside CircuitBreakerConfig.builder().build()
this.slidingWindow = compatibility.isEnabled(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE)
    ? new InclusiveSlidingWindow(windowSize)
    : new ExclusiveSlidingWindow(windowSize);  // legacy
```

### What does NOT get a flag

- **API breaking changes** — communicated via semantic versioning and deprecation annotations. The compiler is the flag.
- **New features** — a new configuration option with a sensible default is additive, not breaking. No flag needed.
- **Security fixes** — if the old behavior is a security vulnerability, the fix is unconditional. No opt-out.
- **Bug fixes that correct a violated contract** — if the old behavior was wrong per the documented specification (e.g., `failureRateThreshold(50)` opened at 60% due to a bug), the fix is unconditional. The old behavior was not an alternative — it was a defect.

Flags are reserved for changes where **both the old and the new behavior are reasonable**, and the choice affects production behavior in a way that should be validated before adoption.

### Relationship to event system (ADR-003)

When a flag is active, the affected element emits a one-time `InqCompatibilityEvent` at creation time:

```java
public class InqCompatibilityEvent extends InqEvent {
    private final InqFlag flag;
    private final boolean enabled;
    private final String description;
}
```

This event flows through all observability consumers (Micrometer, JFR, custom listeners) and provides an audit trail of which compatibility choices are active in a running system. Useful for post-incident analysis: "Was the new sliding window behavior active when the circuit breaker opened unexpectedly?"

### Startup logging

At application startup, Inqudium logs the active compatibility state:

```
[Inqudium] Compatibility flags:
  SLIDING_WINDOW_BOUNDARY_INCLUSIVE  = false (legacy — introduced 0.3.0, default-on in 0.4.0)
  RETRY_BACKOFF_FULL_JITTER          = true  (adopted — introduced 0.4.0, default-on in 0.5.0)
```

This makes the active behavior visible without requiring the developer to inspect the code.

## Consequences

**Positive:**
- Library upgrades are safe by default — no behavioral changes without explicit opt-in.
- Individual flags enable incremental adoption — test one change at a time in staging.
- `adoptAll()` and `preserveAll()` provide clear bulk strategies for both ends of the spectrum.
- Startup logging makes the active behavior visible — no hidden state.
- Flag lifecycle prevents permanent compatibility debt.
- Configuration-time resolution means zero performance overhead on the hot path.
- Compatibility events provide an audit trail in production.

**Negative:**
- Every behavioral change requires defining a flag, documenting both behaviors, and maintaining the branching code until the flag is removed. This increases the cost of making behavioral changes — intentionally.
- The flag lifecycle adds process overhead to the release workflow.
- The startup log mitigates silent misconfigurations but cannot enforce adoption.

**Neutral:**
- The flag mechanism lives in `inqudium-core` alongside the configuration infrastructure. All paradigm modules respect the same flags — a flag that changes sliding window semantics affects the imperative, Kotlin, Reactor, and RxJava implementations identically (because the sliding window algorithm is shared in core, per ADR-005).
- The number of active flags at any given time should be small (2–5). A large flag count signals that the library is making too many behavioral changes per release cycle.
