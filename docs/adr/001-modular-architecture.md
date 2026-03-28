# ADR-001: Modular architecture

**Status:** Accepted  
**Date:** 2026-03-22  
**Last updated:** 2026-03-23  
**Deciders:** Core team

## Context

Resilience libraries tend to ship as a single artifact or a small set of large modules. This forces consumers to pull in code they don't need — a project that only wants a Circuit Breaker still gets Rate Limiter, Bulkhead, and Cache on the classpath.

We need a module strategy that balances granularity with practicality across two very different use cases: imperative Java projects that may only need one or two patterns, and reactive/coroutine projects that typically want the full set in a single paradigm.

## Decision

We adopt a **hybrid module strategy**:

### Imperative Java — fine-grained, one element per module

Each resilience element is its own Maven module with its own artifact:

- `inqudium-circuitbreaker`
- `inqudium-retry`
- `inqudium-ratelimiter`
- `inqudium-bulkhead`
- `inqudium-timelimiter`
- `inqudium-cache`

Each module depends only on `inqudium-core` and has zero transitive dependencies to other elements.

### Paradigm modules — one module per paradigm, all elements included

For Kotlin Coroutines, Project Reactor, and RxJava 3, all element implementations ship in a single module:

- `inqudium-kotlin` — all elements, native coroutine implementations
- `inqudium-reactor` — all elements, native Reactor implementations
- `inqudium-rxjava3` — all elements, native RxJava 3 implementations

The rationale: a project that adopts a reactive paradigm typically wants the full resilience toolkit in that paradigm. Splitting into `inqudium-kotlin-circuitbreaker`, `inqudium-kotlin-retry`, etc. would create 18+ additional modules with minimal real-world benefit.

### BOM for version alignment

`inqudium-bom` provides a Bill of Materials so that consumers can align versions across modules without specifying individual versions.

### Java Platform Module System (JPMS)

Every Inqudium module ships with a `module-info.java` that declares its exports and dependencies. This is a deliberate investment that pays off in three ways:

1. **Strong encapsulation.** The `internal` packages (e.g. `eu.inqudium.circuitbreaker.internal`) are not exported. JPMS enforces this at compile time and runtime — not just by convention, but by the module system itself. Consumers cannot accidentally depend on internal state machine classes, even via reflection.

2. **Explicit dependency graph.** Each module declares exactly what it `requires`. `inqudium-circuitbreaker` requires `inqudium.core` and nothing else. This makes the dependency graph verifiable by the compiler and visible in tools like `jdeps`.

3. **Ecosystem alignment.** Spring Boot 3, Project Reactor, and Kotlin all ship with JPMS module descriptors. Inqudium modules integrate cleanly into modular applications without split-package problems or `--add-opens` workarounds.

Example `module-info.java` for `inqudium-circuitbreaker`:

```java
module inqudium.circuitbreaker {
    requires inqudium.core;

    exports eu.inqudium.circuitbreaker;
    exports eu.inqudium.circuitbreaker.event;
    // eu.inqudium.circuitbreaker.internal is NOT exported
}
```

For modules that need reflective access (e.g. `inqudium-spring-boot3` for annotation processing), targeted `opens` directives are used instead of blanket `opens` to the entire world.

### OSGi — explicitly out of scope

OSGi provides a dynamic module system with runtime bundle management, versioned package imports/exports, and service registries. While powerful, it introduces significant complexity that is not justified for Inqudium:

- **Bundle metadata maintenance.** Every module would need a `MANIFEST.MF` with `Import-Package`, `Export-Package`, and version ranges — maintained in parallel to JPMS module descriptors and Maven dependency declarations. Three systems describing the same dependency graph.
- **Runtime dynamism not needed.** Inqudium elements are configured at startup and remain stable for the application's lifetime. There is no use case for hot-swapping a Circuit Breaker implementation at runtime or loading a Rate Limiter bundle on demand.
- **Shrinking ecosystem relevance.** OSGi adoption in new Java projects has declined significantly since JPMS became production-ready in Java 11+. The target audience for Inqudium — Spring Boot, Quarkus, Micronaut applications — overwhelmingly uses flat classpaths or JPMS, not OSGi containers.
- **Testing burden.** OSGi-correct behavior requires integration tests in an OSGi container (Felix, Equinox). This doubles the CI matrix without serving the primary user base.

If a consumer runs Inqudium inside an OSGi container (e.g. Eclipse RCP, Apache Karaf), the JARs will work as regular bundles on the classpath. We do not prevent OSGi usage — we simply do not invest in OSGi-specific metadata or testing.

Should significant demand for OSGi support materialize (tracked via GitHub Issues), the appropriate response would be a community-maintained `inqudium-osgi` module that generates bundle manifests — not OSGi metadata in every core module.

## Consequences

**Positive:**
- Imperative Java consumers pay only for what they use — minimal classpath.
- Reactive/coroutine consumers get a single dependency for their paradigm.
- BOM ensures version consistency across multi-module adoption.
- Each imperative module can be versioned and released independently if needed.
- JPMS module descriptors enforce encapsulation of `internal` packages at the language level — not just by naming convention.
- Clean integration into modular Java applications without `--add-opens` flags.

**Negative:**
- More modules to maintain than a monolithic approach.
- Paradigm modules are larger artifacts since they bundle all elements.
- Build configuration (parent POM, plugin management) has higher upfront complexity.
- JPMS module descriptors must be kept in sync with the actual package structure. A new public package requires updating `module-info.java` — easy to forget.
- Mixed Kotlin/Java modules require care in `module-info.java` authoring, since Kotlin compiles to the same packages but may produce synthetic classes that need to be accounted for.

**Neutral:**
- `inqudium-core` becomes the only transitive dependency shared by all modules. It must remain minimal and stable.
- OSGi is not supported but not blocked. JARs work in OSGi containers as regular classpath bundles.
