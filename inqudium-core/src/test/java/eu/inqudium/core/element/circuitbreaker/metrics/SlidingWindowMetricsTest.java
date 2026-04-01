package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Sliding Window Metrics")
class SlidingWindowMetricsTest {

  private final Instant now = Instant.now();

  private static CircuitBreakerConfig createConfig(int failureThreshold) {
    return CircuitBreakerConfig.builder("test")
        .failureThreshold(failureThreshold)
        .build();
  }

  @Nested
  @DisplayName("Initialization")
  class Initialization {

    @Test
    @DisplayName("should initialize with zero failures and empty size")
    void should_initialize_with_zero_failures_and_empty_size() {
      // Given / When
      SlidingWindowMetrics metrics = SlidingWindowMetrics.initial(10, 5);

      // Then
      assertThat(metrics.failureCount()).isZero();
      assertThat(metrics.size()).isZero();
      assertThat(metrics.windowSize()).isEqualTo(10);
      assertThat(metrics.minimumNumberOfCalls()).isEqualTo(5);
    }

    @Test
    @DisplayName("should throw exception when window size is zero or negative")
    void should_throw_exception_when_window_size_is_zero_or_negative() {
      // Given / When / Then
      assertThatThrownBy(() -> SlidingWindowMetrics.initial(0, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("windowSize must be greater than 0");

      assertThatThrownBy(() -> SlidingWindowMetrics.initial(-5, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("windowSize must be greater than 0");
    }

    @Test
    @DisplayName("should throw exception when minimum number of calls is invalid")
    void should_throw_exception_when_minimum_number_of_calls_is_invalid() {
      // Given / When / Then
      assertThatThrownBy(() -> SlidingWindowMetrics.initial(10, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("minimumNumberOfCalls must be between 1 and windowSize");

      assertThatThrownBy(() -> SlidingWindowMetrics.initial(10, 11))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("minimumNumberOfCalls must be between 1 and windowSize");
    }
  }

  @Nested
  @DisplayName("Recording Outcomes")
  class RecordingOutcomes {

    @Test
    @DisplayName("should correctly increment failure count and size when recording failures")
    void should_correctly_increment_failure_count_and_size_when_recording_failures() {
      // Given
      FailureMetrics metrics = SlidingWindowMetrics.initial(10, 5);

      // When
      FailureMetrics updated = metrics.recordFailure(now).recordFailure(now);

      // Then
      assertThat(((SlidingWindowMetrics) updated).failureCount()).isEqualTo(2);
      assertThat(((SlidingWindowMetrics) updated).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("should increase size but not failure count when recording successes")
    void should_increase_size_but_not_failure_count_when_recording_successes() {
      // Given
      FailureMetrics metrics = SlidingWindowMetrics.initial(10, 5);

      // When
      FailureMetrics updated = metrics.recordSuccess(now).recordSuccess(now);

      // Then
      assertThat(((SlidingWindowMetrics) updated).failureCount()).isZero();
      assertThat(((SlidingWindowMetrics) updated).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("should not exceed window size when recording more outcomes than the window capacity")
    void should_not_exceed_window_size_when_recording_more_outcomes_than_the_window_capacity() {
      // Given
      FailureMetrics metrics = SlidingWindowMetrics.initial(3, 2);

      // When
      FailureMetrics updated = metrics
          .recordSuccess(now)
          .recordSuccess(now)
          .recordSuccess(now)
          .recordSuccess(now);

      // Then
      assertThat(((SlidingWindowMetrics) updated).size()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Sliding Window Behavior (Eviction)")
  class SlidingWindowBehavior {

    @Test
    @DisplayName("should decrement failure count when an old failure is evicted by a new success")
    void should_decrement_failure_count_when_an_old_failure_is_evicted_by_a_new_success() {
      // Given - Window of size 3. Fill it entirely with failures.
      FailureMetrics metrics = SlidingWindowMetrics.initial(3, 3)
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      assertThat(((SlidingWindowMetrics) metrics).failureCount()).isEqualTo(3);

      // When - Record a success. This should evict the oldest failure.
      FailureMetrics updated = metrics.recordSuccess(now);

      // Then - The failure count should drop to 2.
      assertThat(((SlidingWindowMetrics) updated).failureCount()).isEqualTo(2);
      assertThat(((SlidingWindowMetrics) updated).size()).isEqualTo(3);
    }

    @Test
    @DisplayName("should maintain failure count when an old failure is evicted by a new failure")
    void should_maintain_failure_count_when_an_old_failure_is_evicted_by_a_new_failure() {
      // Given - Window of size 3. Fill it entirely with failures.
      FailureMetrics metrics = SlidingWindowMetrics.initial(3, 3)
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      // When - Record another failure.
      FailureMetrics updated = metrics.recordFailure(now);

      // Then - Failure count remains 3 because an old failure was replaced by a new failure.
      assertThat(((SlidingWindowMetrics) updated).failureCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("should increment failure count when an old success is evicted by a new failure")
    void should_increment_failure_count_when_an_old_success_is_evicted_by_a_new_failure() {
      // Given - Window of size 3. Fill it with successes.
      FailureMetrics metrics = SlidingWindowMetrics.initial(3, 3)
          .recordSuccess(now)
          .recordSuccess(now)
          .recordSuccess(now);

      // When - Record a failure. This evicts the oldest success.
      FailureMetrics updated = metrics.recordFailure(now);

      // Then - Failure count increases to 1.
      assertThat(((SlidingWindowMetrics) updated).failureCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Threshold Evaluation")
  class ThresholdEvaluation {

    @Test
    @DisplayName("should return false when the minimum number of calls has not been reached")
    void should_return_false_when_the_minimum_number_of_calls_has_not_been_reached() {
      // Given
      CircuitBreakerConfig config = createConfig(2);
      // We set minimum calls to 5, so even if we hit the threshold of 2 early, it should not trigger.
      FailureMetrics metrics = SlidingWindowMetrics.initial(10, 5)
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isFalse();
    }

    @Test
    @DisplayName("should return true when minimum calls are met and failure threshold is reached")
    void should_return_true_when_minimum_calls_are_met_and_failure_threshold_is_reached() {
      // Given
      CircuitBreakerConfig config = createConfig(3);
      FailureMetrics metrics = SlidingWindowMetrics.initial(5, 4)
          .recordSuccess(now)
          .recordFailure(now)
          .recordFailure(now)
          .recordFailure(now);

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then - We have 4 total calls (meets minimum of 4) and 3 failures (meets threshold of 3).
      assertThat(thresholdReached).isTrue();
    }

    @Test
    @DisplayName("should return false when minimum calls are met but failures are below threshold")
    void should_return_false_when_minimum_calls_are_met_but_failures_are_below_threshold() {
      // Given
      CircuitBreakerConfig config = createConfig(4);
      FailureMetrics metrics = SlidingWindowMetrics.initial(10, 5)
          .recordFailure(now)
          .recordFailure(now)
          .recordSuccess(now)
          .recordSuccess(now)
          .recordSuccess(now);

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
    @DisplayName("should clear all state and retain configuration when resetting")
    void should_clear_all_state_and_retain_configuration_when_resetting() {
      // Given
      FailureMetrics metrics = SlidingWindowMetrics.initial(10, 5)
          .recordFailure(now)
          .recordSuccess(now);

      // When
      FailureMetrics resetMetrics = metrics.reset(now);

      // Then
      assertThat(((SlidingWindowMetrics) resetMetrics).failureCount()).isZero();
      assertThat(((SlidingWindowMetrics) resetMetrics).size()).isZero();
      assertThat(((SlidingWindowMetrics) resetMetrics).windowSize()).isEqualTo(10);
      assertThat(((SlidingWindowMetrics) resetMetrics).minimumNumberOfCalls()).isEqualTo(5);
    }
  }
}
