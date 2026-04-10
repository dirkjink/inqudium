package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinearBackoffStrategyTest {

    private Duration initialDelay;
    private Duration increment;
    private Duration maxDelay;
    private LinearBackoffStrategy strategy;

    @BeforeEach
    void setUp() {
        initialDelay = Duration.ofSeconds(1);
        increment = Duration.ofMillis(500);
        maxDelay = Duration.ofSeconds(5);
        strategy = new LinearBackoffStrategy(initialDelay, increment, maxDelay);
    }

    @Nested
    @DisplayName("Delay Computation")
    class ComputationTests {

        @Test
        @DisplayName("The strategy should add the increment for each subsequent attempt")
        void strategyShouldComputeLinearDelays() {
            // Given / When / Then
            // Attempt 0: 1000ms + 0 * 500ms = 1000ms
            assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ofSeconds(1));
            // Attempt 1: 1000ms + 1 * 500ms = 1500ms
            assertThat(strategy.computeDelay(1)).isEqualTo(Duration.ofMillis(1500));
            // Attempt 2: 1000ms + 2 * 500ms = 2000ms
            assertThat(strategy.computeDelay(2)).isEqualTo(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("The strategy should cap the delay at maxDelay")
        void strategyShouldCapAtMaxDelay() {
            // Given
            // Attempt 10: 1000ms + 10 * 500ms = 6000ms -> exceeds maxDelay (5000ms)

            // When
            Duration delay = strategy.computeDelay(10);

            // Then
            assertThat(delay).isEqualTo(maxDelay);
        }

        @Test
        @DisplayName("The strategy should handle overflow properly")
        void strategyShouldHandleOverflow() {
            // Given
            int veryHighIndex = Integer.MAX_VALUE;

            // When
            Duration delay = strategy.computeDelay(veryHighIndex);

            // Then
            assertThat(delay).isEqualTo(maxDelay);
        }
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ValidationTests {

        @Test
        @DisplayName("The strategy should reject invalid parameter combinations")
        void strategyShouldRejectInvalidParameters() {
            // Given / When / Then
            assertThatThrownBy(() -> new LinearBackoffStrategy(Duration.ofMillis(-1), increment, maxDelay))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new LinearBackoffStrategy(initialDelay, Duration.ofMillis(-1), maxDelay))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new LinearBackoffStrategy(initialDelay, increment, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
