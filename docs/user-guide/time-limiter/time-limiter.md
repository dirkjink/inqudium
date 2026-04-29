# Time Limiter

The time limiter bounds the **caller's wait time**. It does not interrupt the downstream operation — orphaned operations
continue to completion in the background.

## Quick start

```java
var config = TimeLimiterConfig.builder()
    .timeoutDuration(Duration.ofSeconds(5))
    .onOrphanedResult((ctx, result) ->
        log.warn("Orphaned result from {}: {}", ctx.elementName(), result))
    .onOrphanedError((ctx, error) ->
        log.error("Orphaned error from {}: {}", ctx.elementName(), error.getMessage()))
    .build();
```

## Orphaned calls

When the time limiter fires, the caller receives `InqTimeLimitExceededException` (`INQ-TL-001`). But the downstream
operation may still be running. The `onOrphanedResult` and `onOrphanedError` handlers let you observe what happens to
the orphaned call:

```java
.onOrphanedResult((ctx, result) -> {
    log.info("Call to {} completed {}ms after timeout with: {}",
        ctx.elementName(), ctx.actualDuration().toMillis(), result);
})
```

This is important for operations with side effects (database writes, payment processing). The timeout fires, but the
write may still succeed.

## Timeout profile — deriving consistent timeouts

`InqTimeoutProfile` computes the TimeLimiter timeout and Circuit Breaker slow call threshold from your HTTP client
timeouts. This prevents the common misconfiguration where the TimeLimiter fires before the HTTP client, or the slow call
threshold is set arbitrarily:

```java
var profile = InqTimeoutProfile.builder()
    .connectTimeout(Duration.ofMillis(250))   // from your HTTP client
    .responseTimeout(Duration.ofSeconds(3))   // from your HTTP client
    .method(TimeoutCalculation.RSS)           // statistical tolerance analysis
    .safetyMarginFactor(1.2)                  // 20% above computed value
    .build();

var tlConfig = TimeLimiterConfig.builder()
    .timeoutDuration(profile.timeLimiterTimeout())
    .build();

var cbConfig = CircuitBreakerConfig.builder()
    .slowCallDurationThreshold(profile.slowCallDurationThreshold())
    .build();
```

RSS (Root Sum of Squares) produces tighter timeouts than simple addition because it assumes timeout components are
statistically independent. Use `WORST_CASE` for a conservative upper bound.

## Error code

| Code         | Exception                       | When                                         |
|--------------|---------------------------------|----------------------------------------------|
| `INQ-TL-001` | `InqTimeLimitExceededException` | Caller wait time exceeded configured timeout |

---

## Configuration reference

### TimeLimiterConfig

| Parameter          | Type                                         | Default        | Description                                                                   |
|--------------------|----------------------------------------------|----------------|-------------------------------------------------------------------------------|
| `timeoutDuration`  | `Duration`                                   | `5s`           | Maximum time the caller waits. The operation may continue after this timeout. |
| `onOrphanedResult` | `BiConsumer<OrphanedCallContext, Object>`    | `null`         | Called when an orphaned call completes successfully.                          |
| `onOrphanedError`  | `BiConsumer<OrphanedCallContext, Throwable>` | `null`         | Called when an orphaned call fails.                                           |
| `compatibility`    | `InqCompatibility`                           | `ofDefaults()` | Behavioral change flags.                                                      |

**OrphanedCallContext fields:**

| Field                | Type       | Description                                 |
|----------------------|------------|---------------------------------------------|
| `elementName`        | `String`   | The time limiter instance name.             |
| `configuredDuration` | `Duration` | The configured timeout.                     |
| `actualDuration`     | `Duration` | How long the operation actually took.       |
| `callId`             | `String`   | The unique call identifier for correlation. |

**Full example:**

```java
TimeLimiterConfig.builder()
    .timeoutDuration(Duration.ofSeconds(3))
    .onOrphanedResult((ctx, result) ->
        log.warn("{}: orphaned call completed after {}ms",
            ctx.callId(), ctx.actualDuration().toMillis()))
    .onOrphanedError((ctx, error) ->
        log.error("{}: orphaned call failed: {}",
            ctx.callId(), error.getMessage()))
    .build();
```

### InqTimeoutProfile

| Parameter            | Type                 | Default | Description                                               |
|----------------------|----------------------|---------|-----------------------------------------------------------|
| Network Timeout      | `Duration`           | —       | Agnostic Network Timeout Configuration Set. See below     |
| `additionalTimeout`  | `Duration`           | —       | Extra components (TLS handshake, DNS, etc.). Repeatable.  |
| `method`             | `TimeoutCalculation` | `RSS`   | `RSS` (statistical) or `WORST_CASE` (sum).                |
| `safetyMarginFactor` | `double`             | `1.2`   | Factor applied to computed timeout. `1.2` = 20% headroom. |

#### Agnostic HTTP Timeout Configuration Set

| Timeout Type                 | Agnostic Parameter Name          | Monitored Time Span                                                                      | Equivalent in Common Clients                                       |
|:-----------------------------|:---------------------------------|:-----------------------------------------------------------------------------------------|:-------------------------------------------------------------------|
| **Pool Wait Time**           | `connectionAcquireTimeout`       | The maximum time waiting for a free TCP connection from an internal pool.                | Apache: `ConnectionRequestTimeout`<br>Spring: `ConnectionProvider` |
| **Connection Establishment** | `connectionEstablishmentTimeout` | The time for the actual TCP connection (and TLS handshake).                              | OkHttp/Java 11+: `connectTimeout`<br>Apache: `ConnectTimeout`      |
| **Server Response Time**     | `serverResponseTimeout`          | Maximum time waited for the server to start responding (TTFB) after the request is sent. | Spring: `responseTimeout`                                          |
| **Read Inactivity**          | `readInactivityTimeout`          | Maximum wait time between two received data packets during download.                     | OkHttp: `readTimeout`<br>Apache: `ResponseTimeout`                 |
| **Write Operation**          | `writeOperationTimeout`          | Maximum time a single write operation to the network socket is allowed to block.         | OkHttp: `writeTimeout`<br>Spring: `WriteTimeoutHandler`            |

#### Mapping: Agnostic Configuration to JVM Clients

| Agnostic Parameter                   | Apache HttpClient (v5)                                             | OkHttp                                                         | Java 11+ HttpClient                                              | Spring WebClient *(Reactor Netty)*     |
|:-------------------------------------|:-------------------------------------------------------------------|:---------------------------------------------------------------|:-----------------------------------------------------------------|:---------------------------------------|
| **`connectionAcquireTimeout`**       | `ConnectionRequestTimeout`                                         | Implicitly covered by `callTimeout`. If unset: infinite block. | Implicitly covered by total `timeout`. If unset: infinite block. | `pendingAcquireTimeout`                |
| **`connectionEstablishmentTimeout`** | `ConnectTimeout`                                                   | `connectTimeout`                                               | `connectTimeout`                                                 | `ChannelOption.CONNECT_TIMEOUT_MILLIS` |
| **`serverResponseTimeout`**          | Implicitly covered by `ResponseTimeout`.                           | Implicitly covered by `readTimeout`.                           | Implicitly covered by total `timeout`.                           | `responseTimeout`                      |
| **`readInactivityTimeout`**          | `ResponseTimeout`                                                  | `readTimeout`                                                  | Implicitly covered by total `timeout`. If unset: infinite block. | `ReadTimeoutHandler`                   |
| **`writeOperationTimeout`**          | No native limit. Relies on OS-level TCP socket timeouts.           | `writeTimeout`                                                 | Implicitly covered by total `timeout`. If unset: infinite block. | `WriteTimeoutHandler`                  |
| **`totalExecutionTimeout`**          | No native limit. Requires external wrapper (e.g., timed `Future`). | `callTimeout`                                                  | `timeout`                                                        | `.timeout(Duration)`                   |

#### Timeout Configuration incl. Data Types & Units (JVM)

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

**Output methods:**

| Method                        | Returns    | Description                                                |
|-------------------------------|------------|------------------------------------------------------------|
| `timeLimiterTimeout()`        | `Duration` | Recommended TimeLimiter timeout.                           |
| `slowCallDurationThreshold()` | `Duration` | Recommended CB slow call threshold (= timeLimiterTimeout). |

**Full example:**

```java
var profile = InqTimeoutProfile.builder()
    .connectTimeout(Duration.ofMillis(250))
    .responseTimeout(Duration.ofSeconds(3))
    .additionalTimeout(Duration.ofMillis(100))  // TLS handshake
    .method(TimeoutCalculation.RSS)
    .safetyMarginFactor(1.2)
    .build();
```
