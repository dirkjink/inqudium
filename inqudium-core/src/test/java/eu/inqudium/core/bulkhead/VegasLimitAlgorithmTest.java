package eu.inqudium.core.bulkhead;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VegasLimitAlgorithmTest {

  @Nested
  class LatencyBasedLimitAdjustments {

    @Test
    void an_increase_in_latency_causes_the_gradient_limit_to_smoothly_decrease() {
      // Given
      // Initial limit 10, min 2, max 20, smoothing 1.0 (instant reaction for testing)
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(10, 2, 20, 1.0);

      // We establish a baseline no-load RTT of 50ms
      algorithm.update(Duration.ofMillis(50), true);
      assertThat(algorithm.getLimit()).isGreaterThanOrEqualTo(10);

      // When
      // The backend becomes slow, and the latency spikes to 100ms (gradient 0.5)
      algorithm.update(Duration.ofMillis(100), true);

      // Then
      // The limit must scale down towards half of the current limit
      assertThat(algorithm.getLimit()).isLessThan(10);
    }

    @Test
    void excellent_latency_causes_the_limit_to_probe_upwards() {
      // Given
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(10, 2, 20, 1.0);
      algorithm.update(Duration.ofMillis(50), true); // baseline

      // When
      // Latency stays exactly at the baseline repeatedly
      algorithm.update(Duration.ofMillis(50), true);
      algorithm.update(Duration.ofMillis(50), true);
      algorithm.update(Duration.ofMillis(50), true);

      // Then
      // The limit probes upwards because the system is performing optimally
      assertThat(algorithm.getLimit()).isGreaterThan(10);
    }

    @Test
    void an_absolute_failure_immediately_penalizes_the_limit_to_protect_the_backend() {
      // Given
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(10, 2, 20, 1.0);

      // When
      // A request fails completely (e.g. 503 error)
      algorithm.update(Duration.ofMillis(50), false);

      // Then
      // The limit must be aggressively reduced (multiplicative decrease)
      assertThat(algorithm.getLimit()).isEqualTo(8); // 10 * 0.8
    }
  }
}
