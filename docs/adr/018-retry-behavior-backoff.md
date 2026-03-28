# ADR-018: Retry behavior and backoff strategies

**Status:** Accepted  
**Date:** 2026-03-28  
**Deciders:** Core team

## Context

The Retry element re-executes a failed operation with configurable delays between attempts. ADR-005 defines it as a shared contract in core with pure backoff algorithms, and ADR-017 places it as the innermost element in the canonical pipeline order. But the behavioral contract, configuration parameters, and backoff algorithms are not specified.

### What must be specified

1. **RetryConfig** — which parameters control retry behavior, their defaults, and their constraints.
2. **RetryBehavior** — the contract that decides whether to retry and what to record.
3. **BackoffStrategy** — the interface that computes wait duration from attempt number.
4. **Three implementations** — FixedBackoff, ExponentialBackoff, RandomizedBackoff with concrete algorithms.

### The jitter problem

When a downstream service recovers after an outage, all clients that were retrying simultaneously send their next retry at the same instant. This "retry storm" can re-overload the service immediately. Jitter — randomizing the wait duration — spreads retries over time and prevents thundering herd effects.

The choice of jitter algorithm is not cosmetic. AWS's analysis ("Exponential Backoff And Jitter", 2015) demonstrated that different jitter strategies produce dramatically different load profiles:

- **No jitter** — All clients retry at exactly `2^attempt * base`. Thundering herd on every retry cycle.
- **Full jitter** — `random(0, 2^attempt * base)`. Maximum spread, but some retries happen very quickly (near zero delay).
- **Equal jitter** — `2^attempt * base / 2 + random(0, 2^attempt * base / 2)`. Guaranteed minimum delay of half the backoff, with jitter in the upper half.
- **Decorrelated jitter** — `min(cap, random(base, previousDelay * 3))`. Each delay depends on the previous one, producing a more organic spread. Best overall performance in AWS's analysis.

## Decision

### RetryConfig

```java
public record RetryConfig(
    int maxAttempts,                              // total attempts including the first call
    Duration initialInterval,                     // base delay before the first retry
    BackoffStrategy backoffStrategy,              // computes delay from attempt number
    Duration maxInterval,                         // cap on computed delay (prevents unbounded growth)
    Set<Class<? extends Throwable>> retryOn,      // exception types that trigger a retry
    Set<Class<? extends Throwable>> ignoreOn,     // exception types that are never retried
    boolean retryOnInqExceptions,                 // whether to retry on InqException (default: false, ADR-017)
    Predicate<Throwable> retryOnPredicate,        // custom predicate for retry decision
    InqCompatibility compatibility                // behavioral flags (ADR-013)
) {
    public static RetryConfig ofDefaults() {
        return RetryConfig.builder().build();
    }

    public static Builder builder() { ... }
}
```

Default values:

| Parameter | Default | Rationale |
|---|---|---|
| `maxAttempts` | 3 | First call + 2 retries. Covers transient failures without excessive load. |
| `initialInterval` | 500ms | Short enough to feel responsive, long enough for a transient issue to resolve. |
| `backoffStrategy` | `ExponentialBackoff.withEqualJitter()` | Exponential growth prevents hammering; equal jitter prevents thundering herd while guaranteeing a minimum delay. |
| `maxInterval` | 30s | Prevents exponential growth from producing absurd delays (2^10 * 500ms = 8.5 minutes). |
| `retryOn` | empty (= retry on all exceptions) | Unless restricted, any exception triggers a retry. |
| `ignoreOn` | empty | No exceptions explicitly ignored (but see `retryOnInqExceptions`). |
| `retryOnInqExceptions` | `false` | Retrying against an open Circuit Breaker or exhausted Rate Limiter is pointless (ADR-009, ADR-017). |
| `retryOnPredicate` | `null` | No custom predicate. |

### RetryBehavior

The behavioral contract determines whether a specific failure should be retried and what the next delay should be. It is a pure function — no threading, no sleeping.

```java
public interface RetryBehavior {

    /**
     * Decides whether the given exception should trigger a retry.
     * Returns empty if no retry should be attempted (exhausted or exception not retryable).
     * Returns the Duration to wait before the next attempt if retry is warranted.
     *
     * Pure — does not sleep or schedule. The caller decides how to wait.
     */
    Optional<Duration> shouldRetry(int attemptNumber, Throwable exception, RetryConfig config);
}
```

The default implementation:

```java
public class DefaultRetryBehavior implements RetryBehavior {

    @Override
    public Optional<Duration> shouldRetry(int attemptNumber, Throwable exception, RetryConfig config) {
        // 1. Check if max attempts exhausted
        if (attemptNumber >= config.maxAttempts()) {
            return Optional.empty();
        }

        // 2. Check InqException exclusion
        if (!config.retryOnInqExceptions() && exception instanceof InqException) {
            return Optional.empty();
        }

        // 3. Check ignoreOn list
        if (config.ignoreOn().stream().anyMatch(cls -> cls.isInstance(exception))) {
            return Optional.empty();
        }

        // 4. Check retryOn list (if non-empty, acts as allowlist)
        if (!config.retryOn().isEmpty()
            && config.retryOn().stream().noneMatch(cls -> cls.isInstance(exception))) {
            return Optional.empty();
        }

        // 5. Check custom predicate
        if (config.retryOnPredicate() != null && !config.retryOnPredicate().test(exception)) {
            return Optional.empty();
        }

        // 6. Compute delay
        Duration delay = config.backoffStrategy().computeDelay(attemptNumber, config.initialInterval());
        Duration capped = delay.compareTo(config.maxInterval()) > 0 ? config.maxInterval() : delay;
        return Optional.of(capped);
    }
}
```

### BackoffStrategy

```java
@FunctionalInterface
public interface BackoffStrategy {

    /**
     * Computes the delay before the next retry attempt.
     * Pure — no side effects, no randomness source dependency (randomness is internal).
     *
     * @param attemptNumber  1-based attempt number (1 = first retry, not the initial call)
     * @param initialInterval  the base interval from RetryConfig
     * @return the computed delay (before maxInterval capping, which the caller applies)
     */
    Duration computeDelay(int attemptNumber, Duration initialInterval);
}
```

### FixedBackoff

Every retry waits the same duration:

```
delay = initialInterval
```

```java
public class FixedBackoff implements BackoffStrategy {

    @Override
    public Duration computeDelay(int attemptNumber, Duration initialInterval) {
        return initialInterval;
    }
}
```

Use case: Retrying against a rate-limited API where the rate limit resets at fixed intervals.

### ExponentialBackoff

Each retry waits exponentially longer:

```
delay = initialInterval × multiplier^(attemptNumber - 1)
```

```java
public class ExponentialBackoff implements BackoffStrategy {

    private final double multiplier;

    public ExponentialBackoff() {
        this(2.0);  // default: doubling
    }

    public ExponentialBackoff(double multiplier) {
        if (multiplier < 1.0) throw new IllegalArgumentException("Multiplier must be >= 1.0");
        this.multiplier = multiplier;
    }

    @Override
    public Duration computeDelay(int attemptNumber, Duration initialInterval) {
        double factor = Math.pow(multiplier, attemptNumber - 1);
        long millis = (long) (initialInterval.toMillis() * factor);
        return Duration.ofMillis(millis);
    }
}
```

Example with defaults (multiplier=2, initialInterval=500ms):
- Attempt 1: 500ms
- Attempt 2: 1000ms
- Attempt 3: 2000ms
- Attempt 4: 4000ms

### RandomizedBackoff (jitter decorator)

`RandomizedBackoff` is a **decorator** that wraps any `BackoffStrategy` and adds jitter to its output. Three jitter algorithms are provided as factory methods:

```java
public class RandomizedBackoff implements BackoffStrategy {

    private final BackoffStrategy delegate;
    private final JitterAlgorithm jitter;
    private final ThreadLocalRandom random;

    private RandomizedBackoff(BackoffStrategy delegate, JitterAlgorithm jitter) {
        this.delegate = delegate;
        this.jitter = jitter;
        this.random = ThreadLocalRandom.current();
    }

    @Override
    public Duration computeDelay(int attemptNumber, Duration initialInterval) {
        Duration baseDelay = delegate.computeDelay(attemptNumber, initialInterval);
        return jitter.apply(baseDelay, random);
    }
}
```

#### Full jitter

```
delay = random(0, baseDelay)
```

Maximum spread. Some retries fire almost immediately (near zero). Best for systems where any spread is better than no spread.

```java
public static RandomizedBackoff fullJitter(BackoffStrategy delegate) { ... }
```

#### Equal jitter (recommended default)

```
delay = baseDelay / 2 + random(0, baseDelay / 2)
```

The delay is always at least half of the computed backoff. The upper half is randomized. This guarantees a minimum delay (preventing "lucky" near-zero retries) while still spreading retries.

```java
public static RandomizedBackoff equalJitter(BackoffStrategy delegate) { ... }
```

#### Decorrelated jitter

```
delay = min(maxInterval, random(initialInterval, previousDelay × 3))
```

Each delay depends on the **previous delay**, not the attempt number. Produces a more organic, less predictable spread. Best overall performance in high-concurrency retry storms per AWS's analysis.

```java
public static RandomizedBackoff decorrelatedJitter(BackoffStrategy delegate) { ... }
```

Note: Decorrelated jitter is **stateful** — it remembers the previous delay. This is the only `BackoffStrategy` implementation that is not purely functional. Instances must not be shared across concurrent retry sequences. Each Retry element instance creates its own `RandomizedBackoff` for each call.

### Convenience factory methods

```java
// Common combinations
BackoffStrategy.fixed()                          // FixedBackoff
BackoffStrategy.exponential()                    // ExponentialBackoff (multiplier=2)
BackoffStrategy.exponential(1.5)                 // ExponentialBackoff (custom multiplier)
BackoffStrategy.exponentialWithFullJitter()       // Exponential + full jitter
BackoffStrategy.exponentialWithEqualJitter()      // Exponential + equal jitter (DEFAULT)
BackoffStrategy.exponentialWithDecorrelatedJitter() // Exponential + decorrelated jitter
```

## Consequences

**Positive:**
- Three jitter algorithms with documented trade-offs. The developer chooses based on their load profile.
- Equal jitter as default balances spread and minimum delay — no near-zero retries, no thundering herd.
- Decorator pattern for jitter means any backoff strategy can be jittered — including custom implementations.
- `maxInterval` cap prevents exponential growth from producing absurd delays.
- `retryOnInqExceptions = false` by default prevents the most common pipeline bug (ADR-017).
- `RetryBehavior` is pure — no threading, no sleeping. The paradigm module decides how to wait.

**Negative:**
- Decorrelated jitter is stateful (remembers previous delay). This breaks the "pure function" principle for this one strategy. Documented and mitigated by per-call instantiation.
- Three jitter algorithms require explanation and documentation. Most developers will use the default (equal jitter) and never think about it — but the choice must be available for teams with specific requirements.

**Neutral:**
- The `retryOn` / `ignoreOn` mechanism uses exception class matching, not cause-chain traversal. This is intentional — cause-chain matching would interact unpredictably with `InqFailure.find()` (ADR-009). If the developer needs cause-chain matching, they use `retryOnPredicate`.
- `maxAttempts` counts the total number of attempts including the initial call. `maxAttempts=3` means: initial call + 2 retries. This matches Resilience4J's semantics (ADR-006).
