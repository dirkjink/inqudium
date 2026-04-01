package eu.inqudium.core.bulkhead;

import eu.inqudium.core.bulkhead.algo.VegasLimitAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class VegasLimitAlgorithmTest {

  @Nested
  @DisplayName("Windowed Probing and Growth")
  class WindowedProbingAndGrowth {

    @Test
    void limit_should_grow_exactly_by_one_after_full_window_of_successful_requests_without_congestion() {
      // Given
      // Initial limit is 10. To grow by 1, it should take exactly 10 requests.
      int initialLimit = 10;
      AtomicLong virtualClock = new AtomicLong(0);

      // Smoothing 1ns acts as instant overwrite (no smoothing), simulating the old 1.0 factor.
      // Drift ZERO disables the baseline decay.
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          initialLimit,
          1,
          100,
          Duration.ofNanos(1),
          Duration.ZERO,
          virtualClock::get
      );

      // When
      // We simulate a stable downstream system where RTT matches the baseline (gradient = 1.0)
      for (int i = 0; i < initialLimit; i++) {
        // Advance time and feed the algorithm
        virtualClock.addAndGet(Duration.ofMillis(50).toNanos());
        algorithm.update(Duration.ofMillis(50), true);
      }

      // Then
      // The limit should have increased from 10 to exactly 11.
      assertThat(algorithm.getLimit()).isEqualTo(11);
    }

    @Test
    void limit_should_not_grow_explosively_when_handling_high_throughput() {
      // Given
      // A large initial limit to simulate high throughput capacity.
      int initialLimit = 500;
      AtomicLong virtualClock = new AtomicLong(0);
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          initialLimit, 1, 2000, Duration.ofNanos(1), Duration.ZERO, virtualClock::get
      );

      // When
      // We fire 100 successful requests. With a naive +1 logic, the limit would grow by 100.
      for (int i = 0; i < 100; i++) {
        virtualClock.addAndGet(Duration.ofMillis(20).toNanos());
        algorithm.update(Duration.ofMillis(20), true);
      }

      // Then
      // With windowed probing (+1.0/500), 100 requests only add ~0.2.
      // Truncated to int, the visible limit remains at exactly 500.
      assertThat(algorithm.getLimit()).isEqualTo(500);
    }
  }

  @Nested
  @DisplayName("Congestion and Gradient Reaction")
  class CongestionAndGradientReaction {

    @Test
    void limit_should_decrease_proportionally_when_current_Rtt_is_twice_the_baseline() {
      // Given
      AtomicLong virtualClock = new AtomicLong(0);
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          100, 1, 200, Duration.ofNanos(1), Duration.ZERO, virtualClock::get
      );

      // Establish the no-load baseline at 10ms
      virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
      algorithm.update(Duration.ofMillis(10), true);

      // When
      // Downstream slows down to 20ms (gradient = 10 / 20 = 0.5)
      virtualClock.addAndGet(Duration.ofMillis(20).toNanos());
      algorithm.update(Duration.ofMillis(20), true);

      // Then
      // newLimit = 100 * 0.5 + (1.0 / 100) = 50.01 -> truncated to 50
      assertThat(algorithm.getLimit()).isEqualTo(50);
    }

    @Test
    void limit_should_drop_safely_to_the_configured_minimum_limit_but_never_below() {
      // Given
      int minLimit = 5;
      AtomicLong virtualClock = new AtomicLong(0);
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          10, minLimit, 100, Duration.ofNanos(1), Duration.ZERO, virtualClock::get
      );

      // Establish baseline at 10ms
      virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
      algorithm.update(Duration.ofMillis(10), true);

      // When
      // Massive congestion occurs (gradient is capped at 0.5 minimum per step)
      // We run it enough times to force the limit into the floor.
      for (int i = 0; i < 10; i++) {
        virtualClock.addAndGet(Duration.ofMillis(5000).toNanos());
        algorithm.update(Duration.ofMillis(5000), true);
      }

      // Then
      // Limit must not be lower than minLimit
      assertThat(algorithm.getLimit()).isEqualTo(minLimit);
    }
  }

  @Nested
  @DisplayName("Continuous-Time EWMA and Drift Properties")
  class ContinuousTimeEwmaProperties {

    @Test
    void limit_reaction_should_be_smooth_when_using_a_moderate_time_constant() {
      // Given
      AtomicLong virtualClock = new AtomicLong(0);
      // We use a 1-second smoothing constant
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          100, 1, 200, Duration.ofSeconds(1), Duration.ZERO, virtualClock::get
      );

      // Establish baseline at 10ms
      virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
      algorithm.update(Duration.ofMillis(10), true);

      // When
      // Latency spikes to 20ms, but only a short time (10ms) has passed since the last update.
      // Because tau is 1 second, the EWMA will barely move towards 20ms.
      virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
      algorithm.update(Duration.ofMillis(20), true);

      // Then
      // The limit should not drop all the way to 50, but stay very close to 100
      // because the smoothed RTT is still very close to 10ms.
      assertThat(algorithm.getLimit()).isGreaterThan(95);
    }

    @Test
    void baseline_should_drift_upward_over_time_when_drift_is_enabled() {
      // Given
      AtomicLong virtualClock = new AtomicLong(0);
      // Drift time constant is 2 seconds
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          100, 1, 200, Duration.ofNanos(1), Duration.ofSeconds(2), virtualClock::get
      );

      // Establish baseline at 10ms
      virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
      algorithm.update(Duration.ofMillis(10), true);

      // When
      // We simulate a long period (4 seconds) where latency is persistently 20ms.
      virtualClock.addAndGet(Duration.ofSeconds(4).toNanos());
      algorithm.update(Duration.ofMillis(20), true);

      // Then
      // Without drift, gradient = 10 / 20 = 0.5 -> Limit drops to 50.
      // With drift, the baseline of 10ms drifted towards 20ms over the 4 seconds.
      // The new gradient is much higher than 0.5, so the limit should be significantly higher than 50.
      assertThat(algorithm.getLimit()).isGreaterThan(50);
    }
  }

  @Nested
  @DisplayName("Failure Fallback")
  class FailureFallback {

    @Test
    void limit_should_be_reduced_by_fixed_multiplicative_factor_on_failed_requests() {
      // Given
      AtomicLong virtualClock = new AtomicLong(0);
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          100, 1, 200, Duration.ofNanos(1), Duration.ZERO, virtualClock::get
      );

      // When
      // A request fails (e.g. timeout or 5xx error)
      virtualClock.addAndGet(Duration.ofMillis(100).toNanos());
      algorithm.update(Duration.ofMillis(100), false);

      // Then
      // Fallback scales by 0.8: 100 * 0.8 = 80
      assertThat(algorithm.getLimit()).isEqualTo(80);
    }

    @Test
    void an_absolute_failure_immediately_penalizes_the_limit_to_protect_the_backend() {
      // Given
      AtomicLong virtualClock = new AtomicLong(0);
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          10, 2, 20, Duration.ofNanos(1), Duration.ZERO, virtualClock::get
      );

      // When
      // A request fails completely (e.g. 503 error)
      virtualClock.addAndGet(Duration.ofMillis(50).toNanos());
      algorithm.update(Duration.ofMillis(50), false);

      // Then
      // The limit must be aggressively reduced (multiplicative decrease)
      assertThat(algorithm.getLimit()).isEqualTo(8); // 10 * 0.8
    }
  }
}