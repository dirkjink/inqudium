package eu.inqudium.imperative.trafficshapper;

import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.ThrottleSnapshot;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;
import eu.inqudium.core.trafficshaper.TrafficShaperCore;
import eu.inqudium.core.trafficshaper.TrafficShaperEvent;
import eu.inqudium.core.trafficshaper.TrafficShaperException;

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
 * Thread-safe, imperative traffic shaper implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom). Uses lock-free
 * CAS operations for scheduling and {@link LockSupport#parkNanos} for
 * the throttle delay, which is virtual-thread-friendly.
 *
 * <p>Unlike a rate limiter that rejects excess requests, the traffic shaper
 * <em>delays</em> them to produce smooth, evenly-spaced output. Only when
 * the queue overflows or the wait exceeds the configured maximum are
 * requests rejected.
 *
 * <p>Delegates all scheduling logic to the functional
 * {@link TrafficShaperCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = TrafficShaperConfig.builder("api-shaper")
 *     .ratePerSecond(100)         // 100 req/s, evenly spaced
 *     .maxQueueDepth(200)         // up to 200 waiting
 *     .maxWaitDuration(Duration.ofSeconds(5))
 *     .build();
 *
 * var shaper = new ImperativeTrafficShaper(config);
 *
 * // Each call is delayed to maintain even spacing
 * String result = shaper.execute(() -> httpClient.call());
 *
 * // With fallback on overflow
 * String result = shaper.executeWithFallback(
 *     () -> httpClient.call(),
 *     () -> "overflow-fallback"
 * );
 * }</pre>
 */
public class ImperativeTrafficShaper {

  private final TrafficShaperConfig config;
  private final AtomicReference<ThrottleSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<TrafficShaperEvent>> eventListeners;

  public ImperativeTrafficShaper(TrafficShaperConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeTrafficShaper(TrafficShaperConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(ThrottleSnapshot.initial(clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Callable Execution ========================

  /**
   * Executes the given callable, throttling it to the configured rate.
   *
   * <p>If the request must wait for a slot, the calling virtual thread
   * is parked for the computed delay. If the queue is full, a
   * {@link TrafficShaperException} is thrown.
   *
   * @param callable the operation to shape
   * @param <T>      the return type
   * @return the result of the callable
   * @throws TrafficShaperException if the request is rejected (overflow)
   * @throws Exception              if the callable itself throws
   */
  public <T> T execute(Callable<T> callable) throws Exception {
    waitForSlot();
    return callable.call();
  }

  /**
   * Executes a {@link Runnable}, throttling it to the configured rate.
   */
  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (TrafficShaperException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Executes with a fallback on overflow rejection.
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TrafficShaperException e) {
      return fallback.get();
    }
  }

  // ======================== Direct Scheduling API ========================

  /**
   * Acquires a slot, waiting if necessary.
   *
   * @throws TrafficShaperException if the request is rejected
   */
  public void waitForSlot() {
    ThrottlePermission permission = acquireSlot();

    if (!permission.admitted()) {
      throw new TrafficShaperException(
          config.name(), permission.waitDuration(), permission.snapshot().queueDepth());
    }

    if (permission.requiresWait()) {
      LockSupport.parkNanos(permission.waitDuration().toNanos());
    }

    // Record that this request has left the queue
    recordExecution();
  }

  /**
   * Attempts to acquire a slot without blocking. Returns the scheduling
   * decision without waiting.
   *
   * @return the throttle permission (caller is responsible for honouring the wait)
   */
  public ThrottlePermission tryAcquireSlot() {
    return acquireSlot();
  }

  // ======================== Internal ========================

  private ThrottlePermission acquireSlot() {
    Instant now = clock.instant();
    while (true) {
      ThrottleSnapshot current = snapshotRef.get();
      ThrottlePermission permission = TrafficShaperCore.schedule(current, config, now);

      if (!permission.admitted()) {
        // Rejection: apply the rejection counter via CAS
        if (snapshotRef.compareAndSet(current, permission.snapshot())) {
          emitEvent(TrafficShaperEvent.rejected(
              config.name(), permission.waitDuration(),
              permission.snapshot().queueDepth(), now));
          return permission;
        }
        // CAS failed — retry
        continue;
      }

      if (snapshotRef.compareAndSet(current, permission.snapshot())) {
        if (permission.requiresWait()) {
          emitEvent(TrafficShaperEvent.admittedDelayed(
              config.name(), permission.waitDuration(),
              permission.snapshot().queueDepth(), now));
        } else {
          emitEvent(TrafficShaperEvent.admittedImmediate(
              config.name(), permission.snapshot().queueDepth(), now));
        }
        return permission;
      }
      // CAS failed — retry
    }
  }

  private void recordExecution() {
    Instant now = clock.instant();
    while (true) {
      ThrottleSnapshot current = snapshotRef.get();
      ThrottleSnapshot updated = TrafficShaperCore.recordExecution(current);
      if (snapshotRef.compareAndSet(current, updated)) {
        emitEvent(TrafficShaperEvent.executing(
            config.name(), updated.queueDepth(), now));
        return;
      }
    }
  }

  // ======================== Listeners ========================

  /**
   * Registers a listener that is called on every traffic shaper event.
   */
  public void onEvent(Consumer<TrafficShaperEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(TrafficShaperEvent event) {
    for (Consumer<TrafficShaperEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  // ======================== Introspection ========================

  /**
   * Returns the current queue depth.
   */
  public int getQueueDepth() {
    return snapshotRef.get().queueDepth();
  }

  /**
   * Returns the estimated wait time for a request arriving now.
   */
  public Duration getEstimatedWait() {
    return TrafficShaperCore.estimateWait(snapshotRef.get(), clock.instant());
  }

  /**
   * Returns a snapshot of the current internal state.
   */
  public ThrottleSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  /**
   * Returns the configuration.
   */
  public TrafficShaperConfig getConfig() {
    return config;
  }

  /**
   * Resets the traffic shaper to its initial state.
   */
  public void reset() {
    Instant now = clock.instant();
    snapshotRef.set(TrafficShaperCore.reset(now));
    emitEvent(TrafficShaperEvent.reset(config.name(), now));
  }
}
