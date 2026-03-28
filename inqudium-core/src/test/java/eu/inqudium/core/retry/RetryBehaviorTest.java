package eu.inqudium.core.retry;

import eu.inqudium.core.circuitbreaker.CircuitBreakerState;
import eu.inqudium.core.exception.InqCallNotPermittedException;
import eu.inqudium.core.retry.backoff.BackoffStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetryBehavior")
class RetryBehaviorTest {

  private final RetryBehavior behavior = RetryBehavior.defaultBehavior();

  @Nested
  @DisplayName("Max attempts")
  class MaxAttempts {

    @Test
    void should_allow_retry_when_attempts_not_exhausted() {
      // Given
      var config = RetryConfig.builder().maxAttempts(3).backoffStrategy(BackoffStrategy.fixed()).build();

      // When
      var result = behavior.shouldRetry(1, new RuntimeException(), config);

      // Then
      assertThat(result).isPresent();
    }

    @Test
    void should_deny_retry_when_max_attempts_reached() {
      // Given
      var config = RetryConfig.builder().maxAttempts(3).build();

      // When
      var result = behavior.shouldRetry(3, new RuntimeException(), config);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("InqException exclusion")
  class InqExceptionExclusion {

    @Test
    void should_not_retry_on_inqudium_exceptions_by_default() {
      // Given
      var config = RetryConfig.builder().build(); // retryOnInqExceptions = false
      var inqException = new InqCallNotPermittedException("test", CircuitBreakerState.OPEN, 80f);

      // When
      var result = behavior.shouldRetry(1, inqException, config);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    void should_retry_on_inqudium_exceptions_when_explicitly_enabled() {
      // Given
      var config = RetryConfig.builder()
          .retryOnInqExceptions(true)
          .backoffStrategy(BackoffStrategy.fixed())
          .build();
      var inqException = new InqCallNotPermittedException("test", CircuitBreakerState.OPEN, 80f);

      // When
      var result = behavior.shouldRetry(1, inqException, config);

      // Then
      assertThat(result).isPresent();
    }
  }

  @Nested
  @DisplayName("Exception filtering")
  class ExceptionFiltering {

    @Test
    void should_not_retry_on_ignored_exception_types() {
      // Given
      var config = RetryConfig.builder()
          .ignoreOn(IllegalArgumentException.class)
          .backoffStrategy(BackoffStrategy.fixed())
          .build();

      // When
      var result = behavior.shouldRetry(1, new IllegalArgumentException(), config);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    void should_only_retry_on_allowed_exception_types_when_retryOn_is_set() {
      // Given
      var config = RetryConfig.builder()
          .retryOn(java.io.IOException.class)
          .backoffStrategy(BackoffStrategy.fixed())
          .build();

      // When — IOException should retry
      var ioResult = behavior.shouldRetry(1, new java.io.IOException(), config);
      // RuntimeException should not retry (not in retryOn list)
      var rtResult = behavior.shouldRetry(1, new RuntimeException(), config);

      // Then
      assertThat(ioResult).isPresent();
      assertThat(rtResult).isEmpty();
    }
  }

  @Nested
  @DisplayName("Delay computation")
  class DelayComputation {

    @Test
    void should_compute_delay_using_the_configured_backoff_strategy() {
      // Given
      var config = RetryConfig.builder()
          .initialInterval(Duration.ofMillis(200))
          .backoffStrategy(BackoffStrategy.fixed())
          .build();

      // When
      var result = behavior.shouldRetry(1, new RuntimeException(), config);

      // Then
      assertThat(result).isPresent().hasValue(Duration.ofMillis(200));
    }

    @Test
    void should_cap_delay_at_max_interval() {
      // Given
      var config = RetryConfig.builder()
          .initialInterval(Duration.ofSeconds(10))
          .backoffStrategy(BackoffStrategy.exponential())
          .maxInterval(Duration.ofSeconds(5))
          .build();

      // When — exponential: 10s * 2^0 = 10s, capped at 5s
      var result = behavior.shouldRetry(1, new RuntimeException(), config);

      // Then
      assertThat(result).isPresent().hasValue(Duration.ofSeconds(5));
    }
  }
}
