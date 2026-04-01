package eu.inqudium.core.element.retry.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomBackoffStrategyTest {

  @Nested
  @DisplayName("Execution and Value Calculation")
  class ExecutionTests {

    @Test
    @DisplayName("The strategy should return the exact duration provided by the custom function")
    void strategyShouldReturnDurationFromFunction() {
      // Given
      Duration[] delays = {Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10)};
      IntFunction<Duration> delayFunction = i -> delays[Math.min(i, delays.length - 1)];
      CustomBackoffStrategy strategy = new CustomBackoffStrategy(delayFunction);

      // When
      Duration delayAttempt0 = strategy.computeDelay(0);
      Duration delayAttempt1 = strategy.computeDelay(1);
      Duration delayAttempt5 = strategy.computeDelay(5); // Should cap at last array index

      // Then
      assertThat(delayAttempt0).isEqualTo(Duration.ofSeconds(1));
      assertThat(delayAttempt1).isEqualTo(Duration.ofSeconds(5));
      assertThat(delayAttempt5).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("The strategy should gracefully handle zero duration if the function provides it")
    void strategyShouldHandleZeroDuration() {
      // Given
      CustomBackoffStrategy strategy = new CustomBackoffStrategy(i -> Duration.ZERO);

      // When
      Duration delay = strategy.computeDelay(2);

      // Then
      assertThat(delay).isEqualTo(Duration.ZERO);
    }
  }

  @Nested
  @DisplayName("Validation and Error Handling")
  class ValidationTests {

    @Test
    @DisplayName("The strategy should reject a null function during instantiation")
    void strategyShouldRejectNullFunction() {
      // Given / When / Then
      assertThatThrownBy(() -> new CustomBackoffStrategy(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("delayFunction must not be null");
    }

    @Test
    @DisplayName("The strategy should throw an exception if the custom function returns null")
    void strategyShouldRejectNullReturnFromFunction() {
      // Given
      CustomBackoffStrategy strategy = new CustomBackoffStrategy(i -> null);

      // When / Then
      assertThatThrownBy(() -> strategy.computeDelay(0))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("delayFunction must not return null for attemptIndex 0");
    }

    @Test
    @DisplayName("The strategy should throw an exception if the custom function returns a negative duration")
    void strategyShouldRejectNegativeDuration() {
      // Given
      CustomBackoffStrategy strategy = new CustomBackoffStrategy(i -> Duration.ofSeconds(-5));

      // When / Then
      assertThatThrownBy(() -> strategy.computeDelay(1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("delayFunction returned negative delay");
    }
  }
}
