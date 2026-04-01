package eu.inqudium.imperative.trafficshaper;

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
 * <h2>Reset behavior (Fix 9)</h2>
 * <p>{@link #reset()} increments the snapshot's epoch and unparks all waiting
 * threads. Parked threads detect the epoch change, skip their stale slot, and
 * re-acquire a fresh slot from the reset state. This avoids the previous behavior
 * where threads would continue waiting for their (now irrelevant) original slots
 * for potentially dozens of seconds after a reset.
 */
public class ImperativeTrafficShaper {

  private static final Logger LOG = Logger.getLogger(ImperativeTrafficShaper.class.getName());

  // Fix 10: Maximum CAS retries before yielding to prevent CPU spin
  private static final int MAX_CAS_RETRIES_BEFORE_YIELD = 64;

  private final TrafficShaperConfig config;
  private final AtomicReference<ThrottleSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<TrafficShaperEvent>> eventListeners;
  private final String instanceId;

  // Fix 9: Track parked threads for unparking on reset
  private final Set<Thread> parkedThreads;

  public ImperativeTrafficShaper(TrafficShaperConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeTrafficShaper(TrafficShaperConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(ThrottleSnapshot.initial(clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.instanceId = UUID.randomUUID().toString();
    this.parkedThreads = ConcurrentHashMap.newKeySet();
  }

  // ======================== Execution ========================

  private static int yieldIfExcessiveRetries(int retries) {
    if (retries > MAX_CAS_RETRIES_BEFORE_YIELD) {
      Thread.yield();
      return 0;
    }
    return retries + 1;
  }

  public <T> T execute(Callable<T> callable) throws Exception {
    Objects.requireNonNull(callable, "callable must not be null");
    waitForSlot();
    return callable.call();
  }

  /**
   * Fix 6: Explicit InterruptedException handling for the Runnable variant.
   * The interrupt status is preserved; the InterruptedException from the
   * slot wait is wrapped as unchecked since execute(Runnable) cannot declare
   * checked exceptions.
   */
  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (InterruptedException e) {
      // Interrupt flag was already restored in parkUntil.
      throw new RuntimeException(e);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Slot acquisition ========================

  /**
   * Uses instanceId for identity-based comparison — prevents false positives
   * when multiple shapers share the same name.
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TrafficShaperException e) {
      if (Objects.equals(e.getInstanceId(), this.instanceId)) {
        return fallback.get();
      }
      throw e;
    }
  }

  /**
   * Acquires a slot and waits for it if necessary.
   *
   * <p>Fix 2: Throws a real {@link InterruptedException} instead of the
   * previous unchecked {@code TrafficShaperInterruptedException}, ensuring
   * callers that catch InterruptedException see the correct type.
   *
   * @throws InterruptedException   if the thread is interrupted while waiting
   * @throws TrafficShaperException if the request is rejected
   */
  private void waitForSlot() throws InterruptedException {
    ThrottlePermission permission = acquireSlot();

    // Immediate requests don't enter the queue — no dequeue needed
    if (!permission.requiresWait()) {
      return;
    }

    long epochAtSchedule = permission.snapshot().epoch();

    try {
      parkedThreads.add(Thread.currentThread());
      parkUntil(permission.scheduledSlot(), epochAtSchedule);
    } catch (InterruptedException e) {
      // Interrupted during wait — still need to dequeue to fix queueDepth
      recordExecution();
      throw e;
    } finally {
      parkedThreads.remove(Thread.currentThread());
    }

    // Fix 9: Check if a reset occurred while we were parked.
    // If so, skip the stale slot and re-acquire from the fresh state.
    ThrottleSnapshot currentAfterWake = snapshotRef.get();
    if (currentAfterWake.epoch() != epochAtSchedule) {
      // Our old slot was invalidated — dequeue the stale reservation
      recordExecution();
      // Retry: acquire a fresh slot from the reset state
      waitForSlot();
      return;
    }

    // Normal path: dequeue and proceed
    recordExecution();
  }

  // ======================== Internal — Parking ========================

  /**
   * Fix 1: The rejection path now also attempts a CAS to commit the
   * totalRejected counter. If the CAS fails, the counter is lost (acceptable
   * since rejection stats are best-effort under contention), but the scheduling
   * state remains consistent.
   */
  private ThrottlePermission acquireSlot() {
    int retries = 0;
    while (true) {
      retries = yieldIfExcessiveRetries(retries);

      Instant now = clock.instant();
      ThrottleSnapshot current = snapshotRef.get();

      ThrottlePermission permission = TrafficShaperCore.schedule(current, config, now);

      if (!permission.admitted()) {
        // Fix 1: Try to commit the rejection counter via CAS.
        // If it fails (concurrent modification), the counter is lost — acceptable
        // since another thread's state change may have made this rejection moot anyway.
        snapshotRef.compareAndSet(current, permission.snapshot());

        emitEvent(TrafficShaperEvent.rejected(
            config.name(), permission.waitDuration(), permission.snapshot().queueDepth(), now));
        throw new TrafficShaperException(
            config.name(), instanceId, permission.waitDuration(), current.queueDepth());
      }

      if (snapshotRef.compareAndSet(current, permission.snapshot())) {
        if (permission.requiresWait()) {
          emitEvent(TrafficShaperEvent.admittedDelayed(
              config.name(), permission.waitDuration(),
              permission.snapshot().queueDepth(), now));

          // Check if the unbounded queue has grown dangerously
          if (TrafficShaperCore.isUnboundedQueueWarning(permission.snapshot(), config, now)) {
            emitEvent(TrafficShaperEvent.unboundedQueueWarning(
                config.name(),
                permission.snapshot().projectedTailWait(now),
                permission.snapshot().queueDepth(),
                now));
          }
        } else {
          emitEvent(TrafficShaperEvent.admittedImmediate(
              config.name(), permission.snapshot().queueDepth(), now));
        }
        return permission;
      }
      // CAS failed — retry
    }
  }

  /**
   * Parks the current thread until the scheduled slot or until the epoch changes.
   *
   * <p>Fix 2: Throws a real {@link InterruptedException} instead of the previous
   * unchecked wrapper. {@link LockSupport#parkNanos} does NOT consume the
   * interrupt status — we check it explicitly at the top of each iteration.
   *
   * <p>Fix 9: Exits early if the epoch changes (reset occurred). The caller
   * detects this and re-acquires a fresh slot.
   *
   * @param targetWakeup  the absolute instant at which the slot becomes available
   * @param expectedEpoch the epoch at time of scheduling; if it changes, exit early
   * @throws InterruptedException if the thread is interrupted while parked
   */
  private void parkUntil(Instant targetWakeup, long expectedEpoch) throws InterruptedException {
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException(
            "Thread interrupted while waiting for traffic shaper '%s' slot"
                .formatted(config.name()));
      }

      // Fix 9: Epoch changed → reset occurred, exit early
      if (snapshotRef.get().epoch() != expectedEpoch) {
        break;
      }

      Duration remaining = Duration.between(clock.instant(), targetWakeup);
      if (remaining.isNegative() || remaining.isZero()) {
        break;
      }

      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  // ======================== Fix 10: CAS starvation guard ========================

  private void recordExecution() {
    int retries = 0;
    while (true) {
      retries = yieldIfExcessiveRetries(retries);

      Instant now = clock.instant();
      ThrottleSnapshot current = snapshotRef.get();
      ThrottleSnapshot dequeued = TrafficShaperCore.recordExecution(current);

      if (snapshotRef.compareAndSet(current, dequeued)) {
        emitEvent(TrafficShaperEvent.executing(config.name(), dequeued.queueDepth(), now));
        return;
      }
    }
  }

  // ======================== Fix 9: Unpark all waiting threads ========================

  private void unparkAll() {
    for (Thread thread : parkedThreads) {
      LockSupport.unpark(thread);
    }
  }

  // ======================== Listeners ========================

  /**
   * Registers an event listener.
   *
   * <p>Fix 4: Returns a {@link Runnable} that, when executed, unregisters
   * this listener. Prevents memory leaks when listeners are registered
   * from short-lived contexts.
   *
   * @param listener the listener to be notified on traffic shaper events
   * @return a disposable handle that removes the listener when invoked
   */
  public Runnable onEvent(Consumer<TrafficShaperEvent> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    eventListeners.add(listener);
    return () -> eventListeners.remove(listener);
  }

  /**
   * Fix 3: Catches {@link Throwable} (not just {@link Exception}) to prevent
   * an {@link Error} from a monitoring listener from breaking the scheduling
   * flow. This is especially critical between CAS-commit and parkUntil — a
   * leaked Error would leave the queueDepth incremented without a matching dequeue.
   */
  private void emitEvent(TrafficShaperEvent event) {
    for (Consumer<TrafficShaperEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for traffic shaper '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()),
            t);
      }
    }
  }

  // ======================== Introspection & Maintenance ========================

  public int getQueueDepth() {
    return snapshotRef.get().queueDepth();
  }

  public Duration getEstimatedWait() {
    return TrafficShaperCore.estimateWait(snapshotRef.get(), config, clock.instant());
  }

  public ThrottleSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  public TrafficShaperConfig getConfig() {
    return config;
  }

  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Resets the traffic shaper to its initial state.
   *
   * <p>Fix 9: Increments the epoch and unparks all waiting threads.
   * Parked threads detect the epoch change in {@link #parkUntil}, exit early,
   * dequeue their stale reservation, and re-acquire a fresh slot from the
   * reset state. This avoids threads waiting for dozens of seconds on
   * slots that are no longer relevant.
   */
  public void reset() {
    Instant now = clock.instant();
    while (true) {
      ThrottleSnapshot current = snapshotRef.get();
      ThrottleSnapshot fresh = TrafficShaperCore.reset(current, now);
      if (snapshotRef.compareAndSet(current, fresh)) {
        emitEvent(TrafficShaperEvent.reset(config.name(), now));
        unparkAll();
        return;
      }
    }
  }
}
