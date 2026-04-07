package eu.inqudium.core.element.circuitbreaker;

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
 * <p>All methods under test are static and side-effect-free. No concurrency,
 * no I/O — only immutable snapshot transformations. Time is controlled
 * deterministically via explicit nanosecond timestamps.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CircuitBreakerCoreTest {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final long T0 = 100 * NANOS_PER_SECOND;
  private static final Duration WAIT_DURATION = Duration.ofSeconds(30);

  // ======================== Test helpers ========================

  /**
   * Creates a config using ConsecutiveFailuresMetrics for simplicity.
   * The failureThreshold is interpreted as absolute consecutive failures.
   */
  private CircuitBreakerConfig consecutiveConfig(
      int failureThreshold,
      int successThresholdInHalfOpen,
      int permittedCallsInHalfOpen) {

    return CircuitBreakerConfig.builder("test-cb")
        .failureThreshold(failureThreshold)
        .successThresholdInHalfOpen(successThresholdInHalfOpen)
        .permittedCallsInHalfOpen(permittedCallsInHalfOpen)
        .waitDurationInOpenState(WAIT_DURATION)
        .metricsStrategy(nowNanos -> ConsecutiveFailuresMetrics.initial(failureThreshold))
        .build();
  }

  /** Shorthand: threshold=3, successThreshold=2, permitted=3 */
  private CircuitBreakerConfig defaultConfig() {
    return consecutiveConfig(3, 2, 3);
  }

  /** Creates a fresh CLOSED snapshot with the given config's metrics. */
  private CircuitBreakerSnapshot closedSnapshot(CircuitBreakerConfig config) {
    FailureMetrics metrics = config.metricsFactory().apply(T0);
    return CircuitBreakerSnapshot.initial(T0, metrics);
  }

  /** Creates a snapshot in OPEN state by recording enough failures. */
  private CircuitBreakerSnapshot openSnapshot(CircuitBreakerConfig config) {
    var snapshot = closedSnapshot(config);
    for (int i = 0; i < config.failureThreshold(); i++) {
      snapshot = CircuitBreakerCore.recordFailure(snapshot, config, T0);
    }
    assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);
    return snapshot;
  }

  /** Creates a snapshot in HALF_OPEN state by expiring the wait duration. */
  private CircuitBreakerSnapshot halfOpenSnapshot(CircuitBreakerConfig config) {
    var open = openSnapshot(config);
    long afterWait = open.stateChangedAtNanos() + WAIT_DURATION.toNanos() + NANOS_PER_SECOND;
    var result = CircuitBreakerCore.tryAcquirePermission(open, config, afterWait);
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
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, T0);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_return_the_same_snapshot_in_closed_state() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, T0);

      // Then — snapshot is unchanged (no state transition)
      assertThat(result.snapshot()).isSameAs(snapshot);
    }

    @Test
    void should_permit_calls_regardless_of_how_much_time_has_passed() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When — far in the future
      long farFuture = T0 + 1000 * NANOS_PER_SECOND;
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, farFuture);

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
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When — only half the wait duration has passed
      long beforeExpiry = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos() / 2;
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, beforeExpiry);

      // Then
      assertThat(result.permitted()).isFalse();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_reject_calls_exactly_one_nano_before_wait_duration_expires() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When — exactly 1 nanosecond before expiry
      long justBefore = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos() - 1;
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, justBefore);

      // Then
      assertThat(result.permitted()).isFalse();
    }

    @Test
    void should_transition_to_half_open_when_wait_duration_expires_exactly() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When — exactly at expiry
      long exactlyAtExpiry = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, exactlyAtExpiry);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    void should_transition_to_half_open_when_wait_duration_is_well_past() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      long wellPast = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos() + 60 * NANOS_PER_SECOND;
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, wellPast);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    void should_set_half_open_attempts_to_one_after_transition() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, afterWait);

      // Then — the first probe call consumes one attempt slot
      assertThat(result.snapshot().halfOpenAttempts()).isEqualTo(1);
    }

    @Test
    void should_reset_success_count_to_zero_on_transition_to_half_open() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, afterWait);

      // Then
      assertThat(result.snapshot().successCount()).isZero();
    }

    @Test
    void should_include_wait_duration_in_transition_reason() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, afterWait);

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
      // Given — permittedCallsInHalfOpen=3, halfOpenAttempts already 1 from transition
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);

      // When — acquire 2 more (already 1 from transition)
      var result1 = CircuitBreakerCore.tryAcquirePermission(snapshot, config, T0 + 50 * NANOS_PER_SECOND);
      assertThat(result1.permitted()).isTrue();

      var result2 = CircuitBreakerCore.tryAcquirePermission(result1.snapshot(), config, T0 + 50 * NANOS_PER_SECOND);
      assertThat(result2.permitted()).isTrue();

      // Then — attempts exhausted
      var result3 = CircuitBreakerCore.tryAcquirePermission(result2.snapshot(), config, T0 + 50 * NANOS_PER_SECOND);
      assertThat(result3.permitted()).isFalse();
    }

    @Test
    void should_increment_half_open_attempts_on_each_permitted_call() {
      // Given
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      assertThat(snapshot.halfOpenAttempts()).isEqualTo(1); // from transition

      // When
      var result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, T0 + 50 * NANOS_PER_SECOND);

      // Then
      assertThat(result.snapshot().halfOpenAttempts()).isEqualTo(2);
    }

    @Test
    void should_reject_when_all_slots_are_consumed() {
      // Given — permittedCallsInHalfOpen=3
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      // Manually set attempts to max
      var fullSlots = new CircuitBreakerSnapshot(
          CircuitState.HALF_OPEN, snapshot.failureMetrics(), 0, 3,
          snapshot.stateChangedAtNanos(), "test");

      // When
      var result = CircuitBreakerCore.tryAcquirePermission(fullSlots, config, T0 + 50 * NANOS_PER_SECOND);

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
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When
      var updated = CircuitBreakerCore.recordSuccess(snapshot, config, T0);

      // Then
      assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
      assertThat(updated.failureMetrics()).isNotSameAs(snapshot.failureMetrics());
    }

    @Test
    void should_remain_closed_after_many_successes() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When
      var updated = snapshot;
      for (int i = 0; i < 100; i++) {
        updated = CircuitBreakerCore.recordSuccess(updated, config, T0);
      }

      // Then
      assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_trip_if_success_causes_minimum_calls_to_be_met_with_high_failure_rate() {
      // Given — SlidingWindow: threshold=3, window=10, min=5
      //   record 4 failures first (min not met), then 1 success meets min with 80% failure rate
      var config = CircuitBreakerConfig.builder("trip-on-success-cb")
          .failureThreshold(3)
          .successThresholdInHalfOpen(2)
          .permittedCallsInHalfOpen(3)
          .waitDurationInOpenState(WAIT_DURATION)
          .metricsStrategy(nowNanos -> SlidingWindowMetrics.initial(3, 10, 5))
          .build();
      var snapshot = closedSnapshot(config);

      // When — 4 failures + 1 success = 5 calls (min met), 4 failures >= threshold 3
      var updated = snapshot;
      for (int i = 0; i < 4; i++) {
        updated = CircuitBreakerCore.recordFailure(updated, config, T0);
      }
      assertThat(updated.state()).isEqualTo(CircuitState.CLOSED); // min not met yet
      updated = CircuitBreakerCore.recordSuccess(updated, config, T0);

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
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      assertThat(snapshot.successCount()).isZero();

      // When
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;
      var updated = CircuitBreakerCore.recordSuccess(snapshot, config, now);

      // Then
      assertThat(updated.successCount()).isEqualTo(1);
      assertThat(updated.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    void should_transition_to_closed_when_success_threshold_is_met() {
      // Given — successThresholdInHalfOpen=2
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

      // When — 2 successes
      var after1 = CircuitBreakerCore.recordSuccess(snapshot, config, now);
      var after2 = CircuitBreakerCore.recordSuccess(after1, config, now);

      // Then
      assertThat(after2.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_not_close_with_one_fewer_success_than_threshold() {
      // Given — successThresholdInHalfOpen=2
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

      // When — only 1 success
      var after1 = CircuitBreakerCore.recordSuccess(snapshot, config, now);

      // Then
      assertThat(after1.state()).isEqualTo(CircuitState.HALF_OPEN);
      assertThat(after1.successCount()).isEqualTo(1);
    }

    @Test
    void should_reset_metrics_when_transitioning_to_closed() {
      // Given
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

      // When
      var closed = CircuitBreakerCore.recordSuccess(
          CircuitBreakerCore.recordSuccess(snapshot, config, now),
          config, now);

      // Then — successCount and halfOpenAttempts should be reset
      assertThat(closed.successCount()).isZero();
      assertThat(closed.halfOpenAttempts()).isZero();
    }

    @Test
    void should_include_success_threshold_in_transition_reason() {
      // Given
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

      // When
      var closed = CircuitBreakerCore.recordSuccess(
          CircuitBreakerCore.recordSuccess(snapshot, config, now),
          config, now);

      // Then
      assertThat(closed.transitionReason())
          .containsIgnoringCase("success threshold")
          .contains("2");
    }
  }

  // ======================== recordSuccess — OPEN ========================

  @Nested
  class RecordSuccess_Open {

    @Test
    void should_be_a_no_op_in_open_state() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      var updated = CircuitBreakerCore.recordSuccess(snapshot, config, T0);

      // Then — exact same instance
      assertThat(updated).isSameAs(snapshot);
    }
  }

  // ======================== recordFailure — CLOSED ========================

  @Nested
  class RecordFailure_Closed {

    @Test
    void should_update_metrics_without_state_change_below_threshold() {
      // Given — threshold=3
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When — 2 failures
      var updated = CircuitBreakerCore.recordFailure(snapshot, config, T0);
      updated = CircuitBreakerCore.recordFailure(updated, config, T0);

      // Then
      assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_transition_to_open_when_failure_threshold_is_reached() {
      // Given — threshold=3 consecutive
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When — 3 consecutive failures
      var updated = snapshot;
      for (int i = 0; i < 3; i++) {
        updated = CircuitBreakerCore.recordFailure(updated, config, T0);
      }

      // Then
      assertThat(updated.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_reset_success_count_and_attempts_on_transition_to_open() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When
      var updated = snapshot;
      for (int i = 0; i < 3; i++) {
        updated = CircuitBreakerCore.recordFailure(updated, config, T0);
      }

      // Then
      assertThat(updated.successCount()).isZero();
      assertThat(updated.halfOpenAttempts()).isZero();
    }

    @Test
    void should_set_state_changed_timestamp_on_transition() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When — trip at a specific timestamp
      long tripTime = T0 + 5 * NANOS_PER_SECOND;
      var updated = snapshot;
      for (int i = 0; i < 3; i++) {
        updated = CircuitBreakerCore.recordFailure(updated, config, tripTime);
      }

      // Then
      assertThat(updated.stateChangedAtNanos()).isEqualTo(tripTime);
    }

    @Test
    void should_include_metrics_trip_reason_in_transition() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When
      var updated = snapshot;
      for (int i = 0; i < 3; i++) {
        updated = CircuitBreakerCore.recordFailure(updated, config, T0);
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
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);

      // When — single failure
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;
      var updated = CircuitBreakerCore.recordFailure(snapshot, config, now);

      // Then
      assertThat(updated.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_set_transition_reason_to_probe_failure() {
      // Given
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);

      // When
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;
      var updated = CircuitBreakerCore.recordFailure(snapshot, config, now);

      // Then
      assertThat(updated.transitionReason())
          .containsIgnoringCase("probe")
          .containsIgnoringCase("failed")
          .containsIgnoringCase("HALF_OPEN");
    }

    @Test
    void should_update_state_changed_timestamp() {
      // Given
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      long failureTime = snapshot.stateChangedAtNanos() + 2 * NANOS_PER_SECOND;

      // When
      var updated = CircuitBreakerCore.recordFailure(snapshot, config, failureTime);

      // Then
      assertThat(updated.stateChangedAtNanos()).isEqualTo(failureTime);
    }

    @Test
    void should_reset_counters_on_transition_back_to_open() {
      // Given — snapshot with 1 success already
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;
      var withOneSuccess = CircuitBreakerCore.recordSuccess(snapshot, config, now);
      assertThat(withOneSuccess.successCount()).isEqualTo(1);

      // When — failure after the success
      var reopened = CircuitBreakerCore.recordFailure(withOneSuccess, config, now);

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
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      var updated = CircuitBreakerCore.recordFailure(snapshot, config, T0);

      // Then
      assertThat(updated).isSameAs(snapshot);
    }
  }

  // ======================== recordIgnored ========================

  @Nested
  class RecordIgnored {

    @Test
    void should_decrement_half_open_attempts_in_half_open_state() {
      // Given — halfOpenAttempts=1 from transition
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config);
      assertThat(snapshot.halfOpenAttempts()).isEqualTo(1);

      // When
      var updated = CircuitBreakerCore.recordIgnored(snapshot);

      // Then — attempt slot released
      assertThat(updated.halfOpenAttempts()).isZero();
    }

    @Test
    void should_not_decrement_below_zero() {
      // Given — manually create snapshot with 0 attempts
      var config = defaultConfig();
      var metrics = config.metricsFactory().apply(T0);
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
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // When
      var updated = CircuitBreakerCore.recordIgnored(snapshot);

      // Then
      assertThat(updated).isSameAs(snapshot);
    }

    @Test
    void should_be_a_no_op_in_open_state() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      var updated = CircuitBreakerCore.recordIgnored(snapshot);

      // Then
      assertThat(updated).isSameAs(snapshot);
    }

    @Test
    void should_free_slot_for_subsequent_probe_call() {
      // Given — 3 permitted, already 3 consumed → rejected
      var config = defaultConfig();
      var snapshot = halfOpenSnapshot(config); // attempts=1
      long now = snapshot.stateChangedAtNanos() + NANOS_PER_SECOND;

      var with2 = CircuitBreakerCore.tryAcquirePermission(snapshot, config, now).snapshot();  // attempts=2
      var with3 = CircuitBreakerCore.tryAcquirePermission(with2, config, now).snapshot();     // attempts=3
      assertThat(CircuitBreakerCore.tryAcquirePermission(with3, config, now).permitted()).isFalse();

      // When — one ignored call releases a slot
      var afterIgnored = CircuitBreakerCore.recordIgnored(with3);

      // Then — one slot is free again
      assertThat(CircuitBreakerCore.tryAcquirePermission(afterIgnored, config, now).permitted()).isTrue();
    }
  }

  // ======================== isWaitDurationExpired ========================

  @Nested
  class IsWaitDurationExpired {

    @Test
    void should_return_false_when_no_time_has_passed() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      boolean expired = CircuitBreakerCore.isWaitDurationExpired(
          snapshot, config, snapshot.stateChangedAtNanos());

      // Then
      assertThat(expired).isFalse();
    }

    @Test
    void should_return_false_one_nano_before_expiry() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      long justBefore = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos() - 1;
      boolean expired = CircuitBreakerCore.isWaitDurationExpired(snapshot, config, justBefore);

      // Then
      assertThat(expired).isFalse();
    }

    @Test
    void should_return_true_exactly_at_expiry() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      long exactlyAt = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      boolean expired = CircuitBreakerCore.isWaitDurationExpired(snapshot, config, exactlyAt);

      // Then
      assertThat(expired).isTrue();
    }

    @Test
    void should_return_true_well_after_expiry() {
      // Given
      var config = defaultConfig();
      var snapshot = openSnapshot(config);

      // When
      long wellAfter = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos() + 60 * NANOS_PER_SECOND;
      boolean expired = CircuitBreakerCore.isWaitDurationExpired(snapshot, config, wellAfter);

      // Then
      assertThat(expired).isTrue();
    }
  }

  // ======================== currentState ========================

  @Nested
  class CurrentState {

    @Test
    void should_return_closed_for_initial_snapshot() {
      // Given
      var snapshot = closedSnapshot(defaultConfig());

      // When / Then
      assertThat(CircuitBreakerCore.currentState(snapshot)).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_return_open_for_tripped_snapshot() {
      // Given
      var snapshot = openSnapshot(defaultConfig());

      // When / Then
      assertThat(CircuitBreakerCore.currentState(snapshot)).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_return_half_open_for_probing_snapshot() {
      // Given
      var snapshot = halfOpenSnapshot(defaultConfig());

      // When / Then
      assertThat(CircuitBreakerCore.currentState(snapshot)).isEqualTo(CircuitState.HALF_OPEN);
    }
  }

  // ======================== detectTransition ========================

  @Nested
  class DetectTransition {

    @Test
    void should_return_empty_when_state_did_not_change() {
      // Given
      var config = defaultConfig();
      var before = closedSnapshot(config);
      var after = CircuitBreakerCore.recordSuccess(before, config, T0);

      // When
      Optional<StateTransition> transition = CircuitBreakerCore.detectTransition(
          "test-cb", before, after, T0);

      // Then
      assertThat(transition).isEmpty();
    }

    @Test
    void should_detect_closed_to_open_transition() {
      // Given
      var config = defaultConfig();
      var before = closedSnapshot(config);
      var after = before;
      for (int i = 0; i < 3; i++) {
        after = CircuitBreakerCore.recordFailure(after, config, T0);
      }

      // When
      Optional<StateTransition> transition = CircuitBreakerCore.detectTransition(
          "my-cb", before, after, T0);

      // Then
      assertThat(transition).isPresent();
      var t = transition.get();
      assertThat(t.name()).isEqualTo("my-cb");
      assertThat(t.fromState()).isEqualTo(CircuitState.CLOSED);
      assertThat(t.toState()).isEqualTo(CircuitState.OPEN);
      assertThat(t.timestampNanos()).isEqualTo(T0);
      assertThat(t.reason()).isNotBlank();
    }

    @Test
    void should_detect_open_to_half_open_transition() {
      // Given
      var config = defaultConfig();
      var before = openSnapshot(config);
      long afterWait = before.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      var result = CircuitBreakerCore.tryAcquirePermission(before, config, afterWait);

      // When
      Optional<StateTransition> transition = CircuitBreakerCore.detectTransition(
          "my-cb", before, result.snapshot(), afterWait);

      // Then
      assertThat(transition).isPresent();
      assertThat(transition.get().fromState()).isEqualTo(CircuitState.OPEN);
      assertThat(transition.get().toState()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    void should_detect_half_open_to_closed_transition() {
      // Given
      var config = defaultConfig();
      var before = halfOpenSnapshot(config);
      long now = before.stateChangedAtNanos() + NANOS_PER_SECOND;
      var after = CircuitBreakerCore.recordSuccess(before, config, now);
      after = CircuitBreakerCore.recordSuccess(after, config, now);

      // When
      Optional<StateTransition> transition = CircuitBreakerCore.detectTransition(
          "my-cb", before, after, now);

      // Then
      assertThat(transition).isPresent();
      assertThat(transition.get().fromState()).isEqualTo(CircuitState.HALF_OPEN);
      assertThat(transition.get().toState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_detect_half_open_to_open_transition() {
      // Given
      var config = defaultConfig();
      var before = halfOpenSnapshot(config);
      long now = before.stateChangedAtNanos() + NANOS_PER_SECOND;
      var after = CircuitBreakerCore.recordFailure(before, config, now);

      // When
      Optional<StateTransition> transition = CircuitBreakerCore.detectTransition(
          "my-cb", before, after, now);

      // Then
      assertThat(transition).isPresent();
      assertThat(transition.get().fromState()).isEqualTo(CircuitState.HALF_OPEN);
      assertThat(transition.get().toState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_capture_reason_from_after_snapshot() {
      // Given
      var config = defaultConfig();
      var before = halfOpenSnapshot(config);
      long now = before.stateChangedAtNanos() + NANOS_PER_SECOND;
      var after = CircuitBreakerCore.recordFailure(before, config, now);

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
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // Step 1: CLOSED → OPEN via 3 consecutive failures
      for (int i = 0; i < 3; i++) {
        snapshot = CircuitBreakerCore.recordFailure(snapshot, config, T0);
      }
      assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

      // Step 2: OPEN → HALF_OPEN after wait duration
      long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      var permission = CircuitBreakerCore.tryAcquirePermission(snapshot, config, afterWait);
      snapshot = permission.snapshot();
      assertThat(snapshot.state()).isEqualTo(CircuitState.HALF_OPEN);

      // Step 3: HALF_OPEN → CLOSED via 2 successes
      long probeTime = afterWait + NANOS_PER_SECOND;
      snapshot = CircuitBreakerCore.recordSuccess(snapshot, config, probeTime);
      snapshot = CircuitBreakerCore.recordSuccess(snapshot, config, probeTime);
      assertThat(snapshot.state()).isEqualTo(CircuitState.CLOSED);

      // Step 4: CLOSED — calls permitted again
      var finalPermission = CircuitBreakerCore.tryAcquirePermission(snapshot, config, probeTime);
      assertThat(finalPermission.permitted()).isTrue();
    }

    @Test
    void should_complete_a_closed_open_half_open_open_cycle_on_probe_failure() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);

      // Step 1: CLOSED → OPEN
      for (int i = 0; i < 3; i++) {
        snapshot = CircuitBreakerCore.recordFailure(snapshot, config, T0);
      }
      assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

      // Step 2: OPEN → HALF_OPEN
      long afterWait = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
      snapshot = CircuitBreakerCore.tryAcquirePermission(snapshot, config, afterWait).snapshot();
      assertThat(snapshot.state()).isEqualTo(CircuitState.HALF_OPEN);

      // Step 3: HALF_OPEN → OPEN on probe failure
      long probeTime = afterWait + NANOS_PER_SECOND;
      snapshot = CircuitBreakerCore.recordFailure(snapshot, config, probeTime);
      assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

      // Step 4: still in OPEN — calls rejected
      var rejection = CircuitBreakerCore.tryAcquirePermission(snapshot, config, probeTime);
      assertThat(rejection.permitted()).isFalse();
    }

    @Test
    void should_survive_multiple_consecutive_trip_and_recovery_cycles() {
      // Given
      var config = defaultConfig();
      var snapshot = closedSnapshot(config);
      long time = T0;

      for (int cycle = 0; cycle < 5; cycle++) {
        // Trip: CLOSED → OPEN
        for (int i = 0; i < 3; i++) {
          snapshot = CircuitBreakerCore.recordFailure(snapshot, config, time);
        }
        assertThat(snapshot.state()).isEqualTo(CircuitState.OPEN);

        // Wait: OPEN → HALF_OPEN
        time = snapshot.stateChangedAtNanos() + WAIT_DURATION.toNanos();
        snapshot = CircuitBreakerCore.tryAcquirePermission(snapshot, config, time).snapshot();
        assertThat(snapshot.state()).isEqualTo(CircuitState.HALF_OPEN);

        // Recover: HALF_OPEN → CLOSED
        time += NANOS_PER_SECOND;
        snapshot = CircuitBreakerCore.recordSuccess(snapshot, config, time);
        snapshot = CircuitBreakerCore.recordSuccess(snapshot, config, time);
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
      var config = defaultConfig();
      var original = closedSnapshot(config);

      // When
      CircuitBreakerCore.recordFailure(original, config, T0);

      // Then — original is untouched
      assertThat(original.state()).isEqualTo(CircuitState.CLOSED);
      assertThat(original.failureMetrics())
          .isInstanceOf(ConsecutiveFailuresMetrics.class);
      assertThat(((ConsecutiveFailuresMetrics) original.failureMetrics()).consecutiveFailures())
          .isZero();
    }

    @Test
    void should_never_modify_the_input_snapshot_on_permission() {
      // Given
      var config = defaultConfig();
      var original = openSnapshot(config);
      long afterWait = original.stateChangedAtNanos() + WAIT_DURATION.toNanos();

      // When
      CircuitBreakerCore.tryAcquirePermission(original, config, afterWait);

      // Then — original still OPEN
      assertThat(original.state()).isEqualTo(CircuitState.OPEN);
      assertThat(original.halfOpenAttempts()).isZero();
    }
  }
}
