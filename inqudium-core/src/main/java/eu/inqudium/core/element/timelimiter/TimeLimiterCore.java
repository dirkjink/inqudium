package eu.inqudium.core.element.timelimiter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Pure functional core of the time limiter.
 *
 * <p>All methods are static and side-effect-free. They manage the
 * per-execution lifecycle as an immutable state machine:
 *
 * <pre>
 *   IDLE ──[start]──► RUNNING ──[complete]──► COMPLETED
 *                        │
 *                        ├──[fail]──────────► FAILED
 *                        │
 *                        ├──[timeout]───────► TIMED_OUT ──[cancel]──► CANCELLED
 *                        │
 *                        └──[cancel]────────► CANCELLED
 * </pre>
 *
 * <p>Unlike CircuitBreaker or RateLimiter, there is no shared mutable state
 * between executions. Each execution gets its own {@link ExecutionSnapshot}
 * that tracks its independent lifecycle. The core provides the transition
 * logic and deadline computation; the wrappers provide the concurrency
 * mechanism (virtual thread + Future vs. Mono.timeout).
 *
 * <p>This design allows the same lifecycle logic to be shared between an
 * imperative (virtual-thread) wrapper and a reactive (Project Reactor) wrapper.
 */
public final class TimeLimiterCore {

    private TimeLimiterCore() {
        // Utility class — not instantiable
    }

    // ======================== Lifecycle Transitions ========================

    /**
     * Creates a new execution and transitions it from IDLE to RUNNING.
     *
     * <p>Computes the absolute deadline from the config's timeout and
     * the given start time.
     *
     * @param config the time limiter configuration
     * @param now    the execution start time
     * @return a snapshot in the RUNNING state with the computed deadline
     */
    public static ExecutionSnapshot start(TimeLimiterConfig config, Instant now) {
        return ExecutionSnapshot.idle().withStarted(now, config.timeout());
    }

    /**
     * Creates a new execution with an explicit timeout override.
     *
     * <p>Fix 4: When the caller provides a timeout that differs from the config default,
     * the snapshot's deadline must reflect the actual timeout used — not the config default.
     *
     * @param config           the time limiter configuration (used for name)
     * @param effectiveTimeout the actual timeout to use for this execution
     * @param now              the execution start time
     * @return a snapshot in the RUNNING state with the overridden deadline
     */
    public static ExecutionSnapshot start(TimeLimiterConfig config, Duration effectiveTimeout, Instant now) {
        return ExecutionSnapshot.idle().withStarted(now, effectiveTimeout);
    }

    /**
     * Records a successful completion of the execution.
     *
     * @param snapshot the current RUNNING snapshot
     * @param now      the completion time
     * @return a snapshot in the COMPLETED state
     * @throws IllegalStateException if the snapshot is not in RUNNING state
     */
    public static ExecutionSnapshot recordSuccess(ExecutionSnapshot snapshot, Instant now) {
        requireState(snapshot, ExecutionState.RUNNING, "recordSuccess");
        return snapshot.withCompleted(now);
    }

    /**
     * Records a failure of the execution (exception thrown within time limit).
     *
     * @param snapshot the current RUNNING snapshot
     * @param cause    the exception that caused the failure
     * @param now      the failure time
     * @return a snapshot in the FAILED state
     * @throws IllegalStateException if the snapshot is not in RUNNING state
     */
    public static ExecutionSnapshot recordFailure(
            ExecutionSnapshot snapshot,
            Throwable cause,
            Instant now) {

        requireState(snapshot, ExecutionState.RUNNING, "recordFailure");
        return snapshot.withFailed(now, cause);
    }

    /**
     * Records a timeout of the execution.
     *
     * @param snapshot the current RUNNING snapshot
     * @param now      the timeout detection time
     * @return a snapshot in the TIMED_OUT state
     * @throws IllegalStateException if the snapshot is not in RUNNING state
     */
    public static ExecutionSnapshot recordTimeout(ExecutionSnapshot snapshot, Instant now) {
        requireState(snapshot, ExecutionState.RUNNING, "recordTimeout");
        return snapshot.withTimedOut(now);
    }

    /**
     * Records a cancellation of the execution (typically following a timeout).
     *
     * <p>Fix 7: Cancellation is only allowed from RUNNING or TIMED_OUT states.
     * The state machine diagram explicitly shows TIMED_OUT → CANCELLED as a valid
     * transition (the wrapper times out, then cancels the underlying Future).
     * RUNNING → CANCELLED is also valid (direct cancellation without timeout).
     * All other terminal states are truly final.
     *
     * @param snapshot the current snapshot (RUNNING or TIMED_OUT)
     * @param now      the cancellation time
     * @return a snapshot in the CANCELLED state
     * @throws IllegalStateException if the snapshot is in any other terminal state
     */
    public static ExecutionSnapshot recordCancellation(ExecutionSnapshot snapshot, Instant now) {
        ExecutionState state = snapshot.state();
        if (state != ExecutionState.RUNNING && state != ExecutionState.TIMED_OUT) {
            throw new IllegalStateException(
                    "Cannot cancel execution in state %s (expected RUNNING or TIMED_OUT)".formatted(state));
        }
        return snapshot.withCancelled(now);
    }

    // ======================== Deadline Checks ========================

    /**
     * Checks whether the execution's deadline has been exceeded.
     *
     * @param snapshot the current snapshot
     * @param now      the current time
     * @return {@code true} if the deadline has passed
     */
    public static boolean isDeadlineExceeded(ExecutionSnapshot snapshot, Instant now) {
        return snapshot.isDeadlineExceeded(now);
    }

    /**
     * Returns the remaining duration until the deadline.
     * Returns {@link Duration#ZERO} if no deadline is set or if it has already passed.
     *
     * @param snapshot the current snapshot
     * @param now      the current time
     * @return the remaining duration (non-negative)
     */
    public static Duration remainingTime(ExecutionSnapshot snapshot, Instant now) {
        Duration remaining = snapshot.remaining(now);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Returns the elapsed duration since the execution started.
     *
     * @param snapshot the current snapshot
     * @param now      the current time
     * @return the elapsed duration
     */
    public static Duration elapsedTime(ExecutionSnapshot snapshot, Instant now) {
        return snapshot.elapsed(now);
    }

    // ======================== Result Construction ========================

    /**
     * Evaluates a RUNNING snapshot at the given time and determines whether
     * a timeout has occurred.
     *
     * <p>Fix 5: Returns {@link Optional} instead of nullable for API consistency
     * with the rest of the framework.
     *
     * @param snapshot the current RUNNING snapshot
     * @param now      the current time
     * @return an Optional containing a timeout result if the deadline is exceeded, empty otherwise
     */
    public static <T> Optional<ExecutionResult<T>> checkForTimeout(ExecutionSnapshot snapshot, Instant now) {
        if (snapshot.state() == ExecutionState.RUNNING && isDeadlineExceeded(snapshot, now)) {
            ExecutionSnapshot timedOut = recordTimeout(snapshot, now);
            return Optional.of(new ExecutionResult.Timeout<>(timedOut));
        }
        return Optional.empty();
    }

    /**
     * Creates a success result from a value and a RUNNING snapshot.
     *
     * @param value    the result value
     * @param snapshot the RUNNING snapshot
     * @param now      the completion time
     * @return a success result
     */
    public static <T> ExecutionResult<T> toSuccess(T value, ExecutionSnapshot snapshot, Instant now) {
        ExecutionSnapshot completed = recordSuccess(snapshot, now);
        return new ExecutionResult.Success<>(value, completed);
    }

    /**
     * Creates a failure result from an exception and a RUNNING snapshot.
     *
     * @param cause    the exception
     * @param snapshot the RUNNING snapshot
     * @param now      the failure time
     * @return a failure result
     */
    public static <T> ExecutionResult<T> toFailure(
            Throwable cause,
            ExecutionSnapshot snapshot,
            Instant now) {

        ExecutionSnapshot failed = recordFailure(snapshot, cause, now);
        return new ExecutionResult.Failure<>(cause, failed);
    }

    /**
     * Creates a timeout result from a RUNNING snapshot.
     *
     * @param snapshot the RUNNING snapshot
     * @param now      the timeout time
     * @return a timeout result
     */
    public static <T> ExecutionResult<T> toTimeout(ExecutionSnapshot snapshot, Instant now) {
        ExecutionSnapshot timedOut = recordTimeout(snapshot, now);
        return new ExecutionResult.Timeout<>(timedOut);
    }

    /**
     * Creates a cancellation result.
     *
     * @param snapshot the current snapshot (RUNNING or TIMED_OUT)
     * @param now      the cancellation time
     * @return a cancellation result
     */
    public static <T> ExecutionResult<T> toCancelled(ExecutionSnapshot snapshot, Instant now) {
        ExecutionSnapshot cancelled = recordCancellation(snapshot, now);
        return new ExecutionResult.Cancelled<>(cancelled);
    }

    // ======================== Internal ========================

    private static void requireState(ExecutionSnapshot snapshot, ExecutionState required, String operation) {
        if (snapshot.state() != required) {
            throw new IllegalStateException(
                    "Cannot %s in state %s (expected %s)".formatted(operation, snapshot.state(), required));
        }
    }
}
