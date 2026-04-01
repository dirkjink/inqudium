package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Consecutive Failures Metrics")
class ConsecutiveFailuresMetricsTest {

  private final Instant now = Instant.now();

  private static CircuitBreakerConfig createConfig(int failureThreshold) {
    return CircuitBreakerConfig.builder("test-breaker")
        .failureThreshold(failureThreshold)
        .build();
  }

  @Nested
  @DisplayName("Initialization")
  class Initialization {

    @Test
    @DisplayName("should initialize with zero consecutive failures")
    void should_initialize_with_zero_consecutive_failures() {
      // Given / When
      ConsecutiveFailuresMetrics metrics = ConsecutiveFailuresMetrics.initial();

      // Then
      assertThat(metrics.consecutiveFailures()).isZero();
    }
  }

  @Nested
  @DisplayName("Recording Failures")
  class RecordingFailures {

    @Test
    @DisplayName("should increment the consecutive failure count by one when recording a failure")
    void should_increment_the_consecutive_failure_count_by_one_when_recording_a_failure() {
      // Given
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial();

      // When
      FailureMetrics updated = metrics.recordFailure(now);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) updated).consecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("should accumulate consecutive failures when multiple failures are recorded sequentially")
    void should_accumulate_consecutive_failures_when_multiple_failures_are_recorded_sequentially() {
      // Given
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial();

      // When
      FailureMetrics updated = metrics
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) updated).consecutiveFailures()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Recording Successes")
  class RecordingSuccesses {

    @Test
    @DisplayName("should completely reset the failure count to zero when recording a single success")
    void should_completely_reset_the_failure_count_to_zero_when_recording_a_single_success() {
      // Given - Accumulate 5 failures first
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial()
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      assertThat(((ConsecutiveFailuresMetrics) metrics).consecutiveFailures()).isEqualTo(5);

      // When
      FailureMetrics updated = metrics.recordSuccess(now);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) updated).consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("should keep the failure count at zero when recording a success on an initial state")
    void should_keep_the_failure_count_at_zero_when_recording_a_success_on_an_initial_state() {
      // Given
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial();

      // When
      FailureMetrics updated = metrics.recordSuccess(now);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) updated).consecutiveFailures()).isZero();
    }
  }

  @Nested
  @DisplayName("Threshold Evaluation")
  class ThresholdEvaluation {

    @Test
    @DisplayName("should return true when the consecutive failure count exactly meets the configured threshold")
    void should_return_true_when_the_consecutive_failure_count_exactly_meets_the_configured_threshold() {
      // Given
      CircuitBreakerConfig config = createConfig(3);
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial()
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isTrue();
    }

    @Test
    @DisplayName("should return true when the consecutive failure count exceeds the configured threshold")
    void should_return_true_when_the_consecutive_failure_count_exceeds_the_configured_threshold() {
      // Given
      CircuitBreakerConfig config = createConfig(3);
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial()
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isTrue();
    }

    @Test
    @DisplayName("should return false when the consecutive failure count is strictly below the configured threshold")
    void should_return_false_when_the_consecutive_failure_count_is_strictly_below_the_configured_threshold() {
      // Given
      CircuitBreakerConfig config = createConfig(5);
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial()
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isFalse();
    }

    @Test
    @DisplayName("should return false when a success interrupts the consecutive failures before reaching the threshold")
    void should_return_false_when_a_success_interrupts_the_consecutive_failures_before_reaching_the_threshold() {
      // Given
      CircuitBreakerConfig config = createConfig(3);
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial()
          .recordFailure(now)
          .recordFailure(now)
          .recordSuccess(now) // This resets the counter to 0
          .recordFailure(now); // Counter is now 1

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isFalse();
    }
  }

  @Nested
  @DisplayName("Resetting State")
  class ResettingState {

    @Test
    @DisplayName("should return a fresh instance with zero failures when reset is called")
    void should_return_a_fresh_instance_with_zero_failures_when_reset_is_called() {
      // Given
      FailureMetrics metrics = ConsecutiveFailuresMetrics.initial()
          .recordFailure(now)
          .recordFailure(now);

      // When
      FailureMetrics resetMetrics = metrics.reset(now);

      // Then
      assertThat(((ConsecutiveFailuresMetrics) resetMetrics).consecutiveFailures()).isZero();
    }
  }
}
