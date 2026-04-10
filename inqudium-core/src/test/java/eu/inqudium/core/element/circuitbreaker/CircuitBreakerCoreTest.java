package eu.inqudium.core.element.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.metrics.ConsecutiveFailuresMetrics;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.element.circuitbreaker.metrics.SlidingWindowMetrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the pure functional core of the circuit breaker state machine.
 *
 * <p>All methods under test are static, side-effect-free, and have no dependency
 * on {@link InqCircuitBreakerConfig}. Each parameter is passed explicitly as a
 * primitive, making every test self-contained and trivially readable.
 *
 * <p>No concurrency, no I/O — only immutable snapshot transformations.
 * Time is controlled deterministically via explicit nanosecond timestamps.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CircuitBreakerCoreTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long T0 = 100 * NANOS_PER_SECOND;

    // Default parameters used across most tests
    private static final long WAIT_DURATION_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int FAILURE_THRESHOLD = 3;
    private static final int SUCCESS_THRESHOLD_IN_HALF_OPEN = 2;
    private static final int PERMITTED_CALLS_IN_HALF_OPEN = 3;

    // ======================== Test helpers ========================

    /**
     * Creates a fresh CLOSED snapshot with ConsecutiveFailuresMetrics.
     */
    private CircuitBreakerSnapshot closedSnapshot() {
        FailureMetrics metrics = ConsecutiveFailuresMetrics.initial(FAILURE_THRESHOLD, 0);
        return CircuitBreakerSnapshot.initial(T0, metrics);
    }

    /**
     * Creates a snapshot in OPEN state by recording enough consecutive failures.
     */
    private CircuitBreakerSnapshot openSnapshot() {
        var snapshot = closedSnapshot();
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            snapshot = CircuitBreakerCore.recordFailure(snapshot, T0);
        }
        assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);
        return snapshot;
    }

    /**
     * Creates a snapshot in HALF_OPEN state by expiring the wait duration in OPEN.
     */
    private CircuitBreakerSnapshot halfOpenSnapshot() {
        var open = openSnapshot();
        long afterWait = open.stateChangedAtNanos() + WAIT_DURATION_NANOS + NANOS_PER_SECOND;
        var result = CircuitBreakerCore.tryAcquirePermission(
                open, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait);
        assertThat(result.permitted()).isTrue();
        assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
        return result.snapshot();
    }

    // ======================== tryAcquirePermission — CLOSED ========================

    @Nested
    class TryAcquirePermission_Closed {

        @Test
        void should_always_permit_calls_in_closed_state() {
            // Given
            var snapshot = closedSnapshot();

            // When
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, T0);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.snapshot().state()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void should_return_the_same_snapshot_in_closed_state() {
            // Given
            var snapshot = closedSnapshot();

            // When
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, T0);

            // Then — snapshot is unchanged (no state transition)
            assertThat(result.snapshot()).isSameAs(snapshot);
        }

        @Test
        void should_permit_calls_regardless_of_how_much_time_has_passed() {
            // Given
            var snapshot = closedSnapshot();

            // When — far in the future
            long farFuture = T0 + 1000 * NANOS_PER_SECOND;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, farFuture);

            // Then
            assertThat(result.permitted()).isTrue();
        }
    }

    // ======================== tryAcquirePermission — OPEN ========================

    @Nested
    class TryAcquirePermission_Open {

        @Test
        void should_reject_calls_when_wait_duration_has_not_expired() {
            // Given
            var snapshot = openSnapshot();

            // When — only half the wait duration has passed
            long beforeExpiry = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS / 2;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, beforeExpiry);

            // Then
            assertThat(result.permitted()).isFalse();
            assertThat(result.snapshot().state()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void should_reject_calls_exactly_one_nano_before_wait_duration_expires() {
            // Given
            var snapshot = openSnapshot();

            // When
            long justBefore = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS - 1;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, justBefore);

            // Then
            assertThat(result.permitted()).isFalse();
        }

        @Test
        void should_transition_to_half_open_when_wait_duration_expires_exactly() {
            // Given
            var snapshot = openSnapshot();

            // When
            long exactlyAtExpiry = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, exactlyAtExpiry);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
        }

        @Test
        void should_transition_to_half_open_when_wait_duration_is_well_past() {
            // Given
            var snapshot = openSnapshot();

            // When
            long wellPast = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS + 60 * NANOS_PER_SECOND;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, wellPast);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
        }

        @Test
        void should_set_half_open_attempts_to_one_after_transition() {
            // Given
            var snapshot = openSnapshot();

            // When
            long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait);

            // Then — the first probe call consumes one attempt slot
            assertThat(result.snapshot().halfOpenAttempts()).isEqualTo(1);
        }

        @Test
        void should_reset_success_count_to_zero_on_transition_to_half_open() {
            // Given
            var snapshot = openSnapshot();

            // When
            long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait);

            // Then
            assertThat(result.snapshot().successCount()).isZero();
        }

        @Test
        void should_include_wait_duration_in_transition_reason() {
            // Given
            var snapshot = openSnapshot();

            // When
            long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait);

            // Then
            assertThat(result.snapshot().transitionReason())
                    .containsIgnoringCase("wait duration")
                    .containsIgnoringCase("expired");
        }
    }

    // ======================== tryAcquirePermission — HALF_OPEN ========================

    @Nested
    class TryAcquirePermission_HalfOpen {

        @Test
        void should_permit_calls_up_to_the_configured_limit() {
            // Given — halfOpenAttempts already 1 from transition
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When — acquire 2 more (already 1 from transition)
            var result1 = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now);
            assertThat(result1.permitted()).isTrue();

            var result2 = CircuitBreakerCore.tryAcquirePermission(
                    result1.snapshot(), WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now);
            assertThat(result2.permitted()).isTrue();

            // Then — all 3 slots exhausted
            var result3 = CircuitBreakerCore.tryAcquirePermission(
                    result2.snapshot(), WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now);
            assertThat(result3.permitted()).isFalse();
        }

        @Test
        void should_increment_half_open_attempts_on_each_permitted_call() {
            // Given
            var snapshot = halfOpenSnapshot();
            assertThat(snapshot.halfOpenAttempts()).isEqualTo(1);
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When
            var result = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now);

            // Then
            assertThat(result.snapshot().halfOpenAttempts()).isEqualTo(2);
        }

        @Test
        void should_reject_when_all_slots_are_consumed() {
            // Given — manually set attempts to max
            var snapshot = halfOpenSnapshot();
            var fullSlots = new CircuitBreakerSnapshot(
                    CircuitState.HALF_OPEN, snapshot.failureMetrics(), 0,
                    PERMITTED_CALLS_IN_HALF_OPEN, snapshot.stateChangedAtNanos(), "test");
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When
            var result = CircuitBreakerCore.tryAcquirePermission(
                    fullSlots, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now);

            // Then
            assertThat(result.permitted()).isFalse();
            assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
        }
    }

    // ======================== recordSuccess — CLOSED ========================

    @Nested
    class RecordSuccess_Closed {

        @Test
        void should_update_metrics_without_state_change() {
            // Given
            var snapshot = closedSnapshot();

            // When
            var updated = CircuitBreakerCore.recordSuccess(
                    snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, T0);

            // Then
            assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
            assertThat(updated.failureMetrics()).isNotSameAs(snapshot.failureMetrics());
        }

        @Test
        void should_remain_closed_after_many_successes() {
            // Given
            var snapshot = closedSnapshot();

            // When
            var updated = snapshot;
            for (int i = 0; i < 100; i++) {
                updated = CircuitBreakerCore.recordSuccess(
                        updated, SUCCESS_THRESHOLD_IN_HALF_OPEN, T0);
            }

            // Then
            assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void should_trip_if_success_causes_minimum_calls_to_be_met_with_high_failure_rate() {
            // Given — SlidingWindow: threshold=3, window=10, min=5
            FailureMetrics metrics = SlidingWindowMetrics.initial(3, 10, 5);
            var snapshot = CircuitBreakerSnapshot.initial(T0, metrics);

            // When — 4 failures + 1 success = 5 calls (min met), 4 failures >= threshold 3
            var updated = snapshot;
            for (int i = 0; i < 4; i++) {
                updated = CircuitBreakerCore.recordFailure(updated, T0);
            }
            assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
            updated = CircuitBreakerCore.recordSuccess(
                    updated, SUCCESS_THRESHOLD_IN_HALF_OPEN, T0);

            // Then — success was the tipping point
            assertThat(updated.state()).isEqualTo(CircuitState.OPEN);
        }
    }

    // ======================== recordSuccess — HALF_OPEN ========================

    @Nested
    class RecordSuccess_HalfOpen {

        @Test
        void should_increment_success_count() {
            // Given
            var snapshot = halfOpenSnapshot();
            assertThat(snapshot.successCount()).isZero();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When
            var updated = CircuitBreakerCore.recordSuccess(
                    snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, now);

            // Then
            assertThat(updated.successCount()).isEqualTo(1);
            assertThat(updated.state()).isEqualTo(CircuitState.HALF_OPEN);
        }

        @Test
        void should_transition_to_closed_when_success_threshold_is_met() {
            // Given — successThresholdInHalfOpen=2
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When — 2 successes
            var after1 = CircuitBreakerCore.recordSuccess(
                    snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, now);
            var after2 = CircuitBreakerCore.recordSuccess(
                    after1, SUCCESS_THRESHOLD_IN_HALF_OPEN, now);

            // Then
            assertThat(after2.state()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void should_not_close_with_one_fewer_success_than_threshold() {
            // Given — successThresholdInHalfOpen=2
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When — only 1 success
            var after1 = CircuitBreakerCore.recordSuccess(
                    snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, now);

            // Then
            assertThat(after1.state()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(after1.successCount()).isEqualTo(1);
        }

        @Test
        void should_reset_counters_when_transitioning_to_closed() {
            // Given
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When
            var closed = CircuitBreakerCore.recordSuccess(
                    CircuitBreakerCore.recordSuccess(snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, now),
                    SUCCESS_THRESHOLD_IN_HALF_OPEN, now);

            // Then
            assertThat(closed.successCount()).isZero();
            assertThat(closed.halfOpenAttempts()).isZero();
        }

        @Test
        void should_include_success_threshold_in_transition_reason() {
            // Given
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When
            var closed = CircuitBreakerCore.recordSuccess(
                    CircuitBreakerCore.recordSuccess(snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, now),
                    SUCCESS_THRESHOLD_IN_HALF_OPEN, now);

            // Then
            assertThat(closed.transitionReason())
                    .containsIgnoringCase("success threshold")
                    .contains("2");
        }

        @Test
        void should_close_with_a_custom_success_threshold() {
            // Given — successThreshold=5
            int customThreshold = 5;
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When — 4 successes → still HALF_OPEN
            var updated = snapshot;
            for (int i = 0; i < 4; i++) {
                updated = CircuitBreakerCore.recordSuccess(updated, customThreshold, now);
            }
            assertThat(updated.state()).isEqualTo(CircuitState.HALF_OPEN);

            // When — 5th success → CLOSED
            updated = CircuitBreakerCore.recordSuccess(updated, customThreshold, now);

            // Then
            assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
        }
    }

    // ======================== recordSuccess — OPEN ========================

    @Nested
    class RecordSuccess_Open {

        @Test
        void should_be_a_no_op_in_open_state() {
            // Given
            var snapshot = openSnapshot();

            // When
            var updated = CircuitBreakerCore.recordSuccess(
                    snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, T0);

            // Then
            assertThat(updated).isSameAs(snapshot);
        }
    }

    // ======================== recordFailure — CLOSED ========================

    @Nested
    class RecordFailure_Closed {

        @Test
        void should_update_metrics_without_state_change_below_threshold() {
            // Given
            var snapshot = closedSnapshot();

            // When — 2 failures (below threshold of 3)
            var updated = CircuitBreakerCore.recordFailure(snapshot, T0);
            updated = CircuitBreakerCore.recordFailure(updated, T0);

            // Then
            assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void should_transition_to_open_when_failure_threshold_is_reached() {
            // Given
            var snapshot = closedSnapshot();

            // When — 3 consecutive failures
            var updated = snapshot;
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                updated = CircuitBreakerCore.recordFailure(updated, T0);
            }

            // Then
            assertThat(updated.state()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void should_reset_counters_on_transition_to_open() {
            // Given
            var snapshot = closedSnapshot();

            // When
            var updated = snapshot;
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                updated = CircuitBreakerCore.recordFailure(updated, T0);
            }

            // Then
            assertThat(updated.successCount()).isZero();
            assertThat(updated.halfOpenAttempts()).isZero();
        }

        @Test
        void should_set_state_changed_timestamp_on_transition() {
            // Given
            var snapshot = closedSnapshot();
            long tripTime = T0 + 5 * NANOS_PER_SECOND;

            // When
            var updated = snapshot;
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                updated = CircuitBreakerCore.recordFailure(updated, tripTime);
            }

            // Then
            assertThat(updated.stateChangedAtNanos()).isEqualTo(tripTime);
        }

        @Test
        void should_include_metrics_trip_reason_in_transition() {
            // Given
            var snapshot = closedSnapshot();

            // When
            var updated = snapshot;
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                updated = CircuitBreakerCore.recordFailure(updated, T0);
            }

            // Then
            assertThat(updated.transitionReason())
                    .isNotBlank()
                    .containsIgnoringCase("consecutive");
        }
    }

    // ======================== recordFailure — HALF_OPEN ========================

    @Nested
    class RecordFailure_HalfOpen {

        @Test
        void should_immediately_transition_to_open_on_any_failure() {
            // Given
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When
            var updated = CircuitBreakerCore.recordFailure(snapshot, now);

            // Then
            assertThat(updated.state()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void should_set_transition_reason_to_probe_failure() {
            // Given
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            // When
            var updated = CircuitBreakerCore.recordFailure(snapshot, now);

            // Then
            assertThat(updated.transitionReason())
                    .containsIgnoringCase("probe")
                    .containsIgnoringCase("failed")
                    .containsIgnoringCase("HALF_OPEN");
        }

        @Test
        void should_update_state_changed_timestamp() {
            // Given
            var snapshot = halfOpenSnapshot();
            long failureTime = snapshot.stateChangedAtNanos() + 2 * NANOS_PER_SECOND;

            // When
            var updated = CircuitBreakerCore.recordFailure(snapshot, failureTime);

            // Then
            assertThat(updated.stateChangedAtNanos()).isEqualTo(failureTime);
        }

        @Test
        void should_reset_counters_on_transition_back_to_open() {
            // Given — snapshot with 1 success already
            var snapshot = halfOpenSnapshot();
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;
            var withOneSuccess = CircuitBreakerCore.recordSuccess(
                    snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, now);
            assertThat(withOneSuccess.successCount()).isEqualTo(1);

            // When
            var reopened = CircuitBreakerCore.recordFailure(withOneSuccess, now);

            // Then
            assertThat(reopened.state()).isEqualTo(CircuitState.OPEN);
            assertThat(reopened.successCount()).isZero();
            assertThat(reopened.halfOpenAttempts()).isZero();
        }
    }

    // ======================== recordFailure — OPEN ========================

    @Nested
    class RecordFailure_Open {

        @Test
        void should_be_a_no_op_in_open_state() {
            // Given
            var snapshot = openSnapshot();

            // When
            var updated = CircuitBreakerCore.recordFailure(snapshot, T0);

            // Then
            assertThat(updated).isSameAs(snapshot);
        }
    }

    // ======================== recordIgnored ========================

    @Nested
    class RecordIgnored {

        @Test
        void should_decrement_half_open_attempts_in_half_open_state() {
            // Given
            var snapshot = halfOpenSnapshot();
            assertThat(snapshot.halfOpenAttempts()).isEqualTo(1);

            // When
            var updated = CircuitBreakerCore.recordIgnored(snapshot);

            // Then
            assertThat(updated.halfOpenAttempts()).isZero();
        }

        @Test
        void should_not_decrement_below_zero() {
            // Given
            var metrics = ConsecutiveFailuresMetrics.initial(FAILURE_THRESHOLD, 0);
            var snapshot = new CircuitBreakerSnapshot(
                    CircuitState.HALF_OPEN, metrics, 0, 0, T0, "test");

            // When
            var updated = CircuitBreakerCore.recordIgnored(snapshot);

            // Then
            assertThat(updated.halfOpenAttempts()).isZero();
        }

        @Test
        void should_be_a_no_op_in_closed_state() {
            // Given
            var snapshot = closedSnapshot();

            // When
            var updated = CircuitBreakerCore.recordIgnored(snapshot);

            // Then
            assertThat(updated).isSameAs(snapshot);
        }

        @Test
        void should_be_a_no_op_in_open_state() {
            // Given
            var snapshot = openSnapshot();

            // When
            var updated = CircuitBreakerCore.recordIgnored(snapshot);

            // Then
            assertThat(updated).isSameAs(snapshot);
        }

        @Test
        void should_free_slot_for_subsequent_probe_call() {
            // Given — consume all 3 slots
            var snapshot = halfOpenSnapshot(); // attempts=1
            long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

            var with2 = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now).snapshot();
            var with3 = CircuitBreakerCore.tryAcquirePermission(
                    with2, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now).snapshot();
            assertThat(CircuitBreakerCore.tryAcquirePermission(
                    with3, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now).permitted()).isFalse();

            // When — one ignored call releases a slot
            var afterIgnored = CircuitBreakerCore.recordIgnored(with3);

            // Then
            assertThat(CircuitBreakerCore.tryAcquirePermission(
                    afterIgnored, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, now).permitted()).isTrue();
        }
    }

    // ======================== isWaitDurationExpired ========================

    @Nested
    class IsWaitDurationExpired {

        @Test
        void should_return_false_when_no_time_has_passed() {
            // Given
            var snapshot = openSnapshot();

            // When / Then
            assertThat(CircuitBreakerCore.isWaitDurationExpired(
                    snapshot, WAIT_DURATION_NANOS, snapshot.stateChangedAtNanos())).isFalse();
        }

        @Test
        void should_return_false_one_nano_before_expiry() {
            // Given
            var snapshot = openSnapshot();
            long justBefore = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS - 1;

            // When / Then
            assertThat(CircuitBreakerCore.isWaitDurationExpired(
                    snapshot, WAIT_DURATION_NANOS, justBefore)).isFalse();
        }

        @Test
        void should_return_true_exactly_at_expiry() {
            // Given
            var snapshot = openSnapshot();
            long exactlyAt = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;

            // When / Then
            assertThat(CircuitBreakerCore.isWaitDurationExpired(
                    snapshot, WAIT_DURATION_NANOS, exactlyAt)).isTrue();
        }

        @Test
        void should_return_true_well_after_expiry() {
            // Given
            var snapshot = openSnapshot();
            long wellAfter = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS + 60 * NANOS_PER_SECOND;

            // When / Then
            assertThat(CircuitBreakerCore.isWaitDurationExpired(
                    snapshot, WAIT_DURATION_NANOS, wellAfter)).isTrue();
        }

        @Test
        void should_work_with_different_wait_durations() {
            // Given
            var snapshot = openSnapshot();
            long shortWait = Duration.ofSeconds(1).toNanos();
            long longWait = Duration.ofMinutes(5).toNanos();
            long evalTime = snapshot.stateChangedAtNanos() + Duration.ofSeconds(2).toNanos();

            // When / Then — 2s elapsed: short wait (1s) expired, long wait (5min) not expired
            assertThat(CircuitBreakerCore.isWaitDurationExpired(
                    snapshot, shortWait, evalTime)).isTrue();
            assertThat(CircuitBreakerCore.isWaitDurationExpired(
                    snapshot, longWait, evalTime)).isFalse();
        }
    }

    // ======================== currentState ========================

    @Nested
    class CurrentState {

        @Test
        void should_return_closed_for_initial_snapshot() {
            assertThat(CircuitBreakerCore.currentState(closedSnapshot())).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void should_return_open_for_tripped_snapshot() {
            assertThat(CircuitBreakerCore.currentState(openSnapshot())).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void should_return_half_open_for_probing_snapshot() {
            assertThat(CircuitBreakerCore.currentState(halfOpenSnapshot())).isEqualTo(CircuitState.HALF_OPEN);
        }
    }

    // ======================== detectTransition ========================

    @Nested
    class DetectTransition {

        @Test
        void should_return_empty_when_state_did_not_change() {
            // Given
            var before = closedSnapshot();
            var after = CircuitBreakerCore.recordSuccess(
                    before, SUCCESS_THRESHOLD_IN_HALF_OPEN, T0);

            // When
            Optional<StateTransition> transition = CircuitBreakerCore.detectTransition(
                    "test-cb", before, after, T0);

            // Then
            assertThat(transition).isEmpty();
        }

        @Test
        void should_detect_closed_to_open_transition() {
            // Given
            var before = closedSnapshot();
            var after = before;
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                after = CircuitBreakerCore.recordFailure(after, T0);
            }

            // When
            var transition = CircuitBreakerCore.detectTransition("my-cb", before, after, T0);

            // Then
            assertThat(transition).isPresent();
            assertThat(transition.get().name()).isEqualTo("my-cb");
            assertThat(transition.get().fromState()).isEqualTo(CircuitState.CLOSED);
            assertThat(transition.get().toState()).isEqualTo(CircuitState.OPEN);
            assertThat(transition.get().timestampNanos()).isEqualTo(T0);
            assertThat(transition.get().reason()).isNotBlank();
        }

        @Test
        void should_detect_open_to_half_open_transition() {
            // Given
            var before = openSnapshot();
            long afterWait = before.stateChangedAtNanos() + WAIT_DURATION_NANOS;
            var result = CircuitBreakerCore.tryAcquirePermission(
                    before, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait);

            // When
            var transition = CircuitBreakerCore.detectTransition(
                    "my-cb", before, result.snapshot(), afterWait);

            // Then
            assertThat(transition).isPresent();
            assertThat(transition.get().fromState()).isEqualTo(CircuitState.OPEN);
            assertThat(transition.get().toState()).isEqualTo(CircuitState.HALF_OPEN);
        }

        @Test
        void should_detect_half_open_to_closed_transition() {
            // Given
            var before = halfOpenSnapshot();
            long now = before.stateChangedAtNanos() + NANOS_PER_SECOND;
            var after = CircuitBreakerCore.recordSuccess(before, SUCCESS_THRESHOLD_IN_HALF_OPEN, now);
            after = CircuitBreakerCore.recordSuccess(after, SUCCESS_THRESHOLD_IN_HALF_OPEN, now);

            // When
            var transition = CircuitBreakerCore.detectTransition("my-cb", before, after, now);

            // Then
            assertThat(transition).isPresent();
            assertThat(transition.get().fromState()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(transition.get().toState()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void should_detect_half_open_to_open_transition() {
            // Given
            var before = halfOpenSnapshot();
            long now = before.stateChangedAtNanos() + NANOS_PER_SECOND;
            var after = CircuitBreakerCore.recordFailure(before, now);

            // When
            var transition = CircuitBreakerCore.detectTransition("my-cb", before, after, now);

            // Then
            assertThat(transition).isPresent();
            assertThat(transition.get().fromState()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(transition.get().toState()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void should_capture_reason_from_after_snapshot() {
            // Given
            var before = halfOpenSnapshot();
            long now = before.stateChangedAtNanos() + NANOS_PER_SECOND;
            var after = CircuitBreakerCore.recordFailure(before, now);

            // When
            var transition = CircuitBreakerCore.detectTransition("my-cb", before, after, now);

            // Then
            assertThat(transition.get().reason()).isEqualTo(after.transitionReason());
        }
    }

    // ======================== Full State Machine Cycle ========================

    @Nested
    class FullStateMachineCycle {

        @Test
        void should_complete_a_full_closed_open_half_open_closed_cycle() {
            // Given
            var snapshot = closedSnapshot();

            // Step 1: CLOSED → OPEN via 3 consecutive failures
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                snapshot = CircuitBreakerCore.recordFailure(snapshot, T0);
            }
            assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

            // Step 2: OPEN → HALF_OPEN after wait duration
            long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;
            snapshot = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait).snapshot();
            assertThat(snapshot.state()).isEqualTo(CircuitState.HALF_OPEN);

            // Step 3: HALF_OPEN → CLOSED via 2 successes
            long probeTime = afterWait + NANOS_PER_SECOND;
            snapshot = CircuitBreakerCore.recordSuccess(snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, probeTime);
            snapshot = CircuitBreakerCore.recordSuccess(snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, probeTime);
            assertThat(snapshot.state()).isEqualTo(CircuitState.CLOSED);

            // Step 4: calls permitted again
            var finalPermission = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, probeTime);
            assertThat(finalPermission.permitted()).isTrue();
        }

        @Test
        void should_complete_a_closed_open_half_open_open_cycle_on_probe_failure() {
            // Given
            var snapshot = closedSnapshot();

            // Step 1: CLOSED → OPEN
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                snapshot = CircuitBreakerCore.recordFailure(snapshot, T0);
            }
            assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

            // Step 2: OPEN → HALF_OPEN
            long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;
            snapshot = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait).snapshot();
            assertThat(snapshot.state()).isEqualTo(CircuitState.HALF_OPEN);

            // Step 3: HALF_OPEN → OPEN on probe failure
            long probeTime = afterWait + NANOS_PER_SECOND;
            snapshot = CircuitBreakerCore.recordFailure(snapshot, probeTime);
            assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

            // Step 4: calls rejected
            var rejection = CircuitBreakerCore.tryAcquirePermission(
                    snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, probeTime);
            assertThat(rejection.permitted()).isFalse();
        }

        @Test
        void should_survive_multiple_consecutive_trip_and_recovery_cycles() {
            // Given
            var snapshot = closedSnapshot();
            long time = T0;

            for (int cycle = 0; cycle < 5; cycle++) {
                // Trip: CLOSED → OPEN
                for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                    snapshot = CircuitBreakerCore.recordFailure(snapshot, time);
                }
                assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

                // Wait: OPEN → HALF_OPEN
                time = snapshot.stateChangedAtNanos() + WAIT_DURATION_NANOS;
                snapshot = CircuitBreakerCore.tryAcquirePermission(
                        snapshot, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, time).snapshot();
                assertThat(snapshot.state()).isEqualTo(CircuitState.HALF_OPEN);

                // Recover: HALF_OPEN → CLOSED
                time += NANOS_PER_SECOND;
                snapshot = CircuitBreakerCore.recordSuccess(snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, time);
                snapshot = CircuitBreakerCore.recordSuccess(snapshot, SUCCESS_THRESHOLD_IN_HALF_OPEN, time);
                assertThat(snapshot.state()).isEqualTo(CircuitState.CLOSED);
            }
        }
    }

    // ======================== Immutability ========================

    @Nested
    class Immutability {

        @Test
        void should_never_modify_the_input_snapshot_on_failure() {
            // Given
            var original = closedSnapshot();

            // When
            CircuitBreakerCore.recordFailure(original, T0);

            // Then
            assertThat(original.state()).isEqualTo(CircuitState.CLOSED);
            assertThat(((ConsecutiveFailuresMetrics) original.failureMetrics()).consecutiveFailures())
                    .isZero();
        }

        @Test
        void should_never_modify_the_input_snapshot_on_permission() {
            // Given
            var original = openSnapshot();
            long afterWait = original.stateChangedAtNanos() + WAIT_DURATION_NANOS;

            // When
            CircuitBreakerCore.tryAcquirePermission(
                    original, WAIT_DURATION_NANOS, PERMITTED_CALLS_IN_HALF_OPEN, afterWait);

            // Then
            assertThat(original.state()).isEqualTo(CircuitState.OPEN);
            assertThat(original.halfOpenAttempts()).isZero();
        }
    }
}
