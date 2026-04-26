# ADR-027: Validation strategy

**Status:** Accepted
**Date:** 2026-04-26
**Deciders:** Core team
**Related:** ADR-025 (configuration architecture), ADR-026 (runtime and registry)

## Context

The configuration DSL specified in ADR-025 gives the developer broad freedom: any setter can be called at any
time, presets can be combined with individual overrides, and many fields are optional. This freedom is
desirable for ergonomics — encoding every constraint in the type system would produce a brittle, verbose API
— but it creates the risk of internally inconsistent configurations that compile fine and assemble fine yet
produce nonsensical runtime behaviour.

A flexible DSL needs validation that catches mistakes early without obstructing legitimate use. Several
distinct kinds of mistake are possible, and each is best caught at a different point in the configuration
lifecycle:

- **Out-of-range values.** A negative concurrency limit, a null required parameter, an error rate threshold
  outside the [0, 1] interval. These are wrong at the moment they enter the system.
- **Cross-field invariants on a single component.** Min must not exceed max; an initial value must lie within
  its declared bounds. These are checkable as soon as a complete component snapshot exists.
- **Semantic conflicts.** Each value is individually valid, every invariant holds, but the combination
  contradicts the user's apparent intent — a "fail fast" preset paired with a long wait duration, or a
  failure-rate threshold so low that the configured sliding window cannot statistically observe it. These
  require knowledge of how fields interact at the *intent* level, not just the value level.
- **Cross-component conflicts.** Each component is sensible in isolation, but the combination produces
  pathological behaviour: a retry's burst can fill a bulkhead's permit pool with logically-single requests; a
  time limiter shorter than typical downstream latency can fill a bulkhead with orphans. These require the
  full runtime topology to detect.

Bundling all four into a single "validate everything" pass produces poor diagnostics: a value error fails
late, a cross-component issue fails too early, and the user's experience oscillates between cryptic and
overwhelming. Splitting them by enforcement point — and by failure mode — gives each kind of error its
appropriate response.

The design must also accommodate non-DSL configuration paths (per ADR-025): YAML, JSON, programmatic API,
deserialization. Validation that lives only in the DSL surface is bypassed by these paths. Validation that
lives only at deeper layers misses the fast-feedback opportunities the DSL surface offers. The validation
strategy must work uniformly across all entry points.

Finally, the validation surface must be extensible. Application-specific consistency rules ("our bulkheads
should never have a wait duration above 100ms") should plug in without forking the framework. The mechanism
for this should be lightweight and idiomatic.

## Decision

Configuration errors are partitioned into four classes by their *nature*. Each class has its own enforcement
point — the earliest point at which the error is detectable.

| Class                          | Nature                                       | Enforced at                          |
|--------------------------------|----------------------------------------------|--------------------------------------|
| 1. Argument-range errors       | Single argument value out of range           | DSL setter                           |
| 2. Snapshot invariants         | Cross-field invariants within one snapshot   | Snapshot record's compact constructor|
| 3. Semantic conflicts          | Internally consistent but contradictory      | Snapshot build (post-patch)          |
| 4. Cross-component conflicts   | Multi-component interactions                 | `runtime.diagnose()` (on demand)     |

The principle is **fail at the earliest layer where the error is detectable, with the most specific
information available at that layer.** A negative integer fails in the setter that took the int. A
`min > max` mismatch fails when the snapshot is constructed. A protective preset paired with a long
`maxWaitDuration` fails when the snapshot is built (or warns, depending on severity). A pathological
retry-into-bulkhead interaction surfaces only when the operator runs `runtime.diagnose()`.

### Class 1 — Argument-range errors

Single-value validity checks. The setter's argument cannot reasonably be valid: negative
`maxConcurrentCalls`, null required parameter, negative `Duration`, error rate threshold outside (0, 1).

**Enforced at:** the DSL setter that received the value.
**Failure mode:** `IllegalArgumentException` (for range violations) or `NullPointerException` (for null
required parameters), thrown immediately, with a clear message and the offending value.

```java
public BulkheadBuilder<P> maxConcurrentCalls(int n) {
    if (n <= 0) {
        throw new IllegalArgumentException(
            "maxConcurrentCalls must be positive, got: " + n);
    }
    patch.touchMaxConcurrentCalls(n);
    return this;
}

public BulkheadBuilder<P> maxWaitDuration(Duration d) {
    Objects.requireNonNull(d, "maxWaitDuration");
    if (d.isNegative()) {
        throw new IllegalArgumentException(
            "maxWaitDuration must not be negative, got: " + d);
    }
    patch.touchMaxWaitDuration(d);
    return this;
}
```

The setter is the right home: it has the most specific information (the parameter name, the offending value),
and the exception's stack trace points directly at the user's call site. No defer, no aggregation, no soft
mode — invalid arguments are programmer errors.

These checks do not consider any other field on the builder. They do not consult the patch state. They are
purely local.

### Class 2 — Snapshot invariants

Cross-field invariants that any single snapshot must satisfy regardless of how it was assembled. Examples:
`minLimit ≤ maxLimit`, `initialLimit ∈ [minLimit, maxLimit]`, `name != null`, `paradigm != null`.

**Enforced at:** the snapshot record's compact constructor.
**Failure mode:** `IllegalArgumentException`, thrown by record construction.

```java
public record VegasLimitSnapshot(
    int initialLimit,
    int minLimit,
    int maxLimit,
    Duration smoothingTimeConstant,
    Duration baselineDriftTimeConstant,
    Duration errorRateSmoothingTimeConstant,
    double errorRateThreshold,
    double minUtilizationThreshold
) {
    public VegasLimitSnapshot {
        // Argument-range checks repeated here, because the record is also
        // constructable directly (deserialization, programmatic, tests).
        if (initialLimit <= 0) {
            throw new IllegalArgumentException("initialLimit must be positive: " + initialLimit);
        }
        if (minLimit <= 0) {
            throw new IllegalArgumentException("minLimit must be positive: " + minLimit);
        }
        if (maxLimit <= 0) {
            throw new IllegalArgumentException("maxLimit must be positive: " + maxLimit);
        }
        if (errorRateThreshold <= 0.0 || errorRateThreshold >= 1.0) {
            throw new IllegalArgumentException(
                "errorRateThreshold must be in (0.0, 1.0): " + errorRateThreshold);
        }
        Objects.requireNonNull(smoothingTimeConstant, "smoothingTimeConstant");
        // ... (all nullable references)

        // Cross-field invariants — the actual class-2 work.
        if (minLimit > maxLimit) {
            throw new IllegalArgumentException(
                "minLimit (" + minLimit + ") must not exceed maxLimit (" + maxLimit + ")");
        }
        if (initialLimit < minLimit || initialLimit > maxLimit) {
            throw new IllegalArgumentException(
                "initialLimit (" + initialLimit + ") must be in [minLimit, maxLimit] = ["
                    + minLimit + ", " + maxLimit + "]");
        }
    }
}
```

The compact constructor is the right home because the snapshot is constructed from multiple paths: the DSL,
deserialization (Jackson, Moshi, ...), programmatic API for tests, format adapters (YAML, JSON). The compact
constructor is the one place every path crosses. Putting validation anywhere else means re-implementing it for
each path, which is exactly the kind of duplication this design avoids.

The setter-level checks (class 1) are deliberately repeated in the compact constructor. This is intentional
duplication: it ensures that direct constructor use (tests, deserialization) cannot produce invalid records.
The DSL setters fail-fast at the call site for ergonomics; the compact constructor fails as the last line of
defense. Both are needed.

### Class 3 — Semantic conflicts

Field combinations that pass classes 1 and 2 (each value valid, all invariants satisfied) but produce a
configuration the user almost certainly did not intend. Examples:

- `bulkhead.protective().maxWaitDuration(Duration.ofMinutes(5))` — protective implies fail-fast; a
  five-minute wait contradicts the preset's intent.
- A bulkhead with both AIMD and Vegas adaptive limits configured (assuming the API does not already prevent
  this via sealed sub-builders).
- A circuit breaker with a sliding window of 10 calls and a failure threshold of 5% — statistically
  meaningless because 5% of 10 is 0.5 calls.

These are not errors in the strict sense — every value is valid, every invariant holds — but they are likely
*intent mismatches*, and the user benefits from being told.

**Enforced at:** the snapshot build path, after the patch is applied to a default snapshot, before the
snapshot is committed to the live container.
**Failure mode:** structured `BuildReport` containing one or more `ValidationFinding` entries. Each finding
has a severity (`ERROR`, `WARNING`, `INFO`), a rule identifier, a message, and the affected component name.
Errors abort the build; warnings are reported but the build proceeds. Strict mode (`builder.strict()`) elevates
warnings to errors.

#### Three strategies for class 3

**Strategy A — Preset-then-customize discipline (mechanical).** Presets are baseline setters; they may be
called only *before* individual setters. After any individual setter has been called, calling a preset throws
`IllegalStateException`. The reverse is permitted: preset first, then individual overrides. This pattern is
already established in the codebase (`VegasLimitAlgorithmConfigBuilder.guardPreset()`,
`CoDelBulkheadStrategyConfigBuilder`); ADR-027 standardizes it across all component builders.

```java
public BulkheadBuilder<P> protective() {
    guardPresetOrdering();
    patch.touchMaxConcurrentCalls(10);
    patch.touchMaxWaitDuration(Duration.ZERO);
    patch.touchObservability(ObservabilitySnapshot.standard());
    presetMark = "protective";
    return this;
}

private void guardPresetOrdering() {
    if (anyIndividualSetterCalled) {
        throw new IllegalStateException(
            "Cannot apply a preset after individual setters have been called. "
                + "Presets are baselines: call them first, then customize. "
                + "Example: bulkhead(\"x\", b -> b.protective().maxConcurrentCalls(15))");
    }
}
```

This eliminates the most common confusion ("did the preset overwrite my setter?") at the API level. It does
not catch all semantic conflicts — a user can still call `protective().maxWaitDuration(Duration.ofMinutes(5))`
in the correct order — but it removes the structural ambiguity.

**Strategy B — Sealed alternatives via sub-builders (typed).** Mutually exclusive options become sub-builders
with sealed alternatives. Instead of two parallel setters `b.aimd(...)` and `b.vegas(...)` that the user might
call both of, there is a single entry point `b.adaptiveLimit(a -> ...)` with a sub-builder that allows exactly
one of `aimd(...)` or `vegas(...)`. The second call throws.

```java
public BulkheadBuilder<P> adaptiveLimit(Consumer<AdaptiveLimitBuilder> c) {
    AdaptiveLimitBuilder sub = new AdaptiveLimitBuilder();
    c.accept(sub);
    patch.touchAdaptiveLimit(sub.build());
    return this;
}

public final class AdaptiveLimitBuilder {
    private AdaptiveLimitSnapshot chosen;

    public AdaptiveLimitBuilder aimd(Consumer<AimdBuilder> c) {
        guardSingleChoice("aimd");
        AimdBuilder sub = new AimdBuilder();
        c.accept(sub);
        chosen = sub.build();
        return this;
    }

    public AdaptiveLimitBuilder vegas(Consumer<VegasBuilder> c) {
        guardSingleChoice("vegas");
        VegasBuilder sub = new VegasBuilder();
        c.accept(sub);
        chosen = sub.build();
        return this;
    }

    private void guardSingleChoice(String requested) {
        if (chosen != null) {
            throw new IllegalStateException(
                "adaptiveLimit accepts exactly one strategy. Already configured: "
                    + chosen.getClass().getSimpleName() + ", refusing: " + requested);
        }
    }

    AdaptiveLimitSnapshot build() {
        return chosen;   // may be null — adaptive limit is optional
    }
}
```

This makes mutual exclusivity explicit in the API. It is the preferred mechanism for any case where two
options are *fundamentally* incompatible (not just stylistically suspicious).

**Strategy C — Consistency rule framework (declarative).** For semantic conflicts that are too varied or
context-sensitive to encode in the API surface, the validation framework supports declarative rules:

```java
public interface ConsistencyRule<S extends ComponentSnapshot> {
    String ruleId();                          // stable identifier, used in BuildReport
    Class<S> appliesTo();                     // which snapshot type
    Severity defaultSeverity();               // ERROR | WARNING | INFO
    Optional<ValidationFinding> check(S snapshot);
}
```

Rules are registered at framework load time via `ServiceLoader<ConsistencyRule<?>>` (sorted per ADR-014 if
multiple are present). At snapshot-build time, every rule whose `appliesTo` matches is invoked; findings are
collected.

```java
// Built-in rule example, in inqudium-config
public final class ProtectiveWithLongWaitRule implements ConsistencyRule<BulkheadSnapshot> {

    @Override
    public String ruleId() {
        return "BULKHEAD_PROTECTIVE_WITH_LONG_WAIT";
    }

    @Override
    public Class<BulkheadSnapshot> appliesTo() {
        return BulkheadSnapshot.class;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public Optional<ValidationFinding> check(BulkheadSnapshot s) {
        if (!"protective".equals(s.derivedFromPreset())) return Optional.empty();
        if (s.maxWaitDuration().toMillis() <= 100) return Optional.empty();
        return Optional.of(new ValidationFinding(
            ruleId(),
            defaultSeverity(),
            s.name(),
            "Bulkhead '" + s.name() + "' uses 'protective' preset (intent: fail fast) "
                + "but maxWaitDuration is " + s.maxWaitDuration() + ". "
                + "Consider 'balanced' for non-zero wait durations, or set maxWaitDuration "
                + "to Duration.ZERO to honour the protective intent."
        ));
    }
}
```

This requires the snapshot to remember which preset (if any) was the baseline — a small additional field on
component snapshots (`String derivedFromPreset`, nullable). The cost is one nullable string per snapshot;
the benefit is rule expressiveness without API contortions.

The combined effect of strategies A, B, and C: most semantic conflicts are caught structurally (A, B), and
the remaining cases — those that genuinely depend on field-value interaction — are caught declaratively (C)
without polluting the builder code.

### Class 4 — Cross-component conflicts

Configurations where each component is individually sensible but the *combination* is not. Examples:

- A retry with `maxAttempts=5` wrapping a bulkhead with `maxConcurrentCalls=5` — a single failure produces 5
  retries, each holding a permit, filling the bulkhead with one logical request.
- A circuit breaker with sliding-window 10 wrapping a retry with `maxAttempts=4` — three real failures cause
  12 attempts, exceeding the window before the original failures register, breaking the breaker's
  fault-detection logic.
- A time limiter shorter than a downstream's normal latency — every call is timed out, the bulkhead fills
  with orphans (per ADR-020, this is the expected mitigation pattern but it should be explicit).

These conflicts cannot be detected at single-snapshot build time because they require knowledge of how the
components compose at runtime. They also cannot always be detected statically — composition order may be
implicit or determined by user code outside the framework's view.

**Enforced at:** explicit invocation of `runtime.diagnose()`. Not run automatically.
**Failure mode:** `DiagnosisReport`, structurally similar to `BuildReport` but containing
`DiagnosticFinding` entries that may reference multiple components. No exception is thrown; the report is
the result.

```java
public interface DiagnosisReport {
    Instant timestamp();
    List<DiagnosticFinding> findings();

    // Convenience accessors
    default boolean hasErrors() {
        return findings().stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }

    default Stream<DiagnosticFinding> errors() {
        return findings().stream().filter(f -> f.severity() == Severity.ERROR);
    }

    String formatHumanReadable();
}

public record DiagnosticFinding(
    String ruleId,
    Severity severity,
    Set<String> affectedComponents,           // (name, paradigm) tuples as strings, or richer keys
    String message,
    Map<String, Object> context              // structured data for tooling
) { ... }

public interface CrossComponentRule {
    String ruleId();
    Severity defaultSeverity();
    List<DiagnosticFinding> check(InqConfigView view);
}
```

`CrossComponentRule` instances are also registered via `ServiceLoader`. Built-in rules cover the
well-understood pathological combinations; users can register their own for application-specific concerns.

`runtime.diagnose()` is intended for two use cases:

1. **CI/CD validation.** Run after configuration build, fail the deploy if errors are present. Catches
   misconfigurations before production.
2. **Operational health checks.** Run periodically (or on demand) in production to detect configuration drift
   and emerging problems.

`diagnose()` is not run automatically because:

- It is potentially expensive (runs all cross-component rules over all components).
- Some findings depend on operational context the framework cannot know (e.g., "this combination is fine for
  read traffic but not write traffic").
- Automatic invocation would couple the configuration build to the health-check policy, which is an
  application-level concern.

### `BuildReport`

`BuildReport` is the result of any operation that constructs or mutates configuration:

```java
public interface BuildReport {
    Instant timestamp();
    List<ValidationFinding> findings();
    Map<String, ApplyOutcome> componentOutcomes;    // per-component (name → outcome)

    default boolean isSuccess() {
        return findings().stream().noneMatch(f -> f.severity() == Severity.ERROR);
    }

    default Stream<ValidationFinding> warnings() {
        return findings().stream().filter(f -> f.severity() == Severity.WARNING);
    }
}

public record ValidationFinding(
    String ruleId,
    Severity severity,
    String componentName,        // null for general/global findings
    ParadigmTag paradigm,        // null for general/global findings
    String message
) { ... }

public enum ApplyOutcome { ADDED, PATCHED, REMOVED, REJECTED, UNCHANGED }
```

The build report is returned from:

- `Inqudium.configure()...build()` — full initialization. On error, the build either throws a
  `ConfigurationException` carrying the report or returns a runtime in error state, depending on configured
  policy (see "Strict mode" below).
- `runtime.update(...)` — runtime mutation. Always returns a report. Errors abort the affected components but
  the runtime continues to exist.
- `runtime.dryRun(...)` — produces a report without applying any changes.

#### Strict mode

Two configurable policies govern how findings translate into outcomes:

```java
Inqudium.configure()
    .strict()                        // warnings → errors
    .failFast()                      // first error aborts; default: collect all
    ...
```

- `strict()` elevates `WARNING` to `ERROR`. Recommended for tests and CI builds.
- `failFast()` aborts on the first error; default is to collect all errors and report them together. The
  default supports the common "show me everything wrong" workflow; fail-fast suits scripted use cases.

#### Collected vs. fail-fast

The default is *collected*: validation runs to completion, gathering all findings, then reports them at once.
This is essential for usability — if a configuration has five errors, the user wants to see all five, not
fix-rebuild-fix-rebuild.

The collection is bounded by component: an error in component A's snapshot build does not prevent component
B's snapshot from being built. Errors that abort the whole process (paradigm module missing, framework
bootstrap failure) are not collectible and surface immediately.

### Where the validation code lives

| Layer                          | Module               | Code                                                                |
|--------------------------------|----------------------|---------------------------------------------------------------------|
| Class 1 (argument range)       | `inqudium-config`    | DSL setters in builder interfaces                                   |
| Class 2 (snapshot invariants)  | `inqudium-config`    | Snapshot record compact constructors                                |
| Class 3 — Strategy A           | `inqudium-config`    | Builder base class with `guardPresetOrdering()`                     |
| Class 3 — Strategy B           | `inqudium-config`    | Sub-builder classes with `guardSingleChoice()`                      |
| Class 3 — Strategy C           | `inqudium-config`    | `ConsistencyRule` SPI; built-in rules in `inqudium-config`,         |
|                                |                      | extensible via ServiceLoader from any module                        |
| Class 4 (cross-component)      | `inqudium-config`    | `CrossComponentRule` SPI; built-in rules in `inqudium-config`,      |
|                                |                      | extensible via ServiceLoader; invoked by `runtime.diagnose()`       |

Paradigm modules contribute paradigm-specific consistency rules (e.g., a reactive-bulkhead rule about
schedulers) by registering them via ServiceLoader. They do not own the validation framework; they own their
contributions to it.

### Worked example

A configuration that exercises all four classes:

```java
InqRuntime runtime = Inqudium.configure()
    .strict()
    .imperative(im -> im
        .bulkhead("payments", b -> b
            .protective()
            .maxConcurrentCalls(-5)              // CLASS 1: throws IllegalArgumentException immediately
            .maxWaitDuration(Duration.ofMinutes(5))   // CLASS 3: protective + long wait → WARNING (→ ERROR in strict)
        )
        .circuitBreaker("payments", cb -> cb
            .slidingWindow(10)
            .failureThreshold(0.05)              // CLASS 3: 5% of 10 calls is meaningless → WARNING
        )
        .retry("payments", r -> r
            .maxAttempts(5)                      // CLASS 4 with bulkhead: retry burst can fill bulkhead
        )
    )
    .build();
```

Outcomes:

1. The negative `maxConcurrentCalls(-5)` throws `IllegalArgumentException` *at the setter*. The remainder of
   the configuration is never reached. This is class 1 — fail at the call site.

2. If the user fixes that and the build proceeds, the snapshot record's compact constructor double-checks
   (class 2). For this example, no class-2 violations remain.

3. The build then runs class-3 consistency rules. The `BULKHEAD_PROTECTIVE_WITH_LONG_WAIT` rule fires; the
   `CIRCUITBREAKER_FAILURE_THRESHOLD_TOO_LOW_FOR_WINDOW` rule fires. Both are warnings by default. Because the
   user wrote `strict()`, both elevate to errors. The build aborts with a `ConfigurationException` carrying a
   `BuildReport` with both findings.

4. If the user removes `strict()`, the warnings are reported but the build succeeds. The class-4 rule
   (`RETRY_BURST_CAN_FILL_BULKHEAD`) does not fire automatically; only `runtime.diagnose()` triggers it. When
   invoked, it returns a `DiagnosticFinding` describing the interaction and recommending either reducing
   `maxAttempts` or increasing `maxConcurrentCalls`.

The user gets a layered, predictable failure surface: fast feedback for trivial errors, structured reporting
for substantive issues, opt-in deep diagnosis for system-level concerns.

## Consequences

**Positive:**

- Errors fail at the earliest layer that has full information about them. No deferred or ambiguous failures.
- Validation is deduplicated. Each kind of check has exactly one home: setter, compact constructor, build
  rule, or diagnostic rule.
- Records are unconditionally trustworthy. A `BulkheadSnapshot` instance, however constructed, has been
  validated.
- Format adapters (YAML, JSON, ...) inherit all validation by going through the same patch → snapshot path.
  No re-implementation, no drift.
- Collected reporting (the default) shows all errors at once, supporting the natural "fix everything, then
  rebuild" workflow.
- Strict mode cleanly separates lenient (production-tolerant) from strict (CI-tolerant) policies without code
  duplication.
- The rule SPIs (`ConsistencyRule`, `CrossComponentRule`) provide extension points without exposing the
  framework's internals. Custom rules are first-class.
- `BuildReport` and `DiagnosisReport` are structured outputs: machine-readable for tooling, human-readable
  via convenience methods.
- Class-4 diagnostics are opt-in, avoiding surprising auto-runs of expensive checks during routine
  configuration changes.

**Negative:**

- Class-1 checks duplicate (deliberately) into class-2 compact constructors. This is intentional but adds
  repetition. Code generation would compress it; out of scope for this ADR.
- Class-3 strategy A (preset ordering) constrains the DSL: presets must come first. This is a convention,
  not enforced by the type system, and lambda-DSLs cannot impose call order. Violations are caught at
  runtime.
- Class-3 rules require snapshots to remember `derivedFromPreset` (a nullable string). Small storage
  overhead; documented but not free.
- Class-4 diagnostics are opt-in, which means users who never call `runtime.diagnose()` never see them.
  Mitigation: documentation strongly recommends running `diagnose()` in CI/CD pipelines and as part of
  health-check endpoints.
- Custom rules can introduce inconsistent severity or message conventions. No central editorial review.
  Ameliorated by stable rule IDs that allow per-rule severity overrides.

**Neutral:**

- The four-class taxonomy is opinionated. Some validations could plausibly belong to multiple classes (a
  `Duration.ZERO` for `maxWaitDuration` is a class-1 boundary value but also a class-3 semantic concern in
  combination with `permissive` preset). The classification rule of thumb: pick the lowest class that has
  enough information to detect the issue.
- Severities are coarse-grained (`ERROR`, `WARNING`, `INFO`). A finer scale (e.g., `CRITICAL`, `MAJOR`,
  `MINOR`) was rejected as adding ceremony without practical benefit; users can subdivide via rule IDs.
- The validation framework's failure modes are exception-based for class 1 and report-based for classes
  2–4. The asymmetry reflects the difference between "user error at call site" (exception, immediate) and
  "structured error in configuration object" (report, collected).
- Cross-component rules can contradict each other or recommend opposite changes. The framework does not
  attempt to reconcile contradictory advice; it surfaces all findings and trusts the operator.
