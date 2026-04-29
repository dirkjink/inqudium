# ADR-016: Sliding window design

**Status:** Accepted  
**Date:** 2026-03-23  
**Deciders:** Core team

## Context

The sliding window is the foundation of the Circuit Breaker's failure rate calculation. It records the outcome of each
call (success, failure, slow) and computes the current failure rate and slow call rate. When these rates exceed their
configured thresholds, the Circuit Breaker transitions from CLOSED to OPEN.

### Why the sliding window design is an architectural decision

The sliding window is shared across all paradigms (ADR-005). It lives in `inqudium-core` as a pure algorithm. Every
paradigm module — imperative, Kotlin, Reactor, RxJava — delegates to the same sliding window implementation for failure
rate calculation. A design error here propagates to every paradigm.

The two critical constraints:

1. **Purity.** The sliding window must have no threading dependencies — no locks, no atomics, no volatile fields. The
   calling paradigm module provides concurrency control (ReentrantLock, Mutex, Sinks, etc. per ADR-004). The window
   computes; the caller synchronizes.

2. **Testability.** The window's behavior depends on time (for time-based windows) and ordering (for count-based
   windows). Both must be deterministic in tests. Real wall-clock time makes tests flaky, slow, and non-reproducible.

### Two window types

**Count-based:** Records the last N calls in a circular buffer. Failure rate = failures in the buffer / N. Simple,
predictable, but does not account for time — a burst of failures followed by a long quiet period still shows the old
failure rate until N new calls flush the buffer.

**Time-based:** Records all calls within the last N seconds in time buckets. Failure rate = failures in the window /
total calls in the window. More responsive to changes in traffic patterns, but requires a time source and is affected by
clock resolution.

Both types are commonly used in production. The choice depends on the use case — count-based for services with steady
traffic, time-based for services with variable traffic.

## Decision

### Two implementations, one contract

The sliding window contract is an interface in core:

```java
public interface SlidingWindow {

    /**
     * Records a call outcome. Returns the updated snapshot.
     * Pure — no side effects, no synchronization. The caller holds the lock.
     */
    WindowSnapshot record(CallOutcome outcome);

    /**
     * Returns the current snapshot without recording a new outcome.
     * Used for inspection (metrics, actuator) without modifying state.
     */
    WindowSnapshot snapshot();

    /**
     * Resets the window to its initial state.
     * Called when the Circuit Breaker transitions to CLOSED after recovery.
     */
    void reset();
}
```

`CallOutcome` is a value object:

```java
public record CallOutcome(
    boolean success,
    long durationNanos,
    Instant timestamp       // provided by the caller's clock
) {
    public static CallOutcome success(long durationNanos, Instant timestamp) { ... }
    public static CallOutcome failure(long durationNanos, Instant timestamp) { ... }
}
```

`WindowSnapshot` provides the computed rates:

```java
public record WindowSnapshot(
    float failureRate,              // 0.0 to 100.0
    float slowCallRate,             // 0.0 to 100.0
    int totalCalls,
    int failedCalls,
    int slowCalls,
    int successfulCalls,
    int windowSize                  // configured size (count or seconds)
) {
    public boolean hasMinimumCalls(int minimumNumberOfCalls) {
        return totalCalls >= minimumNumberOfCalls;
    }
}
```

### Count-based sliding window: circular buffer

Internally, a fixed-size array of `CallOutcome` values. New outcomes overwrite the oldest entry. Running counters (
totalFailures, totalSlowCalls) are updated incrementally — no full-buffer scan on each call.

```java
public class CountBasedSlidingWindow implements SlidingWindow {

    private final CallOutcome[] buffer;
    private final int size;
    private int head;
    private int count;
    private int failures;
    private int slowCalls;
    private final long slowCallDurationThresholdNanos;

    // ...

    @Override
    public WindowSnapshot record(CallOutcome outcome) {
        if (count >= size) {
            // Evict oldest entry — decrement its contribution
            var evicted = buffer[head];
            if (!evicted.success()) failures--;
            if (evicted.durationNanos() > slowCallDurationThresholdNanos) slowCalls--;
        }
        buffer[head] = outcome;
        head = (head + 1) % size;
        if (count < size) count++;
        if (!outcome.success()) failures++;
        if (outcome.durationNanos() > slowCallDurationThresholdNanos) slowCalls++;

        return buildSnapshot();
    }
}
```

**Time complexity:** O(1) per record. No iteration, no allocation.

### Time-based sliding window: partial time buckets

The time-based window divides the configured duration into N buckets (default: 1 bucket per second). Each bucket
aggregates call counts for its time slice. Buckets older than the window duration are evicted on the next access.

```java
public class TimeBasedSlidingWindow implements SlidingWindow {

    private final TimeBucket[] buckets;
    private final int windowSizeSeconds;
    private final long slowCallDurationThresholdNanos;
    private final InqClock clock;

    // TimeBucket holds: totalCalls, failures, slowCalls, start timestamp
    // Buckets are rotated based on clock.instant()
}
```

**The clock parameter** (see next section) determines "now" for bucket rotation. In production, this is `Instant.now()`.
In tests, this is a controllable clock that the test advances manually.

### Injectable clock: `InqClock`

The sliding window — and more broadly, every core algorithm that depends on time — receives its time source as an
injected dependency:

```java
@FunctionalInterface
public interface InqClock {

    Instant instant();

    static InqClock system() {
        return Instant::now;
    }
}
```

The clock is provided through the element configuration:

```java
var config = CircuitBreakerConfig.builder()
    .slidingWindowType(COUNT_BASED)
    .slidingWindowSize(10)
    .clock(InqClock.system())   // default — can be omitted
    .build();
```

**Why a custom interface instead of `java.time.Clock`?** `java.time.Clock` is an abstract class (not an interface),
requires implementing `getZone()` and `withZone()` which are irrelevant for duration measurement, and carries more
ceremony than a single `Instant instant()` method. `InqClock` is a functional interface — it can be expressed as a
lambda in tests:

```java
// In tests — deterministic, no waiting
var fixedTime = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
var testClock = fixedTime::get;

// Advance time by 5 seconds
fixedTime.set(fixedTime.get().plusSeconds(5));
```

### Purity guarantee

The sliding window implementations have **no** static state, no `System.currentTimeMillis()` calls, no `Instant.now()`
calls. Every time-dependent operation uses the injected `InqClock`. This means:

- Two sliding windows with different clocks can coexist in the same JVM (relevant for testing).
- A test can advance time by hours in microseconds — no `Thread.sleep()`, no flakiness.
- The algorithms are deterministic: same inputs (outcomes + clock values) → same outputs (snapshot), always.

### Minimum number of calls

The Circuit Breaker should not open based on a single failure. If the window has 1 call and it failed, the failure rate
is 100% — but opening the breaker based on one data point is premature.

`CircuitBreakerConfig` includes `minimumNumberOfCalls` (default: 10). The sliding window always computes the failure
rate accurately, but `WindowSnapshot.hasMinimumCalls()` tells the caller whether the sample size is sufficient. The
state machine checks this before deciding to transition:

```java
var snapshot = slidingWindow.record(outcome);
if (snapshot.hasMinimumCalls(config.getMinimumNumberOfCalls())
    && snapshot.failureRate() >= config.getFailureRateThreshold()) {
    transitionToOpen();
}
```

The minimum-calls check is **not** in the sliding window — it is in the behavioral contract (`CircuitBreakerBehavior`
per ADR-005). The window computes; the behavior decides.

### Slow call tracking

Calls are classified as "slow" when their duration exceeds `slowCallDurationThreshold`. This classification happens in
the sliding window based on `CallOutcome.durationNanos()` compared to the configured threshold.

The slow call rate is computed alongside the failure rate. A call can be both successful and slow — these are
independent dimensions:

| Outcome      | success | duration > threshold | Counted as     |
|--------------|---------|----------------------|----------------|
| Fast success | true    | false                | success        |
| Slow success | true    | true                 | success + slow |
| Fast failure | false   | false                | failure        |
| Slow failure | false   | true                 | failure + slow |

The Circuit Breaker can be configured to open on either rate or both:

```java
CircuitBreakerConfig.builder()
    .failureRateThreshold(50)           // open when >50% of calls fail
    .slowCallRateThreshold(80)          // open when >80% of calls are slow
    .slowCallDurationThreshold(Duration.ofSeconds(3))
    .build();
```

## Consequences

**Positive:**

- Pure algorithms with no threading dependencies — safe to share across all paradigms. Consistent with ADR-008: no
  `synchronized`, no `Thread.sleep`, no carrier-thread pinning.
- Injectable clock eliminates time-dependent test flakiness. Tests run in microseconds, not seconds.
- O(1) per-call overhead for count-based windows. No allocation, no iteration.
- Count-based and time-based share the same contract (`SlidingWindow`) — the Circuit Breaker state machine doesn't know
  which type it uses.
- Slow call tracking as an independent dimension alongside failure rate provides richer health signals.
- `minimumNumberOfCalls` prevents premature state transitions on small sample sizes.

**Negative:**

- Two implementations to maintain. Both must pass the same behavioral test suite (parameterized tests with
  `SlidingWindow` as the parameter).
- Time-based window has slightly higher per-call overhead due to bucket rotation checks. Still O(1) but with a larger
  constant.
- `InqClock` is a custom interface instead of `java.time.Clock`. This is a minor ecosystem deviation, but the ergonomic
  gain (functional interface, no zone) justifies it.

**Neutral:**

- The sliding window does not know about states (CLOSED, OPEN, HALF_OPEN). It records and computes. The state machine (
  in the behavioral contract) interprets the snapshot and decides transitions.
- The `slowCallDurationThreshold` in the sliding window should be aligned with the TimeLimiter timeout per ADR-012. The
  sliding window does not enforce this — it is a configuration-level concern.
- Behavioral changes to the sliding window algorithm (e.g. boundary inclusive vs. exclusive) are gated by compatibility
  flags (ADR-013). The flag is resolved at configuration time — the selected implementation runs without branching
  overhead.
