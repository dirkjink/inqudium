# Comprehensive Guide: Coordinating WebClient Timeouts with Resilience4j

## Table of Contents

1. [Introduction](#introduction)
2. [The Two Timeout Layers](#the-two-timeout-layers)
   - [WebClient / Netty Timeouts (Network Layer)](#webclient--netty-timeouts-network-layer)
   - [Resilience4j TimeLimiter (Business Logic Layer)](#resilience4j-timelimiter-business-logic-layer)
3. [The Correct Coordination: Netty Inside, TimeLimiter Outside](#the-correct-coordination-netty-inside-timelimiter-outside)
4. [Calculating the TimeLimiter Value](#calculating-the-timelimiter-value)
5. [Consequences of Misconfigured Timeouts](#consequences-of-misconfigured-timeouts)
   - [TimeLimiter Too Short](#timelimiter-too-short)
   - [TimeLimiter Too Long](#timelimiter-too-long)
6. [CircuitBreaker Coordination](#circuitbreaker-coordination)
7. [Resilience4j Aspect Order](#resilience4j-aspect-order)
   - [Impact of Bulkhead on TimeLimiter](#impact-of-bulkhead-on-timelimiter)
   - [Adjusted Calculation](#adjusted-calculation)
8. [Single Source of Truth: Centralized Configuration](#single-source-of-truth-centralized-configuration)
   - [Configuration Properties](#configuration-properties)
   - [WebClient Factory](#webclient-factory)
   - [TimeLimiter Auto-Configuration via Customizer](#timelimiter-auto-configuration-via-customizer)
   - [Adapter Example](#adapter-example)
   - [Benefits of This Approach](#benefits-of-this-approach)
9. [Statistical Timeout Calculation: Root Sum of Squares (RSS)](#statistical-timeout-calculation-root-sum-of-squares-rss)
   - [The Problem with Worst-Case Addition](#the-problem-with-worst-case-addition)
   - [RSS Method from Manufacturing](#rss-method-from-manufacturing)
   - [Application to the Timeout Chain](#application-to-the-timeout-chain)
   - [Mathematical Foundation](#mathematical-foundation)
   - [Connection to the Pythagorean Theorem](#connection-to-the-pythagorean-theorem)
     - [The Angle as a Measure of Dependence](#the-angle-as-a-measure-of-dependence)
   - [Sigma Levels](#sigma-levels)
   - [Choosing the Right Sigma Level per Adapter](#choosing-the-right-sigma-level-per-adapter)
   - [Implementation with Configurable Sigma Level](#implementation-with-configurable-sigma-level)
   - [Comparison of Methods](#comparison-of-methods)
   - [Assumptions and Limitations](#assumptions-and-limitations)
   - [Related Concepts](#related-concepts)
10. [Timeout Ratios: Rules of Thumb](#timeout-ratios-rules-of-thumb)
    - [What Each Phase Actually Measures](#what-each-phase-actually-measures)
    - [Rules of Thumb](#rules-of-thumb)
    - [Connect Timeout Depends on Network Path, Not Server Speed](#connect-timeout-depends-on-network-path-not-server-speed)
    - [Recommended Timeout Profiles](#recommended-timeout-profiles)
    - [Conceptually Clean Configuration: Grouping by Cause](#conceptually-clean-configuration-grouping-by-cause)
    - [Profile Presets with Individual Overrides](#profile-presets-with-individual-overrides)
11. [Sidecar (Service Mesh) Implications](#sidecar-service-mesh-implications)
    - [Communication Path Change](#communication-path-change)
    - [Impact on Each Timeout](#impact-on-each-timeout)
    - [Visualized Shift](#visualized-shift)
    - [Configuration Comparison](#configuration-comparison)
    - [Impact on RSS Calculation](#impact-on-rss-calculation)
12. [Deep Dive: How ReadTimeoutHandler and WriteTimeoutHandler Actually Work](#deep-dive-how-readtimeouthandler-and-writetimeouthandler-actually-work)
    - [ReadTimeoutHandler](#readtimeouthandler)
    - [WriteTimeoutHandler](#writetimeouthandler)
    - [Irrelevance for GET-Only Adapters](#irrelevance-for-get-only-adapters)
13. [The TLS Handshake Gap](#the-tls-handshake-gap)
    - [What CONNECT_TIMEOUT_MILLIS Actually Covers](#what-connect_timeout_millis-actually-covers)
    - [Where the TLS Handshake Falls in the Timeout Chain](#where-the-tls-handshake-falls-in-the-timeout-chain)
    - [Reactor Netty's TLS Handshake Timeout](#reactor-nettys-tls-handshake-timeout)
    - [In a Sidecar Setup](#in-a-sidecar-setup)
    - [Without a Sidecar](#without-a-sidecar)
    - [Corrected Timeout Chain and Configuration](#corrected-timeout-chain-and-configuration)
14. [RestClient: Synchronous / Blocking Differences](#restclient-synchronous--blocking-differences)
    - [Read Timeout (Socket Timeout)](#read-timeout-socket-timeout)
    - [Write Timeout](#write-timeout)
    - [Practical Recommendation](#practical-recommendation)
    - [TLS Handshake Coverage Depends on the Engine](#tls-handshake-coverage-depends-on-the-engine)
15. [Appendix: JVM HTTP Client Timeout Reference Tables](#appendix-jvm-http-client-timeout-reference-tables)
    - [Timeout Configuration Including Data Types & Units](#timeout-configuration-including-data-types--units)
    - [Agnostic HTTP Timeout Configuration Set](#agnostic-http-timeout-configuration-set)
    - [Mapping: Agnostic Configuration to JVM Clients](#mapping-agnostic-configuration-to-jvm-clients)

---

## Introduction

When building microservice architectures with Spring WebClient and Resilience4j, two independent timeout layers exist: **network-level timeouts** (Netty/WebClient) and **business-logic-level timeouts** (Resilience4j TimeLimiter). If these are not properly coordinated, the result is hard-to-diagnose behavior — either one layer is always redundant, or they compete and produce confusing exception types.

This guide covers how to properly align these layers, calculate optimal timeout values statistically, and adapt the configuration to different deployment topologies.

---

## The Two Timeout Layers

### WebClient / Netty Timeouts (Network Layer)

These timeouts operate at the network level. They detect specific problems in individual phases of HTTP communication:

- **`connectTimeout`** — The TCP handshake takes too long (server unreachable).
- **`responseTimeout`** — After sending the request, no first byte comes back (server hangs).
- **`readTimeout`** — Too much time passes between two received data packets (connection stalls).
- **`writeTimeout`** — Data cannot be sent quickly enough.

These timeouts produce network-specific exceptions (`ReadTimeoutException`, `ConnectTimeoutException`, etc.) that enable precise error diagnosis.

### Resilience4j TimeLimiter (Business Logic Layer)

The TimeLimiter wraps the entire `CompletableFuture` and cancels it after a configured duration — regardless of *why* it is taking so long. This could be network latency, but also Jackson deserialization, GC pauses, thread scheduling delays, or an overloaded Tomcat thread pool delaying the continuation.

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

**Netty timeouts handle the normal case** — they recognize a specific network problem quickly and precisely. **The TimeLimiter is the safety net** — it catches everything that the Netty timeouts do not cover and guarantees a maximum overall duration.

---

## Calculating the TimeLimiter Value

The maximum duration of a single WebClient call is the sum of its phases. For a GET request without a body:

```
Maximum Netty duration ≈ connectTimeout + responseTimeout + readTimeout
```

If all three are set to 2500ms, the theoretical maximum Netty duration is **7500ms** (worst case: each timeout is nearly exhausted before the next phase begins).

The TimeLimiter should be **slightly more** than this sum, so that under normal circumstances the Netty timeouts fire first (with their specific exceptions), and the TimeLimiter only serves as the last line of defense:

```
TimeLimiter = connectTimeout + responseTimeout + readTimeout + buffer
            = 2500 + 2500 + 2500 + 500
            = 8000ms
```

The buffer (~500ms) covers what happens after the Netty timeouts: deserialization, filter processing, thread scheduling, and the overhead until the `CompletableFuture` completes.

---

## Consequences of Misconfigured Timeouts

### TimeLimiter Too Short

Example: TimeLimiter at 2000ms with 2500ms Netty timeouts.

The TimeLimiter always fires first. Netty timeouts are never reached. Every timeout appears as a generic `TimeoutException` from the TimeLimiter — the information about whether it was a connect, response, or read problem is lost. Additionally, the `CompletableFuture` is cancelled, but the Netty request continues running in the background until its own timeout triggers — wasting resources.

### TimeLimiter Too Long

Example: TimeLimiter at 30s with 2500ms Netty timeouts.

In most cases, Netty timeouts fire correctly. But in scenarios not covered by Netty timeouts (e.g., extremely slow deserialization, or a configuration bug), the TimeLimiter waits unnecessarily long. The Tomcat thread remains bound for 30 seconds.

---

## CircuitBreaker Coordination

The CircuitBreaker must know which exceptions count as failures. The chain of possible timeout exceptions is:

1. Netty `ConnectTimeoutException` → mapped in the status code filter to `UnsupportedHttpStatusCodeException`
2. Netty `ReadTimeoutException` → also mapped
3. TimeLimiter `TimeoutException` → comes directly from Resilience4j

The CircuitBreaker should count **all three** as failures. If it is only configured for certain exception types, some timeouts will not be counted and the circuit will never open — even though the upstream service has long stopped responding.

---

## Resilience4j Aspect Order

The fixed aspect order in Resilience4j with Spring Boot is:

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( Function ) ) ) ) )
```

`Retry` is applied last (outermost). The CircuitBreaker **wraps** the TimeLimiter. This means the CircuitBreaker **sees** the `TimeoutException` from the TimeLimiter — which is the desired behavior, as timeouts should open the circuit.

### Impact of Bulkhead on TimeLimiter

The Bulkhead sits **inside** the TimeLimiter. This has a subtle consequence: the time a request **waits in the Bulkhead queue** counts against the TimeLimiter budget.

```
TimeLimiter starts measurement
  └── Bulkhead: request waits in queue ... 800ms
        └── Function: WebClient call ........... 7500ms
                                        Total: 8300ms
TimeLimiter fires at 8000ms → TimeoutException
```

In this scenario, the actual HTTP call has not yet reached its Netty timeout, but the TimeLimiter aborts because the Bulkhead wait time was included. The Netty timeouts never fire — specific error diagnosis is lost.

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

The `bulkheadMaxWaitMs` should correspond to the Resilience4j Bulkhead parameter `maxWaitDuration`. If the Bulkhead is configured as a semaphore with `maxWaitDuration = 0` (default), it rejects immediately and plays no role in the calculation. But if a wait time is configured, it must be factored in.

---

## Single Source of Truth: Centralized Configuration

Instead of maintaining timeouts manually in two places (Resilience4j in `application.yml` and WebClient timeouts hardcoded in the configurer), there should be **one central configuration per adapter** from which both Netty and TimeLimiter values are derived:

```yaml
# application.yml
adapter:
  accounts:
    base-url: https://account-engine.internal
    connect-timeout-ms: 2500
    response-timeout-ms: 3000
    read-timeout-ms: 3000
    time-limiter-buffer-ms: 500

  brokerage:
    base-url: https://brokerage.internal
    connect-timeout-ms: 1000
    response-timeout-ms: 5000
    read-timeout-ms: 5000
    time-limiter-buffer-ms: 1000

  reference-data:
    base-url: https://reference-data.internal
    connect-timeout-ms: 1000
    response-timeout-ms: 1500
    read-timeout-ms: 1500
    time-limiter-buffer-ms: 500
```

### Configuration Properties

```kotlin
@ConfigurationProperties(prefix = "adapter")
data class AdapterProperties(
  val accounts: AdapterEndpointConfig = AdapterEndpointConfig(),
  val brokerage: AdapterEndpointConfig = AdapterEndpointConfig(),
  val referenceData: AdapterEndpointConfig = AdapterEndpointConfig(),
  val impacting: AdapterEndpointConfig = AdapterEndpointConfig(),
  val involvedParty: AdapterEndpointConfig = AdapterEndpointConfig(),
  val permissions: AdapterEndpointConfig = AdapterEndpointConfig(),
  val lap: AdapterEndpointConfig = AdapterEndpointConfig(),
  val savingsTarget: AdapterEndpointConfig = AdapterEndpointConfig(),
  val deposits: AdapterEndpointConfig = AdapterEndpointConfig()
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

The TimeLimiter timeout should **not** be manually defined in the Resilience4j YAML section but derived from the same source:

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
      ACCOUNTS -> adapterProperties.accounts.timeouts
      ACCOUNT_DEPOSITS -> adapterProperties.deposits.timeouts
      BROKERAGE_ACTIVATION_STATUS -> adapterProperties.brokerage.timeouts
      IMPACTING_ACTIVATION_STATUS -> adapterProperties.impacting.timeouts
      // ... further mappings
      else -> null
    }
}
```

### Adapter Example

```kotlin
@Component
class AccountEngineAPIReactiveAdapter(
  builder: WebClient.Builder,
  webClientFactory: WebClientFactory,
  adapterProperties: AdapterProperties
) : AccountEngineAPIAdapterSpec {

  private val webClient = webClientFactory.createWebClient(
    builder, adapterProperties.accounts
  )

  @CircuitBreaker(name = ACCOUNTS)
  @Bulkhead(name = ACCOUNTS)
  @TimeLimiter(name = ACCOUNTS)
  override fun findFutureAccounts(
    accountIds: List<AccountNumberValue>
  ): CompletableFuture<PageAccountsDTO> =
    webClient.get()
      .uri { /* ... */ }
      .retrieve()
      .bodyToMono<PageAccountsDTO>()
      .toFuture()
      .nonNull()
}
```

The adapter itself no longer knows any timeout values. It receives a properly configured `WebClient`, and the TimeLimiter is automatically set to match.

### Benefits of This Approach

- **No shared mutable singleton** — `WebClientFactory` is stateless and creates a dedicated WebClient instance per adapter.
- **Base URL differentiation** — each adapter has its own `baseUrl` in the configuration.
- **No double registration** — adapters no longer call `applyDefaultConfiguration` on an already-configured builder.
- **Structural consistency guarantee** — it is impossible for WebClient timeouts and TimeLimiter to diverge, because the TimeLimiter value is computed from the WebClient timeouts. If someone changes `response-timeout-ms` in the YAML, the TimeLimiter adjusts automatically.

---

## Statistical Timeout Calculation: Root Sum of Squares (RSS)

### The Problem with Worst-Case Addition

In practice, it is extremely rare for all timeouts to be exhausted to their full length simultaneously. The worst-case sum of all timeouts is overly conservative, like assuming that every bolt in an assembly simultaneously hits its maximum manufacturing tolerance. Engineering solved this problem decades ago.

### RSS Method from Manufacturing

RSS (Root Sum of Squares) originates from mechanical engineering, where it has been used in mass production since the 1940s. The principle: when multiple independent quantities each have a tolerance, the total tolerance is not their sum but their **quadratic sum**:

```
Worst Case:    T_total = T₁ + T₂ + T₃
Statistical:   T_total = √(T₁² + T₂² + T₃²)
```

The assumption is that individual phases **vary independently**. A slow TCP handshake says nothing about whether the first response byte will also be slow — these can have different causes (network routing vs. backend load).

### Application to the Timeout Chain

With default values of 2500ms each:

```
Worst Case:  2500 + 2500 + 2500         = 7500ms
RSS:         √(2500² + 2500² + 2500²)   = √18,750,000 ≈ 4330ms
```

With different values, e.g., for a brokerage adapter:

```
connect=1000, response=5000, read=5000

Worst Case:  1000 + 5000 + 5000         = 11000ms
RSS:         √(1000² + 5000² + 5000²)   = √51,000,000 ≈ 7141ms
```

The RSS method yields values that are sufficient with approximately **99.73% probability (3σ)** when latencies are normally distributed. In practice, this is a very conservative confidence interval.

### Mathematical Foundation

The basis is the **variance addition of independent random variables**. If X₁, X₂, X₃ are independent random variables:

```
Var(X₁ + X₂ + X₃) = Var(X₁) + Var(X₂) + Var(X₃)
```

The standard deviation (σ) is the square root of the variance:

```
σ_total = √(σ₁² + σ₂² + σ₃²)
```

In manufacturing, tolerances are typically set to 3σ (the 99.73% interval of the normal distribution). If T₁, T₂, T₃ are each 3σ tolerances:

```
σ₁ = T₁/3,  σ₂ = T₂/3,  σ₃ = T₃/3

σ_total = √((T₁/3)² + (T₂/3)² + (T₃/3)²)
        = (1/3) × √(T₁² + T₂² + T₃²)

T_total (3σ) = 3 × σ_total = √(T₁² + T₂² + T₃²)
```

This is the RSS formula — derived directly from variance addition, scaled to the 3σ confidence interval.

### Connection to the Pythagorean Theorem

RSS has the same structure as the Pythagorean theorem — and this is not a coincidence. If tolerances are viewed as vectors in an n-dimensional space, the total tolerance is the **length of the resulting vector** (Euclidean norm). Orthogonality in vector space corresponds to statistical independence. Correlated quantities are no longer perpendicular, and the total length increases — exactly as in the extended formula with correlation coefficients:

```
‖T‖² = T₁² + T₂² + T₃² + 2ρ₁₂·T₁·T₂ + 2ρ₁₃·T₁·T₃ + 2ρ₂₃·T₂·T₃
```

When ρ = 0, the cross-terms vanish and the Pythagorean theorem remains.

#### The Angle as a Measure of Dependence

The relationship between the geometric angle and statistical correlation is not just an analogy — it is mathematically exact. The correlation coefficient ρ between two quantities corresponds to the **cosine of the angle** between their vectors:

**ρ = cos(θ)**

This yields a highly intuitive picture:

- **θ = 90° (perpendicular)** → cos(90°) = 0 → fully independent. The vectors do not interfere with each other, and the total length is exactly the hypotenuse according to Pythagoras: √(T₁² + T₂²). This is the classic RSS case.
- **θ = 0° (parallel)** → cos(0°) = 1 → fully correlated. Both quantities always move in the same direction simultaneously. The total length is the simple sum T₁ + T₂ — the worst case. Geometrically, the vectors lie on top of each other and add directly.
- **θ between 0° and 90°, e.g. 60°** → cos(60°) = 0.5 → partially correlated. The total length falls between RSS and worst case.
- **θ = 180° (opposite)** → cos(180°) = −1 → perfectly negatively correlated. The quantities compensate each other. The total length is |T₁ − T₂| — shorter than either individual tolerance.

The intuition: the more acute the angle, the more the vectors "pull" in the same direction and the more their lengths add up directly. The more perpendicular they stand, the more each "goes its own way" and the stronger the Pythagorean effect that makes the total length shorter than the sum.

Applied to timeouts: if an overloaded network simultaneously slows down both connect *and* read (positively correlated, acute angle), we approach the worst case. The RSS savings over the worst case are greater the more independent the failure causes of the individual phases are — i.e., the more perpendicular the vectors stand.

### Sigma Levels

The RSS formula `√(T₁² + T₂² + T₃²)` yields the **3σ value** of the combined distribution. The combined standard deviation is:

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

- **2σ (95.45%)** — a good default for most adapters. Aggressive enough to not bind Tomcat threads unnecessarily, with the CircuitBreaker catching the remaining ~4.5% that exceed it. Requests exceeding the 2σ value are statistical outliers where a timeout is more useful than further waiting.
- **3σ (99.73%)** — appropriate for critical adapters where a timeout is expensive, e.g., a permissions adapter where a failed call means the customer sees no products at all.
- **1σ (68.27%)** — only appropriate for adapters with a fast fail-over (e.g., cache fallback) where aggressive timeout behavior is desired.

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

In manufacturing, 3σ is typically used because a 0.27% reject rate is acceptable for millions of parts. For timeout configurations with only thousands of requests per second and a CircuitBreaker as a safety net, 2σ is entirely sufficient.

### Assumptions and Limitations

- **Independence** is the central assumption. The variance addition formula holds exactly only for uncorrelated variables. With positive correlation, RSS underestimates the actual spread.
- **Normal distribution** is often cited as a requirement but is stricter than necessary. Variance addition holds for arbitrary distributions. The normal distribution provides the interpretation as a confidence interval. The **Central Limit Theorem** applies: the sum of many independent variables approaches a normal distribution regardless of individual distributions.
- **Symmetry** is implicitly assumed. Timeouts are one-sided — they cannot be negative. Network latency distributions are typically right-skewed (long upper tail). RSS therefore slightly underestimates the upper tail. In practice, the buffer term (`timeLimiterBufferMs`) compensates for this effect sufficiently.

### Related Concepts

- **Gaussian Error Propagation** — the general form, of which RSS is a special case. It handles arbitrary functions f(x₁, x₂, ...) using partial derivatives to calculate the uncertainty of the result. For a simple sum f = x₁ + x₂ + x₃, all partial derivatives equal 1, and RSS remains.
- **Monte Carlo Simulation** — the numerical counterpart to RSS. Instead of calculating analytically, it simulates thousands of runs with random values and measures the resulting distribution. More accurate for non-normally distributed or correlated quantities, but more complex. For timeout configurations this would be overkill — but it could be fed with real latency measurement data from monitoring to validate the RSS estimate.

---

## Timeout Ratios: Rules of Thumb

Setting all four timeouts to the same value (e.g., 2500ms each) indicates that the different characteristics of each phase have not been considered. Each timeout measures a fundamentally different phase.

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

**Connect timeout** should be the **shortest**. A TCP handshake within the same data center typically takes under 5ms, between data centers under 50ms. If the handshake takes 2500ms, the server is unreachable — waiting longer changes nothing.

**Response timeout** is typically the **longest** value. It measures the server's processing time — database queries, business logic, potential downstream calls. This phase has the greatest natural variance. The response timeout is the **dominant timeout** around which the others are oriented.

**Read timeout** measures the pause **between** two data packets, not the total download duration. Once the server has started responding, packets should flow relatively evenly. A long pause indicates hanging streaming or a broken connection. Rule of thumb: **read = 1/2 to 2/3 of the response timeout**.

**Write timeout** is nearly irrelevant for GET requests — the request body is empty, only headers are sent. Even for POST requests with JSON bodies, payloads are typically small enough to fit in a single TCP packet. Rule of thumb: **write = connect** (short, because little data).

### Connect Timeout Depends on Network Path, Not Server Speed

The connect timeout has **nothing** to do with the response timeout. They measure completely independent things. The connect timeout should be based on **network round-trip time (RTT)** with a safety factor for variance and retransmits:

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

The large jumps come from the fact that a SYN retransmit on packet loss typically takes 1 second. A connect timeout of 500ms within the same data center allows no retransmit — which is intentional, because packet loss within a DC indicates a serious problem that should be detected quickly.

### Recommended Timeout Profiles

```
                    connect   response   read    write

Fast Service          500      1500      1000      500
(reference data,
 small payloads)

Standard Service     1000      3000      2000     1000
(account engine,
 moderate payloads)

Slow Service         1000      5000      3000     1000
(brokerage with
 aggregation,
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
  reference-data:
    base-url: https://reference-data.internal
    network: datacenter-internal
    response-timeout-ms: 1500    # fast master data
    read-timeout-ms: 1000        # small payload

  brokerage:
    base-url: https://brokerage.internal
    network: datacenter-internal  # same DC, same connect
    response-timeout-ms: 5000    # slow aggregation
    read-timeout-ms: 3000        # larger payload

  permissions:
    base-url: https://permissions.internal
    network: datacenter-internal
    response-timeout-ms: 3000
    read-timeout-ms: 2000
```

This makes visible that `reference-data` and `brokerage` share the same connect timeout (same DC) but have completely different response timeouts (different processing complexity).

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
  reference-data:
    base-url: https://reference-data.internal
    profile: FAST

  brokerage:
    base-url: https://brokerage.internal
    profile: SLOW

  permissions:
    base-url: https://permissions.internal
    profile: STANDARD
    timeouts:
      response-timeout-ms: 5000  # override only this
```

---

## Sidecar (Service Mesh) Implications

When connections go through a sidecar (e.g., Envoy/Istio), the timeout semantics shift fundamentally.

### Communication Path Change

```
Without sidecar:   App ──── Network ──── Upstream Server

With sidecar:      App ── localhost ── Sidecar ── Network ── Upstream Server
```

From the WebClient's perspective, only the connection to localhost exists. The entire network path to the actual upstream server is **hidden behind the sidecar**.

### Impact on Each Timeout

**Connect timeout** — becomes trivial. The TCP handshake goes to localhost, taking under 0.1ms. A connect timeout of 100–200ms is more than sufficient.

**Write timeout** — also becomes trivial. Request bytes are pushed over the loopback interface only. No real network, no bandwidth limitation, no packet loss. 200ms suffices.

**Response timeout** — here the critical shift happens. This timeout now measures not just the upstream server's processing time but the **entire chain** the sidecar traverses:

```
Response timeout from the app's perspective now encompasses:
  ├── Sidecar processes the request internally
  ├── Sidecar establishes TCP connection to upstream  ← (previously: connect)
  ├── Sidecar sends request over the network          ← (previously: write)
  ├── Upstream server processes                       ← (previously: response)
  └── First response byte travels back via            ← (previously: part of read)
      Network → Sidecar → localhost → App
```

The response timeout **absorbs** the network latencies that previously resided in the connect and write timeouts. It must be set **higher** than in a setup without a sidecar.

**Read timeout** — partially affected. It still measures the pause between two received data packets, but packets now come from the sidecar over localhost. In practice, the effect is often minor because the sidecar typically buffers the entire response and delivers it in one go — making the read timeout nearly irrelevant as all bytes arrive virtually simultaneously over localhost.

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
  brokerage:
    network: datacenter-internal
    connect-timeout-ms: 500
    write-timeout-ms: 500
    response-timeout-ms: 5000
    read-timeout-ms: 3000

# With sidecar
adapter:
  brokerage:
    network: sidecar-localhost
    connect-timeout-ms: 200
    write-timeout-ms: 200
    response-timeout-ms: 6500     # Server + absorbed network RTT
    read-timeout-ms: 1000         # localhost, sidecar usually buffers
```

The total budget remains in the same range — the latency budgets simply shift from connect/write/read into the response timeout.

### Impact on RSS Calculation

This has a surprising side-effect on the TimeLimiter calculation. Without sidecar, variance distributes across three to four timeouts:

```
RSS without sidecar:  √(500² + 500² + 5000² + 3000²)  ≈ 5882ms
```

With sidecar, almost all variance concentrates in a single timeout:

```
RSS with sidecar:     √(200² + 200² + 6500² + 1000²)  ≈ 6578ms
```

RSS values approach the response timeout because a single dominant summand determines the quadratic sum. This means **RSS provides less savings over the worst case in a sidecar setup** because the independence between phases is effectively gone — there is almost only one relevant phase.

---

## Deep Dive: How ReadTimeoutHandler and WriteTimeoutHandler Actually Work

### ReadTimeoutHandler

Measures the **idle time** between two received data packets. It is not a timer per operation but a continuous timer that resets with every received packet. If nothing arrives for the configured duration, it fires.

```
ReadTimeoutHandler:
  received ──── pause ──── received ──── pause ──── received
                ├─ idle ─┤              ├─ idle ─┤
                must not                must not
                > timeout               > timeout
```

**Important consequence:** This is **not** an absolute timeout for the entire HTTP request. If a server responds very slowly but sends a small data packet every second continuously, a `ReadTimeoutHandler` set to 5 seconds will never trigger, even if the entire download takes minutes.

### WriteTimeoutHandler

Measures the **completion time** of a single write operation. The timer starts when `write()` is called and stops when the operation is complete (data handed to the TCP stack).

```
WriteTimeoutHandler:
  write(chunk) ─────── complete
  ├── must not > timeout ──┤
```

For large request bodies split into multiple writes, each individual write operation has its own timeout budget. The total transfer duration could be `n × writeTimeout` without the handler firing.

### Irrelevance for GET-Only Adapters

All adapters using exclusively GET requests have no body. The only bytes written are the HTTP request line and headers — typically under 1 KB. This is a single write operation that completes in under a millisecond. The WriteTimeoutHandler has practically **no chance** to fire. It would only be relevant for adapters with POST/PUT and large request bodies.

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
- **Read/WriteTimeoutHandler** — registered in `doOnConnected`, which fires only **after** the complete connection establishment including TLS. They do not apply during the TLS handshake.
- **Response timeout** — counts only from the moment the HTTP request is fully sent. The TLS handshake happens before that.

The TLS handshake is therefore covered by **none of the four configured timeouts**.

### Reactor Netty's TLS Handshake Timeout

Reactor Netty has a **dedicated** configuration point:

```kotlin
HttpClient.create()
  .secure { spec ->
    spec.handshakeTimeout(Duration.ofMillis(3000))
  }
```

The default in Reactor Netty is **10 seconds**. If `secure()` is never called, this default applies — meaning a hanging TLS handshake can block for 10 seconds unnoticed while all other timeouts are set to 2500ms.

### In a Sidecar Setup

The problem is mitigated: the connection between app and sidecar is typically **plain HTTP** over localhost. The TLS handshake happens between sidecar and upstream — and from the app's perspective, it is absorbed into the response timeout.

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

## RestClient: Synchronous / Blocking Differences

The `RestClient` (introduced in Spring 6.1 as the modern successor to `RestTemplate`) works **synchronously and blocking**. There are no reactive pipelines or event handlers like `ReadTimeoutHandler` or `WriteTimeoutHandler`. Instead, the `RestClient` delegates all work to the underlying HTTP library (the `ClientHttpRequestFactory`).

### Read Timeout (Socket Timeout)

When the `RestClient` wants to read data from the server, it calls a blocking `read()` method on the network socket. The thread physically halts at this point. The read timeout (`SO_TIMEOUT`) defines the maximum time the thread may block while waiting for the next data packet. Functionally, this is similar to the WebClient's behavior — it is the maximum "silence" time between two received packets. The technical difference is that a thread is physically stopped here, whereas in the WebClient only a timer ticks in an event loop.

### Write Timeout

There is **no direct equivalent** to Netty's `WriteTimeoutHandler` in standard `RestClient` implementations. When writing synchronously, the `write()` call blocks until the data is copied into the OS socket buffer. If the buffer is full, the thread simply continues to block. You are typically reliant on OS-level TCP retransmission timeouts, which often only force a disconnect after several minutes.

### Practical Recommendation

With `RestClient`, you typically configure only a **connection timeout** (TCP handshake time) and a **read timeout** (maximum wait for the next data packet). Write timeouts at the application level are generally not available for blocking I/O.

### TLS Handshake Coverage Depends on the Engine

The behavior depends on which HTTP library is used under the hood:

**Modern clients (JDK `HttpClient` Java 11+, OkHttp):** The connect timeout covers the *entire* logical connection establishment, including DNS resolution, TCP handshake, and TLS handshake. If the server opens the TCP socket but hangs during certificate negotiation, the connect timeout correctly fires.

**Classic clients (`HttpURLConnection`):** The connect timeout applies strictly to the TCP level only. Once the TCP handshake completes, the socket is considered "connected." The subsequent TLS handshake requires the client to *read* certificate data from the server. If the server hangs at this point, ironically the **read timeout** fires, since TCP connect was already successful and the client is now blocking while waiting for data.

**Apache HttpClient (v4/v5):** The classic `ConnectTimeout` also primarily covers the TCP socket. The TLS handshake happens deep in the `ConnectionSocketFactory`. If the server sends no data during the TLS handshake, the **socket timeout (read timeout)** typically fires.

**Best practice:** Always set **both** connect and read timeouts for synchronous clients.

---

## Appendix: JVM HTTP Client Timeout Reference Tables

### Timeout Configuration Including Data Types & Units

| HTTP Client (JVM)                      | Timeout Parameter                             | Data Type & Unit                        | Monitored Time Span                                          |
| :------------------------------------- | :-------------------------------------------- | :-------------------------------------- | :----------------------------------------------------------- |
| **Apache HttpClient**                  | `ConnectTimeout`                              | **v5:** `Timeout`<br>**v4:** `int` (ms) | Time limit for the initial TCP handshake.                    |
|                                        | `ResponseTimeout` (v5) / `SocketTimeout` (v4) | **v5:** `Timeout`<br>**v4:** `int` (ms) | Maximum inactivity time between two received data packets.   |
|                                        | `ConnectionRequestTimeout`                    | **v5:** `Timeout`<br>**v4:** `int` (ms) | Maximum wait time for a free connection from the connection pool. |
| **OkHttp**                             | `connectTimeout`                              | `long` + `TimeUnit` or `Duration`       | Time for establishing the TCP connection and TLS handshake.  |
|                                        | `readTimeout`                                 | `long` + `TimeUnit` or `Duration`       | Maximum inactivity between two successful read operations.   |
|                                        | `writeTimeout`                                | `long` + `TimeUnit` or `Duration`       | Maximum time a single write operation is allowed to block on the network socket. |
|                                        | `callTimeout`                                 | `long` + `TimeUnit` or `Duration`       | Hard upper limit for the entire call.                        |
| **Java 11+ HttpClient**                | `connectTimeout`                              | `java.time.Duration`                    | Maximum time allowed for establishing the connection.        |
|                                        | `timeout`                                     | `java.time.Duration`                    | Maximum total time for the specific request.                 |
| **Spring WebClient** *(Reactor Netty)* | `CONNECT_TIMEOUT_MILLIS`                      | `Integer` (strictly **ms**)             | Configured in Netty `ChannelOption`. Monitors purely the TCP establishment. |
|                                        | `ReadTimeoutHandler`                          | `int` + `TimeUnit` or `Duration`        | Netty level: Time span without new received data.            |
|                                        | `WriteTimeoutHandler`                         | `int` + `TimeUnit` or `Duration`        | Netty level: Maximum time a single write operation to the socket is allowed to block. |
|                                        | `responseTimeout`                             | `java.time.Duration`                    | HttpClient level: Wait time for the response (headers) after sending the request. |
|                                        | `.timeout()`                                  | `java.time.Duration`                    | Reactive operator: Hard upper limit for the asynchronous pipeline. |

### Agnostic HTTP Timeout Configuration Set

| Timeout Type                 | Agnostic Parameter Name          | Monitored Time Span                                          | Equivalent in Common Clients                                 |
| :--------------------------- | :------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
| **Pool Wait Time**           | `connectionAcquireTimeout`       | The maximum time waiting for a free TCP connection from an internal pool. | Apache: `ConnectionRequestTimeout`<br>Spring: `ConnectionProvider` |
| **Connection Establishment** | `connectionEstablishmentTimeout` | The time for the actual TCP connection (and TLS handshake).  | OkHttp/Java 11+: `connectTimeout`<br>Apache: `ConnectTimeout` |
| **Server Response Time**     | `serverResponseTimeout`          | Maximum time waited for the server to start responding (TTFB) after the request is sent. | Spring: `responseTimeout`                                    |
| **Read Inactivity**          | `readInactivityTimeout`          | Maximum wait time between two received data packets during download. | OkHttp: `readTimeout`<br>Apache: `ResponseTimeout`           |
| **Write Operation**          | `writeOperationTimeout`          | Maximum time a single write operation to the network socket is allowed to block. | OkHttp: `writeTimeout`<br>Spring: `WriteTimeoutHandler`      |
| **Total Execution Time**     | `totalExecutionTimeout`          | Absolute upper limit for the entire lifecycle of the call.   | Java 11+: `timeout`<br>OkHttp: `callTimeout`<br>Spring: `.timeout()` |

### Mapping: Agnostic Configuration to JVM Clients

| Agnostic Parameter                   | Apache HttpClient (v5)                                       | OkHttp                                                       | Java 11+ HttpClient                                          | Spring WebClient *(Reactor Netty)*     |
| :----------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- | :------------------------------------- |
| **`connectionAcquireTimeout`**       | `ConnectionRequestTimeout`                                   | Implicitly covered by `callTimeout`. If unset: infinite block. | Implicitly covered by total `timeout`. If unset: infinite block. | `pendingAcquireTimeout`                |
| **`connectionEstablishmentTimeout`** | `ConnectTimeout`                                             | `connectTimeout`                                             | `connectTimeout`                                             | `ChannelOption.CONNECT_TIMEOUT_MILLIS` |
| **`serverResponseTimeout`**          | Implicitly covered by `ResponseTimeout`.                     | Implicitly covered by `readTimeout`.                         | Implicitly covered by total `timeout`.                       | `responseTimeout`                      |
| **`readInactivityTimeout`**          | `ResponseTimeout`                                            | `readTimeout`                                                | Implicitly covered by total `timeout`. If unset: infinite block. | `ReadTimeoutHandler`                   |
| **`writeOperationTimeout`**          | No native limit. Relies on OS-level TCP socket timeouts.     | `writeTimeout`                                               | Implicitly covered by total `timeout`. If unset: infinite block. | `WriteTimeoutHandler`                  |
| **`totalExecutionTimeout`**          | No native limit. Requires external wrapper (e.g., timed `Future`). | `callTimeout`                                                | `timeout`                                                    | `.timeout(Duration)`                   |
