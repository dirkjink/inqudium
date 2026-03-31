package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.*;
import eu.inqudium.core.bulkhead.event.BulkheadCodelRejectedTraceEvent;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * An imperative state machine utilizing a simplified Controlled Delay (CoDel) mechanism
 * for queue-based congestion management in a thread-blocking bulkhead.
 *
 * <h2>Algorithm Overview</h2>
 * <p>CoDel (pronounced "coddle") is a queue management algorithm originally designed by
 * Kathleen Nichols and Van Jacobson for network router buffers (RFC 8289). Unlike
 * {@link AimdLimitAlgorithm} and {@link VegasLimitAlgorithm}, which dynamically adjust
 * the concurrency <em>limit</em>, CoDel operates on the <em>queue</em> itself: it
 * monitors how long requests wait in the bulkhead queue (the "sojourn time") and rejects
 * requests that have been waiting too long during sustained congestion.
 *
 * <p>The core insight is that <b>a long queue is not a problem — a persistently long
 * queue is</b>. A temporary burst of requests that causes a brief spike in wait times
 * is normal and healthy (the queue is absorbing a burst). But if wait times remain above
 * a target delay for an extended interval, the queue is no longer absorbing a burst — it
 * is chronically backed up, and adding more requests only makes things worse.
 *
 * <h2>How CoDel Works (Simplified)</h2>
 * <ol>
 *   <li><b>Measure:</b> When a thread exits the wait loop (a permit becomes available),
 *       the algorithm measures its <em>sojourn time</em> — how long it spent waiting in
 *       the condition queue.</li>
 *   <li><b>Track:</b> If the sojourn time exceeds the configured {@code targetDelay},
 *       the algorithm records the timestamp. This starts the "congestion stopwatch".</li>
 *   <li><b>Detect:</b> If the congestion stopwatch has been running for longer than the
 *       configured {@code interval} — meaning every request in the last interval has
 *       waited longer than the target — CoDel concludes that the system is experiencing
 *       sustained congestion, not just a transient burst.</li>
 *   <li><b>Drop:</b> The request is rejected (not executed), and the next waiting thread
 *       is signaled. That thread will also measure its sojourn time, and if congestion
 *       persists, it too will be rejected — creating a <em>chain-drain</em> that rapidly
 *       clears the queue.</li>
 *   <li><b>Recover:</b> When a request arrives with sojourn time below the target, the
 *       congestion stopwatch is reset. The system is considered healthy again.</li>
 * </ol>
 *
 * <h2>CoDel vs Adaptive Limits (AIMD/Vegas)</h2>
 * <table>
 *   <tr><th>Aspect</th><th>CoDel</th><th>AIMD/Vegas</th></tr>
 *   <tr><td>What it adjusts</td><td>Accepts/rejects individual requests</td><td>Adjusts the concurrency limit</td></tr>
 *   <tr><td>Limit</td><td>Static ({@code maxConcurrentCalls})</td><td>Dynamic (algorithm-driven)</td></tr>
 *   <tr><td>Signal</td><td>Queue sojourn time</td><td>Error rate (AIMD) / RTT gradient (Vegas)</td></tr>
 *   <tr><td>Burst tolerance</td><td>High — short bursts pass through</td><td>Varies — depends on algorithm sensitivity</td></tr>
 *   <tr><td>Best for</td><td>Systems where queue depth is the bottleneck</td><td>Systems where downstream capacity varies</td></tr>
 *   <tr><td>Mutual exclusivity</td><td colspan="2">CoDel and adaptive limits cannot be combined
 *       ({@link BulkheadConfig.Builder#build()} enforces this)</td></tr>
 * </table>
 *
 * <h2>Key Design Decisions</h2>
 *
 * <h3>Simplified CoDel (vs full RFC 8289)</h3>
 * <p>The full CoDel algorithm uses a self-regulating drop schedule that decreases the
 * interval between successive drops as congestion persists (inversely proportional to the
 * square root of the number of drops since entering the dropping state). This implementation
 * uses a simplified model: once sustained congestion is detected (sojourn time above target
 * for longer than one interval), requests are rejected until either (a) a request arrives
 * with sojourn time below target (congestion has cleared) or (b) the system becomes idle
 * (all calls complete and no threads are waiting).
 *
 * <h3>Chain-Drain Mechanism</h3>
 * <p>When a request is CoDel-rejected, it calls {@code permitAvailable.signal()} before
 * returning {@code false}. This wakes the next waiting thread, which will also measure its
 * sojourn time and — if congestion persists — also reject itself and signal the next thread.
 * This creates a rapid cascade that drains the entire queue in one sweep, which is the
 * desired behavior: under sustained congestion, the queue should be cleared quickly rather
 * than dripping out one rejected request at a time.
 *
 * <h3>Idle Recovery (FIX #3)</h3>
 * <p>After a congestion episode ends and all threads have drained, the CoDel state
 * ({@code firstAboveTargetNanos}) must be reset. Without this, a stale timestamp from a
 * previous congestion episode would persist through an idle period. When new traffic
 * arrives later, the enormous time delta ({@code now - firstAboveTargetNanos}) would
 * instantly trigger drops — even if the system has been idle and healthy for minutes.
 *
 * <p>The idle detection uses an explicit {@code acquireThreads} counter rather than
 * {@link ReentrantLock#hasWaiters(Condition)}, because {@code hasWaiters} only counts
 * threads that are already blocked in {@code await()} — missing threads that are between
 * lock acquisition and the first {@code await()}, or between waking from {@code await()}
 * and returning from {@code tryAcquire()}. See the field documentation for details.
 *
 * <h3>Sojourn Time Precision (FIX #3a)</h3>
 * <p>CoDel's correctness depends on accurately measuring <em>queue wait time</em>, not
 * total wait time. This implementation uses two separate timestamps to distinguish them:
 * <ul>
 *   <li>{@code waitStartNanos}: Captured before lock acquisition. Includes lock contention
 *       time. Used for user-facing telemetry (trace events, metrics).</li>
 *   <li>{@code codelEnqueueNanos}: Captured after lock acquisition. Excludes lock contention.
 *       Used exclusively for the CoDel sojourn time decision.</li>
 * </ul>
 * Without this separation, threads fighting for a hot {@link ReentrantLock} under high
 * contention would be falsely rejected by CoDel due to time spent on Java monitor
 * acquisition rather than actual queue congestion.
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state ({@code firstAboveTargetNanos}, {@code activeCalls},
 * {@code acquireThreads}) is protected by the {@link ReentrantLock}. Unlike
 * {@link AimdLimitAlgorithm} and {@link VegasLimitAlgorithm}, which use lock-free
 * CAS loops, this class requires explicit locking because it must coordinate with the
 * {@link Condition} variable for thread parking and signaling — operations that are
 * inherently lock-bound.
 *
 * <p>The lock is always acquired via {@link ReentrantLock#lockInterruptibly()} in
 * {@code tryAcquire()} to respect thread interruption (e.g., executor shutdown), and
 * via {@link ReentrantLock#lock()} in {@code releasePermitInternal()} where interruption
 * is not appropriate (permits must always be released).
 *
 * @see AimdLimitAlgorithm
 * @see VegasLimitAlgorithm
 * @see AdaptiveImperativeStateMachine
 * @see ImperativeBulkheadStateMachine
 * @since 0.2.0
 */
public final class CoDelImperativeStateMachine
    extends AbstractBulkheadStateMachine implements BlockingBulkheadStateMachine {

  // ──────────────────────────────────────────────────────────────────────────
  // Configuration Fields (immutable after construction)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The maximum acceptable sojourn time (queue wait duration) in nanoseconds.
   *
   * <p>If a request's sojourn time exceeds this value, it is a signal that the queue is
   * congested. However, a single above-target measurement does not trigger rejection —
   * CoDel requires sustained congestion (see {@link #intervalNanos}).
   *
   * <p>Choosing the right target delay is workload-dependent:
   * <ul>
   *   <li><b>Low-latency APIs (< 10ms p99):</b> targetDelay = 5ms.
   *       Tight target ensures requests are rejected before the caller's own timeout fires.</li>
   *   <li><b>Standard web services (50–200ms p99):</b> targetDelay = 20–50ms.
   *       Allows reasonable queuing during bursts without excessive wait times.</li>
   *   <li><b>Batch/background processing:</b> targetDelay = 500ms–1s.
   *       Higher tolerance for queuing since latency is less critical.</li>
   * </ul>
   *
   * <p>The target should be <em>significantly below</em> the caller's timeout. If the
   * caller has a 5-second timeout and the target delay is 4 seconds, CoDel will only
   * reject requests that are about to timeout anyway — providing no protection.
   * A good rule of thumb: target = caller_timeout / 10.
   *
   * <p>Converted from the {@link Duration} parameter during construction. Stored as
   * nanoseconds for zero-allocation comparisons on the hot path.
   */
  private final long targetDelayNanos;

  /**
   * The sustained-congestion detection window in nanoseconds.
   *
   * <p>CoDel only enters the dropping state when the sojourn time has been continuously
   * above {@link #targetDelayNanos} for at least this duration. This is the key mechanism
   * that distinguishes transient bursts from sustained congestion:
   *
   * <ul>
   *   <li><b>Transient burst:</b> A sudden spike of 50 requests arrives simultaneously.
   *       The first few requests wait above the target delay. But as they are processed
   *       and permits are released, the queue drains naturally. Within one interval, the
   *       sojourn time drops below target, and CoDel resets — no requests are rejected.
   *       The burst was absorbed successfully.</li>
   *   <li><b>Sustained congestion:</b> Requests continuously arrive faster than they can
   *       be processed. The queue grows, and every request that dequeues has waited longer
   *       than the target. After one full interval, CoDel concludes the congestion is
   *       structural (not burst-related) and begins rejecting requests to shed load.</li>
   * </ul>
   *
   * <p>The interval should be long enough to ride out typical burst durations but short
   * enough to react before the queue grows unboundedly. Common values:
   * <ul>
   *   <li><b>100ms:</b> Aggressive — detects congestion quickly but may false-positive
   *       on bursts lasting more than 100ms.</li>
   *   <li><b>500ms:</b> Balanced — tolerates half-second bursts, catches sustained overload.</li>
   *   <li><b>1–5s:</b> Conservative — very tolerant of bursts, slow to react to overload.</li>
   * </ul>
   *
   * <p>RFC 8289 suggests an interval of 100ms for network routers. Application-level
   * bulkheads typically need longer intervals because "bursts" in service-to-service
   * communication last longer than packet-level bursts.
   *
   * <p>Converted from the {@link Duration} parameter during construction.
   */
  private final long intervalNanos;

  /**
   * The injectable nano-time source for all timing measurements.
   *
   * <p>Defaults to {@code System::nanoTime} in production. Replaced with a controllable
   * {@link java.util.concurrent.atomic.AtomicLong} in tests to enable deterministic
   * verification of CoDel timing behavior without real-time waits.
   *
   * <p>Used for two distinct measurements:
   * <ul>
   *   <li>{@code waitStartNanos}: Total wait time (pre-lock, includes lock contention).
   *       Fed to telemetry methods in the base class.</li>
   *   <li>{@code codelEnqueueNanos}: Pure queue wait time (post-lock, excludes lock
   *       contention). Used exclusively for the CoDel sojourn time evaluation.</li>
   * </ul>
   */
  private final LongSupplier nanoTimeSource;

  // ──────────────────────────────────────────────────────────────────────────
  // Concurrency Primitives
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The main lock protecting all mutable state in this class.
   *
   * <p>A {@link ReentrantLock} is used instead of {@code synchronized} for two reasons:
   * <ol>
   *   <li><b>Condition variables:</b> {@link ReentrantLock#newCondition()} provides the
   *       {@link #permitAvailable} condition, enabling precise thread parking and signaling
   *       that is not possible with {@code Object.wait()/notify()}.</li>
   *   <li><b>Interruptible acquisition:</b> {@link ReentrantLock#lockInterruptibly()} allows
   *       threads waiting for the lock to be interrupted (e.g., during executor shutdown),
   *       which is not possible with {@code synchronized} blocks.</li>
   * </ol>
   *
   * <p>The lock is <em>not</em> created with fairness ({@code new ReentrantLock(false)},
   * the default) because fairness imposes significant throughput overhead. The condition
   * variable provides sufficient ordering: threads waiting on {@code permitAvailable} are
   * signaled in FIFO order by the JVM's condition queue implementation.
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * The condition variable that threads wait on when no permits are available.
   *
   * <p>Threads enter {@link Condition#awaitNanos(long)} when {@code activeCalls >= maxConcurrentCalls}
   * and are woken by {@link Condition#signal()} when a permit is released (in
   * {@link #releasePermitInternal()}) or when a CoDel-rejected thread passes the baton
   * (in the CoDel drop block of {@link #tryAcquire(String, Duration)}).
   *
   * <p>Only {@link Condition#signal()} (not {@code signalAll()}) is used throughout,
   * because exactly one permit is freed or one CoDel drop occurs at a time. Waking all
   * waiting threads would cause a thundering herd where all but one immediately go back
   * to sleep.
   */
  private final Condition permitAvailable = lock.newCondition();

  // ──────────────────────────────────────────────────────────────────────────
  // Mutable State (all protected by the lock)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Tracks the timestamp (in nanoseconds from {@link #nanoTimeSource}) when the system
   * first observed a sojourn time above {@link #targetDelayNanos}.
   *
   * <p>This is the "congestion stopwatch" described in the algorithm overview:
   * <ul>
   *   <li><b>Value = 0:</b> The system is currently healthy. Either no request has
   *       exceeded the target delay recently, or the last one that did was followed by
   *       a request with acceptable sojourn time (which resets the stopwatch).</li>
   *   <li><b>Value > 0:</b> The congestion stopwatch is running. The value records when
   *       the first above-target sojourn time was observed. If the current time minus
   *       this value exceeds {@link #intervalNanos}, CoDel enters the dropping state.</li>
   * </ul>
   *
   * <p>The stopwatch has three transition triggers:
   * <ol>
   *   <li><b>Start:</b> A request's sojourn time exceeds the target while the stopwatch
   *       is at 0. The current time is recorded.</li>
   *   <li><b>Reset (healthy):</b> A request's sojourn time is below the target. The
   *       stopwatch returns to 0 — the congestion has cleared.</li>
   *   <li><b>Reset (idle):</b> The system becomes truly idle ({@code activeCalls == 0}
   *       AND {@code acquireThreads == 0}). Stale timestamps from a concluded congestion
   *       episode are cleared. (FIX #3)</li>
   * </ol>
   *
   * <p>Protected by {@link #lock}. An {@code AtomicLong} is unnecessary because all reads
   * and writes occur while the lock is held.
   */
  private long firstAboveTargetNanos = 0L;

  /**
   * The number of permits currently held by in-flight calls.
   *
   * <p>Incremented when a permit is granted (step 3 of {@link #tryAcquire}), decremented
   * when a permit is released ({@link #releasePermitInternal()}). The value is always in
   * the range [0, {@link #maxConcurrentCalls}].
   *
   * <p>Note that CoDel-rejected requests never increment this counter — they are rejected
   * before the permit is granted. This means {@code activeCalls} can be 0 during an active
   * chain-drain (all permits are free, but threads are being rejected for excessive sojourn
   * time). The {@link #acquireThreads} counter complements this field to detect true idleness.
   *
   * <p>Protected by {@link #lock}.
   */
  private int activeCalls = 0;

  /**
   * The number of threads currently inside the {@link #tryAcquire} critical section.
   *
   * <p>This counter exists solely to support accurate idle detection for CoDel state reset
   * (FIX #3). It tracks every thread that has acquired the lock and entered the tryAcquire
   * flow, regardless of where that thread is within the flow:
   * <ul>
   *   <li>Waiting on {@code permitAvailable.awaitNanos()}</li>
   *   <li>Evaluating the CoDel sojourn time check</li>
   *   <li>Being CoDel-rejected (between the drop decision and the return statement)</li>
   *   <li>Successfully acquiring a permit (between incrementing {@code activeCalls}
   *       and returning)</li>
   * </ul>
   *
   * <h3>Why not use {@code lock.hasWaiters(permitAvailable)}?</h3>
   * <p>The initial FIX #3 implementation used {@code hasWaiters}, but this was unreliable.
   * {@code hasWaiters} only counts threads that are <em>currently blocked</em> inside
   * {@code Condition.await()}. It misses threads in three critical windows:
   * <ol>
   *   <li><b>Pre-await:</b> A thread has acquired the lock but hasn't called
   *       {@code await()} yet (e.g., it's executing the {@code while} condition check).</li>
   *   <li><b>Post-await:</b> A thread has been signaled and woken from {@code await()} but
   *       hasn't returned from {@code tryAcquire()} yet (e.g., it's executing the CoDel
   *       evaluation).</li>
   *   <li><b>Lock-contention:</b> A thread is blocked on {@code lock.lockInterruptibly()}
   *       and hasn't entered the critical section at all. {@code hasWaiters} only examines
   *       the condition queue, not the lock's entry queue.</li>
   * </ol>
   *
   * <p>In a chain-drain scenario, window (2) is particularly dangerous: a CoDel-rejected
   * thread decrements {@code acquireThreads} and signals the next thread, but the next
   * thread hasn't woken yet. If {@code releasePermitInternal()} runs at this exact moment
   * and checks {@code hasWaiters}, it may see zero waiters (the signaled thread has left
   * the condition queue but hasn't re-acquired the lock yet) and incorrectly reset the
   * CoDel state — aborting the chain-drain.
   *
   * <h3>Lifecycle</h3>
   * <ul>
   *   <li><b>Increment:</b> Immediately after {@code lock.lockInterruptibly()} succeeds
   *       in {@code tryAcquire()}, before any other logic. This is the earliest possible
   *       point where the thread is visible to concurrent state checks.</li>
   *   <li><b>Decrement:</b> In a {@code finally} block just before {@code lock.unlock()},
   *       after all tryAcquire logic has completed (success, timeout, CoDel drop, or
   *       interruption). This ensures the counter is decremented on every exit path.</li>
   * </ul>
   *
   * <p>Protected by {@link #lock}.
   */
  private int acquireThreads = 0;

  // ──────────────────────────────────────────────────────────────────────────
  // Constructor
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new CoDel-managed imperative state machine.
   *
   * <p>This constructor is package-private and called exclusively by
   * {@link ImperativeBulkheadFactory#create(String, BulkheadConfig)} when the config has
   * CoDel enabled ({@link BulkheadConfig#isCodelEnabled()} returns {@code true}).
   *
   * <p>The factory guarantees mutual exclusivity between CoDel and adaptive limit algorithms
   * — this is enforced at the config level by {@link BulkheadConfig.Builder#build()}.
   *
   * @param name        the unique bulkhead instance name, used for logging and event publishing
   * @param config      the bulkhead configuration providing maxConcurrentCalls, clock, and
   *                    nano-time source
   * @param targetDelay the maximum acceptable queue wait time before CoDel considers a request
   *                    delayed; must be positive (enforced by {@link BulkheadConfig.Builder#codel})
   * @param interval    the sustained-congestion detection window; must be positive
   *                    (enforced by {@link BulkheadConfig.Builder#codel})
   */
  public CoDelImperativeStateMachine(String name, BulkheadConfig config, Duration targetDelay, Duration interval) {
    super(name, config);
    this.targetDelayNanos = targetDelay.toNanos();
    this.intervalNanos = interval.toNanos();
    this.nanoTimeSource = config.getNanoTimeSource();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // BlockingBulkheadStateMachine Interface
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Attempts to acquire a bulkhead permit, potentially blocking the calling thread up to
   * the specified timeout.
   *
   * <p>This method implements a three-phase protocol:
   * <ol>
   *   <li><b>Wait for capacity:</b> If all permits are in use, the thread parks on the
   *       {@link #permitAvailable} condition until either a permit is released, the timeout
   *       expires, or the thread is interrupted.</li>
   *   <li><b>CoDel evaluation:</b> Once capacity is available, the thread's sojourn time
   *       (time spent waiting) is measured and evaluated against the CoDel parameters.
   *       If sustained congestion is detected, the request is rejected even though a permit
   *       is technically available.</li>
   *   <li><b>Permit grant:</b> If CoDel approves, the permit is acquired and the thread
   *       proceeds to execute the business logic.</li>
   * </ol>
   *
   * <h3>The Pass-the-Baton Pattern</h3>
   * <p>Throughout this method, every exit path that does NOT acquire a permit calls
   * {@code permitAvailable.signal()} before returning. This is the "pass the baton"
   * pattern, critical for preventing lost wakeups:
   *
   * <p>Consider: Thread A holds the last permit. Thread B and C are waiting. A releases
   * the permit and signals B. B wakes up, evaluates CoDel, and decides to reject itself.
   * If B simply returns {@code false} without signaling, C remains parked forever — even
   * though a permit is now available. By signaling before returning, B "passes the baton"
   * to C, which can then attempt its own CoDel evaluation.
   *
   * <p>The same applies to timeout and interruption exits: the thread's timeout or
   * interruption consumed the signal that woke it, so it must re-signal to ensure the
   * next waiter isn't stranded.
   *
   * @param callId  the unique call identifier for tracing and event correlation
   * @param timeout the maximum duration to wait for a permit. {@link Duration#ZERO} means
   *                non-blocking: if no permit is immediately available, the method returns
   *                {@code false} without parking the thread.
   * @return {@code true} if the permit was acquired, {@code false} if the bulkhead is full
   *         (timeout expired) or the request was CoDel-rejected (sustained congestion)
   * @throws InterruptedException if the thread is interrupted while waiting, wrapped in
   *         {@link InqBulkheadInterruptedException} with the thread's interrupt flag restored
   */
  @Override
  public boolean tryAcquire(String callId, Duration timeout) throws InterruptedException {
    long remainingNanos = timeout.toNanos();

    // ── Timestamp 1: Total wait start (pre-lock) ──
    // Captured BEFORE lock acquisition. This timestamp includes time spent waiting
    // for the Java monitor (lock contention) AND time spent in the condition queue.
    // It represents the total user-visible wait time and is used for telemetry
    // methods in the base class (publishWaitTrace, handleAcquireFailure/Success).
    long waitStartNanos = nanoTimeSource.getAsLong();

    lock.lockInterruptibly();
    try {
      // Register this thread in the acquire-flow counter immediately after lock
      // acquisition. This is the earliest possible point where the thread becomes
      // visible to concurrent idle checks. See the acquireThreads field documentation
      // for why this counter exists and why lock.hasWaiters() was insufficient.
      acquireThreads++;

      // ── Timestamp 2: CoDel enqueue time (post-lock) ──
      // FIX #3a: Captured AFTER lock acquisition. This timestamp excludes lock
      // contention time and measures only the pure queue sojourn time — the duration
      // the thread spends waiting on the permitAvailable condition. This is the
      // correct input for the CoDel algorithm: CoDel should evaluate queue congestion,
      // not Java monitor contention.
      //
      // Without this separation, a system under high thread contention (many threads
      // competing for the lock) would see inflated sojourn times even when the actual
      // permit queue is short. CoDel would then incorrectly reject requests that only
      // waited a long time due to lock overhead, not actual congestion.
      long codelEnqueueNanos = nanoTimeSource.getAsLong();

      try {
        // ── Phase 1: Wait for a permit to become available ──
        //
        // Standard condition-wait loop. The thread parks on permitAvailable until
        // either a permit is freed (signaled by releasePermitInternal or a CoDel
        // drop's pass-the-baton signal) or the timeout expires.
        //
        // The loop structure handles spurious wakeups: after awaitNanos returns,
        // the condition (activeCalls >= maxConcurrentCalls) is re-checked. If the
        // wakeup was spurious (condition still true), the thread goes back to sleep
        // with the remaining time.
        while (activeCalls >= maxConcurrentCalls) {
          if (remainingNanos <= 0L) {
            // Timeout expired. No permit was acquired.
            //
            // Pass the baton: a signal may have woken this thread at the exact moment
            // its timeout expired. Without re-signaling, that signal would be consumed
            // (wasted) and the next waiter would never wake up. By signaling, we ensure
            // the next thread in the condition queue gets a chance to try.
            permitAvailable.signal();
            handleAcquireFailure(callId, waitStartNanos);
            return false;
          }
          // Park the thread. Returns the remaining nanos (positive if signaled early,
          // zero or negative if timed out). The loop will re-evaluate on the next iteration.
          remainingNanos = permitAvailable.awaitNanos(remainingNanos);
        }

        // ── Phase 2: CoDel Sojourn Time Evaluation ──
        //
        // At this point, a permit IS available (activeCalls < maxConcurrentCalls).
        // But CoDel may still reject the request if the queue has been congested for
        // too long. This is the fundamental difference between CoDel and a simple
        // semaphore: a semaphore grants the permit as soon as capacity exists, while
        // CoDel additionally evaluates whether the wait was acceptable.
        long now = nanoTimeSource.getAsLong();

        // FIX #3a: Sojourn time is calculated from the post-lock timestamp, measuring
        // only the pure condition-wait duration. The pre-lock timestamp (waitStartNanos)
        // is reserved for telemetry.
        long sojournNanos = now - codelEnqueueNanos;

        if (sojournNanos > targetDelayNanos) {
          // The request waited longer than the target delay. This is a signal of
          // potential congestion, but NOT sufficient to reject on its own — CoDel
          // requires sustained congestion over a full interval.

          if (firstAboveTargetNanos == 0L) {
            // ── Start the congestion stopwatch ──
            // This is the first above-target sojourn time since the system was last
            // healthy. Record the current time as the start of the congestion window.
            // The request itself is NOT rejected — it's the "canary" that starts the
            // detection process. It will be granted a permit normally.
            firstAboveTargetNanos = now;

          } else if (now - firstAboveTargetNanos > intervalNanos) {
            // ── Sustained congestion confirmed — REJECT (CoDel drop) ──
            //
            // The congestion stopwatch has been running for longer than one full interval.
            // Every request that dequeued during this window had a sojourn time above
            // the target. This is structural congestion, not a transient burst.
            //
            // The request is rejected WITHOUT acquiring a permit. This is deliberate:
            // the goal is to shed queued work, not to acquire and immediately release.
            //
            // Pass the baton: signal the next waiting thread so it can perform its own
            // CoDel evaluation. If congestion persists, it too will be rejected, creating
            // the chain-drain that rapidly clears the queue. If congestion has cleared
            // (e.g., because several permits were released while this thread was being
            // evaluated), the next thread's sojourn time may be below target, and it
            // will be granted normally — ending the chain-drain naturally.
            permitAvailable.signal();

            // Publish a trace event for observability. This allows operators to
            // distinguish CoDel rejections (sojourn-time-based, indicating sustained
            // queue congestion) from capacity rejections (timeout-based, indicating
            // all permits are held).
            eventPublisher.publishTrace(() -> new BulkheadCodelRejectedTraceEvent(
                callId,
                name,
                sojournNanos,
                targetDelayNanos,
                config.getClock().instant()
            ));

            handleAcquireFailure(callId, waitStartNanos);
            return false;
          }
          // else: The congestion stopwatch is running but hasn't exceeded the interval yet.
          // The request is in the "observation window" — congestion is suspected but not
          // confirmed. The request proceeds normally. If the next request also has above-target
          // sojourn time and the interval has elapsed, it will trigger the drop.

        } else {
          // ── Sojourn time is acceptable — system is healthy ──
          //
          // The request waited less than the target delay. This could mean:
          // (a) No congestion exists — the queue is short and draining quickly.
          // (b) A burst has passed — the queue has cleared since the stopwatch started.
          //
          // In either case, reset the congestion stopwatch. The next above-target
          // observation will start a fresh interval, requiring a full new duration of
          // sustained congestion before any drops occur.
          firstAboveTargetNanos = 0L;
        }

        // ── Phase 3: Grant the permit ──
        //
        // CoDel has approved the request (or the sojourn time was below target).
        // Increment the active call count and publish the acquire telemetry.
        //
        // If handleAcquireSuccess() throws (e.g., the event publisher crashes), the
        // base class's rollback mechanism will call rollbackPermit() to undo the
        // activeCalls increment, preventing permit leaks.
        activeCalls++;
        return handleAcquireSuccess(callId, waitStartNanos);

      } catch (InterruptedException e) {
        // The thread was interrupted while waiting on the condition.
        //
        // Pass the baton: the interruption consumed the signal that woke this thread.
        // Without re-signaling, the next waiter would be stranded. signal() ensures
        // the chain continues.
        permitAvailable.signal();

        // Restore the interrupt flag. The JVM clears it when throwing InterruptedException
        // from await(). Restoring it allows callers to observe the interrupt via
        // Thread.isInterrupted() after catching InqBulkheadInterruptedException.
        Thread.currentThread().interrupt();

        handleAcquireFailure(callId, waitStartNanos);
        throw new InqBulkheadInterruptedException(callId, name, getConcurrentCalls(), maxConcurrentCalls);

      } finally {
        // Decrement the acquire-flow counter on every exit path (success, timeout,
        // CoDel drop, interruption). This is the counterpart to the increment at
        // the top of the outer try block.
        acquireThreads--;

        // ── FIX #3: Idle detection and CoDel state reset ──
        //
        // Check if the system is truly idle: no permits are held AND no threads are
        // anywhere inside the tryAcquire flow. When both conditions are met, the
        // congestion episode has fully concluded and any stale timestamp in
        // firstAboveTargetNanos must be cleared.
        //
        // This check fires at the exact right moment during a chain-drain:
        //
        //   1. Thread A holds a permit (activeCalls=1). Threads B and C are waiting.
        //   2. A releases → signal wakes B → B evaluates CoDel → B is rejected
        //      → B signals C → B reaches this finally → acquireThreads decrements to 1.
        //      activeCalls=0 but acquireThreads=1 → NO RESET (C is still in the flow).
        //   3. C wakes → C evaluates CoDel → C is rejected → C reaches this finally
        //      → acquireThreads decrements to 0. activeCalls=0 AND acquireThreads=0
        //      → RESET. The chain-drain is complete, the system is truly idle.
        //
        // Without this check, firstAboveTargetNanos would persist from the congestion
        // episode through an idle period. When new traffic arrives minutes later, the
        // delta (now - firstAboveTargetNanos) would be enormous, and the very first
        // request with above-target sojourn time would be instantly dropped — even
        // though the system was idle and healthy the entire time.
        if (activeCalls == 0 && acquireThreads == 0) {
          firstAboveTargetNanos = 0L;
        }
      }
    } finally {
      lock.unlock();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // AbstractBulkheadStateMachine Hooks
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Releases a previously acquired permit and wakes one waiting thread.
   *
   * <p>This method is called from {@link AbstractBulkheadStateMachine#releaseAndReport}
   * inside a try-finally block, guaranteeing execution even if the adaptive algorithm hook
   * throws an exception.
   *
   * <p>The method uses {@link ReentrantLock#lock()} (non-interruptible) rather than
   * {@code lockInterruptibly()} because permit release is a critical operation that must
   * complete regardless of the thread's interrupt status. Failing to release a permit
   * would permanently reduce the bulkhead's capacity.
   *
   * <h3>Idle Detection (FIX #3)</h3>
   * <p>After decrementing {@code activeCalls}, this method also checks for the idle
   * condition ({@code activeCalls == 0 && acquireThreads == 0}). This handles the
   * natural drain scenario where the last in-flight call completes and no threads are
   * waiting — as opposed to the chain-drain scenario handled in the {@code finally}
   * block of {@link #tryAcquire}. Both paths need the check to ensure comprehensive
   * idle detection.
   *
   * <h3>Over-Release Guard</h3>
   * <p>The {@code activeCalls > 0} check prevents semaphore inflation from double-release
   * bugs. Without this guard, releasing a permit that was never acquired (or releasing
   * twice) would decrement {@code activeCalls} below 0, effectively creating phantom
   * capacity that exceeds {@code maxConcurrentCalls}.
   */
  @Override
  protected void releasePermitInternal() {
    lock.lock();
    try {
      if (activeCalls > 0) {
        activeCalls--;

        // FIX #3: Check for idle state on permit release.
        //
        // This catches the scenario where the system drains naturally (without a
        // CoDel chain-drain): the last active call completes, no threads are waiting,
        // and any stale congestion timestamp should be cleared.
        //
        // During a chain-drain, acquireThreads > 0 (threads are still in the
        // tryAcquire flow being rejected), so this condition won't fire prematurely.
        // The chain-drain's idle reset happens in tryAcquire's finally block instead.
        if (activeCalls == 0 && acquireThreads == 0) {
          firstAboveTargetNanos = 0L;
        }

        // Wake one waiting thread. signal() (not signalAll) is used because exactly
        // one permit was freed — waking multiple threads would cause all but one to
        // immediately re-park after finding the condition still true.
        permitAvailable.signal();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Rolls back a permit that was acquired but whose acquire-event publication failed.
   *
   * <p>This is called by {@link AbstractBulkheadStateMachine#handleAcquireSuccess} when
   * the event publisher crashes after the permit was already granted. The rollback
   * ensures the permit is returned to the pool so the bulkhead's capacity is not
   * permanently reduced by a telemetry failure.
   *
   * <p>Delegates to {@link #releasePermitInternal()} because the rollback and release
   * logic are identical: decrement the counter and signal the next waiter.
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
   * <p>Calculated as {@code maxConcurrentCalls - activeCalls}, clamped to a minimum of 0.
   * The clamping handles the theoretical edge case where {@code activeCalls} transiently
   * exceeds {@code maxConcurrentCalls} due to a bug — the result should never be negative.
   *
   * <p>This value is a snapshot and may be stale by the time the caller uses it. It is
   * intended for monitoring and diagnostics, not for making acquire/release decisions.
   *
   * <p>Acquires the lock for a consistent read of {@code activeCalls}.
   *
   * @return the number of available permits, always >= 0
   */
  @Override
  public int getAvailablePermits() {
    lock.lock();
    try {
      return Math.max(0, maxConcurrentCalls - activeCalls);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the number of calls currently in flight (holding a permit).
   *
   * <p>This is the raw {@code activeCalls} counter. It does NOT include threads that are
   * waiting for a permit or being CoDel-evaluated — only those that have successfully
   * acquired a permit and are executing the business logic.
   *
   * <p>This value is a snapshot and may be stale by the time the caller uses it. It is
   * intended for monitoring and diagnostics.
   *
   * <p>Acquires the lock for a consistent read.
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
