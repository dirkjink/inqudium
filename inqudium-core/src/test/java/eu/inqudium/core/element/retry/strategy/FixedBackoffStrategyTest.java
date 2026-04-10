package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedBackoffStrategyTest {

    @Nested
    @DisplayName("Delay Computation")
    class ComputationTests {

        @Test
        @DisplayName("The strategy should always return the exact same fixed delay")
        void strategyShouldAlwaysReturnFixedDelay() {
            // Given
            Duration fixedDelay = Duration.ofSeconds(3);
            FixedBackoffStrategy strategy = new FixedBackoffStrategy(fixedDelay);

            // When / Then
            assertThat(strategy.computeDelay(0)).isEqualTo(fixedDelay);
            assertThat(strategy.computeDelay(5)).isEqualTo(fixedDelay);
            assertThat(strategy.computeDelay(100)).isEqualTo(fixedDelay);
        }
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ValidationTests {

        @Test
        @DisplayName("The strategy should reject a null delay")
        void strategyShouldRejectNullDelay() {
            // Given / When / Then
            assertThatThrownBy(() -> new FixedBackoffStrategy(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("The strategy should reject a negative delay")
        void strategyShouldRejectNegativeDelay() {
            // Given / When / Then
            assertThatThrownBy(() -> new FixedBackoffStrategy(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
