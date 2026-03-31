package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.AbstractBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BlockingBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The static (fixed-limit) imperative implementation of the bulkhead state machine, using a
 * thread-blocking {@link Semaphore} for permit management.
 *
 * <h2>Role in the Architecture</h2>
 * <p>This is the simplest and most straightforward of the three imperative state machines. It
 * manages a fixed concurrency limit that is set once at construction and never changes. This
 * makes it suitable for downstream services with known, stable capacity where dynamic
 * adjustment is unnecessary.
 *
 * <p>It is selected by {@link ImperativeBulkheadFactory#create} when the {@link BulkheadConfig}
 * has neither a {@link eu.inqudium.core.bulkhead.InqLimitAlgorithm} (which would select
 * {@link AdaptiveImperativeStateMachine}) nor CoDel parameters (which would select
 * {@link CoDelImperativeStateMachine}).
 *
 * <h2>Comparison with Other State Machines</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Static (this class)</th><th>Adaptive</th><th>CoDel</th></tr>
 *   <tr><td>Limit</td><td>Fixed at construction</td><td>Dynamic (algorithm-driven)</td><td>Fixed at construction</td></tr>
 *   <tr><td>Concurrency primitive</td><td>Semaphore</td><td>ReentrantLock + Condition</td><td>ReentrantLock + Condition</td></tr>
 *   <tr><td>Feedback input</td><td>None</td><td>RTT + success/failure per call</td><td>Queue sojourn time</td></tr>
 *   <tr><td>Overhead</td><td>Minimal (Semaphore is JVM-optimized)</td><td>Moderate (lock + algorithm CAS)</td><td>Moderate (lock + timing)</td></tr>
 *   <tr><td>Use case</td><td>Known, stable downstream capacity</td><td>Unknown/variable capacity</td><td>Queue depth is the bottleneck</td></tr>
 * </table>
 *
 * <h2>Why a Semaphore?</h2>
 * <p>A {@link Semaphore} is the natural fit for a fixed-limit permit system:
 * <ul>
 *   <li><b>Purpose-built:</b> Semaphore is designed specifically for controlling access to a
 *       finite number of resources. Its {@code tryAcquire}/{@code release} API maps directly
 *       to the bulkhead's permit model.</li>
 *   <li><b>JVM-optimized:</b> {@link Semaphore} is built on {@link java.util.concurrent.locks.AbstractQueuedSynchronizer}
 *       (AQS), which is heavily optimized in HotSpot for low-contention fast paths. Under
 *       low contention, {@code tryAcquire()} is essentially a single CAS operation.</li>
 *   <li><b>Built-in timeout:</b> {@link Semaphore#tryAcquire(long, TimeUnit)} handles the
 *       timeout-based blocking natively, including fair queuing and interrupt handling.</li>
 *   <li><b>Fairness:</b> The semaphore is created with {@code fair=true}, guaranteeing
 *       FIFO ordering. Without fairness, long-waiting threads could be starved by threads
 *       that happen to call tryAcquire at the right moment.</li>
 * </ul>
 *
 * <p>The {@link AdaptiveImperativeStateMachine} and {@link CoDelImperativeStateMachine} cannot
 * use a Semaphore because their behavior requires re-evaluating conditions on every wakeup
 * (dynamic limit for Adaptive, sojourn time for CoDel), which requires a
 * {@link java.util.concurrent.locks.Condition} variable — not available with Semaphore.
 *
 * <h2>The Over-Release Guard</h2>
 * <p>A critical design element of this class is the {@link #acquiredPermits} counter that
 * shadows the semaphore's internal state. This guard exists because {@link Semaphore#release()}
 * does NOT check whether the caller actually holds a permit — it unconditionally increments the
 * semaphore's count. This means:
 * <ul>
 *   <li>Calling {@code release()} without a prior {@code acquire()} silently increases the
 *       semaphore's capacity beyond {@code maxConcurrentCalls}.</li>
 *   <li>Calling {@code release()} twice for a single {@code acquire()} has the same effect.</li>
 *   <li>Over time, these phantom permits accumulate, effectively disabling the bulkhead's
 *       concurrency protection entirely.</li>
 * </ul>
 *
 * <p>The {@code acquiredPermits} counter tracks the true number of permits held. Every
 * {@code release()} and {@code rollback()} checks this counter first and only releases the
 * semaphore if the count is positive. This makes over-release a harmless no-op instead of a
 * silent corruption of the bulkhead's capacity.
 *
 * <h2>Thread Safety</h2>
 * <p>The {@link Semaphore} handles its own thread safety internally (AQS-based). The
 * {@link #acquiredPermits} counter uses {@link AtomicInteger} with
 * {@link AtomicInteger#getAndUpdate} for lock-free, thread-safe decrement-if-positive
 * operations. No external synchronization is required.
 *
 * <p>Note: There is a brief window between {@code semaphore.tryAcquire()} returning
 * {@code true} and {@code acquiredPermits.incrementAndGet()} completing where
 * {@link #getAvailablePermits()} (from the semaphore) and {@link #getConcurrentCalls()}
 * (from the counter) may be temporarily inconsistent. This is a monitoring-only concern:
 * the sum {@code available + concurrent} may briefly not equal {@code maxConcurrentCalls}.
 * This is acceptable because these methods are diagnostic snapshots, not used for
 * acquire/release decisions.
 *
 * @see AdaptiveImperativeStateMachine
 * @see CoDelImperativeStateMachine
 * @see ImperativeBulkheadFactory
 * @since 0.2.0
 */
public final class SemaphoreImperativeStateMachine
    extends AbstractBulkheadStateMachine implements BlockingBulkheadStateMachine {

  // ──────────────────────────────────────────────────────────────────────────
  // Concurrency Primitives & State
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The fair semaphore that manages the fixed pool of permits.
   *
   * <p>Initialized with {@code maxConcurrentCalls} permits and {@code fair=true}:
   * <ul>
   *   <li><b>Permit count:</b> Set once at construction from
   *       {@link BulkheadConfig#getMaxConcurrentCalls()}. The semaphore's internal count
   *       decreases as permits are acquired and increases as they are released. When the
   *       count reaches 0, subsequent {@code tryAcquire()} calls block (with timeout) or
   *       return {@code false} (without timeout).</li>
   *   <li><b>Fairness:</b> A fair semaphore guarantees that permits are granted in the order
   *       threads requested them (FIFO). Without fairness, a thread that arrives later could
   *       "steal" a permit from a thread that has been waiting longer — under sustained high
   *       load, this can cause starvation where some threads wait indefinitely while others
   *       are repeatedly served.
   *       <p>The tradeoff is throughput: fair semaphores have slightly higher overhead because
   *       the AQS must maintain and traverse a FIFO queue. In practice, this overhead is
   *       negligible compared to the cost of the downstream calls being protected by the
   *       bulkhead.</li>
   * </ul>
   *
   * <p><b>Important:</b> The semaphore's internal count can drift above {@code maxConcurrentCalls}
   * if {@code release()} is called without a matching {@code acquire()} — this is a fundamental
   * Semaphore API property. The {@link #acquiredPermits} guard prevents this by tracking the
   * true number of held permits independently.
   */
  private final Semaphore semaphore;

  /**
   * Tracks the exact number of permits currently held by in-flight calls.
   *
   * <p>This counter exists as a guard against semaphore inflation from over-release. It is
   * the source of truth for "how many permits are currently held" — the semaphore's own
   * {@link Semaphore#availablePermits()} can only answer "how many are currently free",
   * which would be misleading if the semaphore's total count has been inflated by
   * double-releases.
   *
   * <p>The counter is managed with {@link AtomicInteger#getAndUpdate} using the pattern:
   * {@code current -> current > 0 ? current - 1 : 0}. This atomically reads the current
   * value and decrements it only if positive. If the value is already 0 (no permits held),
   * the update is a no-op and the semaphore is not released — preventing inflation.
   *
   * <h3>Why AtomicInteger instead of Semaphore's internal state?</h3>
   * <p>{@link Semaphore} does not expose a method to query how many permits have been
   * acquired (only how many are available). More critically, {@code Semaphore.release()}
   * does not check whether the caller holds a permit — it unconditionally increments the
   * count. This means the semaphore's state cannot be trusted for accurate "concurrent calls"
   * reporting or for guarding against over-release. The AtomicInteger provides both.
   *
   * <h3>Consistency window with the Semaphore</h3>
   * <p>Between {@code semaphore.tryAcquire()} succeeding and
   * {@code acquiredPermits.incrementAndGet()} completing, the two sources are momentarily
   * inconsistent: the semaphore shows one fewer available permit, but the counter has not
   * yet incremented. During this window:
   * <ul>
   *   <li>{@code getAvailablePermits()} returns the correct value (semaphore-based).</li>
   *   <li>{@code getConcurrentCalls()} returns a value that is 1 too low (counter-based).</li>
   *   <li>{@code available + concurrent} temporarily sums to {@code maxConcurrentCalls - 1}
   *       instead of {@code maxConcurrentCalls}.</li>
   * </ul>
   * <p>This inconsistency is harmless: both methods are diagnostic snapshots, not used for
   * acquire/release logic. The semaphore alone governs actual permit management.
   */
  private final AtomicInteger acquiredPermits;

  /**
   * The injectable nano-time source for wait-time measurement.
   *
   * <p>Defaults to {@code System::nanoTime} in production. Replaced with a controllable
   * source in tests to enable deterministic verification of wait-time telemetry without
   * real-time waits.
   *
   * <p>Used to capture {@code startWait} in {@link #tryAcquire} for the wait-time trace
   * events published by the base class ({@link AbstractBulkheadStateMachine#publishWaitTrace}).
   * The Semaphore handles its own internal timing for the timeout — this source is only for
   * telemetry, not for the acquire decision.
   */
  private final LongSupplier nanoTimeSource;

  // ──────────────────────────────────────────────────────────────────────────
  // Constructor
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new static imperative state machine with a fixed concurrency limit.
   *
   * <p>This constructor is package-private and called exclusively by
   * {@link ImperativeBulkheadFactory#create(String, BulkheadConfig)} when the config has
   * neither a limit algorithm nor CoDel parameters — the "default" path for the simplest
   * bulkhead configuration.
   *
   * <p>The semaphore is initialized with {@code maxConcurrentCalls} permits (from the base
   * class, which reads it from the config). A value of 0 creates a "closed" bulkhead that
   * rejects every request immediately — this is a legitimate configuration for feature flags
   * or testing scenarios, validated as >= 0 by
   * {@link AbstractBulkheadStateMachine#AbstractBulkheadStateMachine}.
   *
   * @param name   the unique bulkhead instance name, used for logging and event publishing
   * @param config the bulkhead configuration providing maxConcurrentCalls, clock, and
   *               nano-time source
   */
  public SemaphoreImperativeStateMachine(String name, BulkheadConfig config) {
    super(name, config);

    // Create a fair semaphore with the configured permit count.
    // Fair=true ensures FIFO ordering under contention — see the semaphore field
    // documentation for the fairness tradeoff discussion.
    this.semaphore = new Semaphore(maxConcurrentCalls, true);

    // Initialize the permit counter at 0 — no permits are held at construction time.
    this.acquiredPermits = new AtomicInteger(0);

    this.nanoTimeSource = config.getNanoTimeSource();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // BlockingBulkheadStateMachine Interface
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Attempts to acquire a bulkhead permit, potentially blocking the calling thread up to
   * the specified timeout.
   *
   * <p>This method delegates permit management entirely to the {@link Semaphore}, which
   * handles the blocking, timeout, fairness, and interrupt logic internally. The method
   * adds telemetry (wait-time traces, acquire/reject events) and the over-release guard
   * on top.
   *
   * <h3>{@code Duration.ZERO} Semantics</h3>
   * <p>When the timeout is {@link Duration#ZERO}, the method uses the non-blocking
   * {@link Semaphore#tryAcquire()} (no arguments) instead of
   * {@link Semaphore#tryAcquire(long, TimeUnit)}. This distinction is important:
   * <ul>
   *   <li>{@code tryAcquire()} is a single atomic CAS operation. If a permit is immediately
   *       available, it is acquired. If not, the method returns {@code false} instantly
   *       without parking the thread or entering the AQS wait queue.</li>
   *   <li>{@code tryAcquire(0, NANOSECONDS)} would also return quickly, but it enters the
   *       AQS framework's queuing path, which involves thread state transitions and memory
   *       barriers that are unnecessary for a non-blocking attempt.</li>
   * </ul>
   *
   * <p>This means {@code Duration.ZERO} has a fundamentally different execution profile than
   * {@code Duration.ofNanos(1)}: the former is a cheap CAS, the latter enters the full
   * timed-acquire path. Callers should be aware of this semantic boundary.
   *
   * <h3>Semaphore Acquire vs Counter Increment (Consistency Window)</h3>
   * <p>After the semaphore grants a permit ({@code acquired = true}), the
   * {@code acquiredPermits} counter is incremented. Between these two operations, there is
   * a brief window where the semaphore has decremented its internal count but the counter
   * has not yet incremented. During this window, monitoring queries may see a temporarily
   * inconsistent snapshot (see {@link #acquiredPermits} documentation). This is a diagnostic
   * concern only — the semaphore governs actual permit management, not the counter.
   *
   * <h3>Rollback Safety</h3>
   * <p>If {@link AbstractBulkheadStateMachine#handleAcquireSuccess} throws (e.g., the event
   * publisher crashes), the base class calls {@link #rollbackPermit()}, which decrements the
   * counter and releases the semaphore permit. This prevents permit leaks from telemetry
   * failures.
   *
   * @param callId  the unique call identifier for tracing and event correlation
   * @param timeout the maximum duration to wait for a permit. {@link Duration#ZERO} triggers
   *                a non-blocking attempt (see above).
   * @return {@code true} if the permit was acquired, {@code false} if the bulkhead is full
   * @throws InterruptedException if the thread is interrupted while waiting, wrapped in
   *         {@link InqBulkheadInterruptedException} with the thread's interrupt flag restored
   */
  @Override
  public boolean tryAcquire(String callId, Duration timeout) throws InterruptedException {

    // Capture the wait start time for telemetry. This timestamp is used by the base
    // class's publishWaitTrace() to report the total user-visible wait duration.
    // Unlike CoDel's two-timestamp approach (pre-lock vs post-lock), this class has
    // only one timestamp because the Semaphore handles blocking internally — there is
    // no separate "lock contention" phase to exclude.
    long startWait = nanoTimeSource.getAsLong();

    boolean acquired;
    try {
      // ── Delegate to Semaphore for permit acquisition ──
      //
      // The Semaphore handles all blocking, timeout, and fairness logic internally.
      // The method returns true if a permit was acquired, false if the timeout expired.
      acquired = timeout.isZero()
          // CRITICAL WARNING regarding Duration.ZERO semantics:
          // We MUST use `semaphore.tryAcquire(0, TimeUnit.NANOSECONDS)` rather than the
          // parameterless `semaphore.tryAcquire()`.
          //
          // According to the Java API documentation, the parameterless tryAcquire() method
          // is inherently UNFAIR. It will barge in and steal a permit if one happens to be
          // available, completely bypassing the Semaphore's fairness setting (fair = true)
          // and ignoring any threads that are already waiting in the AQS queue.
          //
          // If a system processes a high volume of non-blocking requests (timeout = ZERO),
          // using the parameterless method would cause severe starvation for threads that
          // are patiently waiting with an actual timeout in the fair queue. Using
          // tryAcquire(0, NANOSECONDS) guarantees that even immediate, non-blocking
          // attempts respect the strict FIFO ordering of the fair semaphore.
          ? semaphore.tryAcquire(0, TimeUnit.NANOSECONDS)
          : semaphore.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      // The thread was interrupted while waiting in the Semaphore's AQS queue.
      //
      // Restore the interrupt flag: Semaphore.tryAcquire() clears it when throwing
      // InterruptedException. Restoring allows callers to observe the interrupt via
      // Thread.isInterrupted() after catching InqBulkheadInterruptedException.
      Thread.currentThread().interrupt();

      // Publish the rejection telemetry before wrapping in the domain exception.
      handleAcquireFailure(callId, startWait);

      throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), maxConcurrentCalls);
    }

    if (acquired) {
      // ── Permit granted ──
      //
      // Increment the shadow counter BEFORE calling handleAcquireSuccess(). This ensures
      // that if handleAcquireSuccess() queries getConcurrentCalls() for the telemetry
      // snapshot, it sees the updated count including this new permit.
      //
      // If handleAcquireSuccess() throws, the base class calls rollbackPermit(), which
      // will decrement the counter and release the semaphore — undoing both operations.
      acquiredPermits.incrementAndGet();
      return handleAcquireSuccess(callId, startWait);
    } else {
      // ── Timeout expired — no permit acquired ──
      //
      // The Semaphore timed out without granting a permit. No counter increment, no
      // semaphore state change — just publish the rejection telemetry.
      //
      // Note: Unlike the Adaptive and CoDel state machines, there is no "pass the baton"
      // signal here. The Semaphore handles its internal waiter queue autonomously — a
      // timed-out thread does not consume a signal that would otherwise wake another
      // thread. This is a fundamental Semaphore guarantee (AQS manages the queue).
      handleAcquireFailure(callId, startWait);
      return false;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // AbstractBulkheadStateMachine Hooks
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Releases a previously acquired permit back to the semaphore.
   *
   * <p>This method is called from {@link AbstractBulkheadStateMachine#releaseAndReport}
   * inside a try-finally block, guaranteeing execution even if the adaptive algorithm hook
   * ({@link AbstractBulkheadStateMachine#onCallComplete}) throws an exception.
   *
   * <h3>The Over-Release Guard</h3>
   * <p>The core of this method is the atomic decrement-if-positive pattern:
   * <pre>{@code
   * if (acquiredPermits.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
   *     semaphore.release();
   * }
   * }</pre>
   *
   * <p>This single expression performs three operations atomically:
   * <ol>
   *   <li><b>Read:</b> Gets the current counter value.</li>
   *   <li><b>Conditional decrement:</b> If the value is positive, decrements by 1.
   *       If zero, leaves it unchanged (no-op).</li>
   *   <li><b>Guard check:</b> The return value of {@code getAndUpdate} is the value
   *       <em>before</em> the update. If it was positive (a permit was actually held),
   *       the semaphore is released. If it was zero (no permits held), the semaphore
   *       is NOT released.</li>
   * </ol>
   *
   * <p>This prevents semaphore inflation from double-release bugs:
   * <ul>
   *   <li><b>Without guard:</b> Two calls to {@code release()} for one {@code acquire()}
   *       would increment the semaphore's count to {@code maxConcurrentCalls + 1}. The
   *       bulkhead would then allow one more concurrent call than configured — silently
   *       defeating its purpose. Over time, repeated double-releases would accumulate,
   *       eventually making the bulkhead ineffective.</li>
   *   <li><b>With guard:</b> The first {@code release()} decrements the counter from 1 to 0
   *       and releases the semaphore. The second {@code release()} finds the counter at 0,
   *       the {@code getAndUpdate} returns 0, and the semaphore is NOT released. The
   *       double-release is silently absorbed.</li>
   * </ul>
   *
   * <p>The same guard is used in {@link #rollbackPermit()} to handle the case where a
   * rollback is attempted for a permit that was never successfully acquired (e.g., if
   * the acquire path is refactored incorrectly and rollback fires without a prior acquire).
   */
  @Override
  protected void releasePermitInternal() {
    // Atomically decrement the counter if positive, then release the semaphore only
    // if a permit was actually held. This is the over-release guard.
    if (acquiredPermits.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
      semaphore.release();
    }
  }

  /**
   * Rolls back a permit that was acquired but whose acquire-event publication failed.
   *
   * <p>Called by {@link AbstractBulkheadStateMachine#handleAcquireSuccess} when the event
   * publisher crashes after the permit was already acquired (semaphore decremented +
   * counter incremented in {@link #tryAcquire}). The rollback must undo both operations
   * to prevent permanent capacity loss.
   *
   * <p>Uses the same over-release guard as {@link #releasePermitInternal()}. This protects
   * against two edge cases:
   * <ul>
   *   <li><b>Double rollback:</b> If a bug causes rollback to be called twice for the same
   *       permit, the second call finds the counter already decremented and is a no-op.</li>
   *   <li><b>Rollback without acquire:</b> If a future refactoring error causes rollback to
   *       fire without a prior successful acquire (counter is 0), the semaphore is not
   *       inflated.</li>
   * </ul>
   *
   * <p>Mechanically identical to {@link #releasePermitInternal()} — the rollback of an
   * acquired-but-unpublished permit is the same operation as releasing a normally-held permit.
   */
  @Override
  protected void rollbackPermit() {
    // Same atomic decrement-if-positive guard as releasePermitInternal().
    // Prevents semaphore inflation from double-rollback or rollback-without-acquire.
    if (acquiredPermits.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
      semaphore.release();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // BulkheadStateMachine Interface (State Queries)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns the number of permits currently available for immediate acquisition.
   *
   * <p>Delegates directly to {@link Semaphore#availablePermits()}, which returns the
   * semaphore's current internal count. This is the canonical source for "how many permits
   * are free" because the semaphore is the actual gatekeeper — the {@link #acquiredPermits}
   * counter is a shadow used only for over-release protection.
   *
   * <p>This value is a point-in-time snapshot. Between reading it and acting on it, another
   * thread may acquire or release a permit. It is intended for monitoring and diagnostics,
   * not for conditional acquire logic (use {@link #tryAcquire} for that).
   *
   * <p>Unlike {@link AdaptiveImperativeStateMachine#getAvailablePermits()}, this method does
   * NOT acquire a lock — the Semaphore handles its own thread safety via AQS. This makes
   * the read very cheap (a single volatile read).
   *
   * @return the number of immediately available permits, always >= 0 (unless the semaphore
   *         has been inflated by an external {@code release()} call, which the over-release
   *         guard prevents in normal operation)
   */
  @Override
  public int getAvailablePermits() {
    return semaphore.availablePermits();
  }

  /**
   * Returns the number of calls currently in flight (holding a permit).
   *
   * <p>Returns the value of the {@link #acquiredPermits} counter, which tracks permits
   * acquired via {@link #tryAcquire} and released via {@link #releasePermitInternal} or
   * {@link #rollbackPermit}.
   *
   * <p>This is the counter-based source, not the semaphore-based source. The semaphore
   * only exposes {@code availablePermits()}, not "acquired permits". While you could
   * calculate it as {@code maxConcurrentCalls - semaphore.availablePermits()}, this
   * would give incorrect results if the semaphore has been inflated by a bug. The
   * {@code acquiredPermits} counter is immune to inflation because of the
   * decrement-if-positive guard.
   *
   * <p>This value is a point-in-time snapshot intended for monitoring and diagnostics.
   * See the {@link #acquiredPermits} field documentation for the brief consistency window
   * between the semaphore and the counter.
   *
   * @return the number of concurrent calls, always >= 0
   */
  @Override
  public int getConcurrentCalls() {
    return acquiredPermits.get();
  }
}
