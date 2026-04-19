# Comprehensive Guide: Coordinating WebClient Timeouts with Resilience4j

## Table of Contents

### [Part I — Understanding What You Configure](#part-i--understanding-what-you-configure)

1. [Introduction](#introduction)
2. [The Two Timeout Layers](#the-two-timeout-layers)
    - [WebClient / Netty Timeouts (Network Layer)](#webclient-netty-timeouts-network-layer)
    - [Resilience4j TimeLimiter (Business Logic Layer)](#resilience4j-timelimiter-business-logic-layer)
3. [The Correct Coordination: Netty Inside, TimeLimiter Outside](#the-correct-coordination-netty-inside-timelimiter-outside)
4. [Consequences of Misconfigured Timeouts](#consequences-of-misconfigured-timeouts)
    - [TimeLimiter Too Short](#timelimiter-too-short)
    - [TimeLimiter Too Long](#timelimiter-too-long)
5. [Deep Dive: How ReadTimeoutHandler and WriteTimeoutHandler Actually Work](#deep-dive-how-readtimeouthandler-and-writetimeouthandler-actually-work)
    - [ReadTimeoutHandler](#readtimeouthandler)
    - [WriteTimeoutHandler](#writetimeouthandler)
    - [Irrelevance for GET-Only Adapters](#irrelevance-for-get-only-adapters)
6. [The TLS Handshake Gap](#the-tls-handshake-gap)
    - [What `CONNECT_TIMEOUT_MILLIS` Actually Covers](#what-connecttimeoutmillis-actually-covers)
    - [Where the TLS Handshake Falls in the Timeout Chain](#where-the-tls-handshake-falls-in-the-timeout-chain)
    - [Reactor Netty's TLS Handshake Timeout](#reactor-nettys-tls-handshake-timeout)
    - [In a Sidecar Setup](#in-a-sidecar-setup)
    - [Without a Sidecar](#without-a-sidecar)
    - [Corrected Timeout Chain and Configuration](#corrected-timeout-chain-and-configuration)
7. [Timeout Ratios: Rules of Thumb](#timeout-ratios-rules-of-thumb)
    - [What Each Phase Actually Measures](#what-each-phase-actually-measures)
    - [Rules of Thumb](#rules-of-thumb)
    - [Connect Timeout Depends on Network Path, Not Server Speed](#connect-timeout-depends-on-network-path-not-server-speed)
    - [Recommended Timeout Profiles](#recommended-timeout-profiles)
    - [Conceptually Clean Configuration: Grouping by Cause](#conceptually-clean-configuration-grouping-by-cause)
    - [Profile Presets with Individual Overrides](#profile-presets-with-individual-overrides)

### [Part II — Calculating the Values](#part-ii--calculating-the-values)

8. [Calculating the TimeLimiter Value](#calculating-the-timelimiter-value)
    - [Naive Approach: Worst-Case Addition](#naive-approach-worst-case-addition)
9. [Statistical Timeout Calculation: Root Sum of Squares (RSS)](#statistical-timeout-calculation-root-sum-of-squares-rss)
    - [The Problem with Worst-Case Addition](#the-problem-with-worst-case-addition)
    - [RSS Method from Manufacturing](#rss-method-from-manufacturing)
    - [Application to the Timeout Chain](#application-to-the-timeout-chain)
    - [Mathematical Foundation](#mathematical-foundation)
    - [Connection to the Pythagorean Theorem](#connection-to-the-pythagorean-theorem)
        - [The Angle as a Measure of Dependence](#the-angle-as-a-measure-of-dependence)
        - [Concrete Example: Effect of Correlation on Timeout Calculation](#concrete-example-effect-of-correlation-on-timeout-calculation)
    - [Sigma Levels](#sigma-levels)
        - [Concrete Example](#concrete-example)
    - [Choosing the Right Sigma Level per Adapter](#choosing-the-right-sigma-level-per-adapter)
    - [Implementation with Configurable Sigma Level](#implementation-with-configurable-sigma-level)
    - [Comparison of Methods](#comparison-of-methods)
    - [Assumptions and Limitations](#assumptions-and-limitations)
    - [Related Concepts](#related-concepts)

### [Part III — Integration and Implementation](#part-iii--integration-and-implementation)

10. [Resilience4j Aspect Order](#resilience4j-aspect-order)
    - [Impact of Bulkhead on TimeLimiter](#impact-of-bulkhead-on-timelimiter)
    - [Adjusted Calculation](#adjusted-calculation)
11. [CircuitBreaker Coordination](#circuitbreaker-coordination)
12. [Single Source of Truth: Centralized Configuration](#single-source-of-truth-centralized-configuration)
    - [Approach 1: Bottom-Up — Derive TimeLimiter from Individual Timeouts](#approach-1-bottom-up-derive-timelimiter-from-individual-timeouts)
    - [Approach 2: Top-Down — Derive Individual Timeouts from TimeLimiter](#approach-2-top-down-derive-individual-timeouts-from-timelimiter)
        - [Budget Calculation](#budget-calculation)
        - [Migration Formula](#migration-formula)
        - [Why No Read/Write TimeoutHandler](#why-no-readwrite-timeouthandler)
    - [Choosing the Right Approach](#choosing-the-right-approach)
    - [Configuration Properties](#configuration-properties)
    - [WebClient Factory](#webclient-factory)
    - [TimeLimiter Auto-Configuration via Customizer](#timelimiter-auto-configuration-via-customizer)
    - [Adapter Example](#adapter-example)
    - [Benefits of This Approach](#benefits-of-this-approach)

### [Part IV — Topology- and Client-Specific Variants](#part-iv--topology--and-client-specific-variants)

13. [Sidecar (Service Mesh) Implications](#sidecar-service-mesh-implications)
    - [Communication Path Change](#communication-path-change)
    - [Impact on Each Timeout](#impact-on-each-timeout)
    - [Visualized Shift](#visualized-shift)
    - [Configuration Comparison](#configuration-comparison)
    - [Impact on RSS Calculation](#impact-on-rss-calculation)
    - [Phase Variability: Sidecar vs. Real Network Connection](#phase-variability-sidecar-vs-real-network-connection)
    - [The Key Insight: Model Degeneration](#the-key-insight-model-degeneration)
    - [Configuring the Sidecar's Own Timeouts](#configuring-the-sidecars-own-timeouts)
        - [The Complete Timeout Chain](#the-complete-timeout-chain)
        - [Envoy/Istio Timeout Parameters](#envoyistio-timeout-parameters)
        - [Concrete Configuration Example](#concrete-configuration-example)
        - [Aligning Sidecar Timeouts with App Timeouts](#aligning-sidecar-timeouts-with-app-timeouts)
        - [Strategy A: Sidecar Shorter — Active Protection](#strategy-a-sidecar-shorter--active-protection)
        - [Strategy B: Sidecar Longer — Passthrough](#strategy-b-sidecar-longer--passthrough)
        - [Choosing Between the Strategies](#choosing-between-the-strategies)
        - [Retry Budget Considerations](#retry-budget-considerations)
        - [Important: The More Restrictive Timeout Wins](#important-the-more-restrictive-timeout-wins)
        - [Reference Documentation and the Documentation Gap](#reference-documentation-and-the-documentation-gap)
14. [RestClient: Synchronous / Blocking Differences](#restclient-synchronous-blocking-differences)
    - [Read Timeout (Socket Timeout)](#read-timeout-socket-timeout)
    - [Write Timeout](#write-timeout)
    - [Practical Recommendation](#practical-recommendation)
    - [TLS Handshake Coverage Depends on the Engine](#tls-handshake-coverage-depends-on-the-engine)
15. [Appendix: JVM HTTP Client Timeout Reference Tables](#appendix-jvm-http-client-timeout-reference-tables)
    - [Timeout Configuration Including Data Types & Units](#timeout-configuration-including-data-types-units)
    - [Agnostic HTTP Timeout Configuration Set](#agnostic-http-timeout-configuration-set)
    - [Mapping: Agnostic Configuration to JVM Clients](#mapping-agnostic-configuration-to-jvm-clients)

---

# Part I — Understanding What You Configure

## Introduction

When building microservice architectures with Spring WebClient and Resilience4j, two independent timeout layers exist: *
*network-level timeouts** (Netty/WebClient) and **business-logic-level timeouts** (Resilience4j TimeLimiter). If these
are not properly coordinated, the result is hard-to-diagnose behavior — either one layer is always redundant, or they
compete and produce confusing exception types.

This guide covers how to properly align these layers, calculate optimal timeout values statistically, and adapt the
configuration to different deployment topologies.

---

## The Two Timeout Layers

### WebClient / Netty Timeouts (Network Layer)

These timeouts operate at the network level. They detect specific problems in individual phases of HTTP communication:

- **`connectTimeout`** — The TCP handshake takes too long (server unreachable).
- **`responseTimeout`** — After sending the request, no first byte comes back (server hangs).
- **`readTimeout`** — Too much time passes between two received data packets (connection stalls).
- **`writeTimeout`** — Data cannot be sent quickly enough.

These timeouts produce network-specific exceptions (`ReadTimeoutException`, `ConnectTimeoutException`, etc.) that enable
precise error diagnosis.

### Resilience4j TimeLimiter (Business Logic Layer)

The TimeLimiter wraps the entire `CompletableFuture` and cancels it after a configured duration — regardless of *why* it
is taking so long. This could be network latency, but also Jackson deserialization, GC pauses, thread scheduling delays,
or an overloaded Tomcat thread pool delaying the continuation.

---

## The Correct Coordination: Netty Inside, TimeLimiter Outside

The fundamental principle is **inner timeouts shorter than outer timeouts**:

```
┌─────────────────────────────────────────────────┐
│ TimeLimiter (outer safety net)                  │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │ CompletableFuture                         │  │
│  │                                           │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │ WebClient Request                   │  │  │
│  │  │                                     │  │  │
│  │  │  connect ──► write ──► response     │  │  │
│  │  │              timeout    timeout     │  │  │
│  │  │                         ──► read    │  │  │
│  │  │                            timeout  │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  │  + Deserialization                        │  │
│  │  + Filter processing                      │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

**Netty timeouts handle the normal case** — they recognize a specific network problem quickly and precisely. **The
TimeLimiter is the safety net** — it catches everything that the Netty timeouts do not cover and guarantees a maximum
overall duration.

---

## Consequences of Misconfigured Timeouts

### TimeLimiter Too Short

Example: TimeLimiter at 2000ms with 2500ms Netty timeouts.

The TimeLimiter always fires first. Netty timeouts are never reached. Every timeout appears as a generic
`TimeoutException` from the TimeLimiter — the information about whether it was a connect, response, or read problem is
lost. Additionally, the `CompletableFuture` is cancelled, but the Netty request continues running in the background
until its own timeout triggers — wasting resources.

### TimeLimiter Too Long

Example: TimeLimiter at 30s with 2500ms Netty timeouts.

In most cases, Netty timeouts fire correctly. But in scenarios not covered by Netty timeouts (e.g., extremely slow
deserialization, or a configuration bug), the TimeLimiter waits unnecessarily long. The Tomcat thread remains bound for
30 seconds.

---

## Deep Dive: How ReadTimeoutHandler and WriteTimeoutHandler Actually Work

### ReadTimeoutHandler

Measures the **idle time** between two received data packets. It is not a timer per operation but a continuous timer
that resets with every received packet. If nothing arrives for the configured duration, it fires.

```
ReadTimeoutHandler:
  received ──── pause ──── received ──── pause ──── received
                ├─ idle ─┤              ├─ idle ─┤
                must not                must not
                > timeout               > timeout
```

**Important consequence:** This is **not** an absolute timeout for the entire HTTP request. If a server responds very
slowly but sends a small data packet every second continuously, a `ReadTimeoutHandler` set to 5 seconds will never
trigger, even if the entire download takes minutes.

### WriteTimeoutHandler

Measures the **completion time** of a single write operation. The timer starts when `write()` is called and stops when
the operation is complete (data handed to the TCP stack).

```
WriteTimeoutHandler:
  write(chunk) ─────── complete
  ├── must not > timeout ──┤
```

For large request bodies split into multiple writes, each individual write operation has its own timeout budget. The
total transfer duration could be `n × writeTimeout` without the handler firing.

### Irrelevance for GET-Only Adapters

All adapters using exclusively GET requests have no body. The only bytes written are the HTTP request line and headers —
typically under 1 KB. This is a single write operation that completes in under a millisecond. The WriteTimeoutHandler
has practically **no chance** to fire. It would only be relevant for adapters with POST/PUT and large request bodies.

**Consequence for RSS:** For GET adapters, the write timeout should **not** be included in the RSS formula.

---

## The TLS Handshake Gap

### What `CONNECT_TIMEOUT_MILLIS` Actually Covers

The Netty `ChannelOption.CONNECT_TIMEOUT_MILLIS` covers **exclusively** the TCP handshake:

```
TCP Connect          TLS Handshake                    HTTP
├─ SYN/SYN-ACK/ACK ─┤├─ ClientHello ──────────────┤├─ Request ─►
                      │  ServerHello                │
                      │  Certificate                │
                      │  Key Exchange               │
                      │  Certificate Verify         │
                      │  Finished                   │
                      ├─────────────────────────────┤
                      │                             │
                      │ NOT covered by connect      │
                      │ timeout!                    │

├ connectTimeout ────┤├──────── ??? ───────────────┤├ responseTimeout...
```

### Where the TLS Handshake Falls in the Timeout Chain

The TLS handshake sits in a **gap** between the configured timeouts:

- **Connect timeout** — already completed, TCP is established.
- **Read/WriteTimeoutHandler** — registered in `doOnConnected`, which fires only **after** the complete connection
  establishment including TLS. They do not apply during the TLS handshake.
- **Response timeout** — counts only from the moment the HTTP request is fully sent. The TLS handshake happens before
  that.

The TLS handshake is therefore covered by **none of the four configured timeouts**.

### Reactor Netty's TLS Handshake Timeout

Reactor Netty has a **dedicated** configuration point:

```kotlin
HttpClient.create()
    .secure { spec ->
        spec.handshakeTimeout(Duration.ofMillis(3000))
    }
```

The default in Reactor Netty is **10 seconds**. If `secure()` is never called, this default applies — meaning a hanging
TLS handshake can block for 10 seconds unnoticed while all other timeouts are set to 2500ms.

### In a Sidecar Setup

The problem is mitigated: the connection between app and sidecar is typically **plain HTTP** over localhost. The TLS
handshake happens between sidecar and upstream — and from the app's perspective, it is absorbed into the response
timeout.

### Without a Sidecar

This is a real gap. A TLS 1.3 handshake requires at least **one** additional round-trip (TLS 1.2 requires two):

```
Network Path             TCP Connect    TLS Handshake (TLS 1.3)

Same DC                    ~1ms            ~2ms
Remote DC                  ~20ms           ~40ms (1 RTT)
Internet transatlantic     ~100ms          ~200ms (1 RTT)
Internet + TLS 1.2         ~100ms          ~400ms (2 RTTs)
```

### Corrected Timeout Chain and Configuration

The complete sequential chain for an HTTP request without a sidecar:

```
connect → TLS handshake → write request → response → read
```

The TLS handshake must be included in both the timeout configuration and the RSS calculation:

```kotlin
data class AdapterTimeoutConfig(
    val connectTimeoutMs: Int = 500,
    val tlsHandshakeTimeoutMs: Int = 2000,
    val responseTimeoutMs: Int = 3000,
    val readTimeoutMs: Int = 2000,
    val writeTimeoutMs: Int = 500,
    // ...
) {
    val timeLimiterTimeoutMs: Long
        get() {
            val rss = sqrt(
                connectTimeoutMs.toDouble().pow(2)
                        + tlsHandshakeTimeoutMs.toDouble().pow(2)
                        + responseTimeoutMs.toDouble().pow(2)
                        + readTimeoutMs.toDouble().pow(2)
            )
            val sigmaAdjusted = rss * sigmaLevel / 3.0
            return (sigmaAdjusted + bulkheadMaxWaitMs + timeLimiterBufferMs).toLong()
        }
}
```

WebClient factory with explicit TLS timeout:

```kotlin
HttpClient.create()
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMs)
    .secure { spec ->
        spec.handshakeTimeout(
            Duration.ofMillis(config.tlsHandshakeTimeoutMs.toLong())
        )
    }
    .responseTimeout(Duration.ofMillis(config.responseTimeoutMs.toLong()))
    .doOnConnected { conn ->
        conn.addHandlerLast(ReadTimeoutHandler(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS))
        conn.addHandlerLast(WriteTimeoutHandler(config.writeTimeoutMs.toLong(), TimeUnit.MILLISECONDS))
    }
```

---

## Timeout Ratios: Rules of Thumb

Setting all four timeouts to the same value (e.g., 2500ms each) indicates that the different characteristics of each
phase have not been considered. Each timeout measures a fundamentally different phase.

### What Each Phase Actually Measures

```
Client                          Network                          Server

  ├── writeTimeout ──►  [ request bytes in transit ]
  │
  │                                                    ├── Server processes
  │                     [ first response bytes ]  ◄────┤
  │◄── responseTimeout ─┘
  │                                                    ├── Server streams data
  │                     [ further data packets ]  ◄────┤
  │◄── readTimeout ────┘(between each two packets)
```

The `connectTimeout` precedes all of this — it measures only the TCP handshake (SYN → SYN-ACK → ACK).

### Rules of Thumb

**Connect timeout** should be the **shortest**. A TCP handshake within the same data center typically takes under 5ms,
between data centers under 50ms. If the handshake takes 2500ms, the server is unreachable — waiting longer changes
nothing.

**Response timeout** is typically the **longest** value. It measures the server's processing time — database queries,
business logic, potential downstream calls. This phase has the greatest natural variance. The response timeout is the *
*dominant timeout** around which the others are oriented.

**Read timeout** measures the pause **between** two data packets, not the total download duration. Once the server has
started responding, packets should flow relatively evenly. A long pause indicates hanging streaming or a broken
connection. Rule of thumb: **read = 1/2 to 2/3 of the response timeout**.

**Write timeout** is nearly irrelevant for GET requests — the request body is empty, only headers are sent. Even for
POST requests with JSON bodies, payloads are typically small enough to fit in a single TCP packet. Rule of thumb: *
*write = connect** (short, because little data).

### Connect Timeout Depends on Network Path, Not Server Speed

The connect timeout has **nothing** to do with the response timeout. They measure completely independent things. The
connect timeout should be based on **network round-trip time (RTT)** with a safety factor for variance and retransmits:

```
Connect timeout ≈ expected RTT × safety factor
```

```
Network Path                  Typical RTT    Connect Timeout

Same host / localhost            < 0.1ms          100ms
Same data center                 0.5–2ms          200–500ms
Remote DC (same cloud            10–50ms          500–1000ms
  provider, same region)
Remote DC (different DC,         50–150ms         1000–2000ms
  different company)
Over the internet                50–300ms         2000–3000ms
  (transatlantic)
```

The large jumps come from the fact that a SYN retransmit on packet loss typically takes 1 second. A connect timeout of
500ms within the same data center allows no retransmit — which is intentional, because packet loss within a DC indicates
a serious problem that should be detected quickly.

### Recommended Timeout Profiles

```
                    connect   response   read    write

Fast Service          500      1500      1000      500
(inventory,
 small payloads)

Standard Service     1000      3000      2000     1000
(customer service,
 moderate payloads)

Slow Service         1000      5000      3000     1000
(product catalog with
 search aggregation,
 large payloads)

External Service     2000      8000      5000     2000
(if applicable,
 over WAN/internet)
```

### Conceptually Clean Configuration: Grouping by Cause

Instead of a single profile per adapter, it is conceptually cleaner to group timeouts by their cause:

```yaml
# Network topology: same for all services in the same DC
network:
  datacenter-internal:
    connect-timeout-ms: 500
    write-timeout-ms: 500
  remote-datacenter:
    connect-timeout-ms: 1500
    write-timeout-ms: 1000
  external-internet:
    connect-timeout-ms: 3000
    write-timeout-ms: 2000

# Service behavior: individual per service
adapter:
  inventory:
    base-url: https://inventory.internal
    network: datacenter-internal
    response-timeout-ms: 1500    # fast stock lookups
    read-timeout-ms: 1000        # small payload

  product-catalog:
    base-url: https://product-catalog.internal
    network: datacenter-internal  # same DC, same connect
    response-timeout-ms: 5000    # slow aggregation
    read-timeout-ms: 3000        # larger payload

  authorization:
    base-url: https://authorization.internal
    network: datacenter-internal
    response-timeout-ms: 3000
    read-timeout-ms: 2000
```

This makes visible that `inventory` and `product-catalog` share the same connect timeout (same DC) but have completely
different response timeouts (different processing complexity).

### Profile Presets with Individual Overrides

```kotlin
enum class AdapterProfile(
    val connectTimeoutMs: Int,
    val responseTimeoutMs: Int,
    val readTimeoutMs: Int,
    val writeTimeoutMs: Int
) {
    FAST(500, 1500, 1000, 500),
    STANDARD(1000, 3000, 2000, 1000),
    SLOW(1000, 5000, 3000, 1000),
    EXTERNAL(2000, 8000, 5000, 2000);
}
```

```yaml
adapter:
  inventory:
    base-url: https://inventory.internal
    profile: FAST

  product-catalog:
    base-url: https://product-catalog.internal
    profile: SLOW

  authorization:
    base-url: https://authorization.internal
    profile: STANDARD
    timeouts:
      response-timeout-ms: 5000  # override only this
```

---

# Part II — Calculating the Values

## Calculating the TimeLimiter Value

### Naive Approach: Worst-Case Addition

The simplest way to calculate the TimeLimiter is to assume the **worst case** — every single timeout is exhausted to its
full length before the next phase begins. The maximum duration of a single WebClient call is then the sum of its phases.
For a GET request without a body:

```
Maximum Netty duration ≈ connectTimeout + responseTimeout + readTimeout
```

If all three are set to 2500ms, the theoretical maximum Netty duration is **7500ms** (worst case: each timeout is nearly
exhausted before the next phase begins).

The TimeLimiter should be **slightly more** than this sum, so that under normal circumstances the Netty timeouts fire
first (with their specific exceptions), and the TimeLimiter only serves as the last line of defense:

```
TimeLimiter = connectTimeout + responseTimeout + readTimeout + buffer
            = 2500 + 2500 + 2500 + 500
            = 8000ms
```

The buffer (~500ms) covers what happens after the Netty timeouts: deserialization, filter processing, thread scheduling,
and the overhead until the `CompletableFuture` completes.

**Important caveat:** This worst-case addition assumes that all timeouts are exhausted simultaneously — an extremely
unlikely scenario. It is the statistical equivalent of assuming every bolt in an assembly simultaneously hits its
maximum manufacturing tolerance. In practice, this leads to overly conservative TimeLimiter values that keep Tomcat
threads bound far longer than necessary. For a more realistic calculation based on statistical tolerance analysis,
see [Statistical Timeout Calculation: Root Sum of Squares (RSS)](#statistical-timeout-calculation-root-sum-of-squares-rss).

---

## Statistical Timeout Calculation: Root Sum of Squares (RSS)

### The Problem with Worst-Case Addition

In practice, it is extremely rare for all timeouts to be exhausted to their full length simultaneously. The worst-case
sum of all timeouts is overly conservative, like assuming that every bolt in an assembly simultaneously hits its maximum
manufacturing tolerance. Engineering solved this problem decades ago.

### RSS Method from Manufacturing

RSS (Root Sum of Squares) originates from mechanical engineering, where it has been used in mass production since the
1940s. The principle: when multiple independent quantities each have a tolerance, the total tolerance is not their sum
but their **quadratic sum**:

```
Worst Case:    T_total = T₁ + T₂ + T₃
Statistical:   T_total = √(T₁² + T₂² + T₃²)
```

The assumption is that individual phases **vary independently**. A slow TCP handshake says nothing about whether the
first response byte will also be slow — these can have different causes (network routing vs. backend load).

### Application to the Timeout Chain

With default values of 2500ms each:

```
Worst Case:  2500 + 2500 + 2500         = 7500ms
RSS:         √(2500² + 2500² + 2500²)   = √18,750,000 ≈ 4330ms
```

With different values, e.g., for a product catalog adapter:

```
connect=1000, response=5000, read=5000

Worst Case:  1000 + 5000 + 5000         = 11000ms
RSS:         √(1000² + 5000² + 5000²)   = √51,000,000 ≈ 7141ms
```

The RSS method yields values that are sufficient with approximately **99.73% probability (3σ)** when latencies are
normally distributed. In practice, this is a very conservative confidence interval.

### Mathematical Foundation

The basis is the **variance addition of independent random variables**. If X₁, X₂, X₃ are independent random variables:

```
Var(X₁ + X₂ + X₃) = Var(X₁) + Var(X₂) + Var(X₃)
```

The standard deviation (σ) is the square root of the variance:

```
σ_total = √(σ₁² + σ₂² + σ₃²)
```

In manufacturing, tolerances are typically set to 3σ (the 99.73% interval of the normal distribution). If T₁, T₂, T₃ are
each 3σ tolerances:

```
σ₁ = T₁/3,  σ₂ = T₂/3,  σ₃ = T₃/3

σ_total = √((T₁/3)² + (T₂/3)² + (T₃/3)²)
        = (1/3) × √(T₁² + T₂² + T₃²)

T_total (3σ) = 3 × σ_total = √(T₁² + T₂² + T₃²)
```

This is the RSS formula — derived directly from variance addition, scaled to the 3σ confidence interval.

### Connection to the Pythagorean Theorem

RSS has the same structure as the Pythagorean theorem — and this is not a coincidence. If tolerances are viewed as
vectors in an n-dimensional space, the total tolerance is the **length of the resulting vector** (Euclidean norm).
Orthogonality in vector space corresponds to statistical independence. Correlated quantities are no longer
perpendicular, and the total length increases — exactly as in the extended formula with correlation coefficients:

```
‖T‖² = T₁² + T₂² + T₃² + 2ρ₁₂·T₁·T₂ + 2ρ₁₃·T₁·T₃ + 2ρ₂₃·T₂·T₃
```

When ρ = 0, the cross-terms vanish and the Pythagorean theorem remains.

#### The Angle as a Measure of Dependence

The relationship between the geometric angle and statistical correlation is not just an analogy — it is mathematically
exact. The correlation coefficient ρ between two quantities corresponds to the **cosine of the angle** between their
vectors:

**ρ = cos(θ)**

This yields a highly intuitive picture:

- **θ = 90° (perpendicular)** → cos(90°) = 0 → fully independent. The vectors do not interfere with each other, and the
  total length is exactly the hypotenuse according to Pythagoras: √(T₁² + T₂²). This is the classic RSS case.

  A natural follow-up question: *if the phases are fully independent, why do they still appear in the formula at all?*
  Because independent does not mean "does not occur." It only means that the duration of one phase tells you nothing
  about the duration of another. But all phases are still traversed sequentially — the request must pass through
  connect, then response, then read. Each phase contributes its own uncertainty to the total duration. A dice analogy
  makes this tangible: when rolling three dice, each roll is independent of the others. But the sum of all three still
  depends on all of them. Independence only means that the result of the first die tells you nothing about the second —
  not that you can ignore the second. The question is merely: how likely is it that *all three simultaneously* show a
  six? At ρ = 0 the individual variations *partially* compensate each other (when one phase is slow, another is likely
  faster than its maximum) — which is why the result is smaller than the sum. But it is not zero, because every phase is
  *still traversed*.

- **θ = 0° (parallel)** → cos(0°) = 1 → fully correlated. Both quantities always move in the same direction
  simultaneously. The total length is the simple sum T₁ + T₂ — the worst case. Geometrically, the vectors lie on top of
  each other and add directly.
- **θ between 0° and 90°, e.g. 60°** → cos(60°) = 0.5 → partially correlated. The total length falls between RSS and
  worst case.
- **θ = 180° (opposite)** → cos(180°) = −1 → perfectly negatively correlated. The quantities compensate each other. The
  total length is |T₁ − T₂| — shorter than either individual tolerance.

The intuition: the more acute the angle, the more the vectors "pull" in the same direction and the more their lengths
add up directly. The more perpendicular they stand, the more each "goes its own way" and the stronger the Pythagorean
effect that makes the total length shorter than the sum.

Applied to timeouts: if an overloaded network simultaneously slows down both connect *and* read (positively correlated,
acute angle), we approach the worst case. The RSS savings over the worst case are greater the more independent the
failure causes of the individual phases are — i.e., the more perpendicular the vectors stand.

#### Concrete Example: Effect of Correlation on Timeout Calculation

Using the extended formula with connect=2500, response=3000, read=3000 and assuming the same correlation coefficient ρ
for all pairs:

```
‖T‖² = T₁² + T₂² + T₃² + 2ρ₁₂·T₁·T₂ + 2ρ₁₃·T₁·T₃ + 2ρ₂₃·T₂·T₃
```

**ρ = 0 (independent, θ = 90°):**

```
√(2500² + 3000² + 3000²)
= √24,250,000
≈ 4924ms
```

**ρ = 0.5 (partially correlated, θ = 60°):**

```
√(2500² + 3000² + 3000² + 2·0.5·2500·3000 + 2·0.5·2500·3000 + 2·0.5·3000·3000)
= √(24,250,000 + 7,500,000 + 7,500,000 + 9,000,000)
= √48,250,000
≈ 6946ms
```

**ρ = 1 (fully correlated, θ = 0°):**

```
√(2500² + 3000² + 3000² + 2·1·2500·3000 + 2·1·2500·3000 + 2·1·3000·3000)
= √(24,250,000 + 15,000,000 + 15,000,000 + 18,000,000)
= √72,250,000
= 8500ms
```

The last result is no coincidence — 8500ms is exactly 2500 + 3000 + 3000, the simple sum. The formula confirms itself:
at full correlation, RSS collapses to the worst case.

```
ρ = 0   (independent):          4924ms    (58% of worst case)
ρ = 0.5 (partially correlated): 6946ms    (82% of worst case)
ρ = 1   (fully correlated):     8500ms   (100% = worst case)
```

This shows that even moderate correlation (ρ = 0.5) significantly erodes the RSS savings. In practice, this matters most
during systemic failures (e.g., a saturated network switch affecting all phases simultaneously), where the independence
assumption weakens and the effective timeout budget should be closer to the worst-case sum.

### Sigma Levels

The RSS formula `√(T₁² + T₂² + T₃²)` yields the **3σ value** of the combined distribution. The combined standard
deviation is:

```
σ_combined = RSS / 3
```

From this follows directly:

```
1σ (68.27%):   RSS / 3
2σ (95.45%):   RSS × 2/3
3σ (99.73%):   RSS
```

#### Concrete Example

With connect=2500, response=3000, read=3000:

```
RSS = √(2500² + 3000² + 3000²) = √24,250,000 ≈ 4924ms

1σ:  4924 / 3     ≈ 1641ms
2σ:  4924 × 2/3   ≈ 3283ms
3σ:  4924          ≈ 4924ms
```

Worst case (sum) for comparison: 8500ms.

### Choosing the Right Sigma Level per Adapter

- **2σ (95.45%)** — a good default for most adapters. Aggressive enough to not bind Tomcat threads unnecessarily, with
  the CircuitBreaker catching the remaining ~4.5% that exceed it. Requests exceeding the 2σ value are statistical
  outliers where a timeout is more useful than further waiting.
- **3σ (99.73%)** — appropriate for critical adapters where a timeout is expensive, e.g., an authorization adapter where
  a failed call means the customer cannot access any shop features.
- **1σ (68.27%)** — only appropriate for adapters with a fast fail-over (e.g., cache fallback) where aggressive timeout
  behavior is desired.

### Implementation with Configurable Sigma Level

```kotlin
data class AdapterTimeoutConfig(
    val connectTimeoutMs: Int = 2500,
    val responseTimeoutMs: Int = 2500,
    val readTimeoutMs: Int = 2500,
    val writeTimeoutMs: Int = 2500,
    val bulkheadMaxWaitMs: Int = 0,
    val timeLimiterBufferMs: Int = 500,
    val sigmaLevel: Int = 2
) {
    val timeLimiterTimeoutMs: Long
        get() {
            val rss = sqrt(
                connectTimeoutMs.toDouble().pow(2)
                        + responseTimeoutMs.toDouble().pow(2)
                        + readTimeoutMs.toDouble().pow(2)
            )
            val sigmaAdjusted = rss * sigmaLevel / 3.0
            return (sigmaAdjusted + bulkheadMaxWaitMs + timeLimiterBufferMs).toLong()
        }
}
```

### Comparison of Methods

```
Method              Formula                       Coverage     Conservatism

Worst Case          T₁ + T₂ + T₃                 100%         Very high
RSS (3σ)            √(T₁² + T₂² + T₃²)          99.73%       Moderate
RSS (2σ)            √(T₁² + T₂² + T₃²) × 2/3    95.45%       Low
RSS (1σ)            √(T₁² + T₂² + T₃²) × 1/3    68.27%       Very low
```

In manufacturing, 3σ is typically used because a 0.27% reject rate is acceptable for millions of parts. For timeout
configurations with only thousands of requests per second and a CircuitBreaker as a safety net, 2σ is entirely
sufficient.

### Assumptions and Limitations

- **Independence** is the central assumption. The variance addition formula holds exactly only for uncorrelated
  variables. With positive correlation, RSS underestimates the actual spread.
- **Normal distribution** is often cited as a requirement but is stricter than necessary. Variance addition holds for
  arbitrary distributions. The normal distribution provides the interpretation as a confidence interval. The **Central
  Limit Theorem** applies: the sum of many independent variables approaches a normal distribution regardless of
  individual distributions.
- **Symmetry** is implicitly assumed. Timeouts are one-sided — they cannot be negative. Network latency distributions
  are typically right-skewed (long upper tail). RSS therefore slightly underestimates the upper tail. In practice, the
  buffer term (`timeLimiterBufferMs`) compensates for this effect sufficiently.

### Related Concepts

- **Gaussian Error Propagation** — the general form, of which RSS is a special case. It handles arbitrary functions f(
  x₁, x₂, ...) using partial derivatives to calculate the uncertainty of the result. For a simple sum f = x₁ + x₂ + x₃,
  all partial derivatives equal 1, and RSS remains.
- **Monte Carlo Simulation** — the numerical counterpart to RSS. Instead of calculating analytically, it simulates
  thousands of runs with random values and measures the resulting distribution. More accurate for non-normally
  distributed or correlated quantities, but more complex. For timeout configurations this would be overkill — but it
  could be fed with real latency measurement data from monitoring to validate the RSS estimate.

---

# Part III — Integration and Implementation

## Resilience4j Aspect Order

The fixed aspect order in Resilience4j with Spring Boot is:

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( Function ) ) ) ) )
```

`Retry` is applied last (outermost). The CircuitBreaker **wraps** the TimeLimiter. This means the CircuitBreaker **sees
** the `TimeoutException` from the TimeLimiter — which is the desired behavior, as timeouts should open the circuit.

### Impact of Bulkhead on TimeLimiter

The Bulkhead sits **inside** the TimeLimiter. This has a subtle consequence: the time a request **waits in the Bulkhead
queue** counts against the TimeLimiter budget.

```
TimeLimiter starts measurement
  └── Bulkhead: request waits in queue ... 800ms
        └── Function: WebClient call ........... 7500ms
                                        Total: 8300ms
TimeLimiter fires at 8000ms → TimeoutException
```

In this scenario, the actual HTTP call has not yet reached its Netty timeout, but the TimeLimiter aborts because the
Bulkhead wait time was included. The Netty timeouts never fire — specific error diagnosis is lost.

### Adjusted Calculation

The TimeLimiter formula must account for the maximum Bulkhead wait time:

```kotlin
data class AdapterTimeoutConfig(
    val connectTimeoutMs: Int = 2500,
    val responseTimeoutMs: Int = 2500,
    val readTimeoutMs: Int = 2500,
    val writeTimeoutMs: Int = 2500,
    val bulkheadMaxWaitMs: Int = 0,
    val timeLimiterBufferMs: Int = 500
) {
    val timeLimiterTimeoutMs: Long
        get() = (connectTimeoutMs + responseTimeoutMs + readTimeoutMs
                + bulkheadMaxWaitMs + timeLimiterBufferMs).toLong()
}
```

The `bulkheadMaxWaitMs` should correspond to the Resilience4j Bulkhead parameter `maxWaitDuration`. If the Bulkhead is
configured as a semaphore with `maxWaitDuration = 0` (default), it rejects immediately and plays no role in the
calculation. But if a wait time is configured, it must be factored in.

---

## CircuitBreaker Coordination

The CircuitBreaker must know which exceptions count as failures. The chain of possible timeout exceptions is:

1. Netty `ConnectTimeoutException` → mapped in the status code filter to `UnsupportedHttpStatusCodeException`
2. Netty `ReadTimeoutException` → also mapped
3. TimeLimiter `TimeoutException` → comes directly from Resilience4j

The CircuitBreaker should count **all three** as failures. If it is only configured for certain exception types, some
timeouts will not be counted and the circuit will never open — even though the upstream service has long stopped
responding.

---

## Single Source of Truth: Centralized Configuration

Instead of maintaining timeouts manually in two places (Resilience4j in `application.yml` and WebClient timeouts
hardcoded in the configurer), there should be **one central configuration per adapter** from which both Netty and
TimeLimiter values are derived. There are two fundamentally different directions to approach this.

### Approach 1: Bottom-Up — Derive TimeLimiter from Individual Timeouts

This is the most precise approach. Each network-level timeout is configured explicitly, and the TimeLimiter is *
*computed** from these values (e.g., via RSS). This gives maximum control over each phase and produces specific,
meaningful exceptions when individual phases fail.

```yaml
# application.yml — Bottom-Up: individual timeouts are the source of truth
adapter:
  customer-service:
    base-url: https://customer-service.internal
    connect-timeout-ms: 2500
    response-timeout-ms: 3000
    read-timeout-ms: 3000
    time-limiter-buffer-ms: 500
    # TimeLimiter is computed automatically:
    # RSS(2500, 3000, 3000) × 2/3 + 500 ≈ 3783ms

  product-catalog:
    base-url: https://product-catalog.internal
    connect-timeout-ms: 1000
    response-timeout-ms: 5000
    read-timeout-ms: 5000
    time-limiter-buffer-ms: 1000

  inventory:
    base-url: https://inventory.internal
    connect-timeout-ms: 1000
    response-timeout-ms: 1500
    read-timeout-ms: 1500
    time-limiter-buffer-ms: 500
```

**Advantages:** Full control over every phase. Netty exceptions carry precise diagnostic information (connect vs.
response vs. read failure). The TimeLimiter value is always consistent with the individual timeouts by construction.

**Best suited for:** Real network connections where all four phases have meaningful, independent variability — direct
calls across data centers, WAN, or internet.

### Approach 2: Top-Down — Derive Individual Timeouts from TimeLimiter

This approach inverts the direction. The **TimeLimiter** is the primary configuration value — the total time budget per
adapter — and the individual Netty timeouts are derived from it. This is conceptually simpler but requires a stable
network topology (specifically a sidecar/loopback setup) where most phases are trivially fast.

```yaml
# application.yml — Top-Down: TimeLimiter is the source of truth
adapter:
  customer-service:
    base-url: https://customer-service.internal
    time-limiter-timeout-ms: 4000
    connect-timeout-ms: 100        # fixed for all localhost adapters

  product-catalog:
    base-url: https://product-catalog.internal
    time-limiter-timeout-ms: 8000
    connect-timeout-ms: 100

  inventory:
    base-url: https://inventory.internal
    time-limiter-timeout-ms: 2000
    connect-timeout-ms: 100
```

#### Budget Calculation

The connect timeout is fixed at 100ms because all adapters target the same localhost sidecar — TCP handshake time does
not vary per adapter. The response timeout gets the remaining budget after subtracting connect, but not the full
TimeLimiter budget. Instead, `Mono.timeout()` is placed at **85%** of the TimeLimiter as an intermediate safety net with
clean connection cleanup:

```
Mono.timeout    = TimeLimiter × 85%
responseTimeout = Mono.timeout − connectTimeout
                = TimeLimiter × 85% − 100ms
```

The remaining 15% between `Mono.timeout()` and the TimeLimiter covers deserialization, filter processing, thread
scheduling, and the overhead until the `CompletableFuture` completes. This replaces the explicit `timeLimiterBufferMs`
from the bottom-up approach with a proportional buffer that scales automatically with the total budget.

```kotlin
data class AdapterTopDownTimeoutConfig(
    val timeLimiterTimeoutMs: Int = 4000,
    val connectTimeoutMs: Int = 100,
    val monoTimeoutFactor: Double = 0.85
) {
    /**
     * Mono.timeout() acts as the inner safety net at 85%
     * of the total budget. It cancels the reactive pipeline
     * and cleans up the connection — unlike the TimeLimiter
     * which only cancels the CompletableFuture.
     */
    val monoTimeoutMs: Long
        get() = (timeLimiterTimeoutMs * monoTimeoutFactor).toLong()

    /**
     * The response timeout receives the Mono.timeout budget
     * minus the fixed connect timeout. This is the maximum
     * time the sidecar (and the upstream behind it) has
     * to deliver the first response byte.
     */
    val responseTimeoutMs: Int
        get() = (monoTimeoutMs - connectTimeoutMs).toInt()
}
```

Example with a 4000ms TimeLimiter:

```
TimeLimiter     = 4000ms        (outermost: Resilience4j cancels Future)
Mono.timeout    = 3400ms        (85%: reactive pipeline cancels + cleans up connection)
responseTimeout = 3300ms        (3400 − 100: Netty waits for first byte from sidecar)
connectTimeout  =  100ms        (fixed: TCP handshake to localhost)
```

#### Migration Formula

When migrating from existing TimeLimiter values where the `responseTimeout` was effectively equal to the old
TimeLimiter (e.g., due to a configuration bug where `responseTimeout` was set in seconds instead of milliseconds), the
new TimeLimiter must be scaled up to account for the 85% factor:

```
New TimeLimiter = (Old TimeLimiter + 100ms) × 100 / 85
```

Example: if the old TimeLimiter was 3000ms (which also served as the effective response timeout):

```
New TimeLimiter = (3000 + 100) × 100 / 85 ≈ 3647ms
→ Mono.timeout  = 3647 × 0.85 ≈ 3100ms
→ responseTimeout = 3100 − 100 = 3000ms   ✓ matches old behavior
```

#### Why No Read/Write TimeoutHandler

All adapters communicate with a localhost sidecar over a loopback connection. This reduces the four-phase HTTP timeout
model (connect, write, wait, read) to effectively a single-phase model: only the server processing time behind the
sidecar varies.

| Phase                    | What happens                     | Duration | Variability                                     |
|--------------------------|----------------------------------|----------|-------------------------------------------------|
| **Connect**              | TCP handshake over loopback      | < 1ms    | Practically zero — kernel-internal, no network  |
| **Write**                | Request headers over loopback    | < 1ms    | Practically zero — memory copy in kernel        |
| **Read**                 | JSON body over loopback          | < 1ms    | Practically zero — small payloads, no network   |
| **Response (wait time)** | Backend processes behind sidecar | 5–7000ms | **Extremely variable** — the only relevant size |

Individual `ReadTimeoutHandler` and `WriteTimeoutHandler` would only add failure modes without diagnostic value. Worse,
a `ReadTimeoutHandler` registered in `doOnConnected` fires during the "waiting for first byte" phase and **collides**
with `responseTimeout` — both measure overlapping time windows, leading to non-deterministic exception types.

`Mono.timeout()` at 85% of the TimeLimiter covers all phases as a single budget with clean connection cleanup. If
external APIs with real network latency and large payloads are added later, Read/Write handlers should be introduced —
with `ReadTimeoutHandler` in `doOnResponse` (not `doOnConnected`) to monitor body transfer only.

**Advantages:** Simpler mental model — "this adapter has a 4-second budget, period." Only one value to configure per
adapter. Easy to reason about from a business perspective ("the customer should never wait longer than X seconds for
this call"). The 85% factor provides a built-in proportional buffer.

**Disadvantages:** Less control over individual phases. Diagnostic precision is reduced — when `Mono.timeout()` or the
TimeLimiter fires, the exception does not reveal which specific phase was slow. Only viable when the four-phase model
has degenerated into a single-phase model.

**Best suited for:** Sidecar/loopback setups where connect, write, and read are trivially fast and practically
constant (as shown in the [Phase Variability](#phase-variability-sidecar-vs-real-network-connection) section).

**Not recommended for:** Real network connections where multiple phases have significant, independent variability. Here,
the bottom-up approach with individually tuned values and RSS-based TimeLimiter calculation is substantially more
accurate.

### Choosing the Right Approach

```
                        Bottom-Up                    Top-Down
                        (individual → TimeLimiter)   (TimeLimiter → individual)

Precision               High                         Moderate
Diagnostic value        High (specific exceptions)   Lower (often TimeLimiter fires)
Configuration effort    Higher (tune each phase)     Lower (one primary value)
Required buffer         Small (~500ms)               Larger (~1000ms+)
Network stability       Any topology                 Stable only (sidecar/loopback)
Best for                Real network, WAN, internet  Sidecar, localhost, service mesh
```

Both approaches maintain the single-source-of-truth principle — timeouts are defined once and the other values are
derived. They differ only in which direction the derivation flows.

The following implementation examples use the **bottom-up approach**, as it is the more general solution applicable to
any network topology.

### Configuration Properties

```kotlin
@ConfigurationProperties(prefix = "adapter")
data class AdapterProperties(
    val customerService: AdapterEndpointConfig = AdapterEndpointConfig(),
    val productCatalog: AdapterEndpointConfig = AdapterEndpointConfig(),
    val inventory: AdapterEndpointConfig = AdapterEndpointConfig(),
    val paymentGateway: AdapterEndpointConfig = AdapterEndpointConfig(),
    val shippingProvider: AdapterEndpointConfig = AdapterEndpointConfig(),
    val authorization: AdapterEndpointConfig = AdapterEndpointConfig(),
    val orderManagement: AdapterEndpointConfig = AdapterEndpointConfig(),
    val recommendations: AdapterEndpointConfig = AdapterEndpointConfig(),
    val invoicing: AdapterEndpointConfig = AdapterEndpointConfig()
)

data class AdapterEndpointConfig(
    val baseUrl: String = "http://localhost:8080",
    val timeouts: AdapterTimeoutConfig = AdapterTimeoutConfig()
)
```

### WebClient Factory

```kotlin
@Component
class WebClientFactory {

    fun createWebClient(
        builder: WebClient.Builder,
        config: AdapterEndpointConfig
    ): WebClient =
        builder
            .baseUrl(config.baseUrl)
            .enableDefaultRequestLogging()
            .enableDefaultResponseLogging()
            .clientConnector(
                connectTimeoutMillis = config.timeouts.connectTimeoutMs,
                responseTimeoutMillis = config.timeouts.responseTimeoutMs,
                readTimeoutMillis = config.timeouts.readTimeoutMs,
                writeTimeoutMillis = config.timeouts.writeTimeoutMs
            )
            .defaultStatusHandler({ _ -> true }, { _ -> Mono.empty() })
            .filter(createStatusCodeHandlingFilter(createDefaultStatusCodeConfiguration()))
            .filter(unSupportedHttpStatusCodesFilter())
            .build()
}
```

### TimeLimiter Auto-Configuration via Customizer

The TimeLimiter timeout should **not** be manually defined in the Resilience4j YAML section but derived from the same
source:

```kotlin
@Configuration
class Resilience4jTimeLimiterConfiguration(
    private val adapterProperties: AdapterProperties
) {

    @Bean
    fun timeLimiterCustomizer(): TimeLimiterConfigCustomizer =
        TimeLimiterConfigCustomizer { configName ->
            val config = resolveTimeoutConfig(configName)
            if (config != null) {
                TimeLimiterConfig.custom()
                    .timeoutDuration(
                        Duration.ofMillis(config.timeLimiterTimeoutMs)
                    )
                    .cancelRunningFuture(true)
                    .build()
            } else {
                null // Fall back to Resilience4j defaults
            }
        }

    private fun resolveTimeoutConfig(
        instanceName: String
    ): AdapterTimeoutConfig? =
        when (instanceName) {
            CUSTOMER_SERVICE -> adapterProperties.customerService.timeouts
            INVOICING -> adapterProperties.invoicing.timeouts
            PRODUCT_CATALOG -> adapterProperties.productCatalog.timeouts
            PAYMENT_STATUS -> adapterProperties.paymentGateway.timeouts
            // ... further mappings
            else -> null
        }
}
```

### Adapter Example

```kotlin
@Component
class CustomerServiceReactiveAdapter(
    builder: WebClient.Builder,
    webClientFactory: WebClientFactory,
    adapterProperties: AdapterProperties
) : CustomerServiceAdapterSpec {

    private val webClient = webClientFactory.createWebClient(
        builder, adapterProperties.customerService
    )

    @CircuitBreaker(name = CUSTOMER_SERVICE)
    @Bulkhead(name = CUSTOMER_SERVICE)
    @TimeLimiter(name = CUSTOMER_SERVICE)
    override fun findCustomerProfiles(
        customerIds: List<CustomerIdValue>
    ): CompletableFuture<PageCustomersDTO> =
        webClient.get()
            .uri { /* ... */ }
            .retrieve()
            .bodyToMono<PageCustomersDTO>()
            .toFuture()
            .nonNull()
}
```

The adapter itself no longer knows any timeout values. It receives a properly configured `WebClient`, and the
TimeLimiter is automatically set to match.

### Benefits of This Approach

- **No shared mutable singleton** — `WebClientFactory` is stateless and creates a dedicated WebClient instance per
  adapter.
- **Base URL differentiation** — each adapter has its own `baseUrl` in the configuration.
- **No double registration** — adapters no longer call `applyDefaultConfiguration` on an already-configured builder.
- **Structural consistency guarantee** — it is impossible for WebClient timeouts and TimeLimiter to diverge, because the
  TimeLimiter value is computed from the WebClient timeouts. If someone changes `response-timeout-ms` in the YAML, the
  TimeLimiter adjusts automatically.

---

# Part IV — Topology- and Client-Specific Variants

## Sidecar (Service Mesh) Implications

When connections go through a sidecar (e.g., Envoy/Istio), the timeout semantics shift fundamentally.

### Communication Path Change

```
Without sidecar:   App ──── Network ──── Upstream Server

With sidecar:      App ── localhost ── Sidecar ── Network ── Upstream Server
```

From the WebClient's perspective, only the connection to localhost exists. The entire network path to the actual
upstream server is **hidden behind the sidecar**.

### Impact on Each Timeout

**Connect timeout** — becomes trivial. The TCP handshake goes to localhost, taking under 0.1ms. A connect timeout of
100–200ms is more than sufficient.

**Write timeout** — also becomes trivial. Request bytes are pushed over the loopback interface only. No real network, no
bandwidth limitation, no packet loss. 200ms suffices.

**Response timeout** — here the critical shift happens. This timeout now measures not just the upstream server's
processing time but the **entire chain** the sidecar traverses:

```
Response timeout from the app's perspective now encompasses:
  ├── Sidecar processes the request internally
  ├── Sidecar establishes TCP connection to upstream  ← (previously: connect)
  ├── Sidecar sends request over the network          ← (previously: write)
  ├── Upstream server processes                       ← (previously: response)
  └── First response byte travels back via            ← (previously: part of read)
      Network → Sidecar → localhost → App
```

The response timeout **absorbs** the network latencies that previously resided in the connect and write timeouts. It
must be set **higher** than in a setup without a sidecar.

**Read timeout** — partially affected. It still measures the pause between two received data packets, but packets now
come from the sidecar over localhost. In practice, the effect is often minor because the sidecar typically buffers the
entire response and delivers it in one go — making the read timeout nearly irrelevant as all bytes arrive virtually
simultaneously over localhost.

### Visualized Shift

```
Without sidecar — latencies distributed across all timeouts:

  connect     write        response         read
  ├─ RTT ─┤  ├─ Net ──┤  ├─ Server ─────┤  ├─ Net+Stream ─┤


With sidecar — latencies concentrate in the response timeout:

  connect    write    response                              read
  ├ local ┤  ├ lo ┤  ├─ Sidecar + RTT + Net + Server ──┤  ├ lo ┤
  ~trivial   ~trivial  ↑                                    ~trivial
                       │ now contains everything that was
                       │ previously distributed across four timeouts
```

### Configuration Comparison

```yaml
# Without sidecar
adapter:
  product-catalog:
    network: datacenter-internal
    connect-timeout-ms: 500
    write-timeout-ms: 500
    response-timeout-ms: 5000
    read-timeout-ms: 3000

# With sidecar
adapter:
  product-catalog:
    network: sidecar-localhost
    connect-timeout-ms: 200
    write-timeout-ms: 200
    response-timeout-ms: 6500     # Server + absorbed network RTT
    read-timeout-ms: 1000         # localhost, sidecar usually buffers
```

The total budget remains in the same range — the latency budgets simply shift from connect/write/read into the response
timeout.

### Impact on RSS Calculation

This has a surprising side-effect on the TimeLimiter calculation. Without sidecar, variance distributes across three to
four timeouts:

```
RSS without sidecar:  √(500² + 500² + 5000² + 3000²)  ≈ 5882ms
```

With sidecar, almost all variance concentrates in a single timeout:

```
RSS with sidecar:     √(200² + 200² + 6500² + 1000²)  ≈ 6578ms
```

RSS values approach the response timeout because a single dominant summand determines the quadratic sum. This means *
*RSS provides less savings over the worst case in a sidecar setup** because the independence between phases is
effectively gone — there is almost only one relevant phase.

### Phase Variability: Sidecar vs. Real Network Connection

The following tables quantify why the four-phase timeout model degenerates into a single-phase model behind a sidecar.

**Localhost sidecar setup:**

| Phase                    | What happens                       | Typical Duration | Variability                                     |
|--------------------------|------------------------------------|------------------|-------------------------------------------------|
| **Connect**              | TCP handshake over loopback        | < 1ms            | Practically zero — kernel-internal, no network  |
| **Write**                | Send request headers over loopback | < 1ms            | Practically zero — memory copy in kernel        |
| **Read (body)**          | Read JSON payload over loopback    | < 1ms            | Practically zero — small payloads, no network   |
| **Response (wait time)** | Backend works behind the sidecar   | 5–7000ms         | **Extremely variable** — the only relevant size |

Three of four phases are constant near zero. Only the wait time for the response varies. Therefore, a fixed connect
timeout + a variable response timeout + `Mono.timeout()` as a safety net are sufficient.

**Real network connection (e.g., direct call to an external API):**

| Phase                    | What happens                          | Typical Duration | Variability                                           |
|--------------------------|---------------------------------------|------------------|-------------------------------------------------------|
| **Connect**              | TCP handshake over WAN + possibly TLS | 20–500ms         | High — latency, routing, TLS negotiation, DNS         |
| **Write**                | Send request body over network        | 1–5000ms         | High for POST with large body — bandwidth, congestion |
| **Read (body)**          | Receive response body over network    | 10–30000ms       | Very high — large payloads, streaming, slow-drip      |
| **Response (wait time)** | Server processes                      | 5–10000ms        | High — same as with sidecar                           |

**All four phases are variable and relevant.** Here, all timeouts must be individually configured:

```kotlin
// Real network connection — all timeouts individually tuned
HttpClient.create()
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)    // WAN latency + TLS
    .responseTimeout(Duration.ofSeconds(10))                 // server processing time
    .doOnConnected { conn ->
        conn.addHandlerLast(WriteTimeoutHandler(5, TimeUnit.SECONDS))  // upload protection
    }
    .doOnResponse { _, conn ->
        conn.addHandlerLast(ReadTimeoutHandler(15, TimeUnit.SECONDS))  // slow-drip protection
    }
```

### The Key Insight: Model Degeneration

With a loopback connection, the four-phase timeout model degenerates into a **single-phase model**. Connect, read, and
write are in the noise — the sidecar is a black box that delivers a complete response after X milliseconds. This is
precisely why the simplified model with a fixed connect timeout and a derived response timeout is not just sufficient
but actually **better** than individual timeouts, which would only introduce additional failure modes without any
diagnostic value.

### Configuring the Sidecar's Own Timeouts

The sidecar introduces a **third timeout layer** between the app's timeouts and the upstream server. If not properly
aligned, the same problems arise as between Netty and TimeLimiter — timeouts compete, and exception types become
non-deterministic.

#### The Complete Timeout Chain

```
App                          Sidecar (Envoy)              Upstream
├─ TimeLimiter ──────────────────────────────────────────────────────┤
│  ├─ Mono.timeout ──────────────────────────────────────────────┤  │
│  │  ├─ responseTimeout ─────────────────────────────────────┤  │  │
│  │  │                    ├─ Route Timeout ───────────────┤   │  │  │
│  │  │                    │  ├─ connect_timeout (to ups.) ┤   │  │  │
│  │  │                    │  ├─ Upstream processes ───────┤   │  │  │
│  │  │                    │  ├─ Response back ────────────┤   │  │  │
```

The principle remains: **inner timeouts shorter than outer timeouts**. The sidecar's route timeout should be **shorter**
than the app's `responseTimeout`. This way, the sidecar delivers a clean HTTP 504 (Gateway Timeout) to the app —
diagnostically far more valuable than the app's `responseTimeout` firing blindly.

#### Envoy/Istio Timeout Parameters

Envoy has its own set of timeout parameters at the sidecar level. The most relevant ones for upstream communication are:

**`connect_timeout`** (cluster level) — the time Envoy waits for an upstream TCP connection to be established. Notably,
for upstream TLS connections this **includes the TLS handshake** — unlike Netty's `CONNECT_TIMEOUT_MILLIS` which covers
only TCP. The default is 5 seconds (
see [Envoy timeout FAQ](https://www.envoyproxy.io/docs/envoy/latest/faq/configuration/timeouts)).

**`timeout`** (route level) — the total time Envoy waits for the upstream to respond with a complete response. This is
the hard outer limit for the entire round-trip from the sidecar's perspective. The default is 15 seconds. If the
upstream does not respond in time, Envoy returns a 504 to the downstream (the app).

**`per_try_timeout`** (retry policy) — the timeout for each individual retry attempt. Only applies before any part of
the response is sent to the downstream.

**`per_try_idle_timeout`** (retry policy) — ensures continued response progress within individual retry attempts, useful
for streaming responses.

#### Concrete Configuration Example

For an Istio VirtualService:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: product-catalog
spec:
  hosts:
    - product-catalog.internal
  http:
    - route:
        - destination:
            host: product-catalog.internal
      timeout: 3s                    # route timeout — shorter than app responseTimeout
      retries:
        attempts: 1
        perTryTimeout: 2800ms
        retryOn: 5xx,reset,connect-failure
```

And the corresponding DestinationRule for connection-level settings:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: product-catalog
spec:
  host: product-catalog.internal
  trafficPolicy:
    connectionPool:
      tcp:
        connectTimeout: 500ms        # TCP + TLS to the real upstream
```

In native Envoy configuration:

```yaml
route_config:
  virtual_hosts:
    - name: product-catalog
      routes:
        - match: { prefix: "/" }
          route:
            cluster: product-catalog
            timeout: 3s
            retry_policy:
              num_retries: 1
              per_try_timeout: 2.8s
              retry_on: "5xx,reset,connect-failure"

clusters:
  - name: product-catalog
    connect_timeout: 0.5s
```

#### Aligning Sidecar Timeouts with App Timeouts

In a loopback setup, the sidecar route timeout and the app's `responseTimeout` measure **essentially the same thing**:
how long the upstream behind the sidecar takes to process and respond. The loopback transport of the response is
negligible — the first byte arrives at the app virtually the instant the sidecar has it.

The question is: **which one should fire first?** There are two valid strategies.

#### Strategy A: Sidecar Shorter — Active Protection

The sidecar's route timeout is set **shorter** than the app's `responseTimeout`. The sidecar actively aborts the
upstream call and generates a 504 (Gateway Timeout). The app receives this 504 as a normal HTTP response.

```
Sidecar Route Timeout  <  App responseTimeout
```

This is the **operative pair** — the two timeouts that can actually compete with each other. The sidecar must abort
slightly before, so its 504 reaches the `responseTimeout` before it fires on its own. The gap between them only needs to
cover the loopback delivery of the 504 response — practically nothing.

The outer layers — `Mono.timeout()` and TimeLimiter — are **not** the sidecar's counterparts. They sit further out and
cover phases that have nothing to do with the sidecar: deserialization, filter processing, thread scheduling, Bulkhead
queue wait, and `CompletableFuture` overhead. Each layer aligns with its immediate neighbor:

```
Alignment pair 1 (same phase — server processing):
  Sidecar Route Timeout  <  responseTimeout

Alignment pair 2 (adds deserialization + filter overhead):
  responseTimeout  <  Mono.timeout()

Alignment pair 3 (adds Future overhead + Bulkhead wait):
  Mono.timeout()  <  TimeLimiter
```

Concrete example for a product catalog adapter with a 4000ms TimeLimiter:

```
Layer                    Value       Covers

Envoy connect_timeout      500ms    TCP+TLS to upstream
Envoy per_try_timeout     2800ms    single attempt budget
Envoy route timeout       3000ms    total budget for upstream  ──┐
                                                                 ├─ pair 1
App responseTimeout       3300ms    waits for first byte        ──┘
                                     from sidecar
                                                                ──┐
App Mono.timeout()        3400ms    reactive pipeline cleanup   ──┤─ pair 2
                                                                ──┘
                                                                ──┐
App TimeLimiter           4000ms    outermost safety net        ──┤─ pair 3
                                                                ──┘
```

The 300ms gap between the sidecar route timeout (3000ms) and `responseTimeout` (3300ms) is the operative gap — it gives
the sidecar time to generate the 504 response. If this gap is too small, both fire quasi-simultaneously and the
exception type becomes non-deterministic.

The 100ms gap between `responseTimeout` (3300ms) and `Mono.timeout()` (3400ms) covers deserialization and filter
processing — phases that occur *after* the first byte has arrived and are invisible to the sidecar.

The 600ms gap between `Mono.timeout()` (3400ms) and TimeLimiter (4000ms) covers `CompletableFuture` completion overhead
and potential Bulkhead queue wait — phases that occur *outside* the reactive pipeline and are invisible to both the
sidecar and `responseTimeout`.

#### Strategy B: Sidecar Longer — Passthrough

The sidecar's route timeout is set **significantly longer** than the app's `responseTimeout`. The sidecar stays passive
and lets upstream responses — including upstream-generated errors — flow through unmodified. The app's `responseTimeout`
becomes the active line of defense.

```
App responseTimeout  <<  Sidecar Route Timeout
```

The reasoning: if the upstream service is properly configured, it has its **own** timeouts and delivers a clean error
response when it exceeds them (e.g., HTTP 504, 408, or an application-specific error). With a generous sidecar timeout,
this original upstream error is routed through to the app unchanged. The app receives the **actual error from the source
** — diagnostically more valuable than a sidecar-generated 504 that obscures the origin.

Concrete example with the same 4000ms TimeLimiter:

```
Layer                    Value       Covers

App responseTimeout       3300ms    active timeout, fires first ──┐
                                                                  │
App Mono.timeout()        3400ms    reactive pipeline cleanup     │ app-internal
                                                                  │ chain
App TimeLimiter           4000ms    outermost safety net        ──┘
                                                                  
Envoy route timeout       8000ms    passive safety net — only
                                     catches completely hanging
                                     upstreams that produce no
                                     response at all
Envoy connect_timeout      500ms    TCP+TLS to upstream
```

The tight gap-alignment between sidecar and `responseTimeout` becomes unnecessary. The sidecar timeout is simply set to
a generous multiple (e.g., 2× the `responseTimeout`), and the entire timeout logic resides in the app.

#### Choosing Between the Strategies

```
                        Strategy A               Strategy B
                        (Sidecar shorter)         (Sidecar longer)

Sidecar role            Active timeout enforcer   Passive relay
Error origin            Sidecar (504)             Upstream (original error)
Diagnostic value        Moderate                  Higher (original errors)
Alignment complexity    High (precise gaps)       Low (generous sidecar)
Prerequisite            None                      Upstream has own timeouts
Risk if upstream hangs  Sidecar catches it fast   Sidecar catches it slowly
Resource holding        Short                     Longer (sidecar keeps
                                                   connection open)
Best for                Untrusted upstreams,      Well-configured upstreams,
                        no control over           loopback/sidecar only
                        upstream timeouts         for mTLS and routing
```

In a loopback setup where the sidecar primarily handles mTLS and routing — not timeout enforcement — **Strategy B is the
more natural choice**. The four-phase timeout model has already degenerated into a single phase (as shown in
the [Phase Variability](#phase-variability-sidecar-vs-real-network-connection) section), and the app's `responseTimeout`
is the most informed timeout in the chain because it can be configured per adapter based on business requirements.

**Strategy A** is the safer choice when dealing with untrusted or poorly configured upstreams where you cannot rely on
the upstream generating its own timeout responses. It is also the standard recommendation in most service mesh
documentation, because it is the conservative default that works regardless of upstream behavior.

Both strategies are compatible with the app-internal alignment pairs 2 and 3 (`responseTimeout` < `Mono.timeout()` <
TimeLimiter) — these remain unchanged regardless of the sidecar strategy.

#### Retry Budget Considerations

When the sidecar performs retries, the route timeout is the **hard upper limit** including all retry attempts. A common
pitfall is configuring `per_try_timeout × attempts > route timeout`, which means the last retry never gets its full
budget. A recommended rule of thumb is:

```
route timeout ≥ per_try_timeout × (attempts + 1)
```

The `+ 1` accounts for the original attempt. With the values above: 2800ms × (1 + 1) = 5600ms would be ideal for a route
timeout of 3000ms — which means with only 3000ms budget, the retry may be cut short. This is an acceptable trade-off
when the app's `responseTimeout` is the binding constraint. Alternatively, if retries are important, the entire chain
must be scaled up accordingly.

#### Important: The More Restrictive Timeout Wins

When both the app and the sidecar have timeouts configured, the **more restrictive one takes precedence**. This is a
neutral mechanism — it is not inherently good or bad, but it determines which strategy is in effect:

- In **Strategy A**, the sidecar's route timeout is more restrictive. It fires first and delivers a 504 to the app.
- In **Strategy B**, the app's `responseTimeout` is more restrictive. It fires first and closes the connection. The
  sidecar's timeout serves only as a backstop for completely hanging upstreams.

Istio also supports per-request timeout overrides via the `x-envoy-upstream-rq-timeout-ms` header, allowing the app to
dynamically set the sidecar timeout per call (
see [Istio Request Timeouts](https://istio.io/latest/docs/tasks/traffic-management/request-timeouts/)).

#### Reference Documentation and the Documentation Gap

For a comprehensive overview of all configurable Envoy timeouts (connection, stream, route, and retry), see the
official [Envoy Timeout FAQ](https://www.envoyproxy.io/docs/envoy/latest/faq/configuration/timeouts). For Istio-specific
timeout configuration,
see [Istio Request Timeouts](https://istio.io/latest/docs/tasks/traffic-management/request-timeouts/).

It is worth noting that neither Envoy nor Istio provide guidance on how to align sidecar timeouts with application-level
timeouts. However, this gap is more nuanced than it appears at first glance. The **general principle** — that one of the
two must be clearly shorter than the other, so that one layer fires deterministically before the other — is universally
true and framework-agnostic. Whether the sidecar or the app should be the shorter one depends on the strategy chosen (
see above). What both strategies share is that the two timeouts must **not** be equal or nearly equal — that leads to
non-deterministic behavior where the exception type is unpredictable. This is a statement Envoy and Istio could make
without knowing anything about the application behind them.

What genuinely requires application-specific knowledge — and what this guide addresses — is the **concrete calculation**
of values across all layers: how to derive timeout values across sidecar, `responseTimeout`, `Mono.timeout()`, and
TimeLimiter, how RSS-based statistical tolerance analysis applies, and how retry budgets at the sidecar level interact
with the Bulkhead and CircuitBreaker in the app. That calculation depends on the specific tech stack and cannot be
generalized by an infrastructure project.

---

## RestClient: Synchronous / Blocking Differences

The `RestClient` (introduced in Spring 6.1 as the modern successor to `RestTemplate`) works **synchronously and blocking
**. There are no reactive pipelines or event handlers like `ReadTimeoutHandler` or `WriteTimeoutHandler`. Instead, the
`RestClient` delegates all work to the underlying HTTP library (the `ClientHttpRequestFactory`).

### Read Timeout (Socket Timeout)

When the `RestClient` wants to read data from the server, it calls a blocking `read()` method on the network socket. The
thread physically halts at this point. The read timeout (`SO_TIMEOUT`) defines the maximum time the thread may block
while waiting for the next data packet. Functionally, this is similar to the WebClient's behavior — it is the maximum "
silence" time between two received packets. The technical difference is that a thread is physically stopped here,
whereas in the WebClient only a timer ticks in an event loop.

### Write Timeout

There is **no direct equivalent** to Netty's `WriteTimeoutHandler` in standard `RestClient` implementations. When
writing synchronously, the `write()` call blocks until the data is copied into the OS socket buffer. If the buffer is
full, the thread simply continues to block. You are typically reliant on OS-level TCP retransmission timeouts, which
often only force a disconnect after several minutes.

### Practical Recommendation

With `RestClient`, you typically configure only a **connection timeout** (TCP handshake time) and a **read timeout** (
maximum wait for the next data packet). Write timeouts at the application level are generally not available for blocking
I/O.

### TLS Handshake Coverage Depends on the Engine

The behavior depends on which HTTP library is used under the hood:

**Modern clients (JDK `HttpClient` Java 11+, OkHttp):** The connect timeout covers the *entire* logical connection
establishment, including DNS resolution, TCP handshake, and TLS handshake. If the server opens the TCP socket but hangs
during certificate negotiation, the connect timeout correctly fires.

**Classic clients (`HttpURLConnection`):** The connect timeout applies strictly to the TCP level only. Once the TCP
handshake completes, the socket is considered "connected." The subsequent TLS handshake requires the client to *read*
certificate data from the server. If the server hangs at this point, ironically the **read timeout** fires, since TCP
connect was already successful and the client is now blocking while waiting for data.

**Apache HttpClient (v4/v5):** The classic `ConnectTimeout` also primarily covers the TCP socket. The TLS handshake
happens deep in the `ConnectionSocketFactory`. If the server sends no data during the TLS handshake, the **socket
timeout (read timeout)** typically fires.

**Best practice:** Always set **both** connect and read timeouts for synchronous clients.

---

## Appendix: JVM HTTP Client Timeout Reference Tables

### Timeout Configuration Including Data Types & Units

| HTTP Client (JVM)                      | Timeout Parameter                             | Data Type & Unit                        | Monitored Time Span                                                                   |
|:---------------------------------------|:----------------------------------------------|:----------------------------------------|:--------------------------------------------------------------------------------------|
| **Apache HttpClient**                  | `ConnectTimeout`                              | **v5:** `Timeout`<br>**v4:** `int` (ms) | Time limit for the initial TCP handshake.                                             |
|                                        | `ResponseTimeout` (v5) / `SocketTimeout` (v4) | **v5:** `Timeout`<br>**v4:** `int` (ms) | Maximum inactivity time between two received data packets.                            |
|                                        | `ConnectionRequestTimeout`                    | **v5:** `Timeout`<br>**v4:** `int` (ms) | Maximum wait time for a free connection from the connection pool.                     |
| **OkHttp**                             | `connectTimeout`                              | `long` + `TimeUnit` or `Duration`       | Time for establishing the TCP connection and TLS handshake.                           |
|                                        | `readTimeout`                                 | `long` + `TimeUnit` or `Duration`       | Maximum inactivity between two successful read operations.                            |
|                                        | `writeTimeout`                                | `long` + `TimeUnit` or `Duration`       | Maximum time a single write operation is allowed to block on the network socket.      |
|                                        | `callTimeout`                                 | `long` + `TimeUnit` or `Duration`       | Hard upper limit for the entire call.                                                 |
| **Java 11+ HttpClient**                | `connectTimeout`                              | `java.time.Duration`                    | Maximum time allowed for establishing the connection.                                 |
|                                        | `timeout`                                     | `java.time.Duration`                    | Maximum total time for the specific request.                                          |
| **Spring WebClient** *(Reactor Netty)* | `CONNECT_TIMEOUT_MILLIS`                      | `Integer` (strictly **ms**)             | Configured in Netty `ChannelOption`. Monitors purely the TCP establishment.           |
|                                        | `ReadTimeoutHandler`                          | `int` + `TimeUnit` or `Duration`        | Netty level: Time span without new received data.                                     |
|                                        | `WriteTimeoutHandler`                         | `int` + `TimeUnit` or `Duration`        | Netty level: Maximum time a single write operation to the socket is allowed to block. |
|                                        | `responseTimeout`                             | `java.time.Duration`                    | HttpClient level: Wait time for the response (headers) after sending the request.     |
|                                        | `.timeout()`                                  | `java.time.Duration`                    | Reactive operator: Hard upper limit for the asynchronous pipeline.                    |

### Agnostic HTTP Timeout Configuration Set

| Timeout Type                 | Agnostic Parameter Name          | Monitored Time Span                                                                      | Equivalent in Common Clients                                         |
|:-----------------------------|:---------------------------------|:-----------------------------------------------------------------------------------------|:---------------------------------------------------------------------|
| **Pool Wait Time**           | `connectionAcquireTimeout`       | The maximum time waiting for a free TCP connection from an internal pool.                | Apache: `ConnectionRequestTimeout`<br>Spring: `ConnectionProvider`   |
| **Connection Establishment** | `connectionEstablishmentTimeout` | The time for the actual TCP connection (and TLS handshake).                              | OkHttp/Java 11+: `connectTimeout`<br>Apache: `ConnectTimeout`        |
| **Server Response Time**     | `serverResponseTimeout`          | Maximum time waited for the server to start responding (TTFB) after the request is sent. | Spring: `responseTimeout`                                            |
| **Read Inactivity**          | `readInactivityTimeout`          | Maximum wait time between two received data packets during download.                     | OkHttp: `readTimeout`<br>Apache: `ResponseTimeout`                   |
| **Write Operation**          | `writeOperationTimeout`          | Maximum time a single write operation to the network socket is allowed to block.         | OkHttp: `writeTimeout`<br>Spring: `WriteTimeoutHandler`              |
| **Total Execution Time**     | `totalExecutionTimeout`          | Absolute upper limit for the entire lifecycle of the call.                               | Java 11+: `timeout`<br>OkHttp: `callTimeout`<br>Spring: `.timeout()` |

### Mapping: Agnostic Configuration to JVM Clients

| Agnostic Parameter                   | Apache HttpClient (v5)                                             | OkHttp                                                         | Java 11+ HttpClient                                              | Spring WebClient *(Reactor Netty)*     |
|:-------------------------------------|:-------------------------------------------------------------------|:---------------------------------------------------------------|:-----------------------------------------------------------------|:---------------------------------------|
| **`connectionAcquireTimeout`**       | `ConnectionRequestTimeout`                                         | Implicitly covered by `callTimeout`. If unset: infinite block. | Implicitly covered by total `timeout`. If unset: infinite block. | `pendingAcquireTimeout`                |
| **`connectionEstablishmentTimeout`** | `ConnectTimeout`                                                   | `connectTimeout`                                               | `connectTimeout`                                                 | `ChannelOption.CONNECT_TIMEOUT_MILLIS` |
| **`serverResponseTimeout`**          | Implicitly covered by `ResponseTimeout`.                           | Implicitly covered by `readTimeout`.                           | Implicitly covered by total `timeout`.                           | `responseTimeout`                      |
| **`readInactivityTimeout`**          | `ResponseTimeout`                                                  | `readTimeout`                                                  | Implicitly covered by total `timeout`. If unset: infinite block. | `ReadTimeoutHandler`                   |
| **`writeOperationTimeout`**          | No native limit. Relies on OS-level TCP socket timeouts.           | `writeTimeout`                                                 | Implicitly covered by total `timeout`. If unset: infinite block. | `WriteTimeoutHandler`                  |
| **`totalExecutionTimeout`**          | No native limit. Requires external wrapper (e.g., timed `Future`). | `callTimeout`                                                  | `timeout`                                                        | `.timeout(Duration)`                   |
