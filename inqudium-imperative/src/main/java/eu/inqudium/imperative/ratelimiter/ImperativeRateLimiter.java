package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.ratelimiter.RateLimitPermission;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.ratelimiter.RateLimiterException;
import eu.inqudium.core.ratelimiter.RateLimiterState;
import eu.inqudium.core.ratelimiter.ReservationResult;
import eu.inqudium.core.ratelimiter.strategy.RateLimiterStrategy;
import eu.inqudium.core.ratelimiter.strategy.TokenBucketState;
import eu.inqudium.core.ratelimiter.strategy.TokenBucketStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative rate limiter implementation using a pluggable strategy.
 */
public class ImperativeRateLimiter<S extends RateLimiterState> {

  private static final Logger LOG = Logger.getLogger(ImperativeRateLimiter.class.getName());

  private final RateLimiterConfig<S> config;
  private final RateLimiterStrategy<S> strategy;
  private final AtomicReference<S> stateRef;
  private final Clock clock;
  private final List<Consumer<RateLimiterEvent>> eventListeners;
  private final Set<Thread> parkedThreads;
  private final String instanceId;

  // ======================== Static Factories (Default Algorithm) ========================
  public ImperativeRateLimiter(RateLimiterConfig<S> config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRateLimiter(RateLimiterConfig<S> config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    // Die Strategie wird implizit und typsicher aus der Config extrahiert
    this.strategy = config.strategy();
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.stateRef = new AtomicReference<>(strategy.initial(config, clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.parkedThreads = ConcurrentHashMap.newKeySet();
    this.instanceId = UUID.randomUUID().toString();
  }

  public ImperativeRateLimiter(RateLimiterConfig<S> config, RateLimiterStrategy<S> strategy) {
    this(config, strategy, Clock.systemUTC());
  }

  public ImperativeRateLimiter(RateLimiterConfig<S> config, RateLimiterStrategy<S> strategy, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.stateRef = new AtomicReference<>(strategy.initial(config, clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.parkedThreads = ConcurrentHashMap.newKeySet();
    this.instanceId = UUID.randomUUID().toString();
  }

  // ======================== Constructors (Custom Algorithms) ========================

  /**
   * Creates a rate limiter using the default Token Bucket algorithm.
   */
  public static ImperativeRateLimiter<TokenBucketState> create(RateLimiterConfig config) {
    return new ImperativeRateLimiter<>(config, new TokenBucketStrategy());
  }

  /**
   * Creates a rate limiter using the default Token Bucket algorithm and a custom clock.
   */
  public static ImperativeRateLimiter<TokenBucketState> create(RateLimiterConfig config, Clock clock) {
    return new ImperativeRateLimiter<>(config, new TokenBucketStrategy(), clock);
  }

  // ======================== Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    return execute(callable, 1, config.defaultTimeout());
  }

  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    return execute(callable, 1, timeout);
  }

  public <T> T execute(Callable<T> callable, int permits) throws Exception {
    return execute(callable, permits, config.defaultTimeout());
  }

  public <T> T execute(Callable<T> callable, int permits, Duration timeout) throws Exception {
    Objects.requireNonNull(callable, "callable must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative, got " + timeout);
    }

    acquirePermissionsOrThrow(permits, timeout);
    return callable.call();
  }

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    return executeWithFallback(callable, 1, config.defaultTimeout(), fallback);
  }

  public <T> T executeWithFallback(Callable<T> callable, int permits, Duration timeout, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable, permits, timeout);
    } catch (RateLimiterException e) {
      if (Objects.equals(e.getInstanceId(), this.instanceId)) {
        return fallback.get();
      }
      throw e;
    }
  }

  public void execute(Runnable runnable) throws InterruptedException {
    execute(runnable, 1, config.defaultTimeout());
  }

  public void execute(Runnable runnable, Duration timeout) throws InterruptedException {
    execute(runnable, 1, timeout);
  }

  public void execute(Runnable runnable, int permits, Duration timeout) throws InterruptedException {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      execute(() -> {
        runnable.run();
        return null;
      }, permits, timeout);
    } catch (InterruptedException | RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Direct permission API ========================

  public boolean tryAcquirePermission() {
    return tryAcquirePermissions(1);
  }

  public boolean tryAcquirePermissions(int permits) {
    Instant now = clock.instant();
    while (true) {
      S current = stateRef.get();
      RateLimitPermission<S> result = strategy.tryAcquirePermissions(current, config, now, permits);

      if (stateRef.compareAndSet(current, result.state())) {
        if (result.permitted()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), strategy.availablePermits(result.state(), config, now), now));
        } else {
          emitEvent(RateLimiterEvent.rejected(
              config.name(), strategy.availablePermits(result.state(), config, now), result.waitDuration(), now));
        }
        return result.permitted();
      }
    }
  }

  public void acquirePermission() throws InterruptedException {
    acquirePermissionsOrThrow(1, config.defaultTimeout());
  }

  // ======================== Internal ========================

  private void acquirePermissionsOrThrow(int permits, Duration timeout) throws InterruptedException {
    Instant start = clock.instant();
    Instant deadline = timeout.isZero() ? start : start.plus(timeout);

    while (true) {
      S current = stateRef.get();
      Instant now = clock.instant();

      Duration remainingTimeout = timeout.isZero()
          ? Duration.ZERO
          : Duration.between(now, deadline);
      if (remainingTimeout.isNegative()) {
        remainingTimeout = Duration.ZERO;
      }

      ReservationResult<S> reservation = strategy.reservePermissions(current, config, now, permits, remainingTimeout);

      if (reservation.timedOut()) {
        int available = strategy.availablePermits(current, config, now);
        emitEvent(RateLimiterEvent.rejected(config.name(), available, reservation.waitDuration(), now));
        throw new RateLimiterException(config.name(), instanceId, reservation.waitDuration(), available);
      }

      if (stateRef.compareAndSet(current, reservation.state())) {
        int newAvailable = strategy.availablePermits(reservation.state(), config, now);

        if (reservation.waitDuration().isZero()) {
          emitEvent(RateLimiterEvent.permitted(config.name(), newAvailable, now));
          return;
        }

        emitEvent(RateLimiterEvent.waiting(config.name(), newAvailable, reservation.waitDuration(), now));

        long epochBeforePark = reservation.state().epoch();
        Instant targetWakeup = now.plus(reservation.waitDuration());

        try {
          parkedThreads.add(Thread.currentThread());
          parkUntil(targetWakeup, epochBeforePark);
        } catch (InterruptedException e) {
          refundPermits(permits);
          throw e;
        } finally {
          parkedThreads.remove(Thread.currentThread());
        }

        S currentAfterWake = stateRef.get();
        if (currentAfterWake.epoch() != epochBeforePark) {
          continue; // Epoch changed, retry
        }
        return;
      }
    }
  }

  private void refundPermits(int permits) {
    while (true) {
      S current = stateRef.get();
      S refunded = strategy.refund(current, config, permits);
      if (stateRef.compareAndSet(current, refunded)) {
        return;
      }
    }
  }

  private void parkUntil(Instant targetWakeupTime, long expectedEpoch) throws InterruptedException {
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Thread was interrupted while waiting for rate limiter permits");
      }

      if (stateRef.get().epoch() != expectedEpoch) {
        break;
      }

      Instant now = clock.instant();
      Duration remaining = Duration.between(now, targetWakeupTime);

      if (remaining.isNegative() || remaining.isZero()) {
        break;
      }

      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  private void unparkAll() {
    for (Thread thread : parkedThreads) {
      LockSupport.unpark(thread);
    }
  }

  // ======================== Listeners ========================

  public void onEvent(Consumer<RateLimiterEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(RateLimiterEvent event) {
    for (Consumer<RateLimiterEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for rate limiter '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()),
            t);
      }
    }
  }

  // ======================== Introspection & Maintenance ========================

  public int getAvailablePermits() {
    return strategy.availablePermits(stateRef.get(), config, clock.instant());
  }

  public S getState() {
    return stateRef.get();
  }

  public RateLimiterConfig getConfig() {
    return config;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void drain() {
    Instant now = clock.instant();
    while (true) {
      S current = stateRef.get();
      S drained = strategy.drain(current, config, now);
      if (stateRef.compareAndSet(current, drained)) {
        emitEvent(RateLimiterEvent.drained(config.name(), now));
        unparkAll();
        return;
      }
    }
  }

  public void reset() {
    Instant now = clock.instant();
    while (true) {
      S current = stateRef.get();
      S fresh = strategy.reset(current, config, now);
      if (stateRef.compareAndSet(current, fresh)) {
        emitEvent(RateLimiterEvent.reset(config.name(), strategy.availablePermits(fresh, config, now), now));
        unparkAll();
        return;
      }
    }
  }
}