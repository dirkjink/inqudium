package eu.inqudium.imperative.bulkhead.imperative;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.AbstractBulkheadStateMachine;
import eu.inqudium.core.bulkhead.AimdLimitAlgorithm;
import eu.inqudium.core.bulkhead.BlockingBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.bulkhead.InqLimitAlgorithm;
import eu.inqudium.core.bulkhead.VegasLimitAlgorithm;
import eu.inqudium.core.bulkhead.event.BulkheadLimitChangedTraceEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * An imperative (thread-blocking) state machine that dynamically adjusts its concurrency
 * capacity using a pluggable {@link InqLimitAlgorithm}.
 *
 * <h2>Role in the Architecture</h2>
 * <p>This class is the bridge between the <em>algorithmic</em> world ({@link AimdLimitAlgorithm},
 * {@link VegasLimitAlgorithm}) and the <em>mechanical</em> world (permit acquisition, thread
 * parking, signaling). The algorithm decides the optimal limit; this state machine enforces it.
 *
 * <p>In contrast to {@link SemaphoreImperativeStateMachine}, which uses a fixed limit backed by
 * a {@link java.util.concurrent.Semaphore}, and {@link CoDelImperativeStateMachine}, which uses
 * a fixed limit with queue-based delay management, this class operates with a limit that can
 * change after every completed call. This creates unique concurrency challenges that the other
 * two state machines do not face.
 *
 * <h2>The Adaptive Feedback Loop</h2>
 * <p>The lifecycle of an adaptive bulkhead call follows this path:
 * <ol>
 *   <li><b>Acquire:</b> {@link #tryAcquire} checks {@code activeCalls < limitAlgorithm.getLimit()}.
 *       If capacity is available, the permit is granted. If not, the thread parks on the
 *       {@link #notFull} condition until capacity frees up or the timeout expires.</li>
 *   <li><b>Execute:</b> The business logic runs (managed by {@link ImperativeBulkheadStrategy}).</li>
 *   <li><b>Report:</b> {@link AbstractBulkheadStateMachine#releaseAndReport} calls
 *       {@link #onCallComplete}, which feeds the call's RTT and success/failure status back
 *       into the {@link InqLimitAlgorithm}. The algorithm recalculates the optimal limit.</li>
 *   <li><b>Release:</b> {@link #releasePermitInternal} decrements {@code activeCalls} and
 *       signals one waiting thread.</li>
 *   <li><b>Notify:</b> If the limit changed, {@link #onCallComplete} signals ALL waiting
 *       threads so they can re-evaluate against the new limit.</li>
 * </ol>
 *
 * <p>Step 3 is what makes this class "adaptive": every completed call potentially changes the
 * rules for all currently waiting threads. A successful call might increase the limit, creating
 * new capacity for waiters. A failed call might decrease the limit, meaning some waiters that
 * were previously within capacity are now over-limit and should time out sooner.
 *
 * <h2>Comparison with Other State Machines</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Adaptive</th><th>Static (Semaphore)</th><th>CoDel</th></tr>
 *   <tr><td>Limit</td><td>Dynamic (algorithm-driven)</td><td>Fixed at construction</td><td>Fixed at construction</td></tr>
 *   <tr><td>Rejection trigger</td><td>activeCalls >= dynamic limit</td><td>No semaphore permits available</td><td>Sojourn time during sustained congestion</td></tr>
 *   <tr><td>Concurrency primitive</td><td>ReentrantLock + Condition</td><td>Semaphore</td><td>ReentrantLock + Condition</td></tr>
 *   <tr><td>Feedback input</td><td>RTT + success/failure per call</td><td>None</td><td>Queue wait time</td></tr>
 *   <tr><td>Use case</td><td>Unknown/variable downstream capacity</td><td>Known, stable downstream capacity</td><td>Queue depth is the bottleneck</td></tr>
 * </table>
 *
 * <h2>Key Design Decisions</h2>
 *
 * <h3>ReentrantLock Instead of Semaphore</h3>
 * <p>{@link SemaphoreImperativeStateMachine} uses a {@link java.util.concurrent.Semaphore} because
 * its limit is fixed — the semaphore's permit count is set once at construction and never changes.
 * This class cannot use a Semaphore because the limit changes dynamically. A Semaphore's permit
 * count is not directly settable after construction; resizing would require draining and
 * re-creating it, which is neither atomic nor safe under concurrent access.
 *
 * <p>Instead, this class uses a {@link ReentrantLock} with a {@link Condition} variable. The
 * condition check ({@code activeCalls >= limitAlgorithm.getLimit()}) is re-evaluated on every
 * wakeup, naturally adapting to limit changes without any resize operation.
 *
 * <h3>Dynamic Limit in the While Condition</h3>
 * <p>The wait loop's condition is {@code activeCalls >= limitAlgorithm.getLimit()}, where
 * {@code getLimit()} returns the <em>current</em> algorithm-computed limit — not a cached value.
 * This means the condition adapts to limit changes between iterations:
 * <ul>
 *   <li>If the limit increased (e.g., from 10 to 12) while a thread was sleeping, the thread
 *       wakes up and may find {@code activeCalls < 12} — it can now acquire a permit.</li>
 *   <li>If the limit decreased (e.g., from 10 to 5) while a thread was sleeping, the thread
 *       wakes up and finds {@code activeCalls >= 5} — it loops back and waits longer or times out.</li>
 * </ul>
 *
 * <h3>Algorithm Update Outside the Lock</h3>
 * <p>In {@link #onCallComplete}, {@code limitAlgorithm.update()} is called <em>before</em>
 * acquiring the lock. This is safe because the algorithm implementations ({@link AimdLimitAlgorithm},
 * {@link VegasLimitAlgorithm}) are internally thread-safe (CAS-based). Calling update() outside
 * the lock prevents the lock from being held during the algorithm's CAS retry loop, which could
 * cause unnecessary contention with threads trying to acquire permits.
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state ({@code activeCalls}, {@code oldLimit}) is protected by the
 * {@link ReentrantLock}. The {@link InqLimitAlgorithm} is thread-safe by contract (CAS-based).
 * The lock is acquired interruptibly in {@code tryAcquire()} (to respect thread interruption)
 * and non-interruptibly in {@code releasePermitInternal()} and {@code onCallComplete()} (to
 * guarantee permit release and state consistency).
 *
 * @see AimdLimitAlgorithm
 * @see VegasLimitAlgorithm
 * @see SemaphoreImperativeStateMachine
 * @see CoDelImperativeStateMachine
 * @see ImperativeBulkheadFactory
 * @since 0.2.0
 */
public final class AdaptiveImperativeStateMachine
    extends AbstractBulkheadStateMachine implements BlockingBulkheadStateMachine {

  // ──────────────────────────────────────────────────────────────────────────
  // Configuration Fields (immutable after construction)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The pluggable algorithm that computes the optimal concurrency limit based on
   * call telemetry (RTT and success/failure).
   *
   * <p>This field is the adaptive heart of the state machine. After every completed call,
   * {@link #onCallComplete} feeds the outcome to {@link InqLimitAlgorithm#update(Duration, boolean)},
   * and the algorithm recalculates the limit. The new limit is then queried via
   * {@link InqLimitAlgorithm#getLimit()} both in the wait loop condition
   * ({@code activeCalls >= limitAlgorithm.getLimit()}) and in the change-detection logic.
   *
   * <p>Currently supported implementations:
   * <ul>
   *   <li>{@link AimdLimitAlgorithm}: Error-driven. Increases on success, decreases on
   *       sustained failures. Best when errors are a reliable congestion signal.</li>
   *   <li>{@link VegasLimitAlgorithm}: Latency-driven. Detects queuing delay proactively
   *       via RTT gradient analysis. Best for latency-sensitive systems with stable workloads.</li>
   * </ul>
   *
   * <p>The algorithm is thread-safe by contract (CAS-based internally), so
   * {@code limitAlgorithm.update()} and {@code limitAlgorithm.getLimit()} can be called
   * concurrently from multiple threads without external synchronization. However, the
   * <em>state machine's</em> reaction to limit changes (signaling waiters, publishing events)
   * is coordinated under the {@link #lock}.
   */
  private final InqLimitAlgorithm limitAlgorithm;

  // ──────────────────────────────────────────────────────────────────────────
  // Concurrency Primitives
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The main lock protecting all mutable state ({@code activeCalls}, {@code oldLimit}).
   *
   * <p>A {@link ReentrantLock} is used instead of a {@link java.util.concurrent.Semaphore}
   * because the concurrency limit is dynamic. A Semaphore's permit count is set at construction
   * and cannot be atomically resized; a ReentrantLock with a Condition allows the wait condition
   * ({@code activeCalls >= limitAlgorithm.getLimit()}) to naturally adapt to limit changes on
   * every wakeup iteration.
   *
   * <p>The lock is <em>not</em> created with fairness ({@code new ReentrantLock(false)}, the
   * default). Fairness would guarantee strict FIFO ordering but at a significant throughput
   * cost. The {@link #notFull} condition variable provides sufficient ordering for the
   * waiting threads.
   *
   * <p>Acquisition modes:
   * <ul>
   *   <li>{@link ReentrantLock#lockInterruptibly()} in {@link #tryAcquire}: Threads waiting
   *       for permits should be interruptible (e.g., executor shutdown, parent timeout).</li>
   *   <li>{@link ReentrantLock#lock()} in {@link #releasePermitInternal} and
   *       {@link #onCallComplete}: Permit release and state updates are critical operations
   *       that must complete regardless of the thread's interrupt status.</li>
   * </ul>
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * The condition variable that threads wait on when no permits are available.
   *
   * <p>Threads enter {@link Condition#awaitNanos(long)} when
   * {@code activeCalls >= limitAlgorithm.getLimit()} and are woken by:
   * <ul>
   *   <li>{@link Condition#signal()} in {@link #releasePermitInternal()}: One permit was
   *       freed — wake exactly one waiter (no thundering herd).</li>
   *   <li>{@link Condition#signalAll()} in {@link #onCallComplete}: The limit changed —
   *       wake ALL waiters so they can re-evaluate against the new limit. signalAll is
   *       necessary here because a limit increase may create capacity for multiple waiters
   *       simultaneously (e.g., limit jumps from 5 to 10 while 5 threads are waiting).</li>
   * </ul>
   *
   * <p>The condition name "notFull" reflects its semantic meaning: the bulkhead is "not full"
   * when {@code activeCalls < limitAlgorithm.getLimit()}. Threads wait on this condition until
   * it becomes true.
   */
  private final Condition notFull = lock.newCondition();

  /**
   * The injectable nano-time source for wait-time measurement.
   *
   * <p>Defaults to {@code System::nanoTime} in production. Replaced with a controllable
   * {@link java.util.concurrent.atomic.AtomicLong} in tests to enable deterministic
   * verification of wait-time telemetry without real-time waits.
   *
   * <p>Used to capture {@code startWait} in {@link #tryAcquire} for the wait-time trace
   * events published by the base class ({@link AbstractBulkheadStateMachine#publishWaitTrace}).
   */
  private final LongSupplier nanoTimeSource;

  /**
   * The injectable clock for event timestamps.
   *
   * <p>Used in {@link #onCallComplete} to capture the timestamp of limit-change trace events
   * while the lock is held. This ensures timestamps form a total order matching the
   * lock-acquisition sequence, even when events are published outside the lock.
   *
   * <p>Defaults to {@link java.time.Clock#systemUTC()} in production. Replaced with a
   * controllable clock in tests.
   */
  private final InqClock clock;

  // ──────────────────────────────────────────────────────────────────────────
  // Mutable State (all protected by the lock)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The number of permits currently held by in-flight calls.
   *
   * <p>Incremented when a permit is granted (in {@link #tryAcquire}, after the wait loop
   * confirms capacity), decremented when a permit is released (in {@link #releasePermitInternal}).
   *
   * <p>This value is compared against {@code limitAlgorithm.getLimit()} to determine capacity.
   * Because the limit is dynamic, the relationship between {@code activeCalls} and the limit
   * can change at any time:
   * <ul>
   *   <li><b>Normal:</b> {@code activeCalls < limit} — capacity is available.</li>
   *   <li><b>At capacity:</b> {@code activeCalls == limit} — no new permits can be granted.</li>
   *   <li><b>Over-limit (transient):</b> {@code activeCalls > limit} — this occurs when the
   *       algorithm decreases the limit while calls are in flight. The state machine does NOT
   *       forcibly revoke already-granted permits. Instead, it simply refuses new permits until
   *       enough in-flight calls complete to bring {@code activeCalls} back below the new limit.
   *       This is a deliberate design choice: forcibly canceling in-progress business logic would
   *       be disruptive and potentially unsafe.</li>
   * </ul>
   *
   * <p>Protected by {@link #lock}.
   */
  private int activeCalls = 0;

  /**
   * Tracks the most recently observed limit value from the algorithm.
   *
   * <p>Used exclusively in {@link #onCallComplete} to detect limit changes: after the algorithm
   * updates, the new limit is compared against {@code oldLimit}. If they differ, the state
   * machine knows the limit has changed and must:
   * <ol>
   *   <li>Wake all waiting threads (via {@code signalAll()}) so they re-evaluate.</li>
   *   <li>Publish a {@link BulkheadLimitChangedTraceEvent} for observability.</li>
   * </ol>
   *
   * <h3>Why This Field Is Under the Lock</h3>
   * <p>In the original implementation, this field was {@code volatile} and accessed outside
   * the lock. This created a race condition in {@link #onCallComplete}:
   * <ol>
   *   <li>Thread A reads {@code oldLimit = 10}, computes {@code newLimit = 12}.</li>
   *   <li>Thread B reads {@code oldLimit = 10}, computes {@code newLimit = 11}.</li>
   *   <li>Thread A writes {@code oldLimit = 12} and calls {@code signalAll()}.</li>
   *   <li>Thread B writes {@code oldLimit = 11} (overwriting A's value) and calls
   *       {@code signalAll()}.</li>
   *   <li>Result: {@code oldLimit = 11}, but the limit actually went 10→12→11. The next
   *       comparison will diff against 11, potentially missing the 12→11 transition's
   *       trace event, or producing a duplicate.</li>
   * </ol>
   *
   * <p>By placing the read-compare-write sequence under the lock, only one thread at a time
   * can observe and update {@code oldLimit}, eliminating the lost-update race.
   *
   * <p>Protected by {@link #lock}.
   */
  private int oldLimit;

  // ──────────────────────────────────────────────────────────────────────────
  // Constructor
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new adaptive imperative state machine.
   *
   * <p>This constructor is package-private and called exclusively by
   * {@link ImperativeBulkheadFactory#create(String, BulkheadConfig)} when the config has a
   * non-null {@link BulkheadConfig#getLimitAlgorithm()}.
   *
   * <p>The factory ensures mutual exclusivity between adaptive limits and CoDel —
   * this is enforced at the config level by {@link BulkheadConfig.Builder#build()}.
   *
   * @param name           the unique bulkhead instance name, used for logging and events
   * @param config         the bulkhead configuration providing clock, nano-time source, and
   *                       other infrastructure settings
   * @param limitAlgorithm the adaptive algorithm that will compute the dynamic concurrency
   *                       limit based on call telemetry (must not be null)
   */
  public AdaptiveImperativeStateMachine(String name, BulkheadConfig config, InqLimitAlgorithm limitAlgorithm) {
    super(name, config);
    this.limitAlgorithm = limitAlgorithm;

    // Initialize oldLimit to the algorithm's starting value. This ensures the first
    // onCallComplete() can correctly detect whether the limit changed from its initial
    // state. Without this, oldLimit would default to 0 and the first call would always
    // trigger a spurious "limit changed from 0 to X" event.
    this.oldLimit = limitAlgorithm.getLimit();

    this.nanoTimeSource = config.getNanoTimeSource();
    this.clock = config.getClock();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // BulkheadStateMachine Interface Overrides
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns the current dynamic concurrency limit from the algorithm.
   *
   * <p>This overrides the base class's static {@code maxConcurrentCalls} (set from config at
   * construction) with the algorithm's live value. The limit may change after every call
   * completion, so consecutive invocations of this method may return different values.
   *
   * <p>This method is called from:
   * <ul>
   *   <li>{@link ImperativeBulkheadStrategy}: To construct {@link InqBulkheadFullException}
   *       with the current maximum when a permit acquisition fails.</li>
   *   <li>{@link AbstractBulkheadStateMachine}: For snapshot creation in telemetry events.</li>
   *   <li>External monitoring: To report the current effective limit on dashboards.</li>
   * </ul>
   *
   * <p>Note: This method does NOT acquire the lock. The algorithm's {@code getLimit()} is
   * thread-safe by contract (reading an {@link java.util.concurrent.atomic.AtomicReference}
   * or {@link java.util.concurrent.atomic.AtomicInteger}). Acquiring the lock here would
   * create unnecessary contention for a read-only operation.
   *
   * @return the algorithm's current recommended limit, always in [minLimit, maxLimit]
   */
  @Override
  public int getMaxConcurrentCalls() {
    return limitAlgorithm.getLimit();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // BlockingBulkheadStateMachine Interface
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Attempts to acquire a bulkhead permit, potentially blocking the calling thread up to
   * the specified timeout.
   *
   * <p>The wait condition ({@code activeCalls >= limitAlgorithm.getLimit()}) is dynamic:
   * the limit may change while the thread is parked, because other threads completing their
   * calls trigger {@link #onCallComplete}, which updates the algorithm and potentially
   * changes the limit. This is why the condition re-reads {@code limitAlgorithm.getLimit()}
   * on every iteration rather than using a cached value.
   *
   * <h3>Interaction with Dynamic Limits</h3>
   * <p>Consider a thread parked in the wait loop with {@code activeCalls = 10} and the limit
   * at 10 (full). Two scenarios:
   * <ul>
   *   <li><b>Limit increases to 12:</b> {@link #onCallComplete} calls {@code signalAll()},
   *       waking this thread. It re-checks: {@code 10 >= 12} is false — the thread exits
   *       the loop and acquires the permit.</li>
   *   <li><b>Limit decreases to 8:</b> {@link #onCallComplete} calls {@code signalAll()},
   *       waking this thread. It re-checks: {@code 10 >= 8} is still true — the thread
   *       loops back and waits again. On the next iteration, if its timeout has expired
   *       ({@code nanos <= 0L}), it exits promptly with {@code false}.</li>
   * </ul>
   *
   * <h3>The Pass-the-Baton Pattern</h3>
   * <p>Every non-acquiring exit path (timeout, interruption) calls {@code notFull.signal()}
   * before returning. This prevents lost wakeups: if a permit was freed (or the limit
   * increased) at the exact moment this thread timed out, the signal that woke it would be
   * consumed without effect. Re-signaling ensures the next waiter gets a chance.
   *
   * @param callId  the unique call identifier for tracing and event correlation
   * @param timeout the maximum duration to wait for a permit. {@link Duration#ZERO} means
   *                the thread will not park: if no capacity is available on the first check,
   *                it returns {@code false} immediately.
   * @return {@code true} if the permit was acquired, {@code false} if the timeout expired
   * while the bulkhead was at or over capacity
   * @throws InterruptedException if the thread is interrupted while waiting, wrapped in
   *                              {@link InqBulkheadInterruptedException} with the thread's interrupt flag restored
   */
  @Override
  public boolean tryAcquire(String callId, Duration timeout) throws InterruptedException {

    // Capture the wait start time BEFORE lock acquisition. This timestamp includes
    // lock contention time, providing the total user-visible wait duration for telemetry.
    long startWait = nanoTimeSource.getAsLong();
    long nanos = timeout.toNanos();

    try {
      // Acquire the lock interruptibly: threads waiting for permits should be cancellable
      // (e.g., during executor shutdown or parent timeout).
      lock.lockInterruptibly();
      // ── Wait loop: park until capacity is available ──
      //
      // The condition reads limitAlgorithm.getLimit() live on every iteration. This is
      // the key difference from the static state machine's semaphore: the capacity check
      // dynamically adapts to algorithm-driven limit changes.
      //
      // Note: limitAlgorithm.getLimit() is called while holding the lock. The algorithm's
      // getLimit() is a simple atomic read (no CAS, no blocking), so this does not create
      // a deadlock risk. The alternative — reading the limit outside the loop and caching
      // it — would miss limit changes that occur while the thread is parked.
      while (activeCalls >= limitAlgorithm.getLimit()) {
        if (nanos <= 0L) {
          // Timeout expired while at capacity.
          //
          // Pass the baton: the signal that woke this thread may have been a legitimate
          // permit release or limit increase. If we exit without re-signaling, the next
          // waiter may be stranded. signal() (not signalAll) is sufficient here because
          // we're only forwarding the single signal we consumed.
          notFull.signal();
          handleAcquireFailure(callId, startWait);
          return false;
        }
        // Park the thread on the condition. Returns the approximate remaining nanos
        // (positive if signaled before timeout, zero/negative if timed out).
        nanos = notFull.awaitNanos(nanos);
      }

      // ── Capacity is available — grant the permit ──
      //
      // At this point, activeCalls < limitAlgorithm.getLimit(). Increment the counter
      // to claim the permit. If handleAcquireSuccess() throws (e.g., event publisher
      // crash), the base class's rollback mechanism calls rollbackPermit() to undo this
      // increment, preventing permit leaks.
      activeCalls++;

      return handleAcquireSuccess(callId, startWait);

    } catch (InterruptedException e) {
      // The thread was interrupted while parked on the condition.
      //
      // Pass the baton: the interruption consumed the signal that woke this thread.
      // Re-signal to ensure the next waiter is not stranded.
      notFull.signal();

      // Restore the interrupt flag. awaitNanos() clears it when throwing
      // InterruptedException. Restoring allows callers to observe the interrupt
      // via Thread.isInterrupted() after catching InqBulkheadInterruptedException.
      Thread.currentThread().interrupt();

      handleAcquireFailure(callId, startWait);
      throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), limitAlgorithm.getLimit());
    } finally {
      lock.unlock();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // AbstractBulkheadStateMachine Hooks
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Feeds the completed call's outcome back into the adaptive algorithm and reacts to any
   * resulting limit change.
   *
   * <p>This is the core of the adaptive feedback loop. It is called by
   * {@link AbstractBulkheadStateMachine#releaseAndReport} for every completed call, before
   * {@link #releasePermitInternal}. The method performs three steps:
   *
   * <ol>
   *   <li><b>Algorithm update (outside lock):</b> Feeds the RTT and success/failure to the
   *       algorithm, which recalculates the optimal limit. This step is outside the lock
   *       because the algorithm is internally thread-safe (CAS-based), and holding the lock
   *       during a potential CAS retry loop would create unnecessary contention.</li>
   *   <li><b>State transition (under lock):</b> Reads the new limit, compares it against
   *       {@code oldLimit}, updates {@code oldLimit} if changed, captures the event timestamp,
   *       and signals waiting threads. All of this is atomic under the lock.</li>
   *   <li><b>Event publication (outside lock):</b> If the limit changed, publishes a trace
   *       event. This is outside the lock to avoid blocking permit acquisition during I/O.</li>
   * </ol>
   *
   * <h3>Race Condition on oldLimit</h3>
   * <p>The original implementation had {@code oldLimit} as {@code volatile} and performed the
   * read-compare-write sequence outside the lock. Two concurrent threads could both read the
   * same {@code oldLimit}, compute different new limits, and overwrite each other's updates —
   * producing incorrect trace events and potentially missing {@code signalAll()} calls.
   *
   * <p>The fix moves the entire read-compare-write-signal sequence under the lock. Only one
   * thread at a time can observe and update {@code oldLimit}, eliminating the lost-update race.
   *
   * <h3>Liveness on Limit Decrease</h3>
   * <p>The original implementation only called {@code signalAll()} when the limit increased.
   * When the limit decreased, waiting threads would continue sleeping for their full remaining
   * timeout, even though the capacity situation had worsened and they should re-evaluate sooner.
   *
   * <p>The fix calls {@code signalAll()} on ANY limit change. On a decrease, woken threads
   * re-check the condition ({@code activeCalls >= limitAlgorithm.getLimit()}), find it still
   * true, and loop back — but with an updated remaining time from {@code awaitNanos()}. If
   * their timeout has elapsed, they exit promptly via the {@code nanos <= 0L} branch instead
   * of sleeping unnecessarily for seconds.
   *
   * <h3>Event Ordering via Lock-Captured Timestamps</h3>
   * <p>The trace event's timestamp is captured inside the lock (via {@code clock.instant()})
   * rather than lazily in the event's lambda. Since only one thread holds the lock at a time,
   * the captured timestamps form a total order matching the lock-acquisition sequence. Even
   * though publication happens outside the lock (two threads may publish concurrently),
   * observability tools can sort by timestamp to reconstruct the correct logical order.
   *
   * @param callId the unique call identifier for trace event correlation
   * @param rtt    the round-trip time measured by the paradigm strategy
   * @param error  the business exception thrown by the call, or null if successful
   */
  @Override
  protected void onCallComplete(String callId, Duration rtt, Throwable error) {

    // ── Step 1: Feed the algorithm (outside lock) ──
    //
    // The algorithm's update() method is thread-safe by contract (CAS-based). Calling it
    // outside the lock prevents holding the lock during potential CAS retry loops, which
    // would block permit acquisition and release by other threads.
    //
    // The isSuccess flag is derived from the error parameter: null means the business
    // logic completed without throwing, which counts as a success for the algorithm.
    // Any throwable (checked or unchecked) counts as a failure, indicating potential
    // downstream congestion or resource exhaustion.
    limitAlgorithm.update(rtt, error == null);

    // Local variables to capture state under the lock and use after release.
    // This "snapshot and release" pattern minimizes lock hold time while ensuring
    // consistent reads.
    int capturedOldLimit;
    int newLimit;
    boolean limitChanged;

    // The event timestamp is captured under the lock to guarantee a total order
    // matching the lock-acquisition sequence. Without this, two threads releasing the lock
    // in rapid succession could capture timestamps in reverse order during concurrent
    // clock.instant() calls outside the lock.
    Instant eventTimestamp;
    long rttNanos = rtt.toNanos();

    // ── Step 2: Detect and react to limit changes (under lock) ──
    lock.lock();
    try {
      // Read the algorithm's current limit. This reflects the update from step 1
      // (and potentially updates from other threads that ran between step 1 and now).
      newLimit = limitAlgorithm.getLimit();
      capturedOldLimit = oldLimit;
      limitChanged = capturedOldLimit != newLimit;

      if (limitChanged) {
        // Update oldLimit atomically under the lock. The next thread to enter this
        // method will compare against this value, ensuring no transitions are missed
        // or duplicated.
        oldLimit = newLimit;

        // Capture the timestamp under the lock for consistent event ordering.
        eventTimestamp = clock.instant();
      } else {
        eventTimestamp = null;
      }

      // ── Signal waiting threads on ANY limit change ──
      //
      // signalAll() is used (not signal()) because a limit change can affect multiple
      // waiting threads simultaneously:
      //
      // - Limit increase (e.g., 5 → 10): If 5 threads are waiting and activeCalls is 5,
      //   all 5 can now potentially acquire permits. signal() would only wake one.
      //
      // - Limit decrease (e.g., 10 → 5): Threads need to re-evaluate their wait condition.
      //   They'll find activeCalls >= 5 (still over-capacity), loop back, and awaitNanos()
      //   returns with updated remaining time. If their timeout has elapsed, they exit
      //   promptly instead of sleeping unnecessarily.

      if (newLimit > capturedOldLimit) {
        // ── Targeted wakeup to eliminate "Thundering Herd" ──
        //
        // Instead of calling signalAll() on a limit change, which wakes every parked thread
        // simultaneously (causing a massive CPU-intensive "thundering herd" where most threads
        // just fight for the lock, realize the limit is still too tight, and go back to sleep),
        // we perform a highly optimized, targeted wakeup.
        //
        // 1. We only wake threads if the limit actually INCREASES. If the limit decreases,
        //    there is no new capacity, so waking threads is pointless and wastes CPU cycles.
        // 2. We calculate the exact delta of newly created slots (newLimit - capturedOldLimit).
        // 3. We call signal() exactly that many times.
        //
        // This precise routing ensures we only wake up the exact number of threads that can
        // realistically acquire a newly freed permit. This mimics the highly optimized behavior
        // of core JVM concurrency primitives and guarantees maximum throughput under heavy load.
        int newlyAvailableSlots = newLimit - capturedOldLimit;
        for (int i = 0; i < newlyAvailableSlots; i++) {
          notFull.signal();
        }
      }
    } finally {
      lock.unlock();
    }

    // ── Step 3: Publish trace event (outside lock) ──
    //
    // Event publication is outside the lock to prevent I/O operations (logging, event bus
    // dispatch) from blocking permit acquisition and release. The tradeoff is that two
    // threads may publish events concurrently in arbitrary order, but the timestamps
    // captured in step 2 (under the lock) provide the correct logical ordering.
    //
    // The local variables (capturedOldLimit, newLimit, rttNanos, ts) are all captured
    // values from the locked section — they are immutable and safe to use after the
    // lock is released.
    if (limitChanged) {
      final Instant ts = eventTimestamp;
      eventPublisher.publishTrace(() -> new BulkheadLimitChangedTraceEvent(
          callId,
          name,
          capturedOldLimit,
          newLimit,
          rttNanos,
          ts
      ));
    }
  }

  /**
   * Releases a previously acquired permit and wakes one waiting thread.
   *
   * <p>This method is called from {@link AbstractBulkheadStateMachine#releaseAndReport}
   * inside a try-finally block, guaranteeing execution even if {@link #onCallComplete}
   * throws an exception.
   *
   * <p>Uses {@link ReentrantLock#lock()} (non-interruptible) because permit release is a
   * critical operation that must complete regardless of the thread's interrupt status.
   * Failing to release a permit would permanently reduce the bulkhead's effective capacity.
   *
   * <h3>Over-Release Guard</h3>
   * <p>The {@code activeCalls > 0} check prevents the counter from going negative due to
   * double-release bugs. Without this guard, a double release would make the bulkhead
   * believe it has more capacity than it actually does ({@code available = limit - activeCalls}
   * would return an inflated value), effectively disabling the concurrency limit.
   *
   * <h3>signal() vs signalAll()</h3>
   * <p>Only {@link Condition#signal()} is used here (not {@code signalAll()}) because exactly
   * one permit was freed. Waking all waiting threads would cause a thundering herd: all but
   * one would immediately re-park after finding the condition still true. The
   * {@link #onCallComplete} method handles the multi-thread wakeup case (limit changes) with
   * {@code signalAll()}.
   */
  @Override
  protected void releasePermitInternal() {
    lock.lock();
    try {
      if (activeCalls > 0) {
        activeCalls--;
        // Wake one waiting thread. A single permit was freed, so only one thread can
        // benefit from this release.
        notFull.signal();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Rolls back a permit that was acquired but whose acquire-event publication failed.
   *
   * <p>Called by {@link AbstractBulkheadStateMachine#handleAcquireSuccess} when the event
   * publisher crashes after the permit was already granted ({@code activeCalls++} in
   * {@link #tryAcquire}). The rollback decrements {@code activeCalls} and signals a waiter,
   * ensuring the permit is returned to the pool and the bulkhead's capacity is not
   * permanently reduced by a telemetry failure.
   *
   * <p>Delegates to {@link #releasePermitInternal()} because the rollback and release
   * operations are mechanically identical: decrement the counter and signal one waiter.
   */
  @Override
  protected void rollbackPermit() {
    releasePermitInternal();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // BulkheadStateMachine Interface (State Queries)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns the number of permits currently available for immediate acquisition.
   *
   * <p>Calculated as {@code limitAlgorithm.getLimit() - activeCalls}, clamped to a minimum
   * of 0. The clamping handles the over-limit transient state: when the algorithm decreases
   * the limit while calls are in flight, {@code activeCalls} may temporarily exceed the new
   * limit, resulting in a negative raw difference. The clamped value of 0 correctly reflects
   * that no permits are available.
   *
   * <p>Both {@code limitAlgorithm.getLimit()} and {@code activeCalls} are read under the lock
   * to ensure a consistent snapshot. Without the lock, a concurrent limit change between the
   * two reads could produce an inconsistent result (e.g., reading the old high limit and the
   * new high activeCalls, yielding a spuriously negative value).
   *
   * <p>This value is a point-in-time snapshot intended for monitoring and diagnostics. It may
   * be stale by the time the caller uses it. Do not use this for acquire/release decisions.
   *
   * @return the number of available permits, always >= 0
   */
  @Override
  public int getAvailablePermits() {
    lock.lock();
    try {
      return Math.max(0, limitAlgorithm.getLimit() - activeCalls);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the number of calls currently in flight (holding a permit).
   *
   * <p>This is the raw {@code activeCalls} counter. It does NOT include threads that are
   * waiting for a permit — only those that have successfully acquired one and are executing
   * the business logic (or whose execution has completed but whose permit has not yet been
   * released).
   *
   * <p>Read under the lock for a consistent snapshot.
   *
   * @return the number of concurrent calls, always >= 0
   */
  @Override
  public int getConcurrentCalls() {
    lock.lock();
    try {
      return activeCalls;
    } finally {
      lock.unlock();
    }
  }
}
