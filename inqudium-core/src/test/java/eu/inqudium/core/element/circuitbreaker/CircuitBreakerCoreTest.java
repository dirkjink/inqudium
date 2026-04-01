package eu.inqudium.core.element.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.metrics.GradualDecayMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreakerCore — Functional State Machine")
class CircuitBreakerCoreTest {

  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private static CircuitBreakerConfig defaultConfig() {
    return CircuitBreakerConfig.builder("test")
        .failureThreshold(3)
        .successThresholdInHalfOpen(2)
        .permittedCallsInHalfOpen(3)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .build();
  }

  /**
   * Helper method to create an initial snapshot with the default GradualDecayMetrics.
   */
  private static CircuitBreakerSnapshot initialSnapshot() {
    // Initializing with the new metrics strategy
    return CircuitBreakerSnapshot.initial(NOW, GradualDecayMetrics.initial());
  }

  /**
   * Helper method to extract the failure count from the metrics strategy for assertions.
   */
  private static int getFailureCount(CircuitBreakerSnapshot snapshot) {
    // Casting to the specific implementation to verify internal counts in tests
    return ((GradualDecayMetrics) snapshot.failureMetrics()).failureCount();
  }

  // ================================================================
  // Initial State
  // ================================================================

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("a freshly created snapshot should be in closed state")
    void a_freshly_created_snapshot_should_be_in_closed_state() {
      // Given / When
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // Then
      assertThat(snapshot.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("a freshly created snapshot should have all counters at zero")
    void a_freshly_created_snapshot_should_have_all_counters_at_zero() {
      // Given / When
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // Then
      assertThat(getFailureCount(snapshot)).isZero();
      assertThat(snapshot.successCount()).isZero();
      assertThat(snapshot.halfOpenAttempts()).isZero();
    }

    @Test
    @DisplayName("a freshly created snapshot should record the creation timestamp")
    void a_freshly_created_snapshot_should_record_the_creation_timestamp() {
      // Given / When
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // Then
      assertThat(snapshot.stateChangedAt()).isEqualTo(NOW);
    }
  }

  // ================================================================
  // Permission in CLOSED State
  // ================================================================

  @Nested
  @DisplayName("Permission Acquisition in CLOSED State")
  class PermissionInClosedState {

    @Test
    @DisplayName("should always permit calls when the circuit is closed")
    void should_always_permit_calls_when_the_circuit_is_closed() {
      // Given
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerConfig config = defaultConfig();

      // When
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, NOW);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should not modify the snapshot when permitting in closed state")
    void should_not_modify_the_snapshot_when_permitting_in_closed_state() {
      // Given
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerConfig config = defaultConfig();

      // When
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, NOW);

      // Then
      assertThat(result.snapshot()).isEqualTo(snapshot);
    }
  }

  // ================================================================
  // Failure Recording in CLOSED State
  // ================================================================

  @Nested
  @DisplayName("Failure Recording in CLOSED State")
  class FailureRecordingInClosedState {

    @Test
    @DisplayName("should increment the failure counter on a single failure")
    void should_increment_the_failure_counter_on_a_single_failure() {
      // Given
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerConfig config = defaultConfig();

      // When
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordFailure(snapshot, config, NOW);

      // Then
      assertThat(getFailureCount(updated)).isEqualTo(1);
      assertThat(updated.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should remain closed when failures are below the threshold")
    void should_remain_closed_when_failures_are_below_the_threshold() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // threshold = 3
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // When - record 2 failures (below threshold of 3)
      CircuitBreakerSnapshot afterFirst = CircuitBreakerCore.recordFailure(snapshot, config, NOW);
      CircuitBreakerSnapshot afterSecond = CircuitBreakerCore.recordFailure(afterFirst, config, NOW);

      // Then
      assertThat(afterSecond.state()).isEqualTo(CircuitState.CLOSED);
      assertThat(getFailureCount(afterSecond)).isEqualTo(2);
    }

    @Test
    @DisplayName("should transition to open when the failure threshold is reached")
    void should_transition_to_open_when_the_failure_threshold_is_reached() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // threshold = 3
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // When - record exactly 3 failures
      CircuitBreakerSnapshot current = snapshot;
      for (int i = 0; i < 3; i++) {
        current = CircuitBreakerCore.recordFailure(current, config, NOW);
      }

      // Then
      assertThat(current.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should reset failure metrics when transitioning to open")
    void should_reset_all_counters_when_transitioning_to_open() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // When
      CircuitBreakerSnapshot current = snapshot;
      for (int i = 0; i < 3; i++) {
        current = CircuitBreakerCore.recordFailure(current, config, NOW);
      }

      // Then
      assertThat(getFailureCount(current)).isZero();
      assertThat(current.successCount()).isZero();
      assertThat(current.halfOpenAttempts()).isZero();
    }
  }

  // ================================================================
  // Success Recording in CLOSED State
  // ================================================================

  @Nested
  @DisplayName("Success Recording in CLOSED State")
  class SuccessRecordingInClosedState {

    @Test
    @DisplayName("should decrement the failure counter by one on a successful call")
    void should_decrement_the_failure_counter_by_one_on_a_successful_call() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      // Record 2 failures initially
      CircuitBreakerSnapshot withFailures = CircuitBreakerCore.recordFailure(
          CircuitBreakerCore.recordFailure(snapshot, config, NOW), config, NOW);

      // When
      CircuitBreakerSnapshot afterSuccess = CircuitBreakerCore.recordSuccess(withFailures, config, NOW);

      // Then - Applying gradual decay logic via the metrics strategy
      assertThat(getFailureCount(afterSuccess)).isEqualTo(1);
      assertThat(afterSuccess.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should remain in closed state after a success")
    void should_remain_in_closed_state_after_a_success() {
      // Given
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerConfig config = defaultConfig();

      // When
      CircuitBreakerSnapshot afterSuccess = CircuitBreakerCore.recordSuccess(snapshot, config, NOW);

      // Then
      assertThat(afterSuccess.state()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ================================================================
  // Permission in OPEN State
  // ================================================================

  @Nested
  @DisplayName("Permission Acquisition in OPEN State")
  class PermissionInOpenState {

    private CircuitBreakerSnapshot openSnapshot() {
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerSnapshot current = snapshot;
      for (int i = 0; i < config.failureThreshold(); i++) {
        current = CircuitBreakerCore.recordFailure(current, config, NOW);
      }
      return current;
    }

    @Test
    @DisplayName("should reject calls when the circuit is open and timeout has not expired")
    void should_reject_calls_when_the_circuit_is_open_and_timeout_has_not_expired() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // 30s timeout
      CircuitBreakerSnapshot snapshot = openSnapshot();
      Instant beforeTimeout = NOW.plusSeconds(15);

      // When
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, beforeTimeout);

      // Then
      assertThat(result.permitted()).isFalse();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should transition to half open and permit when the timeout expires")
    void should_transition_to_half_open_and_permit_when_the_timeout_expires() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // 30s timeout
      CircuitBreakerSnapshot snapshot = openSnapshot();
      Instant afterTimeout = NOW.plusSeconds(31);

      // When
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, afterTimeout);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("should transition to half open exactly at the timeout boundary")
    void should_transition_to_half_open_exactly_at_the_timeout_boundary() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // 30s timeout
      CircuitBreakerSnapshot snapshot = openSnapshot();
      Instant exactlyAtTimeout = NOW.plusSeconds(30);

      // When
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, exactlyAtTimeout);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("should count the first probe call as a half open attempt upon transition")
    void should_count_the_first_probe_call_as_a_half_open_attempt_upon_transition() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = openSnapshot();
      Instant afterTimeout = NOW.plusSeconds(31);

      // When
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(snapshot, config, afterTimeout);

      // Then
      assertThat(result.snapshot().halfOpenAttempts()).isEqualTo(1);
    }
  }

  // ================================================================
  // Permission in HALF_OPEN State
  // ================================================================

  @Nested
  @DisplayName("Permission Acquisition in HALF_OPEN State")
  class PermissionInHalfOpenState {

    private CircuitBreakerSnapshot halfOpenSnapshot(CircuitBreakerConfig config) {
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerSnapshot current = snapshot;
      for (int i = 0; i < config.failureThreshold(); i++) {
        current = CircuitBreakerCore.recordFailure(current, config, NOW);
      }
      // Transition to HALF_OPEN
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, NOW.plusSeconds(31));
      return result.snapshot();
    }

    @Test
    @DisplayName("should permit calls up to the configured probe limit")
    void should_permit_calls_up_to_the_configured_probe_limit() {
      // Given - permittedCallsInHalfOpen = 3, first attempt already consumed during transition
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = halfOpenSnapshot(config);

      // When - request 2 more (total 3 including transition probe)
      PermissionResult second = CircuitBreakerCore.tryAcquirePermission(snapshot, config, NOW.plusSeconds(31));
      PermissionResult third = CircuitBreakerCore.tryAcquirePermission(second.snapshot(), config, NOW.plusSeconds(31));

      // Then
      assertThat(second.permitted()).isTrue();
      assertThat(third.permitted()).isTrue();
    }

    @Test
    @DisplayName("should reject calls when the probe limit is exhausted")
    void should_reject_calls_when_the_probe_limit_is_exhausted() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // permittedCallsInHalfOpen = 3
      CircuitBreakerSnapshot snapshot = halfOpenSnapshot(config);

      // When - exhaust remaining 2 slots (1 already used in transition)
      CircuitBreakerSnapshot current = snapshot;
      for (int i = 0; i < 2; i++) {
        PermissionResult perm = CircuitBreakerCore.tryAcquirePermission(current, config, NOW.plusSeconds(31));
        current = perm.snapshot();
      }
      PermissionResult fourth = CircuitBreakerCore.tryAcquirePermission(current, config, NOW.plusSeconds(31));

      // Then
      assertThat(fourth.permitted()).isFalse();
    }
  }

  // ================================================================
  // Success Recording in HALF_OPEN State
  // ================================================================

  @Nested
  @DisplayName("Success Recording in HALF_OPEN State")
  class SuccessRecordingInHalfOpenState {

    private CircuitBreakerSnapshot halfOpenSnapshot(CircuitBreakerConfig config) {
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerSnapshot current = snapshot;
      for (int i = 0; i < config.failureThreshold(); i++) {
        current = CircuitBreakerCore.recordFailure(current, config, NOW);
      }
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, NOW.plusSeconds(31));
      return result.snapshot();
    }

    @Test
    @DisplayName("should increment the success counter on a successful probe call")
    void should_increment_the_success_counter_on_a_successful_probe_call() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // successThresholdInHalfOpen = 2
      CircuitBreakerSnapshot snapshot = halfOpenSnapshot(config);

      // When
      CircuitBreakerSnapshot afterSuccess = CircuitBreakerCore.recordSuccess(snapshot, config, NOW.plusSeconds(31));

      // Then
      assertThat(afterSuccess.successCount()).isEqualTo(1);
      assertThat(afterSuccess.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("should transition to closed when the success threshold is met")
    void should_transition_to_closed_when_the_success_threshold_is_met() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // successThresholdInHalfOpen = 2
      CircuitBreakerSnapshot snapshot = halfOpenSnapshot(config);

      // When - record 2 successes
      Instant later = NOW.plusSeconds(32);
      CircuitBreakerSnapshot first = CircuitBreakerCore.recordSuccess(snapshot, config, later);
      CircuitBreakerSnapshot second = CircuitBreakerCore.recordSuccess(first, config, later);

      // Then
      assertThat(second.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should reset all counters when transitioning back to closed")
    void should_reset_all_counters_when_transitioning_back_to_closed() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = halfOpenSnapshot(config);

      // When
      Instant later = NOW.plusSeconds(32);
      CircuitBreakerSnapshot first = CircuitBreakerCore.recordSuccess(snapshot, config, later);
      CircuitBreakerSnapshot closed = CircuitBreakerCore.recordSuccess(first, config, later);

      // Then
      assertThat(getFailureCount(closed)).isZero();
      assertThat(closed.successCount()).isZero();
      assertThat(closed.halfOpenAttempts()).isZero();
    }
  }

  // ================================================================
  // Failure Recording in HALF_OPEN State
  // ================================================================

  @Nested
  @DisplayName("Failure Recording in HALF_OPEN State")
  class FailureRecordingInHalfOpenState {

    private CircuitBreakerSnapshot halfOpenSnapshot(CircuitBreakerConfig config) {
      CircuitBreakerSnapshot snapshot = initialSnapshot();
      CircuitBreakerSnapshot current = snapshot;
      for (int i = 0; i < config.failureThreshold(); i++) {
        current = CircuitBreakerCore.recordFailure(current, config, NOW);
      }
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, NOW.plusSeconds(31));
      return result.snapshot();
    }

    @Test
    @DisplayName("should immediately transition back to open on any failure")
    void should_immediately_transition_back_to_open_on_any_failure() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = halfOpenSnapshot(config);

      // When
      CircuitBreakerSnapshot afterFailure = CircuitBreakerCore.recordFailure(snapshot, config, NOW.plusSeconds(31));

      // Then
      assertThat(afterFailure.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should transition to open even after prior successes in half open")
    void should_transition_to_open_even_after_prior_successes_in_half_open() {
      // Given - one success recorded, then a failure
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = halfOpenSnapshot(config);
      Instant later = NOW.plusSeconds(31);
      CircuitBreakerSnapshot afterSuccess = CircuitBreakerCore.recordSuccess(snapshot, config, later);

      // When
      CircuitBreakerSnapshot afterFailure = CircuitBreakerCore.recordFailure(afterSuccess, config, later);

      // Then
      assertThat(afterFailure.state()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ================================================================
  // Transition Detection
  // ================================================================

  @Nested
  @DisplayName("State Transition Detection")
  class TransitionDetection {

    @Test
    @DisplayName("should detect a transition when the state has changed")
    void should_detect_a_transition_when_the_state_has_changed() {
      // Given
      CircuitBreakerSnapshot before = initialSnapshot();
      CircuitBreakerSnapshot after =
          before.withState(CircuitState.OPEN, NOW, "Manual intervention or threshold reached in test");

      // When
      Optional<StateTransition> transition = CircuitBreakerCore.detectTransition("test", before, after, NOW);

      // Then
      assertThat(transition.isPresent()).isTrue();
      assertThat(transition.get().fromState()).isEqualTo(CircuitState.CLOSED);
      assertThat(transition.get().toState()).isEqualTo(CircuitState.OPEN);
      assertThat(transition.get().name()).isEqualTo("test");
    }

    @Test
    @DisplayName("should return empty optional when no transition occurred")
    void should_return_empty_optional_when_no_transition_occurred() {
      // Given
      CircuitBreakerSnapshot before = initialSnapshot();
      // Verifying immutability and no-transition when only metrics are updated but state remains same
      CircuitBreakerSnapshot after = before.withUpdatedFailureMetrics(new GradualDecayMetrics(1));

      // When
      Optional<StateTransition> transition = CircuitBreakerCore.detectTransition("test", before, after, NOW);

      // Then
      assertThat(transition.isPresent()).isFalse();
    }
  }

  // ================================================================
  // Wait Duration Check
  // ================================================================

  @Nested
  @DisplayName("Wait Duration Expiry Check")
  class WaitDurationExpiryCheck {

    @Test
    @DisplayName("should report expired when elapsed time exceeds wait duration")
    void should_report_expired_when_elapsed_time_exceeds_wait_duration() {
      // Given
      CircuitBreakerConfig config = defaultConfig(); // 30s wait
      CircuitBreakerSnapshot snapshot = initialSnapshot()
          .withState(CircuitState.OPEN, NOW, "Manual intervention or threshold reached in test");

      // When
      boolean expired = CircuitBreakerCore.isWaitDurationExpired(snapshot, config, NOW.plusSeconds(31));

      // Then
      assertThat(expired).isTrue();
    }

    @Test
    @DisplayName("should report not expired when elapsed time is less than wait duration")
    void should_report_not_expired_when_elapsed_time_is_less_than_wait_duration() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = initialSnapshot()
          .withState(CircuitState.OPEN, NOW, "Manual intervention or threshold reached in test");

      // When
      boolean expired = CircuitBreakerCore.isWaitDurationExpired(snapshot, config, NOW.plusSeconds(10));

      // Then
      assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("should report expired exactly at the boundary")
    void should_report_expired_exactly_at_the_boundary() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot snapshot = initialSnapshot()
          .withState(CircuitState.OPEN, NOW, "Manual intervention or threshold reached in test");

      // When
      boolean expired = CircuitBreakerCore.isWaitDurationExpired(snapshot, config, NOW.plusSeconds(30));

      // Then
      assertThat(expired).isTrue();
    }
  }

  // ================================================================
  // Full Lifecycle
  // ================================================================

  @Nested
  @DisplayName("Full State Machine Lifecycle")
  class FullLifecycle {

    @Test
    @DisplayName("should complete a full cycle from closed through open and half open back to closed")
    void should_complete_a_full_cycle_from_closed_through_open_and_half_open_back_to_closed() {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("lifecycle")
          .failureThreshold(2)
          .successThresholdInHalfOpen(1)
          .permittedCallsInHalfOpen(1)
          .waitDurationInOpenState(Duration.ofSeconds(10))
          .build();
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // When - CLOSED: accumulate failures to reach threshold
      CircuitBreakerSnapshot step1 = CircuitBreakerCore.recordFailure(snapshot, config, NOW);
      assertThat(step1.state()).isEqualTo(CircuitState.CLOSED);

      CircuitBreakerSnapshot step2 = CircuitBreakerCore.recordFailure(step1, config, NOW);
      assertThat(step2.state()).isEqualTo(CircuitState.OPEN);

      // When - OPEN: wait for timeout to expire, then acquire permission
      PermissionResult step3 = CircuitBreakerCore.tryAcquirePermission(step2, config, NOW.plusSeconds(11));
      assertThat(step3.permitted()).isTrue();
      assertThat(step3.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);

      // When - HALF_OPEN: record a success to close the circuit
      CircuitBreakerSnapshot step4 = CircuitBreakerCore.recordSuccess(step3.snapshot(), config, NOW.plusSeconds(12));

      // Then
      assertThat(step4.state()).isEqualTo(CircuitState.CLOSED);
      assertThat(getFailureCount(step4)).isZero();
    }

    @Test
    @DisplayName("should cycle back to open when a probe call fails in half open")
    void should_cycle_back_to_open_when_a_probe_call_fails_in_half_open() {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("retry-lifecycle")
          .failureThreshold(1)
          .successThresholdInHalfOpen(1)
          .permittedCallsInHalfOpen(1)
          .waitDurationInOpenState(Duration.ofSeconds(5))
          .build();
      CircuitBreakerSnapshot snapshot = initialSnapshot();

      // When - open the circuit
      CircuitBreakerSnapshot open = CircuitBreakerCore.recordFailure(snapshot, config, NOW);
      assertThat(open.state()).isEqualTo(CircuitState.OPEN);

      // When - transition to HALF_OPEN
      PermissionResult halfOpen = CircuitBreakerCore.tryAcquirePermission(open, config, NOW.plusSeconds(6));
      assertThat(halfOpen.snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);

      // When - probe fails
      CircuitBreakerSnapshot reopened = CircuitBreakerCore.recordFailure(halfOpen.snapshot(), config, NOW.plusSeconds(7));

      // Then - back to OPEN
      assertThat(reopened.state()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ================================================================
  // Snapshot Immutability
  // ================================================================

  @Nested
  @DisplayName("Snapshot Immutability")
  class SnapshotImmutability {

    @Test
    @DisplayName("should not modify the original snapshot when recording a failure")
    void should_not_modify_the_original_snapshot_when_recording_a_failure() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot original = initialSnapshot();

      // When
      CircuitBreakerCore.recordFailure(original, config, NOW);

      // Then - original is unchanged
      assertThat(getFailureCount(original)).isZero();
      assertThat(original.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should not modify the original snapshot when recording a success")
    void should_not_modify_the_original_snapshot_when_recording_a_success() {
      // Given
      CircuitBreakerConfig config = defaultConfig();
      CircuitBreakerSnapshot original = initialSnapshot()
          .withUpdatedFailureMetrics(new GradualDecayMetrics(2));

      // When
      CircuitBreakerCore.recordSuccess(original, config, NOW);

      // Then - original is unchanged
      assertThat(getFailureCount(original)).isEqualTo(2);
    }
  }
}