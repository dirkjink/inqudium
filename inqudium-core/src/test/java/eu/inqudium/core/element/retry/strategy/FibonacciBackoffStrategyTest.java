package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FibonacciBackoffStrategyTest {

    private Duration initialDelay;
    private Duration maxDelay;
    private FibonacciBackoffStrategy strategy;

    @BeforeEach
    void setUp() {
        initialDelay = Duration.ofSeconds(1);
        maxDelay = Duration.ofSeconds(100);
        strategy = new FibonacciBackoffStrategy(initialDelay, maxDelay);
    }

    @Nested
    @DisplayName("Delay Computation")
    class ComputationTests {

        @Test
        @DisplayName("The strategy should compute delays following the Fibonacci sequence")
        void strategyShouldComputeFibonacciDelays() {
            // Given / When / Then
            // Attempt 0: fib(1) = 1  -> 1s
            assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ofSeconds(1));
            // Attempt 1: fib(2) = 2  -> 2s
            assertThat(strategy.computeDelay(1)).isEqualTo(Duration.ofSeconds(2));
            // Attempt 2: fib(3) = 3  -> 3s
            assertThat(strategy.computeDelay(2)).isEqualTo(Duration.ofSeconds(3));
            // Attempt 3: fib(4) = 5  -> 5s
            assertThat(strategy.computeDelay(3)).isEqualTo(Duration.ofSeconds(5));
            // Attempt 4: fib(5) = 8  -> 8s
            assertThat(strategy.computeDelay(4)).isEqualTo(Duration.ofSeconds(8));
        }

        @Test
        @DisplayName("The strategy should cap the delay at maxDelay")
        void strategyShouldCapAtMaxDelay() {
            // Given
            FibonacciBackoffStrategy strategyWithLowMax = new FibonacciBackoffStrategy(initialDelay, Duration.ofSeconds(10));

            // When / Then
            // Attempt 6: fib(7) = 13 -> exceeds 10s
            assertThat(strategyWithLowMax.computeDelay(6)).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("The strategy should handle integer overflow by falling back to maxDelay")
        void strategyShouldHandleOverflow() {
            // Given
            int veryHighAttemptIndex = 1000;

            // When
            Duration delay = strategy.computeDelay(veryHighAttemptIndex);

            // Then
            assertThat(delay).isEqualTo(maxDelay);
        }
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ValidationTests {

        @Test
        @DisplayName("The strategy should reject negative or zero delays")
        void strategyShouldRejectInvalidParameters() {
            // Given / When / Then
            assertThatThrownBy(() -> new FibonacciBackoffStrategy(Duration.ZERO, maxDelay))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new FibonacciBackoffStrategy(initialDelay, Duration.ofSeconds(-5)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
