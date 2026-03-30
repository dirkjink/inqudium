package eu.inqudium.core.ratelimiter;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.ratelimiter.event.RateLimiterOnPermitEvent;
import eu.inqudium.core.ratelimiter.event.RateLimiterOnRejectEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base implementation for all rate limiter paradigms (imperative, Reactor, Kotlin, RxJava).
 *
 * <p>Contains the complete rate limiter logic — token bucket algorithm (CAS-based),
 * event publishing, exception handling, and decoration. Paradigm modules only
 * provide the wait mechanism: {@link #waitForPermit(Duration)}.
 *
 * <p>This separation ensures that the token bucket, event publishing, and error
 * codes are implemented <strong>once</strong> in the core, not duplicated across
 * every paradigm module.
 *
 * <h2>Subclass contract</h2>
 * <ul>
 *   <li>{@link #waitForPermit(Duration)} — block the caller for the given duration
 *       while waiting for a token to become available. Imperative: {@code LockSupport.parkNanos}.
 *       Kotlin: {@code delay}. Reactor: {@code Mono.delay}.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public abstract class AbstractRateLimiter implements InqDecorator {

  private final String name;
  private final RateLimiterConfig config;
  private final RateLimiterBehavior behavior;
  private final InqEventPublisher eventPublisher;
  private final AtomicReference<TokenBucketState> stateRef;

  protected AbstractRateLimiter(String name, RateLimiterConfig config) {
    this.name = name;
    this.config = config;
    this.behavior = RateLimiterBehavior.defaultBehavior();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.RATE_LIMITER);
    this.stateRef = new AtomicReference<>(TokenBucketState.initial(config));
  }

  // ── InqDecorator / InqElement ──

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqElementType getElementType() {
    return InqElementType.RATE_LIMITER;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  public RateLimiterConfig getConfig() {
    return config;
  }

  // ── Decoration — template method ──

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    return call.withCallable(() -> {
      acquirePermitWithCallId(call.callId());
      return call.callable().call();
    });
  }

  /**
   * Acquires a permit for standalone usage (no pipeline callId).
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link eu.inqudium.core.pipeline.InqPipeline} to compose elements.
   */
  public void acquirePermit() {
    acquirePermitWithCallId(InqCallIdGenerator.NONE);
  }

  /**
   * Core permit acquisition loop — CAS-based token bucket with timeout.
   *
   * <p>On permit: publishes {@link RateLimiterOnPermitEvent} with snapshot.
   * On reject: publishes {@link RateLimiterOnRejectEvent} and throws
   * {@link InqRequestNotPermittedException}.
   *
   * <p>The wait mechanism is delegated to {@link #waitForPermit(Duration)}.
   */
  private void acquirePermitWithCallId(String callId) {
    var deadline = config.getTimeoutDuration().isZero()
        ? Instant.MIN
        : config.getClock().instant().plus(config.getTimeoutDuration());

    while (true) {
      var currentState = stateRef.get();
      var result = behavior.tryAcquire(currentState, config);

      if (result.permitted()) {
        if (stateRef.compareAndSet(currentState, result.updatedState())) {
          var snap = new PermitSnapshot(
              result.updatedState().availableTokens(),
              config.getClock().instant());
          eventPublisher.publish(new RateLimiterOnPermitEvent(
              callId, name, snap.remainingTokens, snap.timestamp));
          return;
        }
        // CAS failed — another thread consumed a token, retry
        continue;
      }

      // Not permitted — check timeout
      if (config.getTimeoutDuration().isZero()) {
        rejectAndThrow(callId, result.waitDuration());
      }

      var now = config.getClock().instant();
      if (now.isAfter(deadline)) {
        rejectAndThrow(callId, result.waitDuration());
      }

      // Wait and retry — delegate to paradigm-specific mechanism
      var remaining = Duration.between(now, deadline);
      var parkDuration = result.waitDuration().compareTo(remaining) < 0
          ? result.waitDuration() : remaining;
      waitForPermit(parkDuration);
    }
  }

  private void rejectAndThrow(String callId, Duration waitEstimate) {
    var now = config.getClock().instant();
    eventPublisher.publish(new RateLimiterOnRejectEvent(callId, name, waitEstimate, now));
    throw new InqRequestNotPermittedException(callId, name, waitEstimate);
  }

  /**
   * Blocks the caller for the given duration while waiting for a token.
   *
   * <p>Imperative: {@code LockSupport.parkNanos(duration.toNanos())}.
   * Kotlin: {@code delay(duration.toMillis())}.
   * Reactor: non-blocking delay via {@code Mono.delay}.
   *
   * @param duration the duration to wait
   */
  protected abstract void waitForPermit(Duration duration);

  // ── Abstract — paradigm-specific wait mechanism ──

  /**
   * Consistent snapshot for event publishing.
   */
  private record PermitSnapshot(int remainingTokens, Instant timestamp) {
  }
}
