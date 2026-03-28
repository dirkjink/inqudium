# Compatibility Flags

When Inqudium makes a behavioral change (e.g., a corrected algorithm boundary), the change is gated by a flag. The old behavior is preserved by default. You opt in to new behavior explicitly.

## Quick start

Adopt all new behaviors (recommended for new projects):

```java
var config = CircuitBreakerConfig.builder()
    .compatibility(InqCompatibility.adoptAll())
    .build();
```

Preserve all old behaviors (for stability during upgrade):

```java
.compatibility(InqCompatibility.preserveAll())
```

Fine-grained control:

```java
.compatibility(InqCompatibility.builder()
    .flag(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE, true)
    .build())
```

## Code-free configuration

Implement `InqCompatibilityOptions` and register via `ServiceLoader`:

```java
public class CompanyDefaults implements InqCompatibilityOptions {
    @Override
    public Map<InqFlag, Boolean> flags() {
        return Map.of(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE, true);
    }
}
```

Register in `META-INF/services/eu.inqudium.core.compatibility.InqCompatibilityOptions`.

## Resolution order

Three layers, lowest to highest priority:

1. Built-in defaults (all flags `false`)
2. `ServiceLoader` providers (`InqCompatibilityOptions`, Comparable-sorted)
3. Programmatic API (`.compatibility()` on the config builder)

Programmatic flags override ServiceLoader flags per-flag, not wholesale. To ignore ServiceLoader entirely:

```java
.compatibility(InqCompatibility.builder()
    .ignoreServiceLoader()
    .flag(InqFlag.SOME_FLAG, true)
    .build())
```

## Flag lifecycle

1. **Introduced** — Default = `false`. Old behavior preserved.
2. **Next minor** — Default flipped to `true`. New behavior is standard.
3. **Next major** — Flag removed. Enum constant deleted → compile error guides migration.
