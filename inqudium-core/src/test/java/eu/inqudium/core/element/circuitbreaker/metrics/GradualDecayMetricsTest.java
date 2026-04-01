package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GradualDecayMetricsTest {

  private final Instant now = Instant.now();

  @Nested
  class RecordSuccess {

    @Test
    void shouldDecrementFailureCountByOneWhenRecordingSuccess() {
      // Given
      GradualDecayMetrics metrics = new GradualDecayMetrics(3);

      // When
      FailureMetrics updatedMetrics = metrics.recordSuccess(now);

      // Then
      assertThat(updatedMetrics).isInstanceOf(GradualDecayMetrics.class);
      assertThat(((GradualDecayMetrics) updatedMetrics).failureCount()).isEqualTo(2);
    }

    @Test
    void shouldNotDecrementFailureCountBelowZeroWhenRecordingSuccess() {
      // Given
      GradualDecayMetrics metrics = new GradualDecayMetrics(0);

      // When
      FailureMetrics updatedMetrics = metrics.recordSuccess(now);

      // Then
      assertThat(((GradualDecayMetrics) updatedMetrics).failureCount()).isZero();
    }
  }

  @Nested
  class RecordFailure {

    @Test
    void shouldIncrementFailureCountByOneWhenRecordingFailure() {
      // Given
      GradualDecayMetrics metrics = new GradualDecayMetrics(2);

      // When
      FailureMetrics updatedMetrics = metrics.recordFailure(now);

      // Then
      assertThat(((GradualDecayMetrics) updatedMetrics).failureCount()).isEqualTo(3);
    }
  }

  @Nested
  class ThresholdEvaluation {

    @Test
    void shouldReturnTrueWhenFailureCountEqualsConfiguredThreshold() {
      // Given
      GradualDecayMetrics metrics = new GradualDecayMetrics(5);
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("test")
          .failureThreshold(5)
          .build();

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isTrue();
    }

    @Test
    void shouldReturnTrueWhenFailureCountExceedsConfiguredThreshold() {
      // Given
      GradualDecayMetrics metrics = new GradualDecayMetrics(6);
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("test")
          .failureThreshold(5)
          .build();

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isTrue();
    }

    @Test
    void shouldReturnFalseWhenFailureCountIsBelowConfiguredThreshold() {
      // Given
      GradualDecayMetrics metrics = new GradualDecayMetrics(4);
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("test")
          .failureThreshold(5)
          .build();

      // When
      boolean thresholdReached = metrics.isThresholdReached(config, now);

      // Then
      assertThat(thresholdReached).isFalse();
    }
  }

  @Nested
  class Reset {

    @Test
    void shouldReturnInitialStateWithZeroFailuresWhenResetting() {
      // Given
      GradualDecayMetrics metrics = new GradualDecayMetrics(10);

      // When
      FailureMetrics resetMetrics = metrics.reset(now);

      // Then
      assertThat(((GradualDecayMetrics) resetMetrics).failureCount()).isZero();
    }
  }
}
