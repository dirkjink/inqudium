package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AimdLimitAlgorithmTest {

  @Nested
  class AdditiveIncrease {

    @Test
    void a_successful_call_increases_the_limit_by_one_up_to_the_maximum() {
      // Given
      // Initial limit is 5, max is 7
      AimdLimitAlgorithm algorithm = new AimdLimitAlgorithm(5, 1, 7, 0.5);

      // When
      algorithm.update(Duration.ofMillis(100), true);

      // Then
      assertThat(algorithm.getLimit()).isEqualTo(6);

      // When we hit the limit
      algorithm.update(Duration.ofMillis(100), true);
      algorithm.update(Duration.ofMillis(100), true);

      // Then it must not exceed the maximum
      assertThat(algorithm.getLimit()).isEqualTo(7);
    }
  }

  @Nested
  class MultiplicativeDecrease {

    @Test
    void a_failed_call_multiplies_the_limit_by_the_backoff_ratio_down_to_the_minimum() {
      // Given
      // Initial limit is 10, backoff is 0.5 (halving)
      AimdLimitAlgorithm algorithm = new AimdLimitAlgorithm(10, 2, 20, 0.5);

      // When
      algorithm.update(Duration.ofMillis(100), false);

      // Then
      assertThat(algorithm.getLimit()).isEqualTo(5);

      // When it drops below minimum via halving
      algorithm.update(Duration.ofMillis(100), false);
      algorithm.update(Duration.ofMillis(100), false);

      // Then it must not drop below the specified minimum
      assertThat(algorithm.getLimit()).isEqualTo(2);
    }
  }
}
