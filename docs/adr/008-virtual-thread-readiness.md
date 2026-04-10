# ADR-008: Virtual-thread-ready imperative primitives

**Status:** Accepted  
**Date:** 2026-03-22  
**Deciders:** Core team

## Context

Java 21 introduced virtual threads (JEP 444) as a production-ready feature. Virtual threads are scheduled by the JVM
onto a small pool of carrier threads (platform threads). The key property: when a virtual thread blocks on I/O or a
lock, the JVM *unmounts* it from the carrier thread, freeing the carrier for other virtual threads.

However, this unmounting does **not** happen in all cases. Two common Java concurrency primitives cause *carrier-thread
pinning* — the virtual thread holds onto its carrier, blocking the entire carrier for the duration of the operation:

1. **`synchronized` blocks and methods.** When a virtual thread enters a `synchronized` block and blocks inside it (e.g.
   on I/O or another lock), the carrier thread is pinned. The JVM cannot unmount the virtual thread because the monitor
   is tied to the OS thread. This is a known limitation documented in JEP 444.

2. **`Object.wait()`** called inside a `synchronized` block. Same pinning behavior.

`Thread.sleep()` was updated in Java 21 to correctly yield virtual threads. However, `LockSupport.parkNanos()` is the
lower-level primitive that `Thread.sleep` delegates to, and using it directly avoids the `InterruptedException` ceremony
and provides more explicit control over the wait mechanism.

## Decision

The imperative modules (`inqudium-circuitbreaker`, `inqudium-retry`, etc.) use the following primitives:

### Locking: `ReentrantLock` instead of `synchronized`

```java
// ✗ Pins the carrier thread when a virtual thread blocks inside
synchronized (stateLock) {
    // state transition logic
}

// ✓ Virtual thread unmounts while waiting to acquire the lock
stateLock.lock();
try {
    // state transition logic
} finally {
    stateLock.unlock();
}
```

`ReentrantLock` cooperates with the virtual thread scheduler. When a virtual thread calls `lock()` and the lock is held
by another thread, the virtual thread is unmounted from its carrier — the carrier is freed to run other virtual threads.
This is fundamentally different from `synchronized`, where the entire carrier blocks.

### Waiting: `LockSupport.parkNanos` instead of `Thread.sleep`

```java
// ✗ Works with virtual threads since Java 21, but throws InterruptedException
Thread.sleep(Duration.ofMillis(500));

// ✓ Lower-level, no checked exception, explicit intent
LockSupport.parkNanos(Duration.ofMillis(500).toNanos());
```

Both approaches correctly yield virtual threads since Java 21. We choose `parkNanos` for three reasons:

1. **No `InterruptedException` handling.** Resilience elements have well-defined cancellation semantics via their
   config (timeout, max attempts). Interrupt-based cancellation adds complexity without benefit in this context.
2. **Explicit intent.** `parkNanos` communicates that we are deliberately parking the thread for a computed duration,
   not "sleeping" in a general sense.
3. **Consistency.** `LockSupport` is already the primitive used by `ReentrantLock` internally. Using it directly for
   waits keeps the concurrency model uniform.

### Concurrency limiting: `java.util.concurrent.Semaphore`

`j.u.c.Semaphore` already cooperates with virtual threads — `acquire()` unmounts the virtual thread while waiting for a
permit. No change needed here.

### What this does NOT cover

This ADR addresses only the **imperative** modules. Kotlin, Reactor, and RxJava modules use their own paradigm-native
primitives (ADR-004) and are unaffected by this decision.

## Consequences

**Positive:**

- Inqudium's imperative elements work correctly with virtual threads out of the box. No carrier-thread pinning, no
  throughput degradation under high concurrency.
- Applications using `Executors.newVirtualThreadPerTaskExecutor()` or Spring Boot's virtual thread support can use
  Inqudium without any special configuration.
- The `ReentrantLock` API provides `tryLock(timeout)` which enables future optimizations (e.g. bounded wait for circuit
  breaker state transitions).

**Negative:**

- `ReentrantLock` requires explicit `try/finally` blocks, making the code slightly more verbose than `synchronized`.
- `LockSupport.parkNanos` does not throw `InterruptedException`, which means interrupt-based cancellation is silently
  ignored. This is acceptable because resilience elements have their own cancellation mechanisms (timeout config,
  circuit breaker rejection), but it should be documented.

**Verification:**

- Integration tests run each imperative element under `Executors.newVirtualThreadPerTaskExecutor()` with high
  concurrency (10,000+ virtual threads) and verify:
    - No carrier-thread pinning (monitored via `jdk.tracePinnedThreads` JVM flag)
    - Correct behavior under contention
    - Throughput does not degrade compared to platform threads
