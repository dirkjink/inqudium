package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RequestBasedEwmaMetricsTest {

  private static final long NOW = 1_000_000_000L;

  // ======================== Initial State ========================

  @Nested
  class InitialState {

    @Test
    void should_start_with_zero_rate_and_zero_calls() {
      // Given / When
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.3, 5);

      // Then
      assertThat(metrics.currentRate()).isCloseTo(0.0, within(0.001));
      assertThat(metrics.callsCount()).isZero();
      assertThat(metrics.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_reject_minimum_calls_of_zero() {
      assertThatThrownBy(() -> RequestBasedEwmaMetrics.initial(50, 0.3, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_store_threshold_as_rounded_long() {
      // Given / When
      var metrics = RequestBasedEwmaMetrics.initial(49.6, 0.3, 5);

      // Then
      assertThat(metrics.failureThreshold()).isEqualTo(50);
    }
  }

  // ======================== Minimum Number of Calls ========================

  @Nested
  class MinimumNumberOfCalls {

    @Test
    void should_not_trip_before_minimum_calls_are_reached_even_with_all_failures() {
      // Given — min=5, threshold=50%
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.5, 5);

      // When — 4 failures (one short of minimum)
      var updated = metrics;
      for (int i = 0; i < 4; i++) {
        updated = (RequestBasedEwmaMetrics) updated.recordFailure(NOW);
      }

      // Then
      assertThat(updated.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_trip_once_minimum_calls_are_met_and_rate_exceeds_threshold() {
      // Given — min=3, threshold=50%, alpha=1.0 (latest sample dominates)
      var metrics = RequestBasedEwmaMetrics.initial(50, 1.0, 3);

      // When — 3 failures
      var updated = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

      // Then — rate should be 1.0 (100%), well above 50%
      assertThat(updated.isThresholdReached(NOW)).isTrue();
    }
  }

  // ======================== EWMA Smoothing Behavior ========================

  @Nested
  class EwmaSmoothing {

    @Test
    void should_weight_recent_samples_more_heavily_with_high_alpha() {
      // Given — alpha=0.9 (aggressive), min=1, threshold=50%
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.9, 1);

      // When — 5 failures then 5 successes
      var updated = metrics;
      for (int i = 0; i < 5; i++) updated = (RequestBasedEwmaMetrics) updated.recordFailure(NOW);
      for (int i = 0; i < 5; i++) updated = (RequestBasedEwmaMetrics) updated.recordSuccess(NOW);

      // Then — rate should have decayed significantly toward 0
      assertThat(((RequestBasedEwmaMetrics) updated).currentRate()).isLessThan(0.1);
    }

    @Test
    void should_retain_more_history_with_low_alpha() {
      // Given — alpha=0.1 (conservative), min=1, threshold=50%
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.1, 1);

      // When — 10 failures then 3 successes
      var updated = metrics;
      for (int i = 0; i < 10; i++) updated = (RequestBasedEwmaMetrics) updated.recordFailure(NOW);
      for (int i = 0; i < 3; i++) updated = (RequestBasedEwmaMetrics) updated.recordSuccess(NOW);

      // Then — rate should still be notably high because low alpha retains history
      assertThat(((RequestBasedEwmaMetrics) updated).currentRate()).isGreaterThan(0.4);
    }

    @Test
    void should_converge_to_zero_after_many_successes() {
      // Given
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.3, 1);
      var withFailures = metrics;
      for (int i = 0; i < 5; i++) withFailures = (RequestBasedEwmaMetrics) withFailures.recordFailure(NOW);

      // When — 50 successes
      var updated = withFailures;
      for (int i = 0; i < 50; i++) updated = (RequestBasedEwmaMetrics) updated.recordSuccess(NOW);

      // Then
      assertThat(((RequestBasedEwmaMetrics) updated).currentRate()).isCloseTo(0.0, within(0.01));
    }

    @Test
    void should_converge_to_one_after_many_failures() {
      // Given
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.3, 1);

      // When — 50 failures
      var updated = metrics;
      for (int i = 0; i < 50; i++) updated = (RequestBasedEwmaMetrics) updated.recordFailure(NOW);

      // Then
      assertThat(((RequestBasedEwmaMetrics) updated).currentRate()).isCloseTo(1.0, within(0.01));
    }
  }

  // ======================== Threshold Interpretation ========================

  @Nested
  class ThresholdInterpretation {

    @Test
    void should_interpret_threshold_as_percentage_so_50_means_half() {
      // Given — alpha=1.0 so rate = latest sample exactly, min=1
      var metrics = RequestBasedEwmaMetrics.initial(50, 1.0, 1);

      // When — a single failure → rate = 1.0 (100%)
      var updated = metrics.recordFailure(NOW);

      // Then — 100% >= 50%
      assertThat(updated.isThresholdReached(NOW)).isTrue();
    }

    @Test
    void should_not_trip_when_rate_is_just_below_threshold() {
      // Given — alpha=1.0, threshold=80%, min=1
      var metrics = RequestBasedEwmaMetrics.initial(80, 1.0, 1);

      // When — single success → rate = 0
      var updated = metrics.recordFailure(NOW).recordSuccess(NOW);

      // Then — 0% < 80%
      assertThat(updated.isThresholdReached(NOW)).isFalse();
    }
  }

  // ======================== Reset ========================

  @Nested
  class Resetting {

    @Test
    void should_return_to_zero_rate_and_zero_calls_after_reset() {
      // Given
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.3, 5);
      var filled = metrics;
      for (int i = 0; i < 10; i++) filled = (RequestBasedEwmaMetrics) filled.recordFailure(NOW);

      // When
      var afterReset = (RequestBasedEwmaMetrics) filled.reset(NOW);

      // Then
      assertThat(afterReset.currentRate()).isCloseTo(0.0, within(0.001));
      assertThat(afterReset.callsCount()).isZero();
      assertThat(afterReset.isThresholdReached(NOW)).isFalse();
    }

    @Test
    void should_preserve_configuration_after_reset() {
      // Given
      var metrics = RequestBasedEwmaMetrics.initial(60, 0.4, 8);

      // When
      var afterReset = (RequestBasedEwmaMetrics) metrics.reset(NOW);

      // Then
      assertThat(afterReset.failureThreshold()).isEqualTo(60);
      assertThat(afterReset.minimumNumberOfCalls()).isEqualTo(8);
    }
  }

  // ======================== Immutability ========================

  @Nested
  class Immutability {

    @Test
    void should_not_modify_original_when_recording() {
      // Given
      var original = RequestBasedEwmaMetrics.initial(50, 0.3, 5);

      // When
      original.recordFailure(NOW);

      // Then
      assertThat(original.currentRate()).isCloseTo(0.0, within(0.001));
      assertThat(original.callsCount()).isZero();
    }
  }

  // ======================== Trip Reason ========================

  @Nested
  class TripReason {

    @Test
    void should_include_rate_and_threshold_and_alpha_in_reason() {
      // Given
      var metrics = RequestBasedEwmaMetrics.initial(50, 0.3, 1);
      var tripped = metrics;
      for (int i = 0; i < 10; i++) tripped = (RequestBasedEwmaMetrics) tripped.recordFailure(NOW);

      // When
      String reason = tripped.getTripReason(NOW);

      // Then
      assertThat(reason)
          .containsIgnoringCase("EWMA")
          .contains("50")
          .contains("Alpha");
    }
  }
}
