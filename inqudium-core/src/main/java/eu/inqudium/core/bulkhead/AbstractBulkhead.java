package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;

import java.time.Duration;
import java.time.Instant;

/**
 * Base implementation for all bulkhead paradigms (imperative, Reactor, Kotlin, RxJava).
 *
 * <p>Contains the complete bulkhead logic — decoration, event publishing, exception
 * handling, and state queries. Paradigm modules only implement the permit mechanism:
 * {@link #tryAcquirePermit(Duration)} and {@link #releasePermit()}.
 *
 * <p>This separation ensures that event publishing, error codes, and the acquire/release
 * contract are implemented <strong>once</strong> in the core, not duplicated across
 * every paradigm module.
 *
 * <h2>Safety guarantees</h2>
 * <ul>
 *   <li><strong>No permit leaks:</strong> Once a permit is acquired, it is guaranteed
 *       to be released via {@code finally} — even if event publishing throws.</li>
 *   <li><strong>Interrupt-aware:</strong> Thread interruption during permit acquisition
 *       is reported as {@link InqBulkheadInterruptedException}, not masked as
 *       {@link InqBulkheadFullException}.</li>
 *   <li><strong>Snapshot-consistent:</strong> Timestamps and concurrent-call counts are
 *       captured once per acquire/release and reused consistently across events and
 *       exceptions.</li>
 * </ul>
 *
 * <h2>Subclass contract</h2>
 * <ul>
 *   <li>{@link #tryAcquirePermit(Duration)} — attempt to acquire a permit within the
 *       given timeout. Return {@code true} if acquired, {@code false} if denied.
 *       Throw {@link InterruptedException} if the thread is interrupted during wait.</li>
 *   <li>{@link #releasePermit()} — release a previously acquired permit. Called exactly
 *       once per successful acquire, in a {@code finally} block.</li>
 *   <li>{@link #getConcurrentCalls()} — current number of in-flight calls.</li>
 *   <li>{@link #getAvailablePermits()} — number of permits currently available.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public abstract class AbstractBulkhead implements InqDecorator {

  private final String name;
  private final BulkheadConfig config;
  private final InqEventPublisher eventPublisher;

  protected AbstractBulkhead(String name, BulkheadConfig config) {
    this.name = name;
    this.config = config;
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
  }

  // ── InqDecorator / InqElement ──

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqElementType getElementType() {
    return InqElementType.BULKHEAD;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  public BulkheadConfig getConfig() {
    return config;
  }

  // ── Decoration — template method ──
  //
  // The acquire event is published INSIDE the try-finally block so that a
  // failing event publisher cannot leak the permit. The sequence is:
  //
  //   1. tryAcquirePermit(timeout) — may throw InterruptedException
  //   2. try {
  //        publish(AcquireEvent)    ← if this throws, finally still releases
  //        call.callable().call()
  //      } finally {
  //        releasePermit()
  //        publish(ReleaseEvent)
  //      }

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    return call.withCallable(() -> {
      acquire(call.callId());
      // Permit is now held — finally guarantees release even if events throw
      try {
        var acquireSnapshot = snapshot();
        eventPublisher.publish(new BulkheadOnAcquireEvent(
            call.callId(), name,
            acquireSnapshot.concurrentCalls, acquireSnapshot.timestamp));
        return call.callable().call();
      } finally {
        releasePermit();
        var releaseSnapshot = snapshot();
        eventPublisher.publish(new BulkheadOnReleaseEvent(
            call.callId(), name,
            releaseSnapshot.concurrentCalls, releaseSnapshot.timestamp));
      }
    });
  }

  /**
   * Acquires a permit or throws. Does NOT publish events — the caller is
   * responsible for entering a try-finally before publishing.
   */
  private void acquire(String callId) {
    boolean acquired;
    try {
      acquired = tryAcquirePermit(config.getMaxWaitDuration());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnRejectEvent(
          callId, name, snap.concurrentCalls, snap.timestamp));
      throw new InqBulkheadInterruptedException(
          callId, name, snap.concurrentCalls, config.getMaxConcurrentCalls());
    }

    if (!acquired) {
      var snap = snapshot();
      eventPublisher.publish(new BulkheadOnRejectEvent(
          callId, name, snap.concurrentCalls, snap.timestamp));
      throw new InqBulkheadFullException(
          callId, name, snap.concurrentCalls, config.getMaxConcurrentCalls());
    }
  }

  /**
   * Captures a consistent point-in-time view of concurrent calls and clock.
   */
  private Snapshot snapshot() {
    return new Snapshot(getConcurrentCalls(), config.getClock().instant());
  }

  /**
   * Attempts to acquire a permit within the given timeout.
   *
   * <p>Implementations must propagate {@link InterruptedException} — do NOT catch
   * it internally. The base class handles interrupt semantics (restoring the flag,
   * publishing reject events, and throwing {@link InqBulkheadInterruptedException}).
   *
   * @param timeout the maximum time to wait ({@link Duration#ZERO} for non-blocking)
   * @return {@code true} if the permit was acquired, {@code false} if denied
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  protected abstract boolean tryAcquirePermit(Duration timeout) throws InterruptedException;

  // ── Abstract — paradigm-specific permit mechanism ──

  /**
   * Releases a previously acquired permit.
   *
   * <p>Called exactly once per successful {@link #tryAcquirePermit} in a
   * {@code finally} block. Must not throw.
   */
  protected abstract void releasePermit();

  /**
   * Returns the current number of in-flight calls.
   *
   * @return the number of concurrently active calls
   */
  public abstract int getConcurrentCalls();

  /**
   * Returns the number of permits currently available.
   *
   * @return the available permit count
   */
  public abstract int getAvailablePermits();

  private record Snapshot(int concurrentCalls, Instant timestamp) {
  }
}
