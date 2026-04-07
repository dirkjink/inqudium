package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GradualDecayMetricsTest {

  private static final long NOW = 1_000_000_000L;
  private static final long LATER = 2_000_000_000L;

  // ======================== Initial State ========================

  @Nested
  class InitialState {

    @Test
    void should_start_with_zero_failure_count() {
      // Given / When
      var metrics = GradualDecayMetrics.initial(5);

      // Then
      assertThat(metrics.failureCount()).isZero();
      assertThat(metrics.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_store_the_configured_threshold() {
      // Given / When
      var metrics = GradualDecayMetrics.initial(10);

      // Then
      assertThat(metrics.failureThreshold()).isEqualTo(10);
    }

    @Test
    void should_round_fractional_threshold() {
      // Given / When
      var metrics = GradualDecayMetrics.initial(3.7);

      // Then
      assertThat(metrics.failureThreshold()).isEqualTo(4);
    }
  }

  // ======================== Recording Failures ========================

  @Nested
  class RecordingFailures {

    @Test
    void should_increment_failure_count_on_each_failure() {
      // Given
      var metrics = GradualDecayMetrics.initial(5);

      // When
      var after = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // Then
      assertThat(((GradualDecayMetrics) after).failureCount()).isEqualTo(3);
    }

    @Test
    void should_reach_threshold_at_exactly_n_failures() {
      // Given
      var metrics = GradualDecayMetrics.initial(3);

      // When
      var updated = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // Then
      assertThat(updated.isThresholdReached(NOW)).isTrue();
    }

    @Test
    void should_not_reach_threshold_with_fewer_failures_than_required() {
      // Given
      var metrics = GradualDecayMetrics.initial(3);

      // When
      var updated = metrics.recordFailure(NOW).recordFailure(NOW);

      // Then
      assertThat(updated.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_allow_failure_count_to_exceed_threshold() {
      // Given
      var metrics = GradualDecayMetrics.initial(2);

      // When
      var updated = metrics.recordFailure(NOW).recordFailure(NOW)
          .recordFailure(NOW).recordFailure(NOW);

      // Then
      assertThat(((GradualDecayMetrics) updated).failureCount()).isEqualTo(4);
      assertThat(updated.isThresholdReached(NOW)).isTrue();
    }
  }

  // ======================== Recording Successes (Gradual Decay) ========================

  @Nested
  class RecordingSuccesses {

    @Test
    void should_decrement_failure_count_by_one_per_success() {
      // Given
      var metrics = GradualDecayMetrics.initial(5);
      var withFailures = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // When
      var afterOneSuccess = withFailures.recordSuccess(NOW);

      // Then
      assertThat(((GradualDecayMetrics) afterOneSuccess).failureCount()).isEqualTo(2);
    }

    @Test
    void should_require_as_many_successes_as_failures_to_fully_heal() {
      // Given
      var metrics = GradualDecayMetrics.initial(5);
      var tripped = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // When
      var healed = tripped.recordSuccess(NOW).recordSuccess(NOW).recordSuccess(NOW);

      // Then
      assertThat(((GradualDecayMetrics) healed).failureCount()).isZero();
    }

    @Test
    void should_not_allow_failure_count_to_drop_below_zero() {
      // Given
      var metrics = GradualDecayMetrics.initial(5);

      // When
      var afterSuccess = metrics.recordSuccess(NOW).recordSuccess(NOW);

      // Then
      assertThat(((GradualDecayMetrics) afterSuccess).failureCount()).isZero();
    }

    @Test
    void should_drop_below_threshold_after_enough_successes() {
      // Given — threshold is 3, we have 4 failures, so we need 2 successes to drop below
      var metrics = GradualDecayMetrics.initial(3);
      var above = metrics.recordFailure(NOW).recordFailure(NOW)
          .recordFailure(NOW).recordFailure(NOW);
      assertThat(above.isThresholdReached(NOW)).isTrue();

      // When
      var belowThreshold = above.recordSuccess(NOW).recordSuccess(NOW);

      // Then
      assertThat(((GradualDecayMetrics) belowThreshold).failureCount()).isEqualTo(2);
      assertThat(belowThreshold.isThresholdReached(NOW)).isFalse();
    }
  }

  // ======================== Interleaved Successes and Failures ========================

  @Nested
  class InterleavedOutcomes {

    @Test
    void should_correctly_track_interleaved_successes_and_failures() {
      // Given
      var metrics = GradualDecayMetrics.initial(3);

      // When — F, F, S, F, F, S → count should be 2
      var result = metrics
          .recordFailure(NOW).recordFailure(NOW)
          .recordSuccess(NOW)
          .recordFailure(NOW).recordFailure(NOW)
          .recordSuccess(NOW);

      // Then
      assertThat(((GradualDecayMetrics) result).failureCount()).isEqualTo(2);
      assertThat(result.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_trip_when_failures_outpace_successes() {
      // Given
      var metrics = GradualDecayMetrics.initial(2);

      // When — F, F, S, F → count should be 2 (3 failures - 1 success)
      var result = metrics
          .recordFailure(NOW).recordFailure(NOW)
          .recordSuccess(NOW)
          .recordFailure(NOW);

      // Then
      assertThat(((GradualDecayMetrics) result).failureCount()).isEqualTo(2);
      assertThat(result.isThresholdReached(NOW)).isTrue();
    }
  }

  // ======================== Reset ========================

  @Nested
  class Resetting {

    @Test
    void should_return_failure_count_to_zero_after_reset() {
      // Given
      var metrics = GradualDecayMetrics.initial(3);
      var tripped = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // When
      var afterReset = tripped.reset(LATER);

      // Then
      assertThat(afterReset.isThresholdReached(LATER)).isFalse();
      assertThat(((GradualDecayMetrics) afterReset).failureCount()).isZero();
    }

    @Test
    void should_preserve_threshold_after_reset() {
      // Given
      var metrics = GradualDecayMetrics.initial(7);

      // When
      var afterReset = (GradualDecayMetrics) metrics.reset(LATER);

      // Then
      assertThat(afterReset.failureThreshold()).isEqualTo(7);
    }
  }

  // ======================== Immutability ========================

  @Nested
  class Immutability {

    @Test
    void should_not_modify_original_when_recording_failure() {
      // Given
      var original = GradualDecayMetrics.initial(5);

      // When
      original.recordFailure(NOW);

      // Then
      assertThat(original.failureCount()).isZero();
    }

    @Test
    void should_not_modify_original_when_recording_success() {
      // Given
      var original = (GradualDecayMetrics) GradualDecayMetrics.initial(5)
          .recordFailure(NOW).recordFailure(NOW);

      // When
      original.recordSuccess(NOW);

      // Then
      assertThat(original.failureCount()).isEqualTo(2);
    }
  }

  // ======================== Trip Reason ========================

  @Nested
  class TripReason {

    @Test
    void should_include_failure_count_and_threshold_in_reason() {
      // Given
      var metrics = GradualDecayMetrics.initial(3);
      var tripped = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // When
      String reason = tripped.getTripReason(NOW);

      // Then
      assertThat(reason).contains("3").contains("Threshold");
    }
  }
}
