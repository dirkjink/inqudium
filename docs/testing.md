# Testing

Inqudium is designed for deterministic, fast testing. No `Thread.sleep()`, no timing-dependent assertions, no flakiness.

## Deterministic time with InqClock

Inject `InqClock` to control time in tests:

```java
var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
InqClock clock = time::get;

var config = CircuitBreakerConfig.builder()
    .clock(clock)
    .waitDurationInOpenState(Duration.ofSeconds(60))
    .build();

// ... trigger circuit breaker to OPEN ...

// Advance time by 60 seconds — instant, no waiting
time.set(time.get().plusSeconds(60));

// Circuit breaker should now transition to HALF_OPEN
```

This works for all time-dependent components: sliding windows, token bucket refill, timeout profiles.

## Assertions on exceptions

Use `InqFailure.find()` to assert on specific exception types:

```java
var thrown = catchThrowable(() -> resilientCall.get());

var failure = InqFailure.find(thrown);
assertThat(failure.isPresent()).isTrue();
assertThat(failure.get()).isPresent()
    .get().isInstanceOf(InqCallNotPermittedException.class);
```

## Error code assertions

Error codes are `public static final` constants — assert on them without instantiating the exception:

```java
assertThat(thrown.getMessage()).startsWith(InqCallNotPermittedException.CODE);
```

## Testing the sliding window directly

The sliding window is a pure data structure with no threading. Test it by feeding outcomes and checking snapshots:

```java
var window = new CountBasedSlidingWindow(10, Duration.ofSeconds(3).toNanos());

window.record(CallOutcome.success(1_000_000L, clock.instant()));
window.record(CallOutcome.failure(1_000_000L, clock.instant()));

var snapshot = window.snapshot();
assertThat(snapshot.failureRate()).isCloseTo(50.0f, within(0.1f));
assertThat(snapshot.totalCalls()).isEqualTo(2);
```

## Testing the token bucket directly

The rate limiter behavior is a pure function. Feed state in, check result out:

```java
var config = RateLimiterConfig.builder()
    .limitForPeriod(5)
    .limitRefreshPeriod(Duration.ofSeconds(1))
    .clock(clock)
    .build();

var state = TokenBucketState.initial(config);
var behavior = RateLimiterBehavior.defaultBehavior();

// Consume all tokens
for (int i = 0; i < 5; i++) {
    var result = behavior.tryAcquire(state, config);
    assertThat(result.permitted()).isTrue();
    state = result.updatedState();
}

// Next one should be denied
var denied = behavior.tryAcquire(state, config);
assertThat(denied.permitted()).isFalse();

// Advance time → tokens refill
time.set(time.get().plusSeconds(1));
var refilled = behavior.tryAcquire(state, config);
assertThat(refilled.permitted()).isTrue();
```

## Testing the bulkhead directly

Acquire/release is also pure — no concurrency needed in unit tests:

```java
var config = BulkheadConfig.builder().maxConcurrentCalls(2).build();
var behavior = BulkheadBehavior.defaultBehavior();
var state = BulkheadState.initial();

// Acquire two permits
var r1 = behavior.tryAcquire(state, config);
state = r1.updatedState();
var r2 = behavior.tryAcquire(state, config);
state = r2.updatedState();

// Third should be denied
assertThat(behavior.tryAcquire(state, config).permitted()).isFalse();

// Release one → slot opens
state = behavior.release(state);
assertThat(behavior.tryAcquire(state, config).permitted()).isTrue();
```
