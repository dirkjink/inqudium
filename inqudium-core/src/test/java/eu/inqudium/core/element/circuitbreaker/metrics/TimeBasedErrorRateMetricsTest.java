package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Time-Based Error Rate Metrics")
class TimeBasedErrorRateMetricsTest {

  private static final Instant START_TIME = Instant.parse("2026-01-01T12:00:00Z");

  private static CircuitBreakerConfig configWithPercentage(int failureThresholdPercentage) {
    return CircuitBreakerConfig.builder("test-error-rate")
        .failureThreshold(failureThresholdPercentage)
        .build();
  }

  @Nested
  @DisplayName("Recording Outcomes")
  class RecordingOutcomes {

    @Test
    @DisplayName("should increment success and failure buckets correctly based on the timestamp")
    void should_increment_success_and_failure_buckets_correctly_based_on_the_timestamp() {
      // Given
      FailureMetrics metrics = TimeBasedErrorRateMetrics.initial(10, 5, START_TIME);

      // When
      FailureMetrics updated = metrics
          .recordFailure(START_TIME)
          .recordSuccess(START_TIME)
          .recordFailure(START_TIME.plusSeconds(1));

      // Then
      TimeBasedErrorRateMetrics rateMetrics = (TimeBasedErrorRateMetrics) updated;
      assertThat(Arrays.stream(rateMetrics.failureBuckets()).sum()).isEqualTo(2);
      assertThat(Arrays.stream(rateMetrics.successBuckets()).sum()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Threshold Evaluation")
  class ThresholdEvaluation {

    @Test
    @DisplayName("should return true when minimum calls are met and failure percentage exceeds threshold")
    void should_return_true_when_minimum_calls_are_met_and_failure_percentage_exceeds_threshold() {
      // Given - 50% threshold, min 4 calls
      CircuitBreakerConfig config = configWithPercentage(50);
      FailureMetrics metrics = TimeBasedErrorRateMetrics.initial(10, 4, START_TIME);

      // When - 2 successes, 3 failures = 60% failure rate
      FailureMetrics updated = metrics
          .recordSuccess(START_TIME)
          .recordSuccess(START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME);

      // Then
      assertThat(updated.isThresholdReached(config, START_TIME)).isTrue();
    }

    @Test
    @DisplayName("should return false when minimum calls are met but failure percentage is below threshold")
    void should_return_false_when_minimum_calls_are_met_but_failure_percentage_is_below_threshold() {
      // Given - 50% threshold, min 4 calls
      CircuitBreakerConfig config = configWithPercentage(50);
      FailureMetrics metrics = TimeBasedErrorRateMetrics.initial(10, 4, START_TIME);

      // When - 3 successes, 1 failure = 25% failure rate
      FailureMetrics updated = metrics
          .recordSuccess(START_TIME)
          .recordSuccess(START_TIME)
          .recordSuccess(START_TIME)
          .recordFailure(START_TIME);

      // Then
      assertThat(updated.isThresholdReached(config, START_TIME)).isFalse();
    }
  }

  @Nested
  @DisplayName("Time Progression")
  class TimeProgression {

    @Test
    @DisplayName("should drop old failures and successes as time advances beyond the window size")
    void should_drop_old_failures_and_successes_as_time_advances_beyond_the_window_size() {
      // Given - 50% threshold, min 2 calls
      CircuitBreakerConfig config = configWithPercentage(50);
      FailureMetrics metrics = TimeBasedErrorRateMetrics.initial(5, 2, START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME);

      assertThat(metrics.isThresholdReached(config, START_TIME)).isTrue();

      // When - advance time completely out of the 5-second window
      Instant later = START_TIME.plusSeconds(6);

      // We must record something to evaluate a new state, or just evaluate directly
      boolean isTripped = metrics.isThresholdReached(config, later);

      // Then - the old failures are forgotten, so we fall below the minimum calls count
      assertThat(isTripped).isFalse();
    }
  }
}
