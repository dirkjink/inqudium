package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NoWaitBackoffStrategyTest {

  @Nested
  @DisplayName("Delay Computation")
  class ComputationTests {

    @Test
    @DisplayName("The strategy should always return zero duration")
    void strategyShouldAlwaysReturnZero() {
      // Given
      NoWaitBackoffStrategy strategy = new NoWaitBackoffStrategy();

      // When / Then
      assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ZERO);
      assertThat(strategy.computeDelay(5)).isEqualTo(Duration.ZERO);
      assertThat(strategy.computeDelay(999)).isEqualTo(Duration.ZERO);
    }
  }
}
