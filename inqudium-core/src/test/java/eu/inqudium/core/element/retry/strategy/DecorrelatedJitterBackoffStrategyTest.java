package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecorrelatedJitterBackoffStrategyTest {

    private Duration initialDelay;
    private Duration maxDelay;
    private DecorrelatedJitterBackoffStrategy strategy;

    @BeforeEach
    void setUp() {
        initialDelay = Duration.ofMillis(100);
        maxDelay = Duration.ofSeconds(10);
        strategy = new DecorrelatedJitterBackoffStrategy(initialDelay, maxDelay);
    }

    @Nested
    @DisplayName("Delay Computation and Jitter Bounds")
    class ComputationTests {

        @Test
        @DisplayName("The first attempt should calculate jitter based on the initial delay")
        void firstAttemptShouldUseInitialDelayAsBase() {
            // Given
            Duration previousDelay = Duration.ZERO; // Simulates first attempt

            // When: Run multiple times to verify bounds across random variations
            for (int i = 0; i < 100; i++) {
                Duration delay = strategy.computeDelay(0, previousDelay);

                // Then: Base is 100ms. Upper bound is min(10s, 100ms * 3) = 300ms
                assertThat(delay).isBetween(initialDelay, Duration.ofMillis(300));
            }
        }

        @Test
        @DisplayName("Subsequent attempts should calculate jitter based on the previous delay")
        void subsequentAttemptsShouldUsePreviousDelayAsBase() {
            // Given
            Duration previousDelay = Duration.ofMillis(500);

            // When: Run multiple times to verify bounds
            for (int i = 0; i < 100; i++) {
                Duration delay = strategy.computeDelay(1, previousDelay);

                // Then: Base is 500ms. Upper bound is min(10s, 500ms * 3) = 1500ms
                // Lower bound is always initialDelay (100ms)
                assertThat(delay).isBetween(initialDelay, Duration.ofMillis(1500));
            }
        }

        @Test
        @DisplayName("The computed delay should never exceed the configured maximum delay")
        void delayShouldNotExceedMaximum() {
            // Given: A very high previous delay
            Duration previousDelay = Duration.ofSeconds(8);

            // When: Run multiple times
            for (int i = 0; i < 100; i++) {
                Duration delay = strategy.computeDelay(2, previousDelay);

                // Then: 8s * 3 = 24s, which exceeds maxDelay. It should be capped at maxDelay (10s).
                assertThat(delay).isBetween(initialDelay, maxDelay);
            }
        }

        @Test
        @DisplayName("The stateless fallback method should delegate correctly simulating a first attempt")
        void statelessFallbackShouldSimulateFirstAttempt() {
            // Given / When
            Duration delay = strategy.computeDelay(0);

            // Then: It behaves like previousDelay was ZERO
            assertThat(delay).isBetween(initialDelay, Duration.ofMillis(300));
        }
    }

    @Nested
    @DisplayName("Validation and Edge Cases")
    class ValidationTests {

        @Test
        @DisplayName("The strategy should reject null or non-positive constructor arguments")
        void strategyShouldRejectInvalidArguments() {
            // Given / When / Then
            assertThatThrownBy(() -> new DecorrelatedJitterBackoffStrategy(null, maxDelay))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new DecorrelatedJitterBackoffStrategy(initialDelay, null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new DecorrelatedJitterBackoffStrategy(Duration.ZERO, maxDelay))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new DecorrelatedJitterBackoffStrategy(initialDelay, Duration.ofMillis(-5)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("The strategy should handle potential long overflow securely")
        void strategyShouldHandleOverflow() {
            // Given: A previous delay that causes multiplication overflow
            Duration hugeDelay = Duration.ofNanos(Long.MAX_VALUE / 2);

            // When
            Duration delay = strategy.computeDelay(5, hugeDelay);

            // Then: The overflow logic should cap it at maxDelay safely
            assertThat(delay).isBetween(initialDelay, maxDelay);
        }
    }
}
