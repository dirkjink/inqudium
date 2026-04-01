package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Request-Based EWMA Metrics")
class RequestBasedEwmaMetricsTest {

  private static final Instant DUMMY_TIME = Instant.parse("2026-01-01T12:00:00Z");
  private static final double SMOOTHING_FACTOR = 0.5; // High alpha for quick reaction in tests

  private static CircuitBreakerConfig createConfigWithPercentage(int failureThresholdPercentage) {
    return CircuitBreakerConfig.builder("test-req-ewma-breaker")
        .failureThreshold(failureThresholdPercentage)
        .build();
  }

  @Nested
  @DisplayName("Initialization")
  class Initialization {

    @Test
    @DisplayName("should initialize metrics with zero rate and zero calls")
    void should_initialize_metrics_with_zero_rate_and_zero_calls() {
      // Given / When
      RequestBasedEwmaMetrics metrics = RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 5);

      // Then
      assertThat(metrics.currentRate()).isZero();
      assertThat(metrics.callsCount()).isZero();
      assertThat(metrics.minimumNumberOfCalls()).isEqualTo(5);
      assertThat(metrics.ewmaCalculator()).isNotNull();
    }

    @Test
    @DisplayName("should throw exception when minimum number of calls is invalid")
    void should_throw_exception_when_minimum_number_of_calls_is_invalid() {
      // Given / When / Then
      assertThatThrownBy(() -> RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("minimumNumberOfCalls must be greater than 0");
    }
  }

  @Nested
  @DisplayName("Recording Outcomes")
  class RecordingOutcomes {

    @Test
    @DisplayName("should increase failure rate immediately when recording a failure")
    void should_increase_failure_rate_immediately_when_recording_a_failure() {
      // Given
      FailureMetrics metrics = RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 5);

      // When
      FailureMetrics updated = metrics.recordFailure(DUMMY_TIME);

      // Then
      RequestBasedEwmaMetrics ewmaMetrics = (RequestBasedEwmaMetrics) updated;
      assertThat(ewmaMetrics.currentRate()).isEqualTo(0.5); // 0.0 * 0.5 + 1.0 * 0.5
      assertThat(ewmaMetrics.callsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should decrease failure rate when recording a success after a failure")
    void should_decrease_failure_rate_when_recording_a_success_after_a_failure() {
      // Given
      FailureMetrics metrics = RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 5);
      FailureMetrics afterFailure = metrics.recordFailure(DUMMY_TIME);
      double rateAfterFailure = ((RequestBasedEwmaMetrics) afterFailure).currentRate();

      // When
      FailureMetrics afterSuccess = afterFailure.recordSuccess(DUMMY_TIME);

      // Then
      double rateAfterSuccess = ((RequestBasedEwmaMetrics) afterSuccess).currentRate();
      assertThat(rateAfterSuccess).isLessThan(rateAfterFailure);
      assertThat(rateAfterSuccess).isEqualTo(0.25); // 0.5 * 0.5 + 0.0 * 0.5
      assertThat(((RequestBasedEwmaMetrics) afterSuccess).callsCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Threshold Evaluation")
  class ThresholdEvaluation {

    @Test
    @DisplayName("should return false if minimum number of calls is not met even with high failure rate")
    void should_return_false_if_minimum_number_of_calls_is_not_met_even_with_high_failure_rate() {
      // Given
      CircuitBreakerConfig config = createConfigWithPercentage(50); // 50% threshold
      FailureMetrics metrics = RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 5);

      // When - Record 4 consecutive failures. The rate will be very high (above 0.9).
      FailureMetrics updated = metrics
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME);

      // Then - Still false because we need at least 5 calls
      assertThat(updated.isThresholdReached(config, DUMMY_TIME)).isFalse();
    }

    @Test
    @DisplayName("should return true when minimum calls are met and rate exceeds threshold")
    void should_return_true_when_minimum_calls_are_met_and_rate_exceeds_threshold() {
      // Given
      CircuitBreakerConfig config = createConfigWithPercentage(50); // 50% threshold
      FailureMetrics metrics = RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 3);

      // When - Record 3 rapid failures.
      FailureMetrics updated = metrics
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME);

      // Then
      assertThat(updated.isThresholdReached(config, DUMMY_TIME)).isTrue();
    }

    @Test
    @DisplayName("should return false when a streak of successes pushes the rate below the threshold")
    void should_return_false_when_a_streak_of_successes_pushes_the_rate_below_the_threshold() {
      // Given
      CircuitBreakerConfig config = createConfigWithPercentage(50); // 50% threshold
      FailureMetrics metrics = RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 3);

      // Spike the rate initially
      FailureMetrics spikedMetrics = metrics
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME);

      assertThat(spikedMetrics.isThresholdReached(config, DUMMY_TIME)).isTrue();

      // When - Record successes to push the average down
      FailureMetrics recoveredMetrics = spikedMetrics
          .recordSuccess(DUMMY_TIME)
          .recordSuccess(DUMMY_TIME)
          .recordSuccess(DUMMY_TIME);

      // Then - The rate should drop well below 50%
      assertThat(recoveredMetrics.isThresholdReached(config, DUMMY_TIME)).isFalse();
    }
  }

  @Nested
  @DisplayName("Resetting State")
  class ResettingState {

    @Test
    @DisplayName("should reset rate and call count to zero and preserve configuration")
    void should_reset_rate_and_call_count_to_zero_and_preserve_configuration() {
      // Given
      FailureMetrics metrics = RequestBasedEwmaMetrics.initial(SMOOTHING_FACTOR, 5)
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME);

      // When
      FailureMetrics resetMetrics = metrics.reset(DUMMY_TIME);

      // Then
      RequestBasedEwmaMetrics ewmaMetrics = (RequestBasedEwmaMetrics) resetMetrics;
      assertThat(ewmaMetrics.currentRate()).isZero();
      assertThat(ewmaMetrics.callsCount()).isZero();
      assertThat(ewmaMetrics.minimumNumberOfCalls()).isEqualTo(5);
    }
  }
}
