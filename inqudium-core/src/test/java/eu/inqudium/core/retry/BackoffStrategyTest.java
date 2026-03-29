package eu.inqudium.core.retry;

import eu.inqudium.core.retry.backoff.BackoffStrategy;
import eu.inqudium.core.retry.backoff.ExponentialBackoff;
import eu.inqudium.core.retry.backoff.FixedBackoff;
import eu.inqudium.core.retry.backoff.RandomizedBackoff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BackoffStrategy")
class BackoffStrategyTest {

  private static final Duration BASE = Duration.ofMillis(500);

  @Nested
  @DisplayName("FixedBackoff")
  class Fixed {

    @Test
    void should_return_the_initial_interval_for_every_attempt() {
      // Given
      var strategy = new FixedBackoff();

      // When / Then
      assertThat(strategy.computeDelay(1, BASE)).isEqualTo(BASE);
      assertThat(strategy.computeDelay(2, BASE)).isEqualTo(BASE);
      assertThat(strategy.computeDelay(10, BASE)).isEqualTo(BASE);
    }
  }

  @Nested
  @DisplayName("ExponentialBackoff")
  class Exponential {

    @Test
    void should_double_delay_per_attempt_with_default_multiplier() {
      // Given
      var strategy = new ExponentialBackoff();

      // When / Then
      assertThat(strategy.computeDelay(1, BASE)).isEqualTo(Duration.ofMillis(500));
      assertThat(strategy.computeDelay(2, BASE)).isEqualTo(Duration.ofMillis(1000));
      assertThat(strategy.computeDelay(3, BASE)).isEqualTo(Duration.ofMillis(2000));
      assertThat(strategy.computeDelay(4, BASE)).isEqualTo(Duration.ofMillis(4000));
    }

    @Test
    void should_respect_custom_multiplier() {
      // Given
      var strategy = new ExponentialBackoff(1.5);

      // When / Then
      assertThat(strategy.computeDelay(1, BASE)).isEqualTo(Duration.ofMillis(500));
      assertThat(strategy.computeDelay(2, BASE)).isEqualTo(Duration.ofMillis(750));
      assertThat(strategy.computeDelay(3, BASE)).isEqualTo(Duration.ofMillis(1125));
    }

    @Test
    void should_reject_multiplier_below_one() {
      assertThatThrownBy(() -> new ExponentialBackoff(0.5))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("RandomizedBackoff — full jitter")
  class FullJitter {

    @RepeatedTest(20)
    void should_produce_delay_between_zero_and_base_delay() {
      // Given
      var strategy = RandomizedBackoff.fullJitter(new ExponentialBackoff());

      // When
      var delay = strategy.computeDelay(2, BASE); // base = 1000ms

      // Then — full jitter: random(0, 1000)
      assertThat(delay.toMillis()).isBetween(0L, 1000L);
    }
  }

  @Nested
  @DisplayName("RandomizedBackoff — equal jitter")
  class EqualJitter {

    @RepeatedTest(20)
    void should_produce_delay_between_half_and_full_base_delay() {
      // Given
      var strategy = RandomizedBackoff.equalJitter(new ExponentialBackoff());

      // When
      var delay = strategy.computeDelay(2, BASE); // base = 1000ms

      // Then — equal jitter: 500 + random(0, 500) → [500, 1000]
      assertThat(delay.toMillis()).isBetween(500L, 1000L);
    }
  }

  @Nested
  @DisplayName("RandomizedBackoff — decorrelated jitter")
  class DecorrelatedJitter {

    @RepeatedTest(20)
    void should_produce_delay_at_least_equal_to_initial_interval() {
      // Given
      var strategy = RandomizedBackoff.decorrelatedJitter(new ExponentialBackoff());

      // When — multiple attempts
      var delay1 = strategy.computeDelay(1, BASE);
      var delay2 = strategy.computeDelay(2, BASE);

      // Then — decorrelated: always >= initialInterval
      assertThat(delay1.toMillis()).isGreaterThanOrEqualTo(BASE.toMillis());
      assertThat(delay2.toMillis()).isGreaterThanOrEqualTo(BASE.toMillis());
    }
  }

  @Nested
  @DisplayName("Factory methods")
  class FactoryMethods {

    @Test
    void should_create_all_strategies_via_factory_methods() {
      // When / Then — should not throw
      assertThat(BackoffStrategy.fixed()).isInstanceOf(FixedBackoff.class);
      assertThat(BackoffStrategy.exponential()).isInstanceOf(ExponentialBackoff.class);
      assertThat(BackoffStrategy.exponential(3.0)).isInstanceOf(ExponentialBackoff.class);
      assertThat(BackoffStrategy.exponentialWithFullJitter()).isInstanceOf(RandomizedBackoff.class);
      assertThat(BackoffStrategy.exponentialWithEqualJitter()).isInstanceOf(RandomizedBackoff.class);
      assertThat(BackoffStrategy.exponentialWithDecorrelatedJitter()).isInstanceOf(RandomizedBackoff.class);
    }
  }
}
