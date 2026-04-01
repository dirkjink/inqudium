package eu.inqudium.core.element.trafficshaper.strategy;

import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveRateStrategyTest {

  private AdaptiveRateStrategy strategy;
  private TrafficShaperConfig<AdaptiveRateState> config;
  private Instant now;

  @BeforeEach
  void setUp() {
    // Decrease by 10% after 3 successes, double on failure. Min: 50ms, Max: 1000ms
    strategy = new AdaptiveRateStrategy(0.9, 2.0, 50_000_000L, 1_000_000_000L, 3);
    config = TrafficShaperConfig.<AdaptiveRateState>builder("adaptive-test")
        .withStrategy(strategy)
        .ratePerSecond(10.0) // initial interval: 100ms
        .build();
    now = Instant.parse("2026-04-02T10:00:00Z");
  }

  @Nested
  @DisplayName("Rate Adaptation on Success")
  class SuccessAdaptationTests {

    @Test
    @DisplayName("The interval should not decrease before the required number of consecutive successes is reached")
    void intervalShouldNotDecreaseBeforeThreshold() {
      // Given
      AdaptiveRateState state = strategy.initial(config, now);

      // When
      AdaptiveRateState afterOneSuccess = strategy.recordSuccess(state);
      AdaptiveRateState afterTwoSuccesses = strategy.recordSuccess(afterOneSuccess);

      // Then
      assertThat(afterTwoSuccesses.currentInterval()).isEqualTo(Duration.ofMillis(100));
      assertThat(afterTwoSuccesses.consecutiveSuccesses()).isEqualTo(2);
    }

    @Test
    @DisplayName("The interval should decrease after the required number of consecutive successes")
    void intervalShouldDecreaseAfterThreshold() {
      // Given
      AdaptiveRateState state = strategy.initial(config, now);
      AdaptiveRateState afterTwoSuccesses = strategy.recordSuccess(strategy.recordSuccess(state));

      // When
      AdaptiveRateState afterThreeSuccesses = strategy.recordSuccess(afterTwoSuccesses);

      // Then
      // 100ms * 0.9 = 90ms
      assertThat(afterThreeSuccesses.currentInterval()).isEqualTo(Duration.ofMillis(90));
      assertThat(afterThreeSuccesses.consecutiveSuccesses()).isZero(); // Resets after increase
    }
  }

  @Nested
  @DisplayName("Rate Adaptation on Failure")
  class FailureAdaptationTests {

    @Test
    @DisplayName("The interval should immediately increase upon failure")
    void intervalShouldIncreaseImmediatelyOnFailure() {
      // Given
      AdaptiveRateState state = strategy.initial(config, now);

      // When
      AdaptiveRateState afterFailure = strategy.recordFailure(state);

      // Then
      // 100ms * 2.0 = 200ms
      assertThat(afterFailure.currentInterval()).isEqualTo(Duration.ofMillis(200));
      assertThat(afterFailure.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("The interval should not exceed the configured maximum interval")
    void intervalShouldNotExceedMaximum() {
      // Given
      AdaptiveRateState state = new AdaptiveRateState(now, 800_000_000L, 0, 0, 0, 0, 0, 0L); // 800ms

      // When
      AdaptiveRateState afterFailure = strategy.recordFailure(state);

      // Then
      // 800ms * 2.0 = 1600ms, but capped at 1000ms
      assertThat(afterFailure.currentInterval()).isEqualTo(Duration.ofMillis(1000));
    }
  }
}
