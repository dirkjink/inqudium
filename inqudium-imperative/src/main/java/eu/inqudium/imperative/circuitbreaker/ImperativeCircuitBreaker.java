package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.*;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative circuit breaker implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom) or traditional
 * platform threads. Uses lock-free CAS operations for high-throughput fast-paths
 * and a {@link ReentrantLock} exclusively to serialize state transitions.
 *
 * <h2>Concurrency model</h2>
 *
 * <p>The circuit breaker's entire mutable state is captured in an immutable
 * {@link CircuitBreakerSnapshot} held behind a single {@link AtomicReference}.
 * Normal operations (permission checks, outcome recording) proceed via lock-free
 * compare-and-set (CAS). Only when a CAS would cause a <em>state transition</em>
 * (e.g., CLOSED → OPEN) does the code acquire the {@link #transitionLock} to
 * serialize the transition and ensure exactly-once listener notification.</p>
 *
 * <h2>Outcome recording contract</h2>
 *
 * <p>Every call that passes permission check <em>must</em> record exactly one
 * outcome: {@link #recordSuccess()}, {@link #handleThrowable(Throwable)}, or
 * {@link #recordIgnored()}. The {@code outcomeRecorded} flag in the sync
 * execution methods guards against edge cases where the finally block would
 * otherwise double-count.</p>
 *
 * <p>All configuration values are extracted into final fields in the constructor
 * to avoid repeated config lookups on the hot path.</p>
 */
public class ImperativeCircuitBreaker<A, R> implements CircuitBreaker<A, R> {

    private static final Logger LOG = Logger.getLogger(ImperativeCircuitBreaker.class.getName());

    /**
     * Safety valve for the CAS retry loops. Under extreme contention, many threads
     * may repeatedly fail their CAS attempts. After this many consecutive failures,
     * the thread yields its time slice to let other threads make progress, breaking
     * potential live-lock scenarios.
     */
    private static final int MAX_CAS_RETRIES_BEFORE_YIELD = 64;

    /**
     * The single source of truth for the circuit breaker's state. Every read sees a
     * consistent, immutable snapshot. Mutations happen by computing a new snapshot
     * from the current one and swapping it in via CAS.
     */
    private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;

    /** Monotonic nano-precision time source, injectable for deterministic testing. */
    private final InqNanoTimeSource timeSource;

    /**
     * Listeners notified after a state transition completes. Uses CopyOnWriteArrayList
     * because transitions are rare (write-rare, read-often pattern) and listener
     * notification must not hold the transition lock.
     */
    private final List<Consumer<StateTransition>> transitionListeners;

    /** Publisher for diagnostic events (ADR-003 observability). */
    private final InqEventPublisher eventPublisher;

    // ── Extracted config values (final fields for hot-path performance) ──

    /** Human-readable name, used in exceptions, events, and log messages. */
    private final String name;

    /**
     * How long the circuit breaker stays OPEN before transitioning to HALF_OPEN,
     * measured in nanoseconds. Compared against the monotonic time source.
     */
    private final long waitDurationNanos;

    /**
     * Maximum number of concurrent trial calls allowed in the HALF_OPEN state.
     * Once this limit is reached, further calls are rejected until a trial completes.
     */
    private final int permittedCallsInHalfOpen;

    /**
     * Number of consecutive successes required in HALF_OPEN to transition back to CLOSED.
     * A single failure resets the counter and transitions back to OPEN.
     */
    private final int successThresholdInHalfOpen;

    /**
     * Determines whether a given exception should be counted as a failure. Exceptions
     * that do not match this predicate are recorded as "ignored" — they do not affect
     * the failure rate and do not trigger state transitions.
     */
    private final Predicate<Throwable> recordFailurePredicate;

    /**
     * Factory for creating fresh {@link FailureMetrics} instances. Called during
     * construction and on {@link #reset()} to initialize the sliding window.
     * Accepts the current nano timestamp to anchor the window's time base.
     */
    private final LongFunction<FailureMetrics> metricsFactory;

    /**
     * Lock used exclusively to serialize state transitions. This prevents two threads
     * from simultaneously transitioning the circuit breaker (e.g., both seeing HALF_OPEN
     * and both trying to move to CLOSED). NOT used for normal permission checks or
     * outcome recording — those remain lock-free via CAS.
     */
    private final ReentrantLock transitionLock = new ReentrantLock();

    /**
     * Creates a new circuit breaker with the given configuration.
     *
     * <p>All config values are extracted into final fields immediately to avoid
     * repeated lookups on the hot path. The initial state is always CLOSED with
     * fresh metrics.</p>
     *
     * @param config                 the circuit breaker configuration (name, timings, thresholds)
     * @param metricsFactory         factory to create sliding-window failure metrics, parameterized
     *                               by the current nano timestamp
     * @param recordFailurePredicate predicate to classify exceptions as failures vs. ignored
     */
    public ImperativeCircuitBreaker(InqCircuitBreakerConfig config,
                                    LongFunction<FailureMetrics> metricsFactory,
                                    Predicate<Throwable> recordFailurePredicate) {
        Objects.requireNonNull(config, "config must not be null");
        this.timeSource = config.general().nanoTimesource();
        this.eventPublisher = config.eventPublisher();

        this.name = config.name();
        this.waitDurationNanos = config.waitDurationNanos();
        this.permittedCallsInHalfOpen = config.permittedCallsInHalfOpen();
        this.successThresholdInHalfOpen = config.successThresholdInHalfOpen();
        this.recordFailurePredicate = recordFailurePredicate;
        this.metricsFactory = metricsFactory;

        // Bootstrap the initial snapshot: CLOSED state, empty metrics, anchored at "now"
        long nowNanos = timeSource.now();
        FailureMetrics initialMetrics = metricsFactory.apply(nowNanos);

        this.snapshotRef = new AtomicReference<>(CircuitBreakerSnapshot.initial(nowNanos, initialMetrics));
        this.transitionListeners = new CopyOnWriteArrayList<>();
    }

    // ======================== CAS contention safety ========================

    /**
     * Tracks CAS retry count and yields the thread's time slice if contention is
     * excessive. Returns the updated retry counter (reset to 0 after a yield,
     * incremented otherwise).
     *
     * <p>This prevents a scenario where many threads spin indefinitely on CAS
     * under high contention. After {@link #MAX_CAS_RETRIES_BEFORE_YIELD} consecutive
     * failures, {@code Thread.yield()} gives competing threads a chance to complete
     * their CAS first, breaking the contention cycle.</p>
     */
    private static int yieldIfExcessiveRetries(int retries) {
        if (retries > MAX_CAS_RETRIES_BEFORE_YIELD) {
            Thread.yield();
            return 0;
        }
        return retries + 1;
    }

    // ======================== InqElement identity ========================

    @Override
    public String getName() {
        return name;
    }

    @Override
    public InqElementType getElementType() {
        return InqElementType.CIRCUIT_BREAKER;
    }

    @Override
    public InqEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    // ======================== Synchronous pipeline execution ========================

    /**
     * Synchronous around-advice for the composition-based pipeline (ADR-022).
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Acquire permission (or throw {@link CircuitBreakerException} if OPEN)</li>
     *   <li>Delegate to the next element in the chain via {@code next.execute(...)}</li>
     *   <li>Record the outcome: success, failure, or ignored</li>
     * </ol>
     *
     * <p>The {@code outcomeRecorded} flag ensures exactly-one-outcome semantics.
     * Without it, a scenario like "catch records failure, then finally also records"
     * would double-count. The finally block only records if no catch block already did.</p>
     *
     * <p>Errors (as opposed to Exceptions) are always recorded as "ignored" because
     * they typically represent JVM-level problems (OutOfMemoryError, StackOverflowError)
     * that should not influence the circuit breaker's failure rate.</p>
     *
     * @param chainId  pipeline chain identifier for event correlation (ADR-022)
     * @param callId   per-invocation call identifier for event correlation (ADR-022)
     * @param argument the argument flowing through the pipeline, passed through unchanged
     * @param next     the next element in the composition chain
     * @return the result from the downstream chain
     * @throws CircuitBreakerException if the circuit breaker is OPEN and rejects the call
     */
    @Override
    public R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        acquirePermissionOrThrow();
        boolean outcomeRecorded = false;
        try {
            R result = next.execute(chainId, callId, argument);
            recordSuccess();
            outcomeRecorded = true;
            return result;
        } catch (Exception e) {
            // Exception path: evaluate against the failure predicate
            handleThrowable(e);
            outcomeRecorded = true;
            throw (RuntimeException) e;
        } catch (Error e) {
            // Error path: JVM-level problems are never counted as failures
            recordIgnored();
            outcomeRecorded = true;
            throw e;
        } finally {
            // Safety net: if neither catch block ran (e.g., a Throwable subclass
            // that is neither Exception nor Error — theoretically impossible in
            // standard Java but defensive), record as ignored to maintain the
            // "exactly one outcome" invariant.
            if (!outcomeRecorded) {
                recordIgnored();
            }
        }
    }

    // ======================== Asynchronous pipeline execution ========================

    /**
     * Asynchronous around-advice for the composition-based pipeline (ADR-022).
     *
     * <p>The permission check happens synchronously on the calling thread — if the
     * circuit is OPEN, the caller fails fast without ever creating the async operation.
     * The outcome recording happens asynchronously when the downstream
     * {@link CompletionStage} completes.</p>
     *
     * <p>Two failure paths exist:</p>
     * <ul>
     *   <li><b>Synchronous failure:</b> {@code next.executeAsync()} itself throws
     *       (e.g., the supplier cannot even start the async operation). The outcome
     *       is recorded immediately and the exception is re-thrown.</li>
     *   <li><b>Asynchronous failure:</b> The stage completes exceptionally at some
     *       later point. The {@code whenComplete} callback records the outcome.</li>
     * </ul>
     *
     * <p><b>ADR-023:</b> Returns the <em>copy</em> produced by {@code whenComplete()},
     * not the original stage. This ensures that if the recording callback itself
     * throws (e.g., a bug in {@link #handleThrowable}), the exception surfaces on
     * the caller's future rather than disappearing silently on a detached branch.</p>
     *
     * @param chainId  pipeline chain identifier for event correlation (ADR-022)
     * @param callId   per-invocation call identifier for event correlation (ADR-022)
     * @param argument the argument flowing through the pipeline, passed through unchanged
     * @param next     the next element in the async composition chain
     * @return a decorated {@link CompletionStage} that completes after outcome recording
     * @throws CircuitBreakerException if the circuit breaker is OPEN and rejects the call
     */
    @Override
    public CompletionStage<R> executeAsync(long chainId, long callId, A argument,
                                           InternalAsyncExecutor<A, R> next) {
        // Synchronous fast-fail: reject immediately if the circuit is OPEN
        acquirePermissionOrThrow();

        CompletionStage<R> stage;
        try {
            stage = next.executeAsync(chainId, callId, argument);
        } catch (Throwable t) {
            // Synchronous failure during stage creation — record outcome now
            if (t instanceof Exception e) {
                handleThrowable(e);
            } else {
                recordIgnored();
            }
            throw t instanceof RuntimeException re ? re : new RuntimeException(t);
        }

        // ADR-023: Always return the decorated copy, never the original.
        // The copy ensures that any exception thrown inside the callback
        // (e.g., from handleThrowable or recordSuccess) surfaces explicitly
        // rather than disappearing silently on a detached branch.
        return stage.whenComplete((result, error) -> {
            if (error != null) {
                if (error instanceof Exception e) {
                    handleThrowable(e);
                } else {
                    recordIgnored();
                }
            } else {
                recordSuccess();
            }
        });
    }

    // ======================== Standalone execution (Callable) ========================

    /**
     * Executes a {@link Callable} protected by this circuit breaker.
     *
     * <p>Standalone convenience method for use outside the pipeline. Unlike
     * {@link #execute(long, long, Object, InternalExecutor)}, this does not
     * participate in chain/call identity propagation (ADR-022).</p>
     *
     * <p>Preserves {@link InterruptedException} semantics: if the callable throws
     * an InterruptedException, the thread's interrupt flag is restored before
     * recording and re-throwing.</p>
     *
     * @param callable the operation to protect
     * @param <T>      the result type
     * @return the callable's result
     * @throws Exception               whatever the callable throws
     * @throws CircuitBreakerException  if the circuit is OPEN
     */
    public <T> T execute(Callable<T> callable) throws Exception {
        acquirePermissionOrThrow();
        boolean outcomeRecorded = false;
        try {
            T result = callable.call();
            recordSuccess();
            outcomeRecorded = true;
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                // Restore the interrupt flag so callers higher up the stack can
                // detect the interruption. Without this, the interrupt would be
                // silently swallowed.
                Thread.currentThread().interrupt();
            }
            handleThrowable(e);
            outcomeRecorded = true;
            throw e;
        } catch (Error e) {
            recordIgnored();
            outcomeRecorded = true;
            throw e;
        } finally {
            if (!outcomeRecorded) {
                recordIgnored();
            }
        }
    }

    // ======================== Standalone execution with fallback ========================

    /**
     * Executes the callable; if the circuit breaker <em>rejects</em> the call (OPEN state),
     * returns the fallback value instead.
     *
     * <p>Only catches {@link CircuitBreakerException} — if the callable itself throws,
     * that exception propagates normally. This is the "fail-fast with alternative" pattern:
     * the fallback is only for rejection, not for business errors.</p>
     *
     * @param callable the operation to protect
     * @param fallback the fallback supplier, invoked only on circuit breaker rejection
     * @param <T>      the result type
     * @return the callable's result, or the fallback value if rejected
     * @throws Exception whatever the callable throws (non-CircuitBreakerException)
     */
    public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
        try {
            return execute(callable);
        } catch (CircuitBreakerException e) {
            return fallback.get();
        }
    }

    /**
     * Executes the callable; if <em>any</em> exception occurs (business error or rejection),
     * returns the fallback value instead.
     *
     * <p>Exceptions from the fallback itself are propagated, with the original exception
     * attached as a suppressed exception for diagnostic purposes.</p>
     *
     * <p>{@link InterruptedException} is explicitly excluded from fallback handling and
     * re-thrown immediately, because swallowing interrupts would break cooperative
     * cancellation contracts.</p>
     *
     * @param callable the operation to protect
     * @param fallback the fallback supplier, invoked on any non-interrupt exception
     * @param <T>      the result type
     * @return the callable's result, or the fallback value on failure
     * @throws Exception            whatever the fallback throws, or InterruptedException
     * @throws InterruptedException if the callable was interrupted (never swallowed)
     */
    public <T> T executeWithFallbackOnAny(Callable<T> callable, Supplier<T> fallback) throws Exception {
        try {
            return execute(callable);
        } catch (InterruptedException e) {
            // Never swallow interrupts — cooperative cancellation must be preserved
            throw e;
        } catch (Exception e) {
            try {
                return fallback.get();
            } catch (Exception fallbackException) {
                // Attach the original cause so diagnostics can see both failures
                fallbackException.addSuppressed(e);
                throw fallbackException;
            }
        }
    }

    // ======================== Standalone execution (Runnable) ========================

    /**
     * Executes a {@link Runnable} protected by this circuit breaker.
     *
     * <p>Same outcome-recording contract as the other execute methods. Provided as
     * a convenience for fire-and-forget operations that don't return a value.</p>
     *
     * @param runnable the operation to protect
     * @throws CircuitBreakerException if the circuit is OPEN
     */
    public void execute(Runnable runnable) {
        acquirePermissionOrThrow();
        boolean outcomeRecorded = false;
        try {
            runnable.run();
            recordSuccess();
            outcomeRecorded = true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleThrowable(e);
            outcomeRecorded = true;
            throw (RuntimeException) e;
        } catch (Error e) {
            recordIgnored();
            outcomeRecorded = true;
            throw e;
        } finally {
            if (!outcomeRecorded) {
                recordIgnored();
            }
        }
    }

    // ======================== Permission acquisition ========================

    /**
     * Attempts to acquire permission to execute a call. Throws immediately if the
     * circuit is OPEN and the wait duration has not elapsed.
     *
     * <p>Uses a CAS loop with a two-tier strategy:</p>
     *
     * <ul>
     *   <li><b>Fast path (no state transition):</b> If the permission check does not
     *       change the circuit breaker's state (e.g., CLOSED → CLOSED after recording
     *       the in-flight call), a simple CAS is attempted. If it fails, another thread
     *       modified the snapshot concurrently, and the loop retries with fresh data.</li>
     *
     *   <li><b>Slow path (state transition detected):</b> If the permission check
     *       would change the state (e.g., OPEN → HALF_OPEN because the wait duration
     *       elapsed), the {@link #transitionLock} is acquired to serialize the transition.
     *       Inside the lock, the entire check is re-executed against the latest snapshot
     *       (double-check pattern) to avoid a race where two threads both see OPEN → HALF_OPEN
     *       and both try to transition. If the re-check shows the transition already happened
     *       (another thread won), the loop restarts.</li>
     * </ul>
     *
     * <p>The {@link #yieldIfExcessiveRetries} call prevents CPU spinning under extreme
     * contention by yielding after {@value #MAX_CAS_RETRIES_BEFORE_YIELD} consecutive
     * CAS failures.</p>
     *
     * @throws CircuitBreakerException if the circuit is OPEN and the call is rejected
     */
    private void acquirePermissionOrThrow() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();
            PermissionResult result = CircuitBreakerCore.tryAcquirePermission(
                    current, waitDurationNanos, permittedCallsInHalfOpen, nowNanos);

            // Fast rejection: no permission, no transition needed
            if (!result.permitted()) {
                throw new CircuitBreakerException(name, result.snapshot().state());
            }

            if (result.snapshot().state() != current.state()) {
                // ── Slow path: state transition detected ──
                // Another thread might also see this transition, so we serialize
                // via the transition lock and re-check inside.
                StateTransition transition = null;
                transitionLock.lock();
                try {
                    // Re-read everything under lock (double-check pattern)
                    nowNanos = timeSource.now();
                    current = snapshotRef.get();
                    result = CircuitBreakerCore.tryAcquirePermission(
                            current, waitDurationNanos, permittedCallsInHalfOpen, nowNanos);

                    if (!result.permitted()) {
                        throw new CircuitBreakerException(name, result.snapshot().state());
                    }

                    // The transition might already have been applied by another thread
                    if (result.snapshot().state() == current.state()) {
                        continue; // No transition after all — retry the outer loop
                    }

                    if (snapshotRef.compareAndSet(current, result.snapshot())) {
                        // CAS succeeded: we own this transition
                        transition = CircuitBreakerCore.detectTransition(
                                name, current, result.snapshot(), nowNanos).orElse(null);
                    } else {
                        // CAS failed: snapshot was modified concurrently, retry
                        continue;
                    }
                } finally {
                    transitionLock.unlock();
                }
                // Notify listeners OUTSIDE the lock to avoid holding it during
                // potentially slow listener code
                notifyListeners(transition);
                return;
            } else {
                // ── Fast path: no state transition ──
                // Simple CAS to swap in the updated snapshot (e.g., incremented
                // halfOpenAttempts counter). If it fails, retry.
                if (snapshotRef.compareAndSet(current, result.snapshot())) {
                    return;
                }
                // CAS failed — another thread modified the snapshot, loop and retry
            }
        }
    }

    // ======================== Outcome recording ========================

    /**
     * Records a successful call outcome and evaluates whether a state transition
     * should occur (relevant in HALF_OPEN: enough successes → transition to CLOSED).
     *
     * <p>Follows the same two-tier CAS/lock strategy as {@link #acquirePermissionOrThrow()}:
     * fast path for no-transition updates, slow path with lock for transitions.</p>
     */
    private void recordSuccess() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordSuccess(
                    current, successThresholdInHalfOpen, nowNanos);

            if (updated.state() != current.state()) {
                // Slow path: success triggered a transition (e.g., HALF_OPEN → CLOSED)
                StateTransition transition = null;
                transitionLock.lock();
                try {
                    nowNanos = timeSource.now();
                    current = snapshotRef.get();
                    updated = CircuitBreakerCore.recordSuccess(
                            current, successThresholdInHalfOpen, nowNanos);

                    if (updated.state() == current.state()) {
                        continue; // Transition already applied by another thread
                    }

                    if (snapshotRef.compareAndSet(current, updated)) {
                        transition = CircuitBreakerCore.detectTransition(
                                name, current, updated, nowNanos).orElse(null);
                    } else {
                        continue;
                    }
                } finally {
                    transitionLock.unlock();
                }
                notifyListeners(transition);
                return;
            } else {
                // Fast path: no state change, just update metrics
                if (snapshotRef.compareAndSet(current, updated)) {
                    return;
                }
            }
        }
    }

    /**
     * Evaluates a thrown exception against the {@link #recordFailurePredicate} and
     * records either a failure or an ignored outcome.
     *
     * <p>If the predicate does not match (e.g., the exception is a business validation
     * error that should not affect the failure rate), the call is recorded as ignored
     * and the method returns without touching the failure metrics.</p>
     *
     * <p>If the predicate matches, a failure is recorded. In HALF_OPEN state, a single
     * failure may trigger a transition back to OPEN. In CLOSED state, the failure is
     * added to the sliding window and may eventually trip the circuit if the failure
     * rate threshold is exceeded.</p>
     *
     * @param throwable the exception thrown by the protected call
     */
    private void handleThrowable(Throwable throwable) {
        if (!recordFailurePredicate.test(throwable)) {
            // This exception type is configured to be ignored (e.g., validation errors)
            recordIgnored();
            return;
        }

        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordFailure(current, nowNanos);

            if (updated.state() != current.state()) {
                // Slow path: failure triggered a transition (e.g., CLOSED → OPEN
                // because failure rate exceeded threshold, or HALF_OPEN → OPEN
                // because a trial call failed)
                StateTransition transition = null;
                transitionLock.lock();
                try {
                    nowNanos = timeSource.now();
                    current = snapshotRef.get();
                    updated = CircuitBreakerCore.recordFailure(current, nowNanos);

                    if (updated.state() == current.state()) {
                        continue;
                    }

                    if (snapshotRef.compareAndSet(current, updated)) {
                        transition = CircuitBreakerCore.detectTransition(
                                name, current, updated, nowNanos).orElse(null);
                    } else {
                        continue;
                    }
                } finally {
                    transitionLock.unlock();
                }
                notifyListeners(transition);
                return;
            } else {
                // Fast path: failure recorded, no state change
                if (snapshotRef.compareAndSet(current, updated)) {
                    return;
                }
            }
        }
    }

    /**
     * Records an ignored outcome. Ignored calls are counted for completeness but do
     * not affect the failure rate or trigger state transitions.
     *
     * <p>This is the simplest recording method: no state transition is possible, so
     * only the fast-path CAS is needed (no lock). The loop only retries if another
     * thread concurrently modified the snapshot.</p>
     */
    private void recordIgnored() {
        int retries = 0;
        while (true) {
            retries = yieldIfExcessiveRetries(retries);

            CircuitBreakerSnapshot current = snapshotRef.get();
            CircuitBreakerSnapshot updated = CircuitBreakerCore.recordIgnored(current);
            if (snapshotRef.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    // ======================== State transition listeners ========================

    /**
     * Registers a listener that is notified whenever the circuit breaker transitions
     * between states (CLOSED → OPEN, OPEN → HALF_OPEN, HALF_OPEN → CLOSED, etc.).
     *
     * <p>Returns a {@link Runnable} that, when invoked, removes the listener. This
     * avoids the need for the caller to retain a reference to the listener instance
     * for later removal.</p>
     *
     * <p>Listeners are invoked outside the transition lock, so they may observe the
     * circuit breaker in a state different from the transition's target (if another
     * transition happened between notification and observation). The
     * {@link StateTransition} parameter captures the exact before/after snapshot.</p>
     *
     * @param listener the callback to invoke on state transitions
     * @return a runnable that removes the listener when invoked
     */
    public Runnable onStateTransition(Consumer<StateTransition> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        transitionListeners.add(listener);
        return () -> transitionListeners.remove(listener);
    }

    /**
     * Notifies all registered listeners of a state transition. Each listener is
     * invoked in a try-catch to prevent one misbehaving listener from blocking
     * notification of subsequent listeners.
     *
     * <p>Silently skips notification if {@code transition} is null (no transition occurred).</p>
     */
    private void notifyListeners(StateTransition transition) {
        if (transition == null) {
            return;
        }
        for (Consumer<StateTransition> listener : transitionListeners) {
            try {
                listener.accept(transition);
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                        "State transition listener threw exception for circuit breaker '%s': %s"
                                .formatted(name, e.getMessage()),
                        e);
            }
        }
    }

    // ======================== Introspection ========================

    /** Returns the current state of the circuit breaker (CLOSED, OPEN, or HALF_OPEN). */
    public CircuitState getState() {
        return snapshotRef.get().state();
    }

    /**
     * Returns the current immutable snapshot, which includes state, metrics, counters,
     * and timestamps. Useful for monitoring dashboards and diagnostic tooling.
     */
    public CircuitBreakerSnapshot getSnapshot() {
        return snapshotRef.get();
    }

    /**
     * Resets the circuit breaker to its initial CLOSED state with fresh metrics.
     *
     * <p>Acquires the transition lock to ensure the reset is atomic with respect to
     * concurrent calls. If the circuit breaker is already in its pristine initial state
     * (CLOSED, no successes recorded, no half-open attempts), the metrics are silently
     * refreshed without emitting a state transition event — this avoids spurious
     * "CLOSED → CLOSED" notifications.</p>
     *
     * <p>In all other cases, the snapshot is replaced with a fresh initial snapshot and
     * a state transition event is emitted (e.g., "OPEN → CLOSED" or "HALF_OPEN → CLOSED").</p>
     */
    public void reset() {
        StateTransition transition = null;
        transitionLock.lock();
        try {
            long nowNanos = timeSource.now();
            CircuitBreakerSnapshot current = snapshotRef.get();

            // Optimization: if already in pristine initial state, just refresh the
            // metrics window without emitting a spurious transition event.
            if (current.state() == CircuitState.CLOSED
                    && current.successCount() == 0
                    && current.halfOpenAttempts() == 0) {
                FailureMetrics freshMetrics = metricsFactory.apply(nowNanos);
                CircuitBreakerSnapshot refreshed = CircuitBreakerSnapshot.initial(nowNanos, freshMetrics);
                snapshotRef.set(refreshed);
                return;
            }

            // Non-pristine state: full reset with transition notification
            FailureMetrics initialMetrics = metricsFactory.apply(nowNanos);
            CircuitBreakerSnapshot initial = CircuitBreakerSnapshot.initial(nowNanos, initialMetrics);
            CircuitBreakerSnapshot before = snapshotRef.getAndSet(initial);
            transition = CircuitBreakerCore.detectTransition(
                    name, before, initial, nowNanos).orElse(null);
        } finally {
            transitionLock.unlock();
        }
        // Notify outside the lock
        notifyListeners(transition);
    }
}
