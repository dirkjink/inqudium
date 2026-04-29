# ADR-012: Timeout value hierarchy

**Status:** Accepted  
**Date:** 2026-03-23  
**Last updated:** 2026-03-23  
**Deciders:** Core team

## Context

In a typical resilience pipeline, multiple layers enforce time boundaries simultaneously:

```
Application
  → TimeLimiter (Inqudium)         timeout: ???
    → Circuit Breaker (Inqudium)   slowCallDurationThreshold: ???
      → HTTP Client (Netty/etc.)   connectTimeout: ???
                                   responseTimeout: ???
                                   readTimeout: ???
        → Network / Service
```

Each layer has its own timeout configuration. If these values are not carefully coordinated, the system exhibits
confusing, counterproductive, or dangerous behavior.

### The two roles: specialist and safety net

HTTP client timeouts (Netty, OkHttp, Apache HttpClient) and the TimeLimiter serve fundamentally different purposes:

**HTTP client timeouts are the fast specialists.** They detect concrete, specific network problems:

- `connectTimeout` — the target host is unreachable or the TCP handshake stalls
- `responseTimeout` / `readTimeout` — the service accepted the connection but does not respond (processing stall,
  deadlock, GC pause)
- `writeTimeout` — the network path is congested and the request cannot be sent

Each timeout catches a specific failure mode, produces a specific exception, and — critically — **releases the HTTP
connection back to the pool immediately.** This is the key property: the specialist cleans up the resource it manages.

**The TimeLimiter is the safety net.** It does not know what the protected call does internally. It does not know
whether the call is an HTTP request, a database query, a file system operation, or a sequence of all three. It
guarantees one thing: the caller will not wait longer than the configured duration, regardless of what happens inside.

The safety net catches everything the specialists miss:

- HTTP client misconfigured without timeouts (common in legacy code)
- Non-HTTP operations that have no timeout mechanism (file I/O, computation)
- Multi-step operations where each step is within its timeout but the total duration exceeds the budget
- Third-party libraries that swallow timeouts internally

### What goes wrong in practice

**TimeLimiter < HTTP client responseTimeout**

The TimeLimiter fires after 3 seconds. The caller receives `InqTimeLimitExceededException` and moves on. But the HTTP
client is still waiting — its responseTimeout is 10 seconds. The HTTP connection stays open for another 7 seconds,
consuming a slot in the connection pool. Under sustained load, the connection pool exhausts, and requests that would
have succeeded fast start failing because they cannot acquire a connection.

The orphaned operation (ADR-010) is not the problem here — the problem is that the safety net triggered before the
specialist, and the specialist's resource (HTTP connection) was not released.

**TimeLimiter = HTTP client responseTimeout**

A race condition. Sometimes the HTTP client times out first and throws `java.util.concurrent.TimeoutException`.
Sometimes the TimeLimiter fires first and throws `InqTimeLimitExceededException`. The application sees two different
exception types for the same root cause (slow service), making error handling unpredictable and dashboards noisy. This
configuration is **never correct** — nondeterministic behavior from identical root causes is a design flaw.

**TimeLimiter >> HTTP client responseTimeout (much larger)**

The TimeLimiter is effectively dormant for normal HTTP calls. This is actually fine — the specialist does its job, and
the safety net is there for the edge cases. The problem is only when the margin is so large that the TimeLimiter
provides no meaningful protection (e.g., HTTP timeout 5s, TimeLimiter 300s).

**Circuit Breaker slowCallDurationThreshold < TimeLimiter timeout**

The Circuit Breaker records calls as "slow" after 2 seconds. The TimeLimiter fires after 5 seconds. Calls that take 3
seconds are recorded as slow by the Circuit Breaker but complete successfully from the caller's perspective. If enough
3-second calls occur, the Circuit Breaker opens — even though no calls actually timed out. The application team sees a
circuit breaker opening "for no reason."

## Decision

### The timeout ordering rule

```
connectTimeout  <  responseTimeout  <  TimeLimiter timeout  ≤  slowCallDurationThreshold
   (specialist)     (specialist)        (safety net)             (recording threshold)
```

The specialists should fire first under normal circumstances. The safety net sits above them and only activates in
exceptional situations. The Circuit Breaker's slow call threshold should be at or above the TimeLimiter timeout — a call
is only "slow" if it reached the safety net's limit.

In no case should two layers share the same value. Equal values produce nondeterministic behavior — the exception type,
the cleanup path, and the metrics attribution all depend on which layer wins the race.

### Calculating the TimeLimiter timeout: RSS, not addition

#### The naive approach: worst-case addition

The intuitive calculation is to sum all possible delays:

```
TimeLimiter timeout = connectTimeout + responseTimeout + processingMargin
                    = 2s + 5s + 1s = 8s
```

This assumes that every delay simultaneously hits its maximum value. It is the statistical equivalent of assuming that
every screw in an assembly simultaneously reaches its maximum manufacturing tolerance. The result is an overly
conservative timeout that is almost never reached — it provides little practical protection because it sits so far above
normal behavior.

#### The correct approach: Root Sum of Squares (RSS)

When multiple independent durations each have a tolerance (variability), the combined tolerance is not their linear sum
but their **quadratic sum**:

```
RSS = √(t₁² + t₂² + t₃² + ... + tₙ²)
```

This comes from statistical tolerance analysis in engineering. Each timeout value has a nominal (expected) duration and
a tolerance (the range of variation). If the variations are independent — which they typically are (connect latency is
independent of response latency) — the probability that all simultaneously reach their maximum is vanishingly small.

#### Concrete example

A payment service call has three independent duration components:

| Component        | Nominal | Tolerance (± variation)              |
|------------------|---------|--------------------------------------|
| TCP connect      | 50ms    | ±200ms (connect timeout is 250ms)    |
| TLS handshake    | 100ms   | ±150ms                               |
| Service response | 800ms   | ±2000ms (response timeout is 2800ms) |

**Worst-case addition:**

```
Total max = 250ms + 250ms + 2800ms = 3300ms → TimeLimiter at ~3.5s
```

**RSS method:**

```
Nominal total = 50 + 100 + 800 = 950ms

RSS tolerance = √(200² + 150² + 2000²)
              = √(40000 + 22500 + 4000000)
              = √4062500
              ≈ 2016ms

TimeLimiter timeout = nominal + RSS tolerance = 950ms + 2016ms ≈ 3000ms → TimeLimiter at 3s
```

The RSS-derived timeout (3s) is tighter than the worst-case sum (3.5s) but still covers the realistic range of combined
variations. It provides meaningful protection without being so loose that it never fires.

#### When to use RSS vs. addition

- **Independent delays** (connect, TLS, response) → RSS. This is the common case.
- **Sequential dependent delays** (retry attempt 1 → retry attempt 2 → retry attempt 3, where each attempt has the same
  timeout) → Addition. Retries are not independent — each attempt adds its full timeout to the total.
- **Mixed** (pipeline with independent network layers but sequential retries) → RSS for the network layers, addition for
  the retries, then combine.

### Retry interaction

When Retry is in the pipeline, the position of the TimeLimiter changes the semantics:

#### TimeLimiter inside Retry (each attempt individually limited)

```
Call → Retry → TimeLimiter → Circuit Breaker → HTTP Client
       3 attempts  3s each     records result
```

Each attempt gets a fresh timeout budget. Total worst case: `3s × 3 = 9s`. The RSS method applies to each individual
attempt (deriving the 3s from the HTTP layer), but the attempts themselves are sequential — they add linearly.

#### TimeLimiter outside Retry (entire sequence limited)

```
Call → TimeLimiter → Retry → Circuit Breaker → HTTP Client
       8s total      3 attempts  records result
```

The entire retry sequence shares one timeout budget. If the first attempt takes 5s and fails, the second attempt has
only 3s remaining. The third attempt may have almost no time left.

Both configurations are valid for different use cases. The first protects per-attempt. The second protects total caller
wait time. The documentation must make this distinction explicit, because it determines which RSS calculation applies.

### Central configuration derivation

Maintaining timeout values at two levels (HTTP client and TimeLimiter) manually and keeping them synchronized is
error-prone. A change in the HTTP client timeout should propagate to the TimeLimiter — but in practice, one gets updated
and the other is forgotten.

#### The `InqTimeoutProfile`

Inqudium provides a `InqTimeoutProfile` as a configuration helper that derives both layers from a single source of
truth:

```java
var profile = InqTimeoutProfile.builder()
    .connectTimeout(Duration.ofMillis(250))
    .responseTimeout(Duration.ofSeconds(3))
    .method(TimeoutCalculation.RSS)       // or WORST_CASE
    .safetyMarginFactor(1.2)              // 20% above RSS result
    .build();

// Derive values
Duration timeLimiterTimeout = profile.timeLimiterTimeout();        // computed via RSS + margin
Duration slowCallThreshold  = profile.slowCallDurationThreshold(); // aligned with timeLimiter
```

The profile computes the TimeLimiter timeout from the HTTP layer values using the chosen method (RSS or worst-case
addition) plus a configurable safety margin. This is **one place** where the timeout relationship is defined — changes
to HTTP timeouts automatically propagate to the derived values.

#### Technology-agnostic design

Inqudium does not configure HTTP clients. The `InqTimeoutProfile` is a **computation tool**, not an integration layer.
It takes timeout values as input and produces derived values as output. The application is responsible for applying the
input values to its HTTP client and the output values to its Inqudium elements:

```java
var profile = InqTimeoutProfile.builder()
    .connectTimeout(Duration.ofMillis(250))
    .responseTimeout(Duration.ofSeconds(3))
    .build();

// Apply to HTTP client — the application's responsibility
WebClient webClient = WebClient.builder()
    .clientConnector(new ReactorClientHttpConnector(
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                (int) profile.connectTimeout().toMillis())
            .responseTimeout(profile.responseTimeout())))
    .build();

// Apply to Inqudium elements — derived from the same profile
var timeLimiter = TimeLimiter.of("paymentService", TimeLimiterConfig.builder()
    .timeoutDuration(profile.timeLimiterTimeout())
    .build());

var circuitBreaker = CircuitBreaker.of("paymentService", CircuitBreakerConfig.builder()
    .slowCallDurationThreshold(profile.slowCallDurationThreshold())
    .build());
```

The profile is a pure computation — no reflection, no classpath scanning, no framework coupling. It works with Netty,
OkHttp, Apache HttpClient, or any other client.

#### Spring Boot integration

In `inqudium-spring-boot3`, the profile can be configured via YAML:

```yaml
inqudium:
  timeout-profiles:
    paymentService:
      connect-timeout: 250ms
      response-timeout: 3s
      method: RSS
      safety-margin-factor: 1.2
```

The auto-configuration reads the profile, computes the derived values, and applies them to the corresponding TimeLimiter
and Circuit Breaker instances. For the HTTP client, a configuration hint is logged at startup:

```
[Inqudium] Timeout profile 'paymentService': HTTP connect=250ms, response=3s →
           TimeLimiter=3.6s (RSS+20%), slowCallThreshold=3.6s
           Hint: Configure your HTTP client with connectTimeout=250ms, responseTimeout=3s
```

### Startup validation

When elements are created within a pipeline, Inqudium validates the timeout hierarchy and emits warnings:

```java
InqPipeline
    .of(paymentService::charge)
    .shield(timeLimiter)        // timeout: 3s
    .shield(circuitBreaker)     // slowCallDurationThreshold: 2s → WARNING
    .decorate();
```

Warning:

```
[Inqudium] Configuration warning: CircuitBreaker 'paymentService' has
slowCallDurationThreshold (2s) < TimeLimiter 'paymentService' timeout (3s).
Calls between 2s-3s will be recorded as slow but will complete successfully.
Consider setting slowCallDurationThreshold >= TimeLimiter timeout,
or use InqTimeoutProfile to derive consistent values.
```

The validation is a **warning**, not an error. There may be legitimate reasons for non-standard configurations. But the
default assumption is misconfiguration.

What Inqudium **cannot** validate: the HTTP client's timeout values. They live outside Inqudium's scope. The
`InqTimeoutProfile` and the startup hint mitigate this — but enforcement is the application's responsibility.

## Consequences

**Positive:**

- Clear conceptual model: specialists (HTTP timeouts) fire first, safety net (TimeLimiter) catches the rest.
- RSS method provides tighter, more realistic timeout budgets than naive worst-case addition.
- `InqTimeoutProfile` eliminates manual synchronization of timeout values across layers — one source of truth, derived
  values.
- Startup warnings catch the most common misconfiguration (slowCallDurationThreshold < TimeLimiter timeout) before it
  causes production incidents.
- Technology-agnostic: the profile computes values without coupling to any HTTP client library.
- Explicit documentation of Retry position semantics prevents the second most common timeout misconfiguration.

**Negative:**

- RSS requires understanding of statistical tolerance analysis. Not every developer is familiar with the concept. The
  documentation must explain it clearly with concrete examples.
- `InqTimeoutProfile` is a helper, not an enforcer. If the application applies the profile's `connectTimeout` to the
  HTTP client and then manually overrides the TimeLimiter timeout, the hierarchy can still be inconsistent.
- The startup validation is partial — it covers Inqudium-internal consistency but not the complete HTTP-to-TimeLimiter
  chain.

**Neutral:**

- The `InqTimeoutProfile` is optional. Applications can configure timeouts manually at each layer. The profile is a
  convenience for projects that want derivation from a single source.
- RSS vs. worst-case addition is a configuration choice per profile. For projects that prefer conservative timeouts,
  worst-case is available.
- The timeout hierarchy rule applies identically across all paradigms. The HTTP client timeout is always the innermost
  boundary, regardless of whether the call is imperative, reactive, or coroutine-based.
- The `slowCallDurationThreshold` feeds into the sliding window's slow-call classification (ADR-016). Calls exceeding
  this threshold are recorded as slow independently of whether they succeeded or failed.
