package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.ratelimiter.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe, imperative rate limiter implementation.
 */
public class ImperativeRateLimiter {

  private final RateLimiterConfig config;
  private final AtomicReference<RateLimiterSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<RateLimiterEvent>> eventListeners;

  public ImperativeRateLimiter(RateLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRateLimiter(RateLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(RateLimiterSnapshot.initial(config, clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    acquirePermissionOrThrow(config.defaultTimeout());
    return callable.call();
  }

  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    acquirePermissionOrThrow(timeout);
    return callable.call();
  }

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (RateLimiterException e) {
      // Fix 3B: Prüfe den Ursprung der Exception, um zu verhindern, dass
      // Fehler von nachgelagerten Rate Limitern fälschlicherweise hier maskiert werden.
      if (Objects.equals(e.getRateLimiterName(), config.name())) {
        return fallback.get();
      }
      throw e;
    }
  }

  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      throw e; // Fängt RuntimeException (inkl. RateLimiterException) und Errors, wirft sie direkt weiter
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Direct permission API ========================

  public boolean tryAcquirePermission() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimitPermission result = RateLimiterCore.tryAcquirePermission(current, config, now);

      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        if (result.permitted()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), result.snapshot().availablePermits(), now));
        } else {
          // Fix 3A: Feuern des REJECTED Events, falls der Request fail-fast scheitert
          emitEvent(RateLimiterEvent.rejected(
              config.name(), result.snapshot().availablePermits(), result.waitDuration(), now));
        }
        return result.permitted();
      }
    }
  }

  public void acquirePermission() {
    acquirePermissionOrThrow(config.defaultTimeout());
  }

  // ======================== Internal ========================

  private void acquirePermissionOrThrow(Duration timeout) {
    Instant start = clock.instant();
    Instant deadline = start.plus(timeout);

    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      Instant now = clock.instant();

      // Fix 2A: Verbleibendes Timeout in der Schleife neu berechnen,
      // um Timeout-Erosion durch Thread-Contention zu verhindern.
      Duration remainingTimeout = timeout.isZero() ? Duration.ZERO : Duration.between(now, deadline);
      if (remainingTimeout.isNegative()) {
        remainingTimeout = Duration.ZERO;
      }

      ReservationResult reservation = RateLimiterCore.reservePermission(
          current, config, now, remainingTimeout);

      if (reservation.timedOut()) {
        emitEvent(RateLimiterEvent.rejected(
            config.name(), current.availablePermits(), reservation.waitDuration(), now));
        throw new RateLimiterException(
            config.name(), reservation.waitDuration(), current.availablePermits());
      }

      if (snapshotRef.compareAndSet(current, reservation.snapshot())) {
        if (reservation.waitDuration().isZero()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), reservation.snapshot().availablePermits(), now));
          return;
        }

        emitEvent(RateLimiterEvent.waiting(
            config.name(), reservation.snapshot().availablePermits(),
            reservation.waitDuration(), now));

        // Ziel-Zeit berechnen, zu der wir aufwachen sollen
        Instant targetWakeup = now.plus(reservation.waitDuration());
        parkUntil(targetWakeup);
        return;
      }
    }
  }

  /**
   * Parks the current thread until the target time is reached.
   */
  private void parkUntil(Instant targetWakeupTime) {
    // Fix 2B: while-Schleife schützt vor Spurious Wakeups
    while (true) {
      Instant now = clock.instant();
      Duration remaining = Duration.between(now, targetWakeupTime);

      if (remaining.isNegative() || remaining.isZero()) {
        break; // Wartezeit ist physisch vergangen, wir dürfen weiter
      }

      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  // ======================== Listeners ========================

  public void onEvent(Consumer<RateLimiterEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(RateLimiterEvent event) {
    for (Consumer<RateLimiterEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  // ======================== Introspection ========================

  public int getAvailablePermits() {
    return RateLimiterCore.availablePermits(snapshotRef.get(), config, clock.instant());
  }

  public RateLimiterSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  public RateLimiterConfig getConfig() {
    return config;
  }

  public void drain() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimiterSnapshot drained = RateLimiterCore.drain(current);
      if (snapshotRef.compareAndSet(current, drained)) {
        emitEvent(RateLimiterEvent.drained(config.name(), now));
        return;
      }
    }
  }

  public void reset() {
    Instant now = clock.instant();
    RateLimiterSnapshot fresh = RateLimiterCore.reset(config, now);
    snapshotRef.set(fresh);
    emitEvent(RateLimiterEvent.reset(config.name(), fresh.availablePermits(), now));
  }
}