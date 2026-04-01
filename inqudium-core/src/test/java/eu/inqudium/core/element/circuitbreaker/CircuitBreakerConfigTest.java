package eu.inqudium.core.element.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.element.circuitbreaker.metrics.LeakyBucketMetrics;
import eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedErrorRateMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreakerConfig Builder")
class CircuitBreakerConfigTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

  @Nested
  @DisplayName("Default Metrics Strategy")
  class DefaultMetricsStrategy {

    @Test
    @DisplayName("should use TimeBasedErrorRateMetrics as the default strategy")
    void should_use_time_based_error_rate_metrics_as_the_default_strategy() {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("default-test").build();

      // When
      FailureMetrics initialMetrics = config.metricsFactory().apply(NOW);

      // Then
      assertThat(initialMetrics).isInstanceOf(TimeBasedErrorRateMetrics.class);
    }

    @Test
    @DisplayName("should apply custom sliding window size and minimum calls to the default strategy")
    void should_apply_custom_sliding_window_size_and_minimum_calls_to_the_default_strategy() {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("default-test")
          .slidingWindow(Duration.ofSeconds(60))
          .minimumNumberOfCalls(25)
          .build();

      // When
      FailureMetrics initialMetrics = config.metricsFactory().apply(NOW);

      // Then
      TimeBasedErrorRateMetrics metrics = (TimeBasedErrorRateMetrics) initialMetrics;
      assertThat(metrics.windowSizeInSeconds()).isEqualTo(60);
      assertThat(metrics.minimumNumberOfCalls()).isEqualTo(25);
    }
  }

  @Nested
  @DisplayName("Custom Metrics Strategy")
  class CustomBackoffStrategyMetricsStrategy {

    @Test
    @DisplayName("should use the explicitly provided metrics factory")
    void should_use_the_explicitly_provided_metrics_factory() {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("custom-test")
          .metricsStrategy(now -> LeakyBucketMetrics.initial(0.5, now))
          .build();

      // When
      FailureMetrics initialMetrics = config.metricsFactory().apply(NOW);

      // Then
      assertThat(initialMetrics).isInstanceOf(LeakyBucketMetrics.class);
      assertThat(((LeakyBucketMetrics) initialMetrics).leakRatePerSecond()).isEqualTo(0.5);
    }
  }
}
