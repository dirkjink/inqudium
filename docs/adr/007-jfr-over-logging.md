# ADR-007: JFR events over log-based tracing

**Status:** Accepted  
**Date:** 2026-03-22  
**Last updated:** 2026-03-23  
**Deciders:** Core team

## Context

Resilience elements generate high-frequency operational data: every call through a circuit breaker, every retry attempt, every rate limiter decision. Capturing this data in production is essential for diagnosing issues — but the capture mechanism must not become a problem itself.

Common approaches:

1. **SLF4J / structured logging** — Widely adopted, but writing a log line per resilience event generates significant I/O at high throughput. Log aggregation pipelines (ELK, Loki) incur cost proportional to volume. Filtering and correlating resilience events requires parsing unstructured or semi-structured text.

2. **Micrometer metrics** — Excellent for aggregated data (counters, gauges, histograms) but loses individual event granularity. You know the retry rate is 12%, but you can't drill into which specific call caused the 5th retry at 14:03:27.

3. **Java Flight Recorder (JFR)** — Structured events recorded into a ring buffer with near-zero overhead when not actively recording. Events carry typed fields (duration, state, attempt number) without parsing. Analyzable in JDK Mission Control, async-profiler, or programmatically via the JFR event streaming API.

## Decision

We provide `inqudium-jfr` as a first-class observability module alongside `inqudium-micrometer`. JFR serves as the **per-event detail layer**; Micrometer serves as the **aggregated metrics layer**. They are complementary, not competing.

### JFR event design

Each resilience interaction maps to a JFR event class annotated with `@Name` and `@Label`:

| JFR Event                               | Triggers on                        | Key fields                              |
|-----------------------------------------|------------------------------------|-----------------------------------------|
| `eu.inqudium.CircuitBreakerCall`        | Every call through a breaker       | duration, success/failure, state        |
| `eu.inqudium.CircuitBreakerStateTransition` | State change (CLOSED→OPEN etc.) | fromState, toState, failureRate         |
| `eu.inqudium.RetryAttempt`              | Each retry attempt                 | attemptNumber, waitDuration, exception  |
| `eu.inqudium.RateLimiterAcquire`        | Permit acquisition attempt         | waitDuration, permitted (boolean)       |
| `eu.inqudium.BulkheadAcquire`           | Concurrency slot acquisition       | waitDuration, concurrentCallCount       |
| `eu.inqudium.TimeLimiterTimeout`        | Timeout triggered                  | configuredDuration, actualDuration      |

Every JFR event also carries `callId` and `elementName` — mapped from the corresponding `InqEvent` fields (ADR-003). The `callId` enables end-to-end correlation of a single call across all resilience elements in a JFR recording. In JDK Mission Control, filtering by `callId` reconstructs the complete lifecycle of one call through the entire pipeline.

Events extend `jdk.jfr.Event` and use `@Threshold("0 ms")` for events that should always be captured when recording is active.

### Binding mechanism

JFR events are emitted by binder classes that subscribe to `InqEventPublisher` (ADR-003). The binder translates `InqEvent` instances to JFR events:

```java
InqJfrRegistry.bindTo(circuitBreaker, retry, rateLimiter);
```

This is opt-in. If `inqudium-jfr` is not on the classpath, no JFR events are emitted — no bytecode enhancement, no agent, no reflection.

### Why not just logging?

| Aspect                | SLF4J logging                      | JFR events                              |
|-----------------------|------------------------------------|-----------------------------------------|
| Overhead when idle    | Zero (if level filtered)           | Zero (if not recording)                 |
| Overhead when active  | I/O per event, string formatting   | Ring buffer write, no I/O until dump    |
| Structure             | Semi-structured text               | Typed fields, queryable                 |
| Correlation           | Requires log parsing               | Native event timeline in JMC            |
| Production safe       | Risk of log flood at high volume   | Fixed ring buffer, old events evicted   |
| Cost                  | Log storage scales with volume     | Local file, fixed size                  |

### Relationship to InqEventPublisher (ADR-003)

JFR events are **not** the primary event system of Inqudium — `InqEventPublisher` (ADR-003) is. The two type hierarchies are fully independent:

- `InqEvent` (ADR-003) — lightweight POJOs in `inqudium-core`, always emitted, zero external dependencies.
- `jdk.jfr.Event` subclasses (this ADR) — JFR-annotated classes in `inqudium-jfr`, only instantiated when the module is on the classpath.

`InqEvent` does **not** extend `jdk.jfr.Event`. Merging them would force JFR's class lifecycle (`begin()`/`end()`/`commit()`, `@Name`/`@Label` annotations) onto `inqudium-core` — coupling the core event model to a specific observability backend.

Instead, the JFR binder acts as a **translation layer**:

```
InqEventPublisher                        JFR subsystem
       │                                       │
  emits InqEvent ──► JFR Binder ──► creates jdk.jfr.Event
                     (subscribes)           (commits)
```

Concretely, the binder subscribes to `InqEventPublisher.onEvent(...)` and for each `InqEvent` it receives:

1. Creates the corresponding `jdk.jfr.Event` subclass
2. Maps fields one-to-one (`event.getFromState()` → `jfrEvent.fromState`)
3. Calls `jfrEvent.commit()`

If no JFR recording is active, `commit()` is a no-op — the JVM optimizes the entire code path away. If `inqudium-jfr` is not on the classpath at all, `InqEventPublisher` still emits its events normally — it has no awareness of JFR.

This same pattern applies to `inqudium-micrometer`: it subscribes to `InqEventPublisher` and translates events into `Counter.increment()` / `Timer.record()` calls. Both are independent consumers of the same canonical event stream.

See ADR-003 for the full event type hierarchy and publisher contract.

### What inqudium-jfr does NOT do

- It does not replace Micrometer. Dashboarding and alerting remain Micrometer's domain.
- It does not provide distributed tracing. For cross-service correlation, use OpenTelemetry or Micrometer Tracing.
- It is safe to leave enabled permanently. When no recording is active, `commit()` is a JVM no-op with near-zero overhead — the binder stays subscribed, but the events cost practically nothing until someone starts a recording.

## Consequences

**Positive:**
- Production-safe detail capture without log volume concerns.
- Structured events enable root-cause analysis: "show me all circuit breaker calls that failed between 14:00 and 14:05 and took longer than 500ms" — a single JFR query.
- Complementary to Micrometer: metrics for dashboards, JFR for deep-dives.
- Zero overhead when not recording — the JFR infrastructure in the JVM handles this.

**Negative:**
- JFR is JVM-specific — not useful for non-JVM consumers (though this is not a concern for a Java/Kotlin library).
- JFR tooling (JDK Mission Control) is less widely adopted than Grafana/Prometheus. Teams need to learn JMC or use async-profiler's JFR output.
- Additional module to maintain, though the JFR binder code is thin (it translates events, no business logic).

**Neutral:**
- JFR events are available from Java 11+ (non-commercial since AdoptOpenJDK). Java 21+ is our minimum, so there are no compatibility concerns.
