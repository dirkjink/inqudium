package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExponentialWithJitterBackoffStrategyTest {

  private Duration initialDelay;
  private Duration maxDelay;
  private ExponentialWithJitterBackoffStrategy strategy;

  @BeforeEach
  void setUp() {
    initialDelay = Duration.ofSeconds(1);
    maxDelay = Duration.ofSeconds(30);
    strategy = new ExponentialWithJitterBackoffStrategy(initialDelay, 2.0, maxDelay);
  }

  @Nested
  @DisplayName("Jittered Delay Computation")
  class ComputationTests {

    @Test
    @DisplayName("The strategy should compute randomized delays within the exponential envelope")
    void strategyShouldComputeJitteredDelaysWithinBounds() {
      // Given / When / Then
      for (int i = 0; i < 50; i++) {
        // Attempt 1: envelope is 1s * 2^1 = 2s
        Duration delay1 = strategy.computeDelay(1);
        assertThat(delay1).isBetween(Duration.ZERO, Duration.ofSeconds(2));

        // Attempt 2: envelope is 1s * 2^2 = 4s
        Duration delay2 = strategy.computeDelay(2);
        assertThat(delay2).isBetween(Duration.ZERO, Duration.ofSeconds(4));
      }
    }

    @Test
    @DisplayName("The strategy should cap the upper envelope bound at maxDelay")
    void strategyShouldCapUpperJitterBoundAtMaxDelay() {
      // Given
      int highAttemptIndex = 6; // 1s * 2^6 = 64s (exceeds maxDelay of 30s)

      // When / Then
      for (int i = 0; i < 50; i++) {
        Duration delay = strategy.computeDelay(highAttemptIndex);
        assertThat(delay).isBetween(Duration.ZERO, maxDelay);
      }
    }
  }

  @Nested
  @DisplayName("Constructor Validation")
  class ValidationTests {

    @Test
    @DisplayName("The strategy should reject invalid configuration parameters")
    void strategyShouldRejectInvalidParameters() {
      // Given / When / Then
      assertThatThrownBy(() -> new ExponentialWithJitterBackoffStrategy(Duration.ofMillis(-1), 2.0, maxDelay))
          .isInstanceOf(IllegalArgumentException.class);

      assertThatThrownBy(() -> new ExponentialWithJitterBackoffStrategy(initialDelay, 0.5, maxDelay))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
