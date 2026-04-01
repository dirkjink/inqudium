package eu.inqudium.imperative.retry;

import eu.inqudium.core.element.retry.RetryConfig;
import eu.inqudium.core.element.retry.RetryEvent;
import eu.inqudium.core.element.retry.RetryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeRetry")
class ImperativeRetryTest {

  private static RetryConfig fastConfig() {
    return RetryConfig.builder("test-retry")
        .maxAttempts(3)
        .noWait()
        .build();
  }

  // ================================================================
  // Successful Execution
  // ================================================================

  @Nested
  @DisplayName("Successful Execution")
  class SuccessfulExecution {

    @Test
    @DisplayName("should return the result when the callable succeeds on the first attempt")
    void should_return_the_result_when_the_callable_succeeds_on_the_first_attempt() throws Exception {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());

      // When
      String result = retry.execute(() -> "hello");

      // Then
      assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("should return the result when the callable succeeds on a retry")
    void should_return_the_result_when_the_callable_succeeds_on_a_retry() throws Exception {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());
      AtomicInteger counter = new AtomicInteger(0);

      // When — fails first, succeeds second
      String result = retry.execute(() -> {
        if (counter.incrementAndGet() < 2) {
          throw new RuntimeException("transient failure");
        }
        return "recovered";
      });

      // Then
      assertThat(result).isEqualTo("recovered");
      assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("should execute a runnable successfully without throwing")
    void should_execute_a_runnable_successfully_without_throwing() throws Exception {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());
      AtomicInteger counter = new AtomicInteger(0);

      // When
      retry.execute(counter::incrementAndGet);

      // Then
      assertThat(counter.get()).isEqualTo(1);
    }
  }

  // ================================================================
  // Retry Exhaustion
  // ================================================================

  @Nested
  @DisplayName("Retry Exhaustion")
  class RetryExhaustion {

    @Test
    @DisplayName("should throw RetryException when all attempts are exhausted")
    void should_throw_retry_exception_when_all_attempts_are_exhausted() {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig()); // maxAttempts=3

      // When / Then
      assertThatThrownBy(() -> retry.execute(() -> {
        throw new RuntimeException("always fails");
      })).isInstanceOf(RetryException.class)
          .hasMessageContaining("test-retry")
          .satisfies(e -> {
            RetryException re = (RetryException) e;
            assertThat(re.getAttempts()).isEqualTo(3);
            assertThat(re.getFailures()).hasSize(3);
          });
    }

    @Test
    @DisplayName("should call the callable exactly maxAttempts times when all fail")
    void should_call_the_callable_exactly_max_attempts_times_when_all_fail() {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig()); // maxAttempts=3
      AtomicInteger counter = new AtomicInteger(0);

      // When
      try {
        retry.execute(() -> {
          counter.incrementAndGet();
          throw new RuntimeException("fail");
        });
      } catch (Exception e) {
      }

      // Then
      assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("should include all failures as suppressed in the RetryException")
    void should_include_all_failures_in_the_retry_exception() {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());
      AtomicInteger counter = new AtomicInteger(0);

      // When / Then
      assertThatThrownBy(() -> retry.execute(() -> {
        throw new RuntimeException("fail " + counter.incrementAndGet());
      })).isInstanceOf(RetryException.class)
          .satisfies(e -> {
            RetryException re = (RetryException) e;
            assertThat(re.getFailures()).hasSize(3);
            assertThat(re.getFailures().get(0).getMessage()).isEqualTo("fail 1");
            assertThat(re.getFailures().get(2).getMessage()).isEqualTo("fail 3");
          });
    }
  }

  // ================================================================
  // Non-Retryable Exception
  // ================================================================

  @Nested
  @DisplayName("Non-Retryable Exception Handling")
  class NonRetryableExceptions {

    @Test
    @DisplayName("should propagate the exception immediately for non-retryable types")
    void should_propagate_the_exception_immediately_for_non_retryable_types() {
      // Given
      RetryConfig config = RetryConfig.builder("filter-test")
          .maxAttempts(5)
          .retryOnExceptions(java.io.IOException.class)
          .noWait()
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);
      AtomicInteger counter = new AtomicInteger(0);

      // When / Then
      assertThatThrownBy(() -> retry.execute(() -> {
        counter.incrementAndGet();
        throw new IllegalArgumentException("not retryable");
      })).isInstanceOf(IllegalArgumentException.class);

      // Only 1 attempt — no retries for non-retryable exceptions
      assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("should retry only the configured exception types")
    void should_retry_only_the_configured_exception_types() throws Exception {
      // Given
      RetryConfig config = RetryConfig.builder("selective-retry")
          .maxAttempts(3)
          .retryOnExceptions(java.io.IOException.class)
          .noWait()
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);
      AtomicInteger counter = new AtomicInteger(0);

      // When — first call throws IOException (retryable), second succeeds
      String result = retry.execute(() -> {
        if (counter.incrementAndGet() < 2) {
          throw new java.io.IOException("transient IO failure");
        }
        return "success";
      });

      // Then
      assertThat(result).isEqualTo("success");
      assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("should not retry on ignored exception types")
    void should_not_retry_on_ignored_exception_types() {
      // Given
      RetryConfig config = RetryConfig.builder("ignore-test")
          .maxAttempts(5)
          .ignoreExceptions(IllegalStateException.class)
          .noWait()
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);
      AtomicInteger counter = new AtomicInteger(0);

      // When / Then
      assertThatThrownBy(() -> retry.execute(() -> {
        counter.incrementAndGet();
        throw new IllegalStateException("ignored");
      })).isInstanceOf(IllegalStateException.class);

      assertThat(counter.get()).isEqualTo(1);
    }
  }

  // ================================================================
  // Fallback
  // ================================================================

  @Nested
  @DisplayName("Fallback Execution")
  class FallbackExecution {

    @Test
    @DisplayName("should return the primary result when no retries are needed")
    void should_return_the_primary_result_when_no_retries_are_needed() throws Exception {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());

      // When
      String result = retry.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("primary");
    }

    @Test
    @DisplayName("should return the fallback value when all retries are exhausted")
    void should_return_the_fallback_value_when_all_retries_are_exhausted() throws Exception {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());

      // When
      String result = retry.executeWithFallback(
          () -> {
            throw new RuntimeException("always fails");
          },
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("should still propagate non-retryable exceptions even with fallback")
    void should_still_propagate_non_retryable_exceptions_even_with_fallback() {
      // Given
      RetryConfig config = RetryConfig.builder("fallback-non-retryable")
          .maxAttempts(3)
          .retryOnExceptions(java.io.IOException.class)
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);

      // When / Then — IllegalArgumentException is not retryable, not caught by fallback
      assertThatThrownBy(() -> retry.executeWithFallback(
          () -> {
            throw new IllegalArgumentException("bad");
          },
          () -> "fallback"
      )).isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ================================================================
  // Backoff Delay
  // ================================================================

  @Nested
  @DisplayName("Backoff Delay Behavior")
  class BackoffDelayBehavior {

    @Test
    @DisplayName("should apply the configured delay between retries")
    void should_apply_the_configured_delay_between_retries() throws Exception {
      // Given — 50ms fixed delay, 2 retries needed
      RetryConfig config = RetryConfig.builder("delay-test")
          .maxAttempts(3)
          .fixedDelay(Duration.ofMillis(50))
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);
      AtomicInteger counter = new AtomicInteger(0);
      long start = System.nanoTime();

      // When — fails twice, succeeds on third
      String result = retry.execute(() -> {
        if (counter.incrementAndGet() < 3) {
          throw new RuntimeException("fail");
        }
        return "ok";
      });

      long elapsed = (System.nanoTime() - start) / 1_000_000;

      // Then — at least 2 × 50ms of delay
      assertThat(result).isEqualTo("ok");
      assertThat(elapsed).isGreaterThanOrEqualTo(80); // some tolerance
    }
  }

  // ================================================================
  // Event Listeners
  // ================================================================

  @Nested
  @DisplayName("Event Listeners")
  class EventListeners {

    @Test
    @DisplayName("should emit ATTEMPT_STARTED and ATTEMPT_SUCCEEDED for immediate success")
    void should_emit_attempt_started_and_attempt_succeeded_for_immediate_success() throws Exception {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());
      List<RetryEvent> events = new ArrayList<>();
      retry.onEvent(events::add);

      // When
      retry.execute(() -> "ok");

      // Then
      assertThat(events).hasSize(2);
      assertThat(events.get(0).type()).isEqualTo(RetryEvent.Type.ATTEMPT_STARTED);
      assertThat(events.get(0).attemptNumber()).isEqualTo(1);
      assertThat(events.get(1).type()).isEqualTo(RetryEvent.Type.ATTEMPT_SUCCEEDED);
    }

    @Test
    @DisplayName("should emit retry events for each retry attempt")
    void should_emit_retry_events_for_each_retry_attempt() throws Exception {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig()); // maxAttempts=3
      List<RetryEvent> events = new ArrayList<>();
      retry.onEvent(events::add);
      AtomicInteger counter = new AtomicInteger(0);

      // When — fails once, succeeds on second attempt
      retry.execute(() -> {
        if (counter.incrementAndGet() < 2) {
          throw new RuntimeException("fail");
        }
        return "ok";
      });

      // Then
      assertThat(events).extracting(RetryEvent::type).containsExactly(
          RetryEvent.Type.ATTEMPT_STARTED,      // attempt 1
          RetryEvent.Type.RETRY_SCHEDULED,       // failure → schedule retry
          RetryEvent.Type.ATTEMPT_STARTED,      // attempt 2
          RetryEvent.Type.ATTEMPT_SUCCEEDED      // success
      );
    }

    @Test
    @DisplayName("should emit RETRIES_EXHAUSTED event when all attempts fail")
    void should_emit_retries_exhausted_event_when_all_attempts_fail() {
      // Given
      ImperativeRetry retry = new ImperativeRetry(fastConfig());
      List<RetryEvent> events = new ArrayList<>();
      retry.onEvent(events::add);

      // When
      try {
        retry.execute(() -> {
          throw new RuntimeException("fail");
        });
      } catch (Exception e) {
      }

      // Then
      assertThat(events.getLast().type()).isEqualTo(RetryEvent.Type.RETRIES_EXHAUSTED);
    }

    @Test
    @DisplayName("should emit FAILED_NON_RETRYABLE event for non-retryable exceptions")
    void should_emit_failed_non_retryable_event_for_non_retryable_exceptions() {
      // Given
      RetryConfig config = RetryConfig.builder("non-retryable-event")
          .maxAttempts(3)
          .retryOnExceptions(java.io.IOException.class)
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);
      List<RetryEvent> events = new ArrayList<>();
      retry.onEvent(events::add);

      // When
      try {
        retry.execute(() -> {
          throw new IllegalStateException("not retryable");
        });
      } catch (Exception e) {
      }

      // Then
      assertThat(events).extracting(RetryEvent::type).containsExactly(
          RetryEvent.Type.ATTEMPT_STARTED,
          RetryEvent.Type.FAILED_NON_RETRYABLE
      );
    }
  }

  // ================================================================
  // Result-Based Retry
  // ================================================================

  @Nested
  @DisplayName("Result-Based Retry")
  class ResultBasedRetry {

    @Test
    @DisplayName("should retry when the result matches the retry predicate")
    void should_retry_when_the_result_matches_the_retry_predicate() throws Exception {
      // Given
      RetryConfig config = RetryConfig.builder("result-retry")
          .maxAttempts(3)
          .noWait()
          .<String>retryOnResult(result -> result == null)
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);
      AtomicInteger counter = new AtomicInteger(0);

      // When — returns null first, then a valid value
      String result = retry.execute(() -> {
        if (counter.incrementAndGet() < 2) {
          return null;
        }
        return "valid";
      });

      // Then
      assertThat(result).isEqualTo("valid");
      assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("should exhaust retries when the result always matches the retry predicate")
    void should_exhaust_retries_when_the_result_always_matches_the_retry_predicate() {
      // Given
      RetryConfig config = RetryConfig.builder("result-exhaust")
          .maxAttempts(2)
          .noWait()
          .<String>retryOnResult(result -> result == null)
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);

      // When / Then
      assertThatThrownBy(() -> retry.execute(() -> (String) null))
          .isInstanceOf(RetryException.class);
    }
  }

  // ================================================================
  // Concurrency with Virtual Threads
  // ================================================================

  @Nested
  @DisplayName("Concurrency with Virtual Threads")
  class ConcurrencyWithVirtualThreads {

    @Test
    @DisplayName("should handle many concurrent retry executions using virtual threads")
    void should_handle_many_concurrent_retry_executions_using_virtual_threads() throws Exception {
      // Given
      RetryConfig config = RetryConfig.builder("concurrent-retry")
          .maxAttempts(3)
          .noWait()
          .build();
      ImperativeRetry retry = new ImperativeRetry(config);
      int threadCount = 100;
      AtomicInteger successCount = new AtomicInteger(0);
      java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

      // When — each thread fails once then succeeds
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              AtomicInteger localCounter = new AtomicInteger(0);
              retry.execute(() -> {
                if (localCounter.incrementAndGet() < 2) {
                  throw new RuntimeException("transient");
                }
                return "ok";
              });
              successCount.incrementAndGet();
            } catch (Exception e) {
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
      }

      // Then
      assertThat(successCount.get()).isEqualTo(threadCount);
    }
  }

  // ================================================================
  // Introspection
  // ================================================================

  @Nested
  @DisplayName("Introspection")
  class Introspection {

    @Test
    @DisplayName("should return the configuration")
    void should_return_the_configuration() {
      // Given
      RetryConfig config = fastConfig();
      ImperativeRetry retry = new ImperativeRetry(config);

      // When / Then
      assertThat(retry.getConfig()).isEqualTo(config);
      assertThat(retry.getConfig().maxAttempts()).isEqualTo(3);
    }
  }
}
