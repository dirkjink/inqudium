package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Continuous-Time EWMA Metrics")
class ContinuousTimeEwmaMetricsTest {

  private static final Instant START_TIME = Instant.parse("2026-01-01T12:00:00Z");
  private static final Duration TIME_CONSTANT = Duration.ofSeconds(10); // Tau

  private static CircuitBreakerConfig createConfigWithPercentage(int failureThresholdPercentage) {
    return CircuitBreakerConfig.builder("test-ewma-breaker")
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
      ContinuousTimeEwmaMetrics metrics = ContinuousTimeEwmaMetrics.initial(TIME_CONSTANT, 5, START_TIME);

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
      assertThatThrownBy(() -> ContinuousTimeEwmaMetrics.initial(TIME_CONSTANT, 0, START_TIME))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("minimumNumberOfCalls must be greater than 0");
    }
  }

  @Nested
  @DisplayName("Recording Outcomes")
  class RecordingOutcomes {

    @Test
    @DisplayName("should increase failure rate when recording a failure")
    void should_increase_failure_rate_when_recording_a_failure() {
      // Given
      FailureMetrics metrics = ContinuousTimeEwmaMetrics.initial(TIME_CONSTANT, 5, START_TIME);

      // When
      FailureMetrics updated = metrics.recordFailure(START_TIME.plusSeconds(1));

      // Then
      ContinuousTimeEwmaMetrics ewmaMetrics = (ContinuousTimeEwmaMetrics) updated;
      assertThat(ewmaMetrics.currentRate()).isGreaterThan(0.0);
      assertThat(ewmaMetrics.callsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should decrease failure rate when recording a success after a failure")
    void should_decrease_failure_rate_when_recording_a_success_after_a_failure() {
      // Given
      FailureMetrics metrics = ContinuousTimeEwmaMetrics.initial(TIME_CONSTANT, 5, START_TIME);
      FailureMetrics afterFailure = metrics.recordFailure(START_TIME.plusSeconds(5));
      double rateAfterFailure = ((ContinuousTimeEwmaMetrics) afterFailure).currentRate();

      // When
      FailureMetrics afterSuccess = afterFailure.recordSuccess(START_TIME.plusSeconds(10));

      // Then
      double rateAfterSuccess = ((ContinuousTimeEwmaMetrics) afterSuccess).currentRate();
      assertThat(rateAfterSuccess).isLessThan(rateAfterFailure);
      assertThat(rateAfterSuccess).isGreaterThan(0.0);
      assertThat(((ContinuousTimeEwmaMetrics) afterSuccess).callsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should gracefully handle time going backwards without corrupting the state")
    void should_gracefully_handle_time_going_backwards_without_corrupting_the_state() {
      // Given
      FailureMetrics metrics = ContinuousTimeEwmaMetrics.initial(TIME_CONSTANT, 5, START_TIME.plusSeconds(10));

      // When - Simulating clock skew by providing a timestamp in the past
      FailureMetrics updated = metrics.recordFailure(START_TIME);

      // Then - State should be updated, but the internal timestamp should not have moved backwards
      ContinuousTimeEwmaMetrics ewmaMetrics = (ContinuousTimeEwmaMetrics) updated;
      long expectedNanos = START_TIME.plusSeconds(10).getEpochSecond() * 1_000_000_000L;
      assertThat(ewmaMetrics.lastUpdateNanos()).isEqualTo(expectedNanos);
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
      FailureMetrics metrics = ContinuousTimeEwmaMetrics.initial(Duration.ofSeconds(1), 5, START_TIME);

      // When - Record 4 consecutive failures spaced by 1 second.
      FailureMetrics updated = metrics
          .recordFailure(START_TIME.plusSeconds(1))
          .recordFailure(START_TIME.plusSeconds(2))
          .recordFailure(START_TIME.plusSeconds(3))
          .recordFailure(START_TIME.plusSeconds(4));

      // Then - Still false because we need at least 5 calls
      assertThat(updated.isThresholdReached(config, START_TIME.plusSeconds(4))).isFalse();
    }

    @Test
    @DisplayName("should return true when minimum calls are met and rate exceeds threshold")
    void should_return_true_when_minimum_calls_are_met_and_rate_exceeds_threshold() {
      // Given
      CircuitBreakerConfig config = createConfigWithPercentage(50); // 50% threshold
      // Tau is 1 second, making it highly reactive to failures spaced 1 second apart
      FailureMetrics metrics = ContinuousTimeEwmaMetrics.initial(Duration.ofSeconds(1), 3, START_TIME);

      // When - Record 3 failures spaced by 1 second.
      FailureMetrics updated = metrics
          .recordFailure(START_TIME.plusSeconds(1))
          .recordFailure(START_TIME.plusSeconds(2))
          .recordFailure(START_TIME.plusSeconds(3));

      // Then - The rate will be approximately 95% at this point
      assertThat(updated.isThresholdReached(config, START_TIME.plusSeconds(3))).isTrue();
    }

    @Test
    @DisplayName("should return false when minimum calls are met but rate decays below threshold over time")
    void should_return_false_when_minimum_calls_are_met_but_rate_decays_below_threshold_over_time() {
      // Given
      CircuitBreakerConfig config = createConfigWithPercentage(50); // 50% threshold
      FailureMetrics metrics = ContinuousTimeEwmaMetrics.initial(Duration.ofSeconds(1), 3, START_TIME);

      // Spike the rate initially
      FailureMetrics spikedMetrics = metrics
          .recordFailure(START_TIME.plusSeconds(1))
          .recordFailure(START_TIME.plusSeconds(2))
          .recordFailure(START_TIME.plusSeconds(3));

      // Ensure it is initially tripped at exactly T+3s
      assertThat(spikedMetrics.isThresholdReached(config, START_TIME.plusSeconds(3))).isTrue();

      // When - Evaluate the threshold 5 seconds later without recording any new calls
      Instant later = START_TIME.plusSeconds(8);

      // Then - The rate should have naturally decayed below 50% due to elapsed time
      assertThat(spikedMetrics.isThresholdReached(config, later)).isFalse();
    }
  }

  @Nested
  @DisplayName("Resetting State")
  class ResettingState {

    @Test
    @DisplayName("should reset rate and call count to zero and update the timestamp")
    void should_reset_rate_and_call_count_to_zero_and_update_the_timestamp() {
      // Given
      FailureMetrics metrics = ContinuousTimeEwmaMetrics.initial(TIME_CONSTANT, 5, START_TIME)
          .recordFailure(START_TIME.plusSeconds(1))
          .recordFailure(START_TIME.plusSeconds(2));

      // When
      Instant resetTime = START_TIME.plusSeconds(10);
      FailureMetrics resetMetrics = metrics.reset(resetTime);

      // Then
      ContinuousTimeEwmaMetrics ewmaMetrics = (ContinuousTimeEwmaMetrics) resetMetrics;
      assertThat(ewmaMetrics.currentRate()).isZero();
      assertThat(ewmaMetrics.callsCount()).isZero();

      long expectedNanos = resetTime.getEpochSecond() * 1_000_000_000L;
      assertThat(ewmaMetrics.lastUpdateNanos()).isEqualTo(expectedNanos);
    }
  }
}