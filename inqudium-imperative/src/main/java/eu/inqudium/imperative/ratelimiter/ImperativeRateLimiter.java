package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.ratelimiter.RateLimitPermission;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterCore;
import eu.inqudium.core.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.ratelimiter.RateLimiterException;
import eu.inqudium.core.ratelimiter.RateLimiterSnapshot;
import eu.inqudium.core.ratelimiter.ReservationResult;

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
 * Thread-safe, imperative rate limiter implementation.
 */
public class ImperativeRateLimiter {

  private static final Logger LOG = Logger.getLogger(ImperativeRateLimiter.class.getName());

  private final RateLimiterConfig config;
  private final AtomicReference<RateLimiterSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<RateLimiterEvent>> eventListeners;

  // Fix 3: Erfasst blockierte Threads, um sie bei reset/drain aufzuwecken
  private final Set<Thread> parkedThreads;

  private final String instanceId;

  public ImperativeRateLimiter(RateLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRateLimiter(RateLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(RateLimiterSnapshot.initial(config, clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.parkedThreads = ConcurrentHashMap.newKeySet();
    this.instanceId = UUID.randomUUID().toString();
  }

  // ======================== Execution (Fix 4: Multi-Permits) ========================

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

    // Fix 2: Erlaubt nun das kontrollierte Durchreichen von InterruptedException
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
      throw e; // Fix 2: Transparentes Durchreichen
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
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimitPermission result = RateLimiterCore.tryAcquirePermissions(
          current, config, now, permits);

      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        if (result.permitted()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), result.snapshot().availablePermits(), now));
        } else {
          emitEvent(RateLimiterEvent.rejected(
              config.name(), result.snapshot().availablePermits(), result.waitDuration(), now));
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
      RateLimiterSnapshot current = snapshotRef.get();
      Instant now = clock.instant();

      Duration remainingTimeout = timeout.isZero()
          ? Duration.ZERO
          : Duration.between(now, deadline);
      if (remainingTimeout.isNegative()) {
        remainingTimeout = Duration.ZERO;
      }

      ReservationResult reservation = RateLimiterCore.reservePermissions(
          current, config, now, permits, remainingTimeout);

      if (reservation.timedOut()) {
        emitEvent(RateLimiterEvent.rejected(
            config.name(), current.availablePermits(), reservation.waitDuration(), now));
        throw new RateLimiterException(
            config.name(), instanceId, reservation.waitDuration(), current.availablePermits());
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

        long epochBeforePark = reservation.snapshot().epoch();
        Instant targetWakeup = now.plus(reservation.waitDuration());

        // Fix 1 & 2: Token Leak Protection und Interrupted Exception
        try {
          parkedThreads.add(Thread.currentThread());
          parkUntil(targetWakeup, epochBeforePark);
        } catch (InterruptedException e) {
          refundPermits(permits); // Gib den reservierten Token dem Bucket zurück!
          throw e; // Und propagiere die semantisch richtige Exception weiter
        } finally {
          parkedThreads.remove(Thread.currentThread());
        }

        RateLimiterSnapshot currentAfterWake = snapshotRef.get();
        if (currentAfterWake.epoch() != epochBeforePark) {
          // Fix 3: Epoch hat sich geändert. Neustart der Evaluierung (z.B. nach Reset)
          continue;
        }
        return;
      }
    }
  }

  /**
   * Erstattet reservierte Token im Bucket zurück, falls der Prozess abbricht.
   */
  private void refundPermits(int permits) {
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimiterSnapshot refunded = RateLimiterCore.refund(current, config, permits);
      if (snapshotRef.compareAndSet(current, refunded)) {
        return;
      }
    }
  }

  private void parkUntil(Instant targetWakeupTime, long expectedEpoch) throws InterruptedException {
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Thread was interrupted while waiting for rate limiter permits");
      }

      // Fix 3: Überprüfe auf Invalidierung durch reset/drain
      if (snapshotRef.get().epoch() != expectedEpoch) {
        break; // Verlasse den Schlaf, da eine Epochengrenze überschritten wurde
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
      } catch (Throwable t) { // Fix 5: Throwable fangen, um Abstürze durch fatale Metrik-Errors zu isolieren
        LOG.log(Level.WARNING,
            "Event listener threw exception for rate limiter '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()),
            t);
      }
    }
  }

  // ======================== Introspection & Maintenance ========================

  public int getAvailablePermits() {
    return RateLimiterCore.availablePermits(snapshotRef.get(), config, clock.instant());
  }

  public RateLimiterSnapshot getSnapshot() {
    return snapshotRef.get();
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
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimiterSnapshot drained = RateLimiterCore.drain(current, now);
      if (snapshotRef.compareAndSet(current, drained)) {
        emitEvent(RateLimiterEvent.drained(config.name(), now));
        unparkAll(); // Fix 3: Wecke Threads auf, damit sie die neue Epoche bemerken
        return;
      }
    }
  }

  public void reset() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimiterSnapshot fresh = RateLimiterCore.reset(current, config, now);
      if (snapshotRef.compareAndSet(current, fresh)) {
        emitEvent(RateLimiterEvent.reset(config.name(), fresh.availablePermits(), now));
        unparkAll(); // Fix 3: Wecke alle wartenden Threads sofort auf, damit sie die frischen Permits nehmen
        return;
      }
    }
  }
}