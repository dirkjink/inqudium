package eu.inqudium.core.timelimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TimeLimiterCore — Functional Per-Execution State Machine")
class TimeLimiterCoreTest {

  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private static TimeLimiterConfig defaultConfig() {
    return TimeLimiterConfig.builder("test")
        .timeout(Duration.ofSeconds(5))
        .cancelOnTimeout(true)
        .build();
  }

  // ================================================================
  // Initial Snapshot
  // ================================================================

  @Nested
  @DisplayName("Initial Snapshot")
  class InitialSnapshot {

    @Test
    @DisplayName("an idle snapshot should be in IDLE state with no timing information")
    void an_idle_snapshot_should_be_in_idle_state_with_no_timing_information() {
      // Given / When
      ExecutionSnapshot snapshot = ExecutionSnapshot.idle();

      // Then
      assertThat(snapshot.state()).isEqualTo(ExecutionState.IDLE);
      assertThat(snapshot.startTime()).isNull();
      assertThat(snapshot.deadline()).isNull();
      assertThat(snapshot.endTime()).isNull();
      assertThat(snapshot.failure()).isNull();
    }

    @Test
    @DisplayName("an idle snapshot should report zero elapsed time")
    void an_idle_snapshot_should_report_zero_elapsed_time() {
      // Given
      ExecutionSnapshot snapshot = ExecutionSnapshot.idle();

      // When
      Duration elapsed = snapshot.elapsed(NOW);

      // Then
      assertThat(elapsed).isEqualTo(Duration.ZERO);
    }
  }

  // ================================================================
  // Start Transition
  // ================================================================

  @Nested
  @DisplayName("Start Transition (IDLE → RUNNING)")
  class StartTransition {

    @Test
    @DisplayName("should transition to RUNNING state with computed deadline")
    void should_transition_to_running_state_with_computed_deadline() {
      // Given
      TimeLimiterConfig config = defaultConfig(); // 5s timeout

      // When
      ExecutionSnapshot running = TimeLimiterCore.start(config, NOW);

      // Then
      assertThat(running.state()).isEqualTo(ExecutionState.RUNNING);
      assertThat(running.startTime()).isEqualTo(NOW);
      assertThat(running.deadline()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("should have no end time or failure when started")
    void should_have_no_end_time_or_failure_when_started() {
      // Given
      TimeLimiterConfig config = defaultConfig();

      // When
      ExecutionSnapshot running = TimeLimiterCore.start(config, NOW);

      // Then
      assertThat(running.endTime()).isNull();
      assertThat(running.failure()).isNull();
    }

    @Test
    @DisplayName("should calculate the correct deadline for various timeout durations")
    void should_calculate_the_correct_deadline_for_various_timeout_durations() {
      // Given
      TimeLimiterConfig config = TimeLimiterConfig.builder("custom")
          .timeout(Duration.ofMillis(250))
          .build();

      // When
      ExecutionSnapshot running = TimeLimiterCore.start(config, NOW);

      // Then
      assertThat(running.deadline()).isEqualTo(NOW.plusMillis(250));
    }
  }

  // ================================================================
  // Success Recording
  // ================================================================

  @Nested
  @DisplayName("Success Recording (RUNNING → COMPLETED)")
  class SuccessRecording {

    @Test
    @DisplayName("should transition to COMPLETED state with end time")
    void should_transition_to_completed_state_with_end_time() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);
      Instant completedAt = NOW.plusSeconds(2);

      // When
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(running, completedAt);

      // Then
      assertThat(completed.state()).isEqualTo(ExecutionState.COMPLETED);
      assertThat(completed.endTime()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("should preserve the original start time and deadline")
    void should_preserve_the_original_start_time_and_deadline() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(running, NOW.plusSeconds(1));

      // Then
      assertThat(completed.startTime()).isEqualTo(NOW);
      assertThat(completed.deadline()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("should report the correct elapsed duration after completion")
    void should_report_the_correct_elapsed_duration_after_completion() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);
      Instant completedAt = NOW.plusSeconds(3);

      // When
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(running, completedAt);

      // Then
      assertThat(completed.elapsed(completedAt)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("should reject recording success on a non-RUNNING snapshot")
    void should_reject_recording_success_on_a_non_running_snapshot() {
      // Given
      ExecutionSnapshot idle = ExecutionSnapshot.idle();

      // When / Then
      assertThatThrownBy(() -> TimeLimiterCore.recordSuccess(idle, NOW))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("IDLE");
    }
  }

  // ================================================================
  // Failure Recording
  // ================================================================

  @Nested
  @DisplayName("Failure Recording (RUNNING → FAILED)")
  class FailureRecording {

    @Test
    @DisplayName("should transition to FAILED state with the cause")
    void should_transition_to_failed_state_with_the_cause() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);
      RuntimeException cause = new RuntimeException("service error");

      // When
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(running, cause, NOW.plusSeconds(1));

      // Then
      assertThat(failed.state()).isEqualTo(ExecutionState.FAILED);
      assertThat(failed.failure()).isSameAs(cause);
      assertThat(failed.endTime()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("should reject recording failure on a non-RUNNING snapshot")
    void should_reject_recording_failure_on_a_non_running_snapshot() {
      // Given
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(
          TimeLimiterCore.start(defaultConfig(), NOW), NOW.plusSeconds(1));

      // When / Then
      assertThatThrownBy(() -> TimeLimiterCore.recordFailure(
          completed, new RuntimeException(), NOW.plusSeconds(2)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("COMPLETED");
    }
  }

  // ================================================================
  // Timeout Recording
  // ================================================================

  @Nested
  @DisplayName("Timeout Recording (RUNNING → TIMED_OUT)")
  class TimeoutRecording {

    @Test
    @DisplayName("should transition to TIMED_OUT state")
    void should_transition_to_timed_out_state() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);
      Instant timedOutAt = NOW.plusSeconds(6);

      // When
      ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(running, timedOutAt);

      // Then
      assertThat(timedOut.state()).isEqualTo(ExecutionState.TIMED_OUT);
      assertThat(timedOut.endTime()).isEqualTo(timedOutAt);
    }

    @Test
    @DisplayName("should reject recording timeout on a non-RUNNING snapshot")
    void should_reject_recording_timeout_on_a_non_running_snapshot() {
      // Given
      ExecutionSnapshot idle = ExecutionSnapshot.idle();

      // When / Then
      assertThatThrownBy(() -> TimeLimiterCore.recordTimeout(idle, NOW))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ================================================================
  // Cancellation Recording
  // ================================================================

  @Nested
  @DisplayName("Cancellation Recording")
  class CancellationRecording {

    @Test
    @DisplayName("should transition from RUNNING to CANCELLED")
    void should_transition_from_running_to_cancelled() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      ExecutionSnapshot cancelled = TimeLimiterCore.recordCancellation(running, NOW.plusSeconds(5));

      // Then
      assertThat(cancelled.state()).isEqualTo(ExecutionState.CANCELLED);
      assertThat(cancelled.endTime()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("should transition from TIMED_OUT to CANCELLED")
    void should_transition_from_timed_out_to_cancelled() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);
      ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(running, NOW.plusSeconds(6));

      // When
      ExecutionSnapshot cancelled = TimeLimiterCore.recordCancellation(timedOut, NOW.plusSeconds(6));

      // Then
      assertThat(cancelled.state()).isEqualTo(ExecutionState.CANCELLED);
    }

    @Test
    @DisplayName("should reject cancellation on a COMPLETED snapshot")
    void should_reject_cancellation_on_a_completed_snapshot() {
      // Given
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(
          TimeLimiterCore.start(defaultConfig(), NOW), NOW.plusSeconds(1));

      // When / Then
      assertThatThrownBy(() -> TimeLimiterCore.recordCancellation(completed, NOW.plusSeconds(2)))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should reject cancellation on a FAILED snapshot")
    void should_reject_cancellation_on_a_failed_snapshot() {
      // Given
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(
          TimeLimiterCore.start(defaultConfig(), NOW),
          new RuntimeException(), NOW.plusSeconds(1));

      // When / Then
      assertThatThrownBy(() -> TimeLimiterCore.recordCancellation(failed, NOW.plusSeconds(2)))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ================================================================
  // Deadline Checks
  // ================================================================

  @Nested
  @DisplayName("Deadline Checking")
  class DeadlineChecking {

    @Test
    @DisplayName("should report deadline not exceeded when within the timeout")
    void should_report_deadline_not_exceeded_when_within_the_timeout() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW); // 5s timeout

      // When
      boolean exceeded = TimeLimiterCore.isDeadlineExceeded(running, NOW.plusSeconds(3));

      // Then
      assertThat(exceeded).isFalse();
    }

    @Test
    @DisplayName("should report deadline exceeded when past the timeout")
    void should_report_deadline_exceeded_when_past_the_timeout() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      boolean exceeded = TimeLimiterCore.isDeadlineExceeded(running, NOW.plusSeconds(6));

      // Then
      assertThat(exceeded).isTrue();
    }

    @Test
    @DisplayName("should report deadline exceeded exactly at the boundary")
    void should_report_deadline_exceeded_exactly_at_the_boundary() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      boolean exceeded = TimeLimiterCore.isDeadlineExceeded(running, NOW.plusSeconds(5));

      // Then
      assertThat(exceeded).isTrue();
    }

    @Test
    @DisplayName("should report the correct remaining time before the deadline")
    void should_report_the_correct_remaining_time_before_the_deadline() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      Duration remaining = TimeLimiterCore.remainingTime(running, NOW.plusSeconds(2));

      // Then
      assertThat(remaining).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("should report zero remaining time when the deadline has passed")
    void should_report_zero_remaining_time_when_the_deadline_has_passed() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      Duration remaining = TimeLimiterCore.remainingTime(running, NOW.plusSeconds(10));

      // Then
      assertThat(remaining).isEqualTo(Duration.ZERO);
    }
  }

  // ================================================================
  // Elapsed Time
  // ================================================================

  @Nested
  @DisplayName("Elapsed Time Calculation")
  class ElapsedTimeCalculation {

    @Test
    @DisplayName("should report elapsed time while the execution is running")
    void should_report_elapsed_time_while_the_execution_is_running() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      Duration elapsed = TimeLimiterCore.elapsedTime(running, NOW.plusSeconds(3));

      // Then
      assertThat(elapsed).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("should report the final elapsed time after completion")
    void should_report_the_final_elapsed_time_after_completion() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(running, NOW.plusMillis(2500));

      // When — query at a later time should still report the time-of-completion elapsed
      Duration elapsed = TimeLimiterCore.elapsedTime(completed, NOW.plusSeconds(100));

      // Then
      assertThat(elapsed).isEqualTo(Duration.ofMillis(2500));
    }
  }

  // ================================================================
  // Timeout Check Helper
  // ================================================================

  @Nested
  @DisplayName("Timeout Check Helper (checkForTimeout)")
  class TimeoutCheckHelper {

    @Test
    @DisplayName("should return null when the deadline has not been exceeded")
    void should_return_null_when_the_deadline_has_not_been_exceeded() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      ExecutionResult<String> result = TimeLimiterCore.checkForTimeout(running, NOW.plusSeconds(2));

      // Then
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("should return a timeout result when the deadline has been exceeded")
    void should_return_a_timeout_result_when_the_deadline_has_been_exceeded() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      ExecutionResult<String> result = TimeLimiterCore.checkForTimeout(running, NOW.plusSeconds(6));

      // Then
      assertThat(result).isInstanceOf(ExecutionResult.Timeout.class);
      assertThat(result.snapshot().state()).isEqualTo(ExecutionState.TIMED_OUT);
    }

    @Test
    @DisplayName("should return null for a non-RUNNING snapshot even if deadline is exceeded")
    void should_return_null_for_a_non_running_snapshot_even_if_deadline_is_exceeded() {
      // Given
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(
          TimeLimiterCore.start(defaultConfig(), NOW), NOW.plusSeconds(1));

      // When
      ExecutionResult<String> result = TimeLimiterCore.checkForTimeout(completed, NOW.plusSeconds(10));

      // Then
      assertThat(result).isNull();
    }
  }

  // ================================================================
  // Result Construction
  // ================================================================

  @Nested
  @DisplayName("Result Construction Helpers")
  class ResultConstruction {

    @Test
    @DisplayName("should create a success result with the value and timing")
    void should_create_a_success_result_with_the_value_and_timing() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      ExecutionResult<String> result = TimeLimiterCore.toSuccess("hello", running, NOW.plusSeconds(1));

      // Then
      assertThat(result).isInstanceOf(ExecutionResult.Success.class);
      assertThat(((ExecutionResult.Success<String>) result).value()).isEqualTo("hello");
      assertThat(result.elapsed()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("should create a failure result with the cause and timing")
    void should_create_a_failure_result_with_the_cause_and_timing() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);
      RuntimeException cause = new RuntimeException("oops");

      // When
      ExecutionResult<String> result = TimeLimiterCore.toFailure(cause, running, NOW.plusMillis(500));

      // Then
      assertThat(result).isInstanceOf(ExecutionResult.Failure.class);
      assertThat(((ExecutionResult.Failure<String>) result).cause()).isSameAs(cause);
    }

    @Test
    @DisplayName("should create a timeout result with timing")
    void should_create_a_timeout_result_with_timing() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      ExecutionResult<String> result = TimeLimiterCore.toTimeout(running, NOW.plusSeconds(5));

      // Then
      assertThat(result).isInstanceOf(ExecutionResult.Timeout.class);
      assertThat(result.snapshot().state()).isEqualTo(ExecutionState.TIMED_OUT);
    }

    @Test
    @DisplayName("should create a cancelled result with timing")
    void should_create_a_cancelled_result_with_timing() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      ExecutionResult<String> result = TimeLimiterCore.toCancelled(running, NOW.plusSeconds(5));

      // Then
      assertThat(result).isInstanceOf(ExecutionResult.Cancelled.class);
      assertThat(result.snapshot().state()).isEqualTo(ExecutionState.CANCELLED);
    }
  }

  // ================================================================
  // ExecutionState
  // ================================================================

  @Nested
  @DisplayName("ExecutionState Properties")
  class ExecutionStateProperties {

    @Test
    @DisplayName("should identify IDLE and RUNNING as non-terminal states")
    void should_identify_idle_and_running_as_non_terminal_states() {
      // Given / When / Then
      assertThat(ExecutionState.IDLE.isTerminal()).isFalse();
      assertThat(ExecutionState.RUNNING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("should identify COMPLETED, FAILED, TIMED_OUT, CANCELLED as terminal states")
    void should_identify_completed_failed_timed_out_cancelled_as_terminal_states() {
      // Given / When / Then
      assertThat(ExecutionState.COMPLETED.isTerminal()).isTrue();
      assertThat(ExecutionState.FAILED.isTerminal()).isTrue();
      assertThat(ExecutionState.TIMED_OUT.isTerminal()).isTrue();
      assertThat(ExecutionState.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("should identify only COMPLETED as a success state")
    void should_identify_only_completed_as_a_success_state() {
      // Given / When / Then
      assertThat(ExecutionState.COMPLETED.isSuccess()).isTrue();
      assertThat(ExecutionState.FAILED.isSuccess()).isFalse();
      assertThat(ExecutionState.TIMED_OUT.isSuccess()).isFalse();
      assertThat(ExecutionState.CANCELLED.isSuccess()).isFalse();
    }
  }

  // ================================================================
  // Snapshot Immutability
  // ================================================================

  @Nested
  @DisplayName("Snapshot Immutability")
  class SnapshotImmutability {

    @Test
    @DisplayName("should not modify the original snapshot when recording success")
    void should_not_modify_the_original_snapshot_when_recording_success() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      TimeLimiterCore.recordSuccess(running, NOW.plusSeconds(1));

      // Then
      assertThat(running.state()).isEqualTo(ExecutionState.RUNNING);
      assertThat(running.endTime()).isNull();
    }

    @Test
    @DisplayName("should not modify the original snapshot when recording failure")
    void should_not_modify_the_original_snapshot_when_recording_failure() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      TimeLimiterCore.recordFailure(running, new RuntimeException(), NOW.plusSeconds(1));

      // Then
      assertThat(running.state()).isEqualTo(ExecutionState.RUNNING);
      assertThat(running.failure()).isNull();
    }

    @Test
    @DisplayName("should not modify the original snapshot when recording timeout")
    void should_not_modify_the_original_snapshot_when_recording_timeout() {
      // Given
      ExecutionSnapshot running = TimeLimiterCore.start(defaultConfig(), NOW);

      // When
      TimeLimiterCore.recordTimeout(running, NOW.plusSeconds(6));

      // Then
      assertThat(running.state()).isEqualTo(ExecutionState.RUNNING);
    }
  }

  // ================================================================
  // Full Lifecycle
  // ================================================================

  @Nested
  @DisplayName("Full Execution Lifecycle")
  class FullLifecycle {

    @Test
    @DisplayName("should complete a successful lifecycle from IDLE through RUNNING to COMPLETED")
    void should_complete_a_successful_lifecycle_from_idle_through_running_to_completed() {
      // Given
      TimeLimiterConfig config = defaultConfig();

      // When
      ExecutionSnapshot running = TimeLimiterCore.start(config, NOW);
      assertThat(running.state()).isEqualTo(ExecutionState.RUNNING);
      assertThat(TimeLimiterCore.isDeadlineExceeded(running, NOW.plusSeconds(2))).isFalse();

      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(running, NOW.plusSeconds(2));

      // Then
      assertThat(completed.state()).isEqualTo(ExecutionState.COMPLETED);
      assertThat(completed.state().isTerminal()).isTrue();
      assertThat(completed.state().isSuccess()).isTrue();
      assertThat(completed.elapsed(NOW.plusSeconds(2))).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should complete a timeout lifecycle from RUNNING through TIMED_OUT to CANCELLED")
    void should_complete_a_timeout_lifecycle_from_running_through_timed_out_to_cancelled() {
      // Given
      TimeLimiterConfig config = defaultConfig();

      // When
      ExecutionSnapshot running = TimeLimiterCore.start(config, NOW);
      assertThat(TimeLimiterCore.isDeadlineExceeded(running, NOW.plusSeconds(6))).isTrue();

      ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(running, NOW.plusSeconds(6));
      ExecutionSnapshot cancelled = TimeLimiterCore.recordCancellation(timedOut, NOW.plusSeconds(6));

      // Then
      assertThat(cancelled.state()).isEqualTo(ExecutionState.CANCELLED);
      assertThat(cancelled.state().isTerminal()).isTrue();
      assertThat(cancelled.state().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("should complete a failure lifecycle from RUNNING to FAILED")
    void should_complete_a_failure_lifecycle_from_running_to_failed() {
      // Given
      TimeLimiterConfig config = defaultConfig();
      RuntimeException error = new RuntimeException("connection refused");

      // When
      ExecutionSnapshot running = TimeLimiterCore.start(config, NOW);
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(running, error, NOW.plusMillis(100));

      // Then
      assertThat(failed.state()).isEqualTo(ExecutionState.FAILED);
      assertThat(failed.failure()).isSameAs(error);
      assertThat(failed.elapsed(NOW.plusMillis(100))).isEqualTo(Duration.ofMillis(100));
    }
  }

  // ================================================================
  // Configuration Validation
  // ================================================================

  @Nested
  @DisplayName("Configuration Validation")
  class ConfigurationValidation {

    @Test
    @DisplayName("should reject a zero timeout")
    void should_reject_a_zero_timeout() {
      assertThatThrownBy(() -> TimeLimiterConfig.builder("bad")
          .timeout(Duration.ZERO)
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a negative timeout")
    void should_reject_a_negative_timeout() {
      assertThatThrownBy(() -> TimeLimiterConfig.builder("bad")
          .timeout(Duration.ofSeconds(-1))
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a null name")
    void should_reject_a_null_name() {
      assertThatThrownBy(() -> TimeLimiterConfig.builder(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should create a timeout exception with the configured name and duration")
    void should_create_a_timeout_exception_with_the_configured_name_and_duration() {
      // Given
      TimeLimiterConfig config = defaultConfig();

      // When
      RuntimeException exception = config.createTimeoutException();

      // Then
      assertThat(exception).isInstanceOf(TimeLimiterException.class);
      TimeLimiterException tle = (TimeLimiterException) exception;
      assertThat(tle.getTimeLimiterName()).isEqualTo("test");
      assertThat(tle.getTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("should use a custom exception factory when configured")
    void should_use_a_custom_exception_factory_when_configured() {
      // Given
      TimeLimiterConfig config = TimeLimiterConfig.builder("custom")
          .timeout(Duration.ofSeconds(3))
          .exceptionFactory((name, duration) ->
              new IllegalStateException("Limiter '" + name + "' custom timeout: " + duration.toMillis() + "ms"))
          .build();

      // When
      RuntimeException exception = config.createTimeoutException();

      // Then
      assertThat(exception).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("custom timeout: 3000");
    }
  }
}
