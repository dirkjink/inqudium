package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AimdLimitAlgorithmTest {

    @Nested
    @DisplayName("Additive Increase")
    class AdditiveIncrease {

        @Test
        @DisplayName("A successful call increases the limit by one up to the maximum")
        void aSuccessfulCallIncreasesTheLimitByOneUpToTheMaximum() {
            // Given
            // Initial limit is 5, max is 7.
            // Using the 4-arg constructor sets minUtilizationThreshold to 0.0 internally.
            AimdLimitAlgorithm algorithm = new AimdLimitAlgorithm(5, 1, 7, 0.5);

            // When
            // Simulate a successful call with current in-flight calls matching the limit
            algorithm.update(Duration.ofMillis(100).toNanos(), true, 5);

            // Then
            assertThat(algorithm.getLimit()).isEqualTo(6);

            // When
            // We hit the maximum limit
            algorithm.update(Duration.ofMillis(100).toNanos(), true, 6);
            algorithm.update(Duration.ofMillis(100).toNanos(), true, 7);

            // Then
            // It must not exceed the configured maximum limit
            assertThat(algorithm.getLimit()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("Multiplicative Decrease")
    class MultiplicativeDecrease {

        @Test
        @DisplayName("A failed call multiplies the limit by the backoff ratio down to the minimum")
        void aFailedCallMultipliesTheLimitByTheBackoffRatioDownToTheMinimum() {
            // Given
            // Initial limit is 10, backoff is 0.5 (halving)
            AimdLimitAlgorithm algorithm = new AimdLimitAlgorithm(10, 2, 20, 0.5);

            // When
            // A failure occurs. The in-flight calls parameter is required but does not affect the decrease logic.
            algorithm.update(Duration.ofMillis(100).toNanos(), false, 10);

            // Then
            assertThat(algorithm.getLimit()).isEqualTo(5);

            // When
            // It drops below the minimum limit via consecutive halvings
            algorithm.update(Duration.ofMillis(100).toNanos(), false, 5);
            algorithm.update(Duration.ofMillis(100).toNanos(), false, 2);

            // Then
            // It must not drop below the specified absolute minimum
            assertThat(algorithm.getLimit()).isEqualTo(2);
        }
    }
}