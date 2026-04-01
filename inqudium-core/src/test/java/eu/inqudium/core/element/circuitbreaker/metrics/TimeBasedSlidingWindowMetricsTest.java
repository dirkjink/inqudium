package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Time-based Sliding Window Metrics")
class TimeBasedSlidingWindowMetricsTest {

  private static final Instant START_TIME = Instant.parse("2025-01-01T12:00:00Z");

  private static CircuitBreakerConfig createConfig(int failureThreshold) {
    return CircuitBreakerConfig.builder("test-breaker")
        .failureThreshold(failureThreshold)
        .build();
  }

  @Nested
  @DisplayName("Initialization")
  class Initialization {

    @Test
    @DisplayName("should initialize with zero failures across all buckets")
    void should_initialize_with_zero_failures_across_all_buckets() {
      // Given / When
      TimeBasedSlidingWindowMetrics metrics = TimeBasedSlidingWindowMetrics.initial(10, START_TIME);

      // Then
      assertThat(metrics.windowSizeInSeconds()).isEqualTo(10);
      assertThat(metrics.lastUpdatedEpochSecond()).isEqualTo(START_TIME.getEpochSecond());
      for (int bucket : metrics.failureBuckets()) {
        assertThat(bucket).isZero();
      }
    }

    @Test
    @DisplayName("should throw exception when window size is zero or negative")
    void should_throw_exception_when_window_size_is_zero_or_negative() {
      // Given / When / Then
      assertThatThrownBy(() -> TimeBasedSlidingWindowMetrics.initial(0, START_TIME))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("windowSizeInSeconds must be greater than 0");
    }
  }

  @Nested
  @DisplayName("Recording Failures")
  class RecordingFailures {

    @Test
    @DisplayName("should accumulate failures occurring in the exact same second")
    void should_accumulate_failures_occurring_in_the_exact_same_second() {
      // Given
      FailureMetrics metrics = TimeBasedSlidingWindowMetrics.initial(10, START_TIME);

      // When - 3 failures occur simultaneously
      FailureMetrics updated = metrics
          .recordFailure(START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME);

      // Then
      CircuitBreakerConfig config = createConfig(3);
      assertThat(updated.isThresholdReached(config, START_TIME)).isTrue();
    }

    @Test
    @DisplayName("should accumulate failures occurring in different seconds within the window")
    void should_accumulate_failures_occurring_in_different_seconds_within_the_window() {
      // Given
      FailureMetrics metrics = TimeBasedSlidingWindowMetrics.initial(10, START_TIME);

      // When - 3 failures occur across 3 consecutive seconds
      FailureMetrics updated = metrics
          .recordFailure(START_TIME)
          .recordFailure(START_TIME.plusSeconds(1))
          .recordFailure(START_TIME.plusSeconds(2));

      // Then
      CircuitBreakerConfig config = createConfig(3);
      assertThat(updated.isThresholdReached(config, START_TIME.plusSeconds(2))).isTrue();
    }
  }

  @Nested
  @DisplayName("Time Progression and Eviction")
  class TimeProgressionAndEviction {

    @Test
    @DisplayName("should evict old failures when time advances beyond the window size")
    void should_evict_old_failures_when_time_advances_beyond_the_window_size() {
      // Given - Window of 5 seconds, record 3 failures at START_TIME
      FailureMetrics metrics = TimeBasedSlidingWindowMetrics.initial(5, START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME);

      CircuitBreakerConfig config = createConfig(3);
      assertThat(metrics.isThresholdReached(config, START_TIME)).isTrue();

      // When - Fast forward 6 seconds and record a success
      Instant later = START_TIME.plusSeconds(6);
      FailureMetrics updated = metrics.recordSuccess(later);

      // Then - The old failures should be entirely forgotten
      assertThat(updated.isThresholdReached(config, later)).isFalse();
    }

    @Test
    @DisplayName("should partially evict old failures as the window slides forward")
    void should_partially_evict_old_failures_as_the_window_slides_forward() {
      // Given - Window of 10 seconds
      FailureMetrics metrics = TimeBasedSlidingWindowMetrics.initial(10, START_TIME)
          .recordFailure(START_TIME) // At second 0 (expires at 10)
          .recordFailure(START_TIME.plusSeconds(5)) // At second 5 (expires at 15)
          .recordFailure(START_TIME.plusSeconds(5)); // At second 5 (expires at 15)

      CircuitBreakerConfig config = createConfig(3);
      assertThat(metrics.isThresholdReached(config, START_TIME.plusSeconds(5))).isTrue();

      // When - Check threshold at second 11. The failure at second 0 should be evicted.
      Instant later = START_TIME.plusSeconds(11);

      // Then - Only the 2 failures from second 5 remain in the window
      assertThat(metrics.isThresholdReached(config, later)).isFalse();
    }

    @Test
    @DisplayName("should handle time going backwards gracefully by ignoring the jump")
    void should_handle_time_going_backwards_gracefully_by_ignoring_the_jump() {
      // Given
      FailureMetrics metrics = TimeBasedSlidingWindowMetrics.initial(10, START_TIME)
          .recordFailure(START_TIME.plusSeconds(5));

      // When - Time jumps backward
      FailureMetrics updated = metrics.recordFailure(START_TIME.plusSeconds(2));

      // Then - We don't crash and we process it as best-effort (recording at the old bucket)
      CircuitBreakerConfig config = createConfig(2);
      assertThat(updated.isThresholdReached(config, START_TIME.plusSeconds(5))).isTrue();
    }
  }

  @Nested
  @DisplayName("Trip Reason")
  class TripReason {

    @Test
    @DisplayName("should provide a detailed reason including failure count and window size")
    void should_provide_a_detailed_reason_including_failure_count_and_window_size() {
      // Given
      int windowSize = 10;
      FailureMetrics metrics = TimeBasedSlidingWindowMetrics.initial(windowSize, START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME.plusSeconds(1));

      CircuitBreakerConfig config = CircuitBreakerConfig.builder("test")
          .failureThreshold(2)
          .build();

      // When
      String reason = metrics.getTripReason(config, START_TIME.plusSeconds(1));

      // Then
      assertThat(reason).contains("Found 2 failures")
          .contains("last 10 seconds")
          .contains("Threshold: 2");
    }
  }

  @Nested
  @DisplayName("Resetting State")
  class ResettingState {

    @Test
    @DisplayName("should clear all buckets and update the timestamp upon reset")
    void should_clear_all_buckets_and_update_the_timestamp_upon_reset() {
      // Given
      FailureMetrics metrics = TimeBasedSlidingWindowMetrics.initial(10, START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME);

      // When
      Instant later = START_TIME.plusSeconds(50);
      FailureMetrics resetMetrics = metrics.reset(later);

      // Then
      assertThat(((TimeBasedSlidingWindowMetrics) resetMetrics).lastUpdatedEpochSecond()).isEqualTo(later.getEpochSecond());
      for (int bucket : ((TimeBasedSlidingWindowMetrics) resetMetrics).failureBuckets()) {
        assertThat(bucket).isZero();
      }
    }
  }
}
