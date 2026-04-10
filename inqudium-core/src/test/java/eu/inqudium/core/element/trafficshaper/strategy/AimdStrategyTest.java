package eu.inqudium.core.element.trafficshaper.strategy;

import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AimdStrategyTest {

    private AimdStrategy strategy;
    private TrafficShaperConfig<AimdState> config;
    private Instant now;

    @BeforeEach
    void setUp() {
        // Decrement 10ms per success, multiply by 2.0 on failure. Min: 20ms, Max: 500ms
        strategy = new AimdStrategy(10_000_000L, 2.0, 20_000_000L, 500_000_000L);
        config = TrafficShaperConfig.builder("aimd-test")
                .withStrategy(strategy)
                .ratePerSecond(10.0) // initial interval: 100ms
                .build();
        now = Instant.parse("2026-04-02T10:00:00Z");
    }

    @Nested
    @DisplayName("Additive Increase")
    class AdditiveIncreaseTests {

        @Test
        @DisplayName("Every success should subtract a fixed amount from the interval")
        void successShouldLinearlyDecreaseInterval() {
            // Given
            AimdState state = strategy.initial(config, now);

            // When
            AimdState afterSuccess = strategy.recordSuccess(state);

            // Then
            // 100ms - 10ms = 90ms
            assertThat(afterSuccess.currentInterval()).isEqualTo(Duration.ofMillis(90));
        }

        @Test
        @DisplayName("The interval should not drop below the minimum allowed limit")
        void intervalShouldNotDropBelowMinimum() {
            // Given
            AimdState state = new AimdState(now, 25_000_000L, 0, 0, 0, 0L); // 25ms

            // When
            AimdState afterSuccess = strategy.recordSuccess(state);

            // Then
            // 25ms - 10ms = 15ms, but capped at 20ms
            assertThat(afterSuccess.currentInterval()).isEqualTo(Duration.ofMillis(20));
        }
    }

    @Nested
    @DisplayName("Multiplicative Decrease")
    class MultiplicativeDecreaseTests {

        @Test
        @DisplayName("Every failure should exponentially increase the interval")
        void failureShouldMultiplyInterval() {
            // Given
            AimdState state = strategy.initial(config, now);

            // When
            AimdState afterFailure = strategy.recordFailure(state);

            // Then
            // 100ms * 2.0 = 200ms
            assertThat(afterFailure.currentInterval()).isEqualTo(Duration.ofMillis(200));
        }
    }
}
