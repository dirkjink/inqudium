package eu.inqudium.core.element.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;

import java.time.Duration;
import java.util.Optional;

/**
 * Pure functional core of the circuit breaker state machine.
 *
 * <p>All methods are static and side-effect-free. They accept the current
 * immutable {@link CircuitBreakerSnapshot} and return a new snapshot
 * reflecting the state transition. No synchronization, no I/O, no mutation.
 *
 * <p>This class has no dependency on {@link InqCircuitBreakerConfig}. Every
 * parameter is passed explicitly as a primitive or simple value, making the
 * core trivially testable without any configuration object.
 *
 * <p>All time parameters are expressed as nanoseconds obtained from
 * {@link eu.inqudium.core.time.InqNanoTimeSource#now()}, ensuring
 * deterministic testability without {@code Thread.sleep()} (ADR-016).
 *
 * <h2>State Machine</h2>
 * <pre>
 * [failures >= threshold]
 * CLOSED ──────────────────────────────► OPEN
 * ▲                                     │
 * │ [successes >= successThreshold]     │ [waitDuration expired]
 * │                                     ▼
 * └──────────────────────────────── HALF_OPEN
 *                                       │
 *                               [any failure]
 *                                       └──► OPEN
 * </pre>
 */
public final class CircuitBreakerCore {

    private CircuitBreakerCore() {
        // Utility class — not instantiable
    }

    /**
     * Evaluates whether a call is permitted given the current state.
     *
     * <p>May trigger a state transition from OPEN → HALF_OPEN when the
     * wait duration has expired. The returned {@link PermissionResult}
     * contains the (possibly updated) snapshot.
     *
     * @param snapshot                 the current state snapshot
     * @param waitDurationNanos        how long the circuit stays open before probing (in nanoseconds)
     * @param permittedCallsInHalfOpen max concurrent probe calls allowed in HALF_OPEN
     * @param nowNanos                 the current nanosecond timestamp
     * @return a {@link PermissionResult} indicating whether the call is permitted
     */
    public static PermissionResult tryAcquirePermission(
            CircuitBreakerSnapshot snapshot,
            long waitDurationNanos,
            int permittedCallsInHalfOpen,
            long nowNanos) {

        return switch (snapshot.state()) {
            case CLOSED -> PermissionResult.permitted(snapshot);

            case OPEN -> {
                if (isWaitDurationExpired(snapshot, waitDurationNanos, nowNanos)) {
                    String reason = "Wait duration of %s expired"
                            .formatted(Duration.ofNanos(waitDurationNanos));

                    CircuitBreakerSnapshot halfOpen = snapshot
                            .withState(CircuitState.HALF_OPEN, nowNanos, reason)
                            .withIncrementedHalfOpenAttempts();

                    yield PermissionResult.permitted(halfOpen);
                }
                yield PermissionResult.rejected(snapshot);
            }

            case HALF_OPEN -> {
                if (snapshot.halfOpenAttempts() < permittedCallsInHalfOpen) {
                    yield PermissionResult.permitted(snapshot.withIncrementedHalfOpenAttempts());
                }
                yield PermissionResult.rejected(snapshot);
            }
        };
    }

    // ======================== Recording outcomes ========================

    /**
     * Records a successful call and returns the updated snapshot.
     *
     * <p>In CLOSED state, the success recording is delegated to the configured
     * {@link FailureMetrics} strategy. It also evaluates the threshold, because
     * in sliding window algorithms, adding a success might reach the
     * 'minimumNumberOfCalls' limit, thereby activating a previously hidden
     * high failure rate.
     *
     * <p>In HALF_OPEN state the success counter is incremented; if it reaches the
     * configured threshold the circuit transitions back to CLOSED.
     *
     * @param snapshot                   the current state snapshot
     * @param successThresholdInHalfOpen number of successes needed to close the circuit
     * @param nowNanos                   the current nanosecond timestamp
     * @return the updated snapshot
     */
    public static CircuitBreakerSnapshot recordSuccess(
            CircuitBreakerSnapshot snapshot,
            int successThresholdInHalfOpen,
            long nowNanos) {

        return switch (snapshot.state()) {
            case CLOSED -> {
                FailureMetrics updatedMetrics = snapshot.failureMetrics().recordSuccess(nowNanos);
                if (updatedMetrics.isThresholdReached(nowNanos)) {
                    String reason = updatedMetrics.getTripReason(nowNanos);
                    yield snapshot.withState(CircuitState.OPEN, nowNanos, reason);
                }
                yield snapshot.withUpdatedFailureMetrics(updatedMetrics);
            }

            case HALF_OPEN -> {
                int newSuccessCount = snapshot.successCount() + 1;
                if (newSuccessCount >= successThresholdInHalfOpen) {
                    String reason = "Success threshold (%d) met in HALF_OPEN state"
                            .formatted(successThresholdInHalfOpen);
                    yield snapshot.withState(CircuitState.CLOSED, nowNanos, reason);
                }
                yield snapshot.withIncrementedSuccessCount();
            }

            case OPEN -> snapshot;
        };
    }

    /**
     * Records a failed call and returns the updated snapshot.
     *
     * <p>In CLOSED state, the failure recording and threshold evaluation are
     * delegated to the configured {@link FailureMetrics} strategy. If the threshold
     * is reached, the circuit transitions to OPEN.
     * In HALF_OPEN state any failure immediately transitions back to OPEN.
     *
     * @param snapshot the current state snapshot
     * @param nowNanos the current nanosecond timestamp
     * @return the updated snapshot
     */
    public static CircuitBreakerSnapshot recordFailure(
            CircuitBreakerSnapshot snapshot,
            long nowNanos) {

        return switch (snapshot.state()) {
            case CLOSED -> {
                FailureMetrics updatedMetrics = snapshot.failureMetrics().recordFailure(nowNanos);
                if (updatedMetrics.isThresholdReached(nowNanos)) {
                    String reason = updatedMetrics.getTripReason(nowNanos);
                    yield snapshot.withState(CircuitState.OPEN, nowNanos, reason);
                }
                yield snapshot.withUpdatedFailureMetrics(updatedMetrics);
            }

            case HALF_OPEN -> {
                String reason = "Probe call failed in HALF_OPEN state";
                yield snapshot.withState(CircuitState.OPEN, nowNanos, reason);
            }

            case OPEN -> snapshot;
        };
    }

    /**
     * Records that a call outcome was ignored (neither success nor failure).
     *
     * <p>In HALF_OPEN state, an ignored exception must release the attempt slot
     * that was consumed during permission acquisition. Without this, ignored exceptions
     * would permanently consume HALF_OPEN slots.
     *
     * <p>In CLOSED and OPEN states, this is a no-op.
     *
     * @param snapshot the current state snapshot
     * @return the updated snapshot with the attempt slot released (in HALF_OPEN)
     */
    public static CircuitBreakerSnapshot recordIgnored(CircuitBreakerSnapshot snapshot) {
        return switch (snapshot.state()) {
            case HALF_OPEN -> snapshot.withDecrementedHalfOpenAttempts();
            case CLOSED, OPEN -> snapshot;
        };
    }

    // ======================== Query helpers ========================

    /**
     * Returns the current {@link CircuitState} of the snapshot.
     */
    public static CircuitState currentState(CircuitBreakerSnapshot snapshot) {
        return snapshot.state();
    }

    /**
     * Checks whether the wait duration in OPEN state has expired.
     *
     * @param snapshot          the current state snapshot
     * @param waitDurationNanos the configured wait duration in nanoseconds
     * @param nowNanos          the current nanosecond timestamp
     * @return true if the elapsed time since the state change exceeds the wait duration
     */
    public static boolean isWaitDurationExpired(
            CircuitBreakerSnapshot snapshot,
            long waitDurationNanos,
            long nowNanos) {

        long elapsedNanos = nowNanos - snapshot.stateChangedAtNanos();
        return elapsedNanos >= waitDurationNanos;
    }

    /**
     * Detects whether a state transition occurred between two snapshots.
     *
     * @param name     the circuit breaker name (for the transition event)
     * @param before   the snapshot before the operation
     * @param after    the snapshot after the operation
     * @param nowNanos the current nanosecond timestamp
     * @return an Optional containing the transition, or empty if no transition occurred
     */
    public static Optional<StateTransition> detectTransition(
            String name,
            CircuitBreakerSnapshot before,
            CircuitBreakerSnapshot after,
            long nowNanos) {

        if (before.state() != after.state()) {
            return Optional.of(new StateTransition(
                    name,
                    before.state(),
                    after.state(),
                    nowNanos,
                    after.transitionReason()
            ));
        }
        return Optional.empty();
    }
}
