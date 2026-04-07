package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConsecutiveFailuresMetricsTest {

  private static final long NOW = 1_000_000_000L;
  private static final long LATER = 2_000_000_000L;

  // ======================== Factory / Initial State ========================

  @Nested
  class InitialState {

    @Test
    void should_start_with_zero_consecutive_failures() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(5);

      // When
      boolean reached = metrics.isThresholdReached(NOW);

      // Then
      assertThat(reached).isFalse();
      assertThat(metrics.consecutiveFailures()).isZero();
    }

    @Test
    void should_store_the_configured_failure_threshold() {
      // Given / When
      var metrics = ConsecutiveFailuresMetrics.initial(10);

      // Then
      assertThat(metrics.failureThreshold()).isEqualTo(10);
    }

    @Test
    void should_round_fractional_threshold_to_nearest_long() {
      // Given / When
      var metrics = ConsecutiveFailuresMetrics.initial(4.6);

      // Then
      assertThat(metrics.failureThreshold()).isEqualTo(5);
    }
  }

  // ======================== Recording Failures ========================

  @Nested
  class RecordingFailures {

    @Test
    void should_increment_consecutive_failures_on_each_failure() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(5);

      // When
      var after1 = metrics.recordFailure(NOW);
      var after2 = after1.recordFailure(NOW);
      var after3 = after2.recordFailure(NOW);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) after1).consecutiveFailures()).isEqualTo(1);
      assertThat(((ConsecutiveFailuresMetrics) after2).consecutiveFailures()).isEqualTo(2);
      assertThat(((ConsecutiveFailuresMetrics) after3).consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void should_reach_threshold_after_exactly_n_consecutive_failures() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(3);

      // When
      var updated = metrics;
      for (int i = 0; i < 3; i++) {
        updated = (ConsecutiveFailuresMetrics) updated.recordFailure(NOW);
      }

      // Then
      assertThat(updated.isThresholdReached(NOW)).isTrue();
    }

    @Test
    void should_not_reach_threshold_with_one_fewer_failure_than_required() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(3);

      // When
      var updated = metrics.recordFailure(NOW).recordFailure(NOW);

      // Then
      assertThat(updated.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_still_be_above_threshold_after_exceeding_it() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(2);

      // When
      var updated = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // Then
      assertThat(updated.isThresholdReached(NOW)).isTrue();
      assertThat(((ConsecutiveFailuresMetrics) updated).consecutiveFailures()).isEqualTo(3);
    }
  }

  // ======================== Recording Successes ========================

  @Nested
  class RecordingSuccesses {

    @Test
    void should_reset_consecutive_failure_counter_on_a_single_success() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(5);
      var withFailures = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // When
      var afterSuccess = withFailures.recordSuccess(NOW);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) afterSuccess).consecutiveFailures()).isZero();
      assertThat(afterSuccess.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_not_go_below_zero_when_recording_success_on_fresh_state() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(5);

      // When
      var afterSuccess = metrics.recordSuccess(NOW);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) afterSuccess).consecutiveFailures()).isZero();
    }

    @Test
    void should_allow_rebuilding_failures_after_a_success_interrupts_the_streak() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(3);
      var twoFailures = metrics.recordFailure(NOW).recordFailure(NOW);

      // When — success breaks the streak, then failures start again
      var restarted = twoFailures.recordSuccess(NOW)
          .recordFailure(NOW).recordFailure(NOW);

      // Then
      assertThat(restarted.isThresholdReached(NOW)).isFalse();
      assertThat(((ConsecutiveFailuresMetrics) restarted).consecutiveFailures()).isEqualTo(2);
    }
  }

  // ======================== Reset ========================

  @Nested
  class Resetting {

    @Test
    void should_return_to_initial_state_after_reset() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(3);
      var withFailures = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);
      assertThat(withFailures.isThresholdReached(NOW)).isTrue();

      // When
      var afterReset = withFailures.reset(LATER);

      // Then
      assertThat(afterReset.isThresholdReached(LATER)).isFalse();
      assertThat(((ConsecutiveFailuresMetrics) afterReset).consecutiveFailures()).isZero();
    }

    @Test
    void should_preserve_threshold_after_reset() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(7);

      // When
      var afterReset = (ConsecutiveFailuresMetrics) metrics.reset(LATER);

      // Then
      assertThat(afterReset.failureThreshold()).isEqualTo(7);
    }
  }

  // ======================== Immutability ========================

  @Nested
  class Immutability {

    @Test
    void should_not_modify_original_instance_when_recording_failure() {
      // Given
      var original = ConsecutiveFailuresMetrics.initial(5);

      // When
      original.recordFailure(NOW);

      // Then
      assertThat(original.consecutiveFailures()).isZero();
    }

    @Test
    void should_not_modify_original_instance_when_recording_success() {
      // Given
      var original = (ConsecutiveFailuresMetrics) ConsecutiveFailuresMetrics.initial(5)
          .recordFailure(NOW).recordFailure(NOW);

      // When
      original.recordSuccess(NOW);

      // Then
      assertThat(original.consecutiveFailures()).isEqualTo(2);
    }
  }

  // ======================== Trip Reason ========================

  @Nested
  class TripReason {

    @Test
    void should_include_current_count_and_threshold_in_trip_reason() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(3);
      var tripped = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // When
      String reason = tripped.getTripReason(NOW);

      // Then
      assertThat(reason)
          .contains("3")
          .contains("Threshold")
          .containsIgnoringCase("consecutive");
    }
  }

  // ======================== Threshold of One ========================

  @Nested
  class EdgeCaseThresholdOfOne {

    @Test
    void should_trip_after_a_single_failure_when_threshold_is_one() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(1);

      // When
      var updated = metrics.recordFailure(NOW);

      // Then
      assertThat(updated.isThresholdReached(NOW)).isTrue();
    }

    @Test
    void should_recover_immediately_after_one_success_when_threshold_is_one() {
      // Given
      var metrics = ConsecutiveFailuresMetrics.initial(1);
      var tripped = metrics.recordFailure(NOW);

      // When
      var recovered = tripped.recordSuccess(NOW);

      // Then
      assertThat(recovered.isThresholdReached(NOW)).isFalse();
    }
  }
}
