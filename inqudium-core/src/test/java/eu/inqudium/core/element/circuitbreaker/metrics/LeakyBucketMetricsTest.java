package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Leaky Bucket Metrics")
class LeakyBucketMetricsTest {

  private static final Instant START_TIME = Instant.parse("2026-01-01T12:00:00Z");

  private static CircuitBreakerConfig configWithThreshold(int absoluteThreshold) {
    return CircuitBreakerConfig.builder("test-leaky-bucket")
        .failureThreshold(absoluteThreshold)
        .build();
  }

  @Nested
  @DisplayName("Recording Failures")
  class RecordingFailures {

    @Test
    @DisplayName("should increment the bucket level by one for each immediate failure")
    void should_increment_the_bucket_level_by_one_for_each_immediate_failure() {
      // Given - Leak rate is 1.0 per second
      FailureMetrics metrics = LeakyBucketMetrics.initial(1.0, START_TIME);

      // When - 3 failures at the exact same millisecond (no time to leak)
      FailureMetrics updated = metrics
          .recordFailure(START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME);

      // Then
      LeakyBucketMetrics leakyMetrics = (LeakyBucketMetrics) updated;
      assertThat(leakyMetrics.currentLevel()).isEqualTo(3.0);
    }
  }

  @Nested
  @DisplayName("Leaking Behavior")
  class LeakingBehavior {

    @Test
    @DisplayName("should leak water linearly as time progresses")
    void should_leak_water_linearly_as_time_progresses() {
      // Given - Leak rate is 0.5 per second
      FailureMetrics metrics = LeakyBucketMetrics.initial(0.5, START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME); // Level is exactly 2.0

      // When - 2 seconds pass, exactly 1.0 should leak out (0.5 * 2)
      FailureMetrics updated = metrics.recordSuccess(START_TIME.plusSeconds(2));

      // Then
      LeakyBucketMetrics leakyMetrics = (LeakyBucketMetrics) updated;
      assertThat(leakyMetrics.currentLevel()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should never leak below a level of zero")
    void should_never_leak_below_a_level_of_zero() {
      // Given - Leak rate is 1.0 per second
      FailureMetrics metrics = LeakyBucketMetrics.initial(1.0, START_TIME)
          .recordFailure(START_TIME); // Level is 1.0

      // When - 10 seconds pass, it would theoretically leak 10.0
      FailureMetrics updated = metrics.recordSuccess(START_TIME.plusSeconds(10));

      // Then - Floor is zero
      LeakyBucketMetrics leakyMetrics = (LeakyBucketMetrics) updated;
      assertThat(leakyMetrics.currentLevel()).isZero();
    }
  }

  @Nested
  @DisplayName("Threshold Evaluation")
  class ThresholdEvaluation {

    @Test
    @DisplayName("should return true when immediate failures reach the absolute threshold")
    void should_return_true_when_immediate_failures_reach_the_absolute_threshold() {
      // Given
      CircuitBreakerConfig config = configWithThreshold(3);
      FailureMetrics metrics = LeakyBucketMetrics.initial(1.0, START_TIME);

      // When
      FailureMetrics updated = metrics
          .recordFailure(START_TIME)
          .recordFailure(START_TIME)
          .recordFailure(START_TIME);

      // Then
      assertThat(updated.isThresholdReached(config, START_TIME)).isTrue();
    }

    @Test
    @DisplayName("should return false when the bucket leaks fast enough to stay below threshold")
    void should_return_false_when_the_bucket_leaks_fast_enough_to_stay_below_threshold() {
      // Given - Threshold 3, Leak rate 1.0 per second
      CircuitBreakerConfig config = configWithThreshold(3);
      FailureMetrics metrics = LeakyBucketMetrics.initial(1.0, START_TIME);

      // When - 3 failures happen, but spaced by 1 second each.
      // It leaks 1.0 before the next is added, never reaching 3.0.
      FailureMetrics updated = metrics
          .recordFailure(START_TIME)
          .recordFailure(START_TIME.plusSeconds(1))
          .recordFailure(START_TIME.plusSeconds(2));

      // Then
      assertThat(updated.isThresholdReached(config, START_TIME.plusSeconds(2))).isFalse();
    }
  }
}
