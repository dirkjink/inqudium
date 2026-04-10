# ADR-010: TimeLimiter semantics

**Status:** Accepted  
**Date:** 2026-03-23  
**Deciders:** Core team

## Context

A TimeLimiter guards against slow responses by enforcing a maximum duration. The fundamental question is: what exactly
does the TimeLimiter limit — the **caller's wait time** or the **operation's execution time**?

This distinction is critical because the JVM does not provide a safe mechanism to forcibly terminate a running
operation. The two available options — `Thread.interrupt()` and `Thread.stop()` (deprecated since Java 1.2) — both have
severe problems in practice.

### Why Thread.interrupt() is not safe for general use

`Thread.interrupt()` is a cooperative signal, not a termination command. Its behavior depends entirely on what the
interrupted thread is doing at the moment:

**Blocking on I/O or locks:** Some operations respond to interrupts (`Thread.sleep`, `Object.wait`, `LockSupport.park`,
NIO channels). Others ignore them entirely: classic `java.io` streams, many JDBC drivers during network I/O, and native
system calls.

**Resource corruption:** A JDBC PreparedStatement interrupted mid-execute can leave the Connection in an undefined
state. The connection pool reclaims it, and the next caller receives a corrupted connection. These bugs are intermittent
and extremely difficult to diagnose.

**Lock leakage:** If a thread holds a `ReentrantLock` and receives an interrupt between acquire and the
`finally { unlock() }` block, the lock remains held. Depending on timing, this causes deadlocks in unrelated parts of
the application.

**Swallowed interrupts:** A significant number of libraries catch `InterruptedException` without restoring the interrupt
flag (`Thread.currentThread().interrupt()`). The flag is lost, the thread continues, but its interrupt state is
corrupted. The caller's attempt to cancel the operation silently fails.

**The fundamental mismatch:** `Thread.interrupt()` was designed for cooperative cancellation between code that agrees on
interrupt semantics. A resilience library wraps arbitrary third-party code (HTTP clients, database drivers, gRPC stubs)
where the interrupt contract is unknown and untested. Sending an interrupt into unknown code is inherently unsafe.

### What happens to the "orphaned" operation

If the TimeLimiter does not interrupt the thread, the downstream operation continues to completion even after the caller
has moved on. This creates a situation where the operation's side effects (database writes, payment charges, message
publications) execute successfully, but the caller has already responded with a timeout error or fallback.

This is a real concern — but it is not the TimeLimiter's problem to solve. It is a **service design problem**:

- If the downstream operation is not idempotent and may cause harm when its result is discarded, the operation should
  not be called through a TimeLimiter without additional safeguards (idempotency keys, compensation transactions).
- If the downstream operation is idempotent or read-only, the orphaned execution is harmless — it completes, the result
  is discarded, and the resources are reclaimed.

A resilience library cannot retroactively make a non-idempotent operation safe. Attempting to do so by interrupting the
thread creates worse problems (resource corruption, lock leakage) than the orphaned execution it tries to prevent.

### Virtual threads change the cost equation

With virtual threads (Java 21+), an orphaned operation that continues to completion is significantly cheaper than it was
with platform threads. A platform thread consumes ~1MB of stack space and a kernel scheduling slot. A virtual thread
consumes kilobytes and yields its carrier when it blocks on I/O. The resource argument for forceful cancellation is much
weaker in a virtual-thread world.

## Decision

### The TimeLimiter limits the caller's wait time, not the operation's execution time

The TimeLimiter's contract is: "I will wait at most N milliseconds for the result. If the result is not available by
then, I signal a timeout to the caller. The operation itself continues."

This is conceptually equivalent to `Future.get(timeout, unit)` — the Future continues to execute, but the caller stops
waiting.

### Imperative API: CompletionStage only (no synchronous Supplier)

The imperative TimeLimiter operates exclusively on `CompletionStage<T>` / `CompletableFuture<T>`, consistent with the
functional decoration API (ADR-002):

```java
var timeLimiter = TimeLimiter.of("paymentService", TimeLimiterConfig.builder()
    .timeoutDuration(Duration.ofSeconds(3))
    .build());

// Explicit: the caller provides an async operation
CompletionStage<PaymentResult> limited = timeLimiter.decorateCompletionStage(
    () -> paymentGateway.chargeAsync(request));
```

Wrapping a synchronous `Supplier<T>` in a hidden thread — even a virtual thread — is not offered because:

1. **Implicit concurrency is dangerous.** The caller writes synchronous code but the execution is secretly asynchronous.
   Thread-local state, MDC context, security context, and transaction boundaries may not propagate to the hidden thread.
2. **No cancellation contract.** The hidden thread runs to completion after timeout. The caller has no handle to
   influence it.
3. **Explicit is better.** If the caller wants to time-limit a synchronous call, wrapping it in
   `CompletableFuture.supplyAsync()` is a single line and makes the concurrency model visible.

### Paradigm-specific implementations use native timeout primitives

| Paradigm   | Mechanism                                 | Orphaned operation behavior                                                                  |
|------------|-------------------------------------------|----------------------------------------------------------------------------------------------|
| Imperative | `CompletableFuture.orTimeout()`           | Future continues, result discarded                                                           |
| Kotlin     | `withTimeout()`                           | Coroutine cancellation via `CancellationException` — cooperative, no thread interrupt        |
| Reactor    | `Mono.timeout()` / `Flux.timeout()`       | Upstream subscription cancelled via `dispose()` — reactive cancellation, no thread interrupt |
| RxJava 3   | `Single.timeout()` / `Flowable.timeout()` | Upstream subscription disposed — reactive cancellation, no thread interrupt                  |

Note that the reactive and coroutine paradigms have **cooperative cancellation** built into their models.
`Mono.timeout()` cancels the upstream subscription, which propagates cancellation through the reactive chain.
`withTimeout()` throws `CancellationException` at the next suspension point. These mechanisms are fundamentally
different from `Thread.interrupt()` — they work within the paradigm's concurrency model rather than against it.

### No interrupt, even as an opt-in

We explicitly do **not** offer a `cancelRunningFuture(true)` option or a `mayInterruptIfRunning` flag. The rationale:

- Interrupt safety depends on the downstream code, which is outside our control.
- Offering the option implies it is safe to use. It is not safe in general.
- Users who genuinely need interrupt-based cancellation can implement it outside of Inqudium by calling
  `future.cancel(true)` directly — making the decision and its consequences explicit in their code rather than hidden in
  library configuration.

### Exception on timeout

When the TimeLimiter fires, it throws `InqTimeLimitExceededException` (see ADR-009). This exception carries:

- `elementName` — which TimeLimiter instance
- `configuredDuration` — the configured timeout
- `actualDuration` — how long the caller actually waited (may be slightly longer than configured due to scheduling)

The original operation's result or exception is not available at timeout time — the operation is still running. To
handle the eventual outcome of orphaned operations, the TimeLimiter provides an **optional completion callback**.

### Completion callback for orphaned operations

When a timeout fires, the underlying operation continues. Its eventual outcome — success or failure — may still be
relevant to the application. Silently discarding it would hide information that could be critical for compensation,
auditing, or cleanup.

The TimeLimiter accepts an optional `OrphanedCallHandler` that is invoked when an operation completes after its caller
has already timed out:

```java
var timeLimiter = TimeLimiter.of("paymentService", TimeLimiterConfig.builder()
    .timeoutDuration(Duration.ofSeconds(3))
    .onOrphanedResult((context, result) -> {
        // The payment went through after the caller gave up
        log.warn("Orphaned success on [{}]: {}", context.elementName(), result);
        compensationService.reverse(context.callId(), result);
    })
    .onOrphanedError((context, exception) -> {
        // The operation failed after the caller already responded with a fallback
        log.info("Orphaned failure on [{}]: {}", context.elementName(), exception.getMessage());
    })
    .build());
```

`OrphanedCallContext` carries:

- `elementName` — which TimeLimiter instance
- `configuredDuration` — the configured timeout
- `actualDuration` — how long the operation actually took
- `callId` — the call's unique identifier from `CallContext` (ADR-003), usable as idempotency correlation key

Design constraints for the callback:

- **Optional.** If no handler is registered, orphaned results are discarded and an event is emitted through
  `InqEventPublisher` (ADR-003) if diagnostic events are enabled. This keeps the simple case simple.
- **Fire-and-forget.** The callback runs asynchronously after the operation completes. It has no way to influence the
  caller's outcome — the caller has already moved on.
- **Exception-safe.** If the callback itself throws, the exception is caught, logged via `InqEventPublisher`, and does
  not propagate. A misbehaving callback must never affect the resilience element's operation.
- **No caller context.** The callback executes on the thread that completed the orphaned operation, not on the caller's
  thread. Thread-local state, MDC, security context, and transaction boundaries from the caller are not available. If
  the callback needs context (e.g. a trace ID), it should be captured in the `TimeLimiterConfig` or via a
  `CallContextSupplier`.

### Paradigm-specific behavior

| Paradigm   | Orphaned result handling                                                                                 |
|------------|----------------------------------------------------------------------------------------------------------|
| Imperative | `OrphanedCallHandler` callback as described above                                                        |
| Kotlin     | `onOrphanedResult` / `onOrphanedError` suspending lambdas, executed in the element's `CoroutineScope`    |
| Reactor    | `orphanedResult()` / `orphanedError()` returning `Mono<Void>` — composed into the subscription's cleanup |
| RxJava 3   | `doOnOrphanedResult()` / `doOnOrphanedError()` side-effect operators                                     |

## Consequences

**Positive:**

- No risk of resource corruption, lock leakage, or swallowed interrupts.
- Clear contract: the caller controls their wait time, the operation controls its own lifecycle.
- Consistent with how `Future.get(timeout)`, `Mono.timeout()`, and `withTimeout()` already behave — no surprising
  semantics.
- Virtual-thread friendly: orphaned operations are cheap.
- Paradigm-native cancellation in Kotlin/Reactor/RxJava — cooperative, not forceful.
- Orphaned operations are not silently lost — the completion callback provides a structured path for compensation,
  auditing, and cleanup.

**Negative:**

- Orphaned operations consume resources until they complete. In extreme cases (many timeouts, slow downstream), this can
  lead to thread/connection pool exhaustion. Mitigation: combine TimeLimiter with Bulkhead to cap total concurrency.
- No synchronous `decorateSupplier()` for the imperative TimeLimiter. Developers must explicitly use
  `CompletableFuture.supplyAsync()`. This is intentionally inconvenient — it forces awareness of the concurrency model.
- Side effects of orphaned operations (writes, payments) may execute after the caller has moved on. The completion
  callback enables compensation but does not prevent the side effect — that remains a service design problem (
  idempotency).
- The completion callback runs without caller context (no MDC, no security context, no transaction). Developers who need
  context must capture it explicitly.

**Neutral:**

- The TimeLimiter's timeout value should be coordinated with HTTP client timeouts and the Circuit Breaker's
  `slowCallDurationThreshold` — see ADR-012 for the timeout hierarchy and the RSS calculation method.

**Mitigation patterns documented:**

- **Completion callback + compensation:** Register `onOrphanedResult` to trigger a reversal or compensation transaction
  when an orphaned operation succeeds.
- **TimeLimiter + Bulkhead:** Bulkhead caps the total number of concurrent calls. Even if every call times out, the
  orphaned operations are bounded by the Bulkhead's permit count.
- **Idempotency keys:** Use `OrphanedCallContext.callId()` as the idempotency key for downstream calls, ensuring that
  retried or orphaned calls can be deduplicated.
- **Circuit Breaker cascade:** Repeated timeouts trigger the Circuit Breaker (through the pipeline, per ADR-003),
  stopping new calls to the slow service entirely.