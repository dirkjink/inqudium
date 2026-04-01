package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExponentialBackoffStrategyTest {

  private Duration initialDelay;
  private Duration maxDelay;

  @BeforeEach
  void setUp() {
    initialDelay = Duration.ofSeconds(1);
    maxDelay = Duration.ofSeconds(30);
  }

  @Nested
  @DisplayName("Delay Computation")
  class ComputationTests {

    @Test
    @DisplayName("The strategy should compute exponentially increasing delays")
    void strategyShouldComputeExponentialDelays() {
      // Given
      ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(initialDelay, 2.0, maxDelay);

      // When / Then
      // Attempt 0: 1s * 2^0 = 1s
      assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ofSeconds(1));
      // Attempt 1: 1s * 2^1 = 2s
      assertThat(strategy.computeDelay(1)).isEqualTo(Duration.ofSeconds(2));
      // Attempt 2: 1s * 2^2 = 4s
      assertThat(strategy.computeDelay(2)).isEqualTo(Duration.ofSeconds(4));
      // Attempt 3: 1s * 2^3 = 8s
      assertThat(strategy.computeDelay(3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("The strategy should cap the delay at the configured maximum")
    void strategyShouldCapAtMaxDelay() {
      // Given
      ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(initialDelay, 2.0, maxDelay);

      // When
      // Attempt 5: 1s * 2^5 = 32s -> exceeds maxDelay (30s)
      Duration delay = strategy.computeDelay(5);

      // Then
      assertThat(delay).isEqualTo(maxDelay);
    }

    @Test
    @DisplayName("The strategy should safely handle mathematical overflow")
    void strategyShouldHandleOverflow() {
      // Given
      ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(initialDelay, 2.0, maxDelay);

      // When: A very high attempt index that causes Double overflow
      Duration delay = strategy.computeDelay(10000);

      // Then
      assertThat(delay).isEqualTo(maxDelay);
    }
  }

  @Nested
  @DisplayName("Constructor Validation")
  class ValidationTests {

    @Test
    @DisplayName("The strategy should reject invalid configuration parameters")
    void strategyShouldRejectInvalidParameters() {
      // Given / When / Then
      assertThatThrownBy(() -> new ExponentialBackoffStrategy(null, 2.0, maxDelay))
          .isInstanceOf(NullPointerException.class);

      assertThatThrownBy(() -> new ExponentialBackoffStrategy(initialDelay, 2.0, null))
          .isInstanceOf(NullPointerException.class);

      assertThatThrownBy(() -> new ExponentialBackoffStrategy(Duration.ZERO, 2.0, maxDelay))
          .isInstanceOf(IllegalArgumentException.class);

      assertThatThrownBy(() -> new ExponentialBackoffStrategy(initialDelay, 0.9, maxDelay))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("multiplier must be >= 1.0");
    }
  }
}
