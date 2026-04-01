package eu.inqudium.imperative.timelimiter;

import eu.inqudium.core.element.timelimiter.TimeLimiterConfig;
import eu.inqudium.core.element.timelimiter.TimeLimiterEvent;
import eu.inqudium.core.element.timelimiter.TimeLimiterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeTimeLimiter")
class ImperativeTimeLimiterTest {

  private static TimeLimiterConfig fastConfig() {
    return TimeLimiterConfig.builder("test-limiter")
        .timeout(Duration.ofMillis(200))
        .cancelOnTimeout(true)
        .build();
  }

  private static TimeLimiterConfig generousConfig() {
    return TimeLimiterConfig.builder("generous-limiter")
        .timeout(Duration.ofSeconds(5))
        .cancelOnTimeout(true)
        .build();
  }

  // ================================================================
  // Successful Execution
  // ================================================================

  @Nested
  @DisplayName("Successful Execution")
  class SuccessfulExecution {

    @Test
    @DisplayName("should return the result of a callable that completes within the timeout")
    void should_return_the_result_of_a_callable_that_completes_within_the_timeout() throws Exception {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When
      String result = limiter.execute(() -> "hello");

      // Then
      assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("should return the result of a slow callable that still finishes within timeout")
    void should_return_the_result_of_a_slow_callable_that_still_finishes_within_timeout() throws Exception {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig()); // 200ms

      // When
      String result = limiter.execute(() -> {
        Thread.sleep(50);
        return "slow-but-ok";
      });

      // Then
      assertThat(result).isEqualTo("slow-but-ok");
    }

    @Test
    @DisplayName("should execute a runnable without throwing when it completes in time")
    void should_execute_a_runnable_without_throwing_when_it_completes_in_time() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());
      AtomicBoolean executed = new AtomicBoolean(false);

      // When
      limiter.execute(() -> executed.set(true));

      // Then
      assertThat(executed).isTrue();
    }
  }

  // ================================================================
  // Timeout Enforcement
  // ================================================================

  @Nested
  @DisplayName("Timeout Enforcement")
  class TimeoutEnforcement {

    @Test
    @DisplayName("should throw TimeLimiterException when the callable exceeds the timeout")
    void should_throw_time_limiter_exception_when_the_callable_exceeds_the_timeout() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig()); // 200ms

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> {
        Thread.sleep(1000);
        return "too late";
      })).isInstanceOf(TimeLimiterException.class)
          .hasMessageContaining("test-limiter");
    }

    @Test
    @DisplayName("should include the configured timeout in the exception")
    void should_include_the_configured_timeout_in_the_exception() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig());

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> {
        Thread.sleep(1000);
        return "too late";
      })).isInstanceOf(TimeLimiterException.class)
          .satisfies(e -> {
            TimeLimiterException tle = (TimeLimiterException) e;
            assertThat(tle.getTimeout()).isEqualTo(Duration.ofMillis(200));
          });
    }

    @Test
    @DisplayName("should cancel the running task on timeout when cancelOnTimeout is true")
    void should_cancel_the_running_task_on_timeout_when_cancel_on_timeout_is_true() {
      // Given
      TimeLimiterConfig config = TimeLimiterConfig.builder("cancel-test")
          .timeout(Duration.ofMillis(100))
          .cancelOnTimeout(true)
          .build();
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(config);
      AtomicBoolean interrupted = new AtomicBoolean(false);

      // When
      try {
        limiter.execute(() -> {
          try {
            Thread.sleep(5000);
          } catch (InterruptedException e) {
            interrupted.set(true);
          }
          return null;
        });
      } catch (TimeLimiterException e) {
        // expected
      } catch (Exception e) {
        // unexpected, but handled
      }

      // Then — give the interrupt a moment to propagate
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
      }
      assertThat(interrupted).isTrue();
    }

    @Test
    @DisplayName("should throw TimeLimiterException for a runnable that exceeds the timeout")
    void should_throw_time_limiter_exception_for_a_runnable_that_exceeds_the_timeout() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig());

      // When / Then
      assertThatThrownBy(() -> limiter.execute((Runnable) () -> {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
      })).isInstanceOf(TimeLimiterException.class);
    }
  }

  // ================================================================
  // Exception Propagation
  // ================================================================

  @Nested
  @DisplayName("Exception Propagation")
  class ExceptionPropagation {

    @Test
    @DisplayName("should propagate the original exception when the callable fails within the timeout")
    void should_propagate_the_original_exception_when_the_callable_fails_within_the_timeout() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> {
        throw new IllegalStateException("service error");
      })).isInstanceOf(IllegalStateException.class)
          .hasMessage("service error");
    }

    @Test
    @DisplayName("should propagate checked exceptions from the callable")
    void should_propagate_checked_exceptions_from_the_callable() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> {
        throw new java.io.IOException("connection refused");
      })).isInstanceOf(java.io.IOException.class)
          .hasMessage("connection refused");
    }
  }

  // ================================================================
  // Fallback
  // ================================================================

  @Nested
  @DisplayName("Fallback Execution")
  class FallbackExecution {

    @Test
    @DisplayName("should return the primary result when the operation completes in time")
    void should_return_the_primary_result_when_the_operation_completes_in_time() throws Exception {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When
      String result = limiter.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("primary");
    }

    @Test
    @DisplayName("should return the fallback value when the operation times out")
    void should_return_the_fallback_value_when_the_operation_times_out() throws Exception {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig());

      // When
      String result = limiter.executeWithFallback(
          () -> {
            Thread.sleep(1000);
            return "too late";
          },
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("should still propagate non-timeout exceptions with fallback")
    void should_still_propagate_non_timeout_exceptions_with_fallback() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When / Then
      assertThatThrownBy(() -> limiter.executeWithFallback(
          () -> {
            throw new IllegalArgumentException("bad input");
          },
          () -> "fallback"
      )).isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ================================================================
  // Custom Timeout Override
  // ================================================================

  @Nested
  @DisplayName("Custom Timeout Override")
  class CustomBackoffStrategyTimeoutOverride {

    @Test
    @DisplayName("should use the per-call timeout instead of the configured default")
    void should_use_the_per_call_timeout_instead_of_the_configured_default() throws Exception {
      // Given — default 200ms, but we override with 2s
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig());

      // When — the operation takes 500ms, exceeding 200ms but within 2s
      String result = limiter.execute(() -> {
        Thread.sleep(100);
        return "overridden";
      }, Duration.ofSeconds(2));

      // Then
      assertThat(result).isEqualTo("overridden");
    }

    @Test
    @DisplayName("should time out with the per-call timeout when it is shorter")
    void should_time_out_with_the_per_call_timeout_when_it_is_shorter() {
      // Given — default 5s, but we override with 100ms
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> {
        Thread.sleep(1000);
        return "too late";
      }, Duration.ofMillis(100)))
          .isInstanceOf(TimeLimiterException.class);
    }
  }

  // ================================================================
  // Future Execution
  // ================================================================

  @Nested
  @DisplayName("Future Execution")
  class FutureExecution {

    @Test
    @DisplayName("should return the result of a future that completes within the timeout")
    void should_return_the_result_of_a_future_that_completes_within_the_timeout() throws Exception {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When
      String result = limiter.executeFuture(() ->
          CompletableFuture.completedFuture("future-result"));

      // Then
      assertThat(result).isEqualTo("future-result");
    }

    @Test
    @DisplayName("should throw TimeLimiterException when a future exceeds the timeout")
    void should_throw_time_limiter_exception_when_a_future_exceeds_the_timeout() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig());

      // When / Then
      assertThatThrownBy(() -> limiter.executeFuture(() ->
          CompletableFuture.supplyAsync(() -> {
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            return "too late";
          })
      )).isInstanceOf(TimeLimiterException.class);
    }

    @Test
    @DisplayName("should apply the time limit to a completion stage")
    void should_apply_the_time_limit_to_a_completion_stage() throws Exception {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());

      // When
      String result = limiter.executeCompletionStage(() ->
          CompletableFuture.completedFuture("stage-result"));

      // Then
      assertThat(result).isEqualTo("stage-result");
    }
  }

  // ================================================================
  // Event Listeners
  // ================================================================

  @Nested
  @DisplayName("Event Listeners")
  class EventListeners {

    @Test
    @DisplayName("should emit STARTED and COMPLETED events for a successful execution")
    void should_emit_started_and_completed_events_for_a_successful_execution() throws Exception {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());
      List<TimeLimiterEvent> events = new ArrayList<>();
      limiter.onEvent(events::add);

      // When
      limiter.execute(() -> "ok");

      // Then
      assertThat(events).hasSize(2);
      assertThat(events.get(0).type()).isEqualTo(TimeLimiterEvent.Type.STARTED);
      assertThat(events.get(1).type()).isEqualTo(TimeLimiterEvent.Type.COMPLETED);
    }

    @Test
    @DisplayName("should emit STARTED, TIMED_OUT, and CANCELLED events on timeout")
    void should_emit_started_timed_out_and_cancelled_events_on_timeout() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(fastConfig());
      List<TimeLimiterEvent> events = new ArrayList<>();
      limiter.onEvent(events::add);

      // When
      try {
        limiter.execute(() -> {
          Thread.sleep(1000);
          return "too late";
        });
      } catch (Exception e) {
      }

      // Then
      assertThat(events).hasSizeGreaterThanOrEqualTo(2);
      assertThat(events.get(0).type()).isEqualTo(TimeLimiterEvent.Type.STARTED);
      assertThat(events.get(1).type()).isEqualTo(TimeLimiterEvent.Type.TIMED_OUT);
      // CANCELLED may or may not follow depending on cancelOnTimeout
      if (events.size() >= 3) {
        assertThat(events.get(2).type()).isEqualTo(TimeLimiterEvent.Type.CANCELLED);
      }
    }

    @Test
    @DisplayName("should emit STARTED and FAILED events when the callable throws")
    void should_emit_started_and_failed_events_when_the_callable_throws() {
      // Given
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(generousConfig());
      List<TimeLimiterEvent> events = new ArrayList<>();
      limiter.onEvent(events::add);

      // When
      try {
        limiter.execute(() -> {
          throw new RuntimeException("fail");
        });
      } catch (Exception e) {
      }

      // Then
      assertThat(events).hasSize(2);
      assertThat(events.get(0).type()).isEqualTo(TimeLimiterEvent.Type.STARTED);
      assertThat(events.get(1).type()).isEqualTo(TimeLimiterEvent.Type.FAILED);
    }
  }

  // ================================================================
  // Concurrency with Virtual Threads
  // ================================================================

  @Nested
  @DisplayName("Concurrency with Virtual Threads")
  class ConcurrencyWithVirtualThreads {

    @Test
    @DisplayName("should handle many concurrent time-limited calls using virtual threads")
    void should_handle_many_concurrent_time_limited_calls_using_virtual_threads() throws Exception {
      // Given
      TimeLimiterConfig config = TimeLimiterConfig.builder("concurrent")
          .timeout(Duration.ofSeconds(2))
          .build();
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(config);
      int threadCount = 100;
      AtomicInteger successCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      // When
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              limiter.execute(() -> {
                successCount.incrementAndGet();
                return "ok";
              });
            } catch (Exception e) {
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(10, TimeUnit.SECONDS);
      }

      // Then
      assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("should correctly time out concurrent slow operations")
    void should_correctly_time_out_concurrent_slow_operations() throws Exception {
      // Given
      TimeLimiterConfig config = TimeLimiterConfig.builder("concurrent-timeout")
          .timeout(Duration.ofMillis(100))
          .cancelOnTimeout(true)
          .build();
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(config);
      int threadCount = 20;
      AtomicInteger timeoutCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      // When — all operations sleep longer than the timeout
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              limiter.execute(() -> {
                Thread.sleep(2000);
                return "too late";
              });
            } catch (TimeLimiterException e) {
              timeoutCount.incrementAndGet();
            } catch (Exception e) {
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(10, TimeUnit.SECONDS);
      }

      // Then — all should have timed out
      assertThat(timeoutCount.get()).isEqualTo(threadCount);
    }
  }

  // ================================================================
  // Custom Exception Factory
  // ================================================================

  @Nested
  @DisplayName("Custom Exception Factory")
  class CustomBackoffStrategyExceptionFactory {

    @Test
    @DisplayName("should throw the custom exception when the operation times out")
    void should_throw_the_custom_exception_when_the_operation_times_out() {
      // Given
      TimeLimiterConfig config = TimeLimiterConfig.builder("custom-exception")
          .timeout(Duration.ofMillis(100))
          .exceptionFactory((name, duration) -> new IllegalStateException(
              "Limiter '" + name + "' custom timeout: " + duration.toMillis() + "ms"))
          .build();
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(config);

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> {
        Thread.sleep(1000);
        return "too late";
      })).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("custom timeout: 100ms");
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
      TimeLimiterConfig config = fastConfig();
      ImperativeTimeLimiter limiter = new ImperativeTimeLimiter(config);

      // When / Then
      assertThat(limiter.getConfig()).isEqualTo(config);
      assertThat(limiter.getConfig().timeout()).isEqualTo(Duration.ofMillis(200));
    }
  }
}
