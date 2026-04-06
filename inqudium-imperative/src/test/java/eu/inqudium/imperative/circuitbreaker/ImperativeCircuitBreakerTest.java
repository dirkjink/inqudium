package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerException;
import eu.inqudium.core.element.circuitbreaker.CircuitState;
import eu.inqudium.core.element.circuitbreaker.StateTransition;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeCircuitBreaker")
class ImperativeCircuitBreakerTest {

  private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");
  private TestClock clock;

  @BeforeEach
  void setUp() {
    clock = new TestClock(BASE_TIME);
  }

  private CircuitBreakerConfig defaultConfig() {
    return CircuitBreakerConfig.builder("test-service")
        .failureThreshold(50) // 50% Error Rate
        .minimumNumberOfCalls(3) // Evaluate after 3 calls
        .successThresholdInHalfOpen(2)
        .permittedCallsInHalfOpen(3)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .build();
  }

  private <A, R> ImperativeCircuitBreaker<A, R> createBreaker() {
    return new ImperativeCircuitBreaker<>(defaultConfig(), clock);
  }

  private <A, R> ImperativeCircuitBreaker<A, R> createBreaker(CircuitBreakerConfig config) {
    return new ImperativeCircuitBreaker<>(config, clock);
  }

  /**
   * A simple mutable clock for testing time-dependent behavior.
   */
  static class TestClock extends Clock {
    private Instant instant;

    TestClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      this.instant = this.instant.plus(duration);
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  // ================================================================
  // InqDecorator Contract
  // ================================================================

  @Nested
  @DisplayName("InqDecorator Contract")
  class InqDecoratorContract {

    @Test
    @DisplayName("should implement InqDecorator interface")
    void should_implement_inq_decorator_interface() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When / Then
      assertThat(cb).isInstanceOf(InqDecorator.class);
    }

    @Test
    @DisplayName("should return the config name as element name")
    void should_return_the_config_name_as_element_name() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      String name = cb.getName();

      // Then
      assertThat(name).isEqualTo("test-service");
    }

    @Test
    @DisplayName("should return CIRCUIT_BREAKER as element type")
    void should_return_circuit_breaker_as_element_type() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      InqElementType type = cb.getElementType();

      // Then
      assertThat(type).isEqualTo(InqElementType.CIRCUIT_BREAKER);
    }

    @Test
    @DisplayName("should provide a non-null event publisher")
    void should_provide_a_non_null_event_publisher() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When / Then
      assertThat(cb.getEventPublisher()).isNotNull();
    }

    @Test
    @DisplayName("should delegate to the next step in the chain on success")
    void should_delegate_to_the_next_step_in_the_chain_on_success() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalExecutor<Void, Object> next = (chainId, callId, arg) -> "chain-result";

      // When
      Object result = cb.execute(1L, 1L, null, next);

      // Then
      assertThat(result).isEqualTo("chain-result");
    }

    @Test
    @DisplayName("should record success and remain closed after chain execution")
    void should_record_success_and_remain_closed_after_chain_execution() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalExecutor<Void, Object> next = (chainId, callId, arg) -> "ok";

      // When
      for (int i = 0; i < 5; i++) {
        cb.execute(1L, (long) i, null, next);
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should transition to OPEN when chain failures exceed the threshold")
    void should_transition_to_open_when_chain_failures_exceed_the_threshold() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalExecutor<Void, Object> failingNext = (chainId, callId, arg) -> {
        throw new RuntimeException("chain-fail");
      };

      // When
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(1L, (long) i, null, failingNext);
        } catch (RuntimeException ignored) {
        }
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should reject chain execution with CircuitBreakerException when open")
    void should_reject_chain_execution_with_circuit_breaker_exception_when_open() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalExecutor<Void, Object> failingNext = (chainId, callId, arg) -> {
        throw new RuntimeException("fail");
      };
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(1L, (long) i, null, failingNext);
        } catch (RuntimeException ignored) {
        }
      }

      // When / Then
      InternalExecutor<Void, Object> shouldNotReach = (chainId, callId, arg) -> "unreachable";
      assertThatThrownBy(() -> cb.execute(1L, 99L, null, shouldNotReach))
          .isInstanceOf(CircuitBreakerException.class)
          .hasMessageContaining("test-service");
    }
  }

  // ================================================================
  // Decorator Factory Methods
  // ================================================================

  @Nested
  @DisplayName("Decorator Factory Methods")
  class DecoratorFactoryMethods {

    @Test
    @DisplayName("should create a decorated supplier that applies circuit breaker logic")
    void should_create_a_decorated_supplier_that_applies_circuit_breaker_logic() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      Supplier<String> decorated = cb.decorateSupplier(() -> "supplier-result");

      // Then
      assertThat(decorated.get()).isEqualTo("supplier-result");
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should create a decorated runnable that applies circuit breaker logic")
    void should_create_a_decorated_runnable_that_applies_circuit_breaker_logic() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      AtomicInteger counter = new AtomicInteger(0);

      // When
      Runnable decorated = cb.decorateRunnable(counter::incrementAndGet);
      decorated.run();

      // Then
      assertThat(counter.get()).isEqualTo(1);
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should create a decorated callable that applies circuit breaker logic")
    void should_create_a_decorated_callable_that_applies_circuit_breaker_logic() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      Callable<String> decorated = cb.decorateCallable(() -> "callable-result");

      // Then
      assertThat(decorated.call()).isEqualTo("callable-result");
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ================================================================
  // InqAsyncDecorator Contract
  // ================================================================

  @Nested
  @DisplayName("InqAsyncDecorator Contract")
  class InqAsyncDecoratorContract {

    @Test
    @DisplayName("should implement InqAsyncDecorator interface")
    void should_implement_inq_async_decorator_interface() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When / Then
      assertThat(cb).isInstanceOf(InqAsyncDecorator.class);
    }

    @Test
    @DisplayName("should delegate to the next async step and return result on success")
    void should_delegate_to_the_next_async_step_and_return_result_on_success() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalAsyncExecutor<Void, Object> next =
          (chainId, callId, arg) -> CompletableFuture.completedFuture("async-result");

      // When
      CompletionStage<Object> stage = cb.executeAsync(1L, 1L, null, next);

      // Then
      assertThat(stage.toCompletableFuture().join()).isEqualTo("async-result");
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should record failure when the async stage completes exceptionally")
    void should_record_failure_when_the_async_stage_completes_exceptionally() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalAsyncExecutor<Void, Object> failingNext =
          (chainId, callId, arg) -> CompletableFuture.failedFuture(new RuntimeException("async-fail"));

      // When
      for (int i = 0; i < 3; i++) {
        try {
          cb.executeAsync(1L, (long) i, null, failingNext).toCompletableFuture().join();
        } catch (Exception ignored) {
        }
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should reject async execution with CircuitBreakerException when open")
    void should_reject_async_execution_with_circuit_breaker_exception_when_open() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalAsyncExecutor<Void, Object> failingNext =
          (chainId, callId, arg) -> CompletableFuture.failedFuture(new RuntimeException("fail"));
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          cb.executeAsync(1L, (long) i, null, failingNext).toCompletableFuture().join();
        } catch (Exception ignored) {
        }
      }

      // When / Then
      InternalAsyncExecutor<Void, Object> shouldNotReach =
          (chainId, callId, arg) -> CompletableFuture.completedFuture("unreachable");
      assertThatThrownBy(() -> cb.executeAsync(1L, 99L, null, shouldNotReach))
          .isInstanceOf(CircuitBreakerException.class)
          .hasMessageContaining("test-service");
    }

    @Test
    @DisplayName("should record failure when next throws synchronously during async chain start")
    void should_record_failure_when_next_throws_synchronously_during_async_chain_start() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalAsyncExecutor<Void, Object> syncThrowingNext =
          (chainId, callId, arg) -> {
            throw new RuntimeException("sync-boom");
          };

      // When
      for (int i = 0; i < 3; i++) {
        try {
          cb.executeAsync(1L, (long) i, null, syncThrowingNext);
        } catch (RuntimeException ignored) {
        }
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should remain closed after successful async completions")
    void should_remain_closed_after_successful_async_completions() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalAsyncExecutor<Void, Object> next =
          (chainId, callId, arg) -> CompletableFuture.completedFuture("ok");

      // When
      for (int i = 0; i < 10; i++) {
        cb.executeAsync(1L, (long) i, null, next).toCompletableFuture().join();
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should transition from OPEN to HALF_OPEN after wait duration in async mode")
    void should_transition_from_open_to_half_open_after_wait_duration_in_async_mode() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      InternalAsyncExecutor<Void, Object> failingNext =
          (chainId, callId, arg) -> CompletableFuture.failedFuture(new RuntimeException("fail"));
      for (int i = 0; i < 3; i++) {
        try {
          cb.executeAsync(1L, (long) i, null, failingNext).toCompletableFuture().join();
        } catch (Exception ignored) {
        }
      }
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When — advance time past wait duration
      clock.advance(Duration.ofSeconds(31));
      InternalAsyncExecutor<Void, Object> successNext =
          (chainId, callId, arg) -> CompletableFuture.completedFuture("probe-ok");
      Object result = cb.executeAsync(1L, 99L, null, successNext).toCompletableFuture().join();

      // Then
      assertThat(result).isEqualTo("probe-ok");
    }
  }

  // ================================================================
  // Async Decorator Factory Methods
  // ================================================================

  @Nested
  @DisplayName("Async Decorator Factory Methods")
  class AsyncDecoratorFactoryMethods {

    @Test
    @DisplayName("should create a decorated async supplier that applies circuit breaker logic")
    void should_create_a_decorated_async_supplier_that_applies_circuit_breaker_logic() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      Supplier<CompletionStage<String>> decorated =
          cb.decorateAsyncSupplier(() -> CompletableFuture.completedFuture("async-supplier"));

      // Then
      assertThat(decorated.get().toCompletableFuture().join()).isEqualTo("async-supplier");
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should create a decorated async runnable that applies circuit breaker logic")
    void should_create_a_decorated_async_runnable_that_applies_circuit_breaker_logic() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      AtomicInteger counter = new AtomicInteger(0);

      // When
      Supplier<CompletionStage<Void>> decorated =
          cb.decorateAsyncRunnable(counter::incrementAndGet);
      decorated.get().toCompletableFuture().join();

      // Then
      assertThat(counter.get()).isEqualTo(1);
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should create a decorated async callable that applies circuit breaker logic")
    void should_create_a_decorated_async_callable_that_applies_circuit_breaker_logic() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      CompletableFuture<String> originalFuture = CompletableFuture.completedFuture("async-callable");
      Callable<CompletionStage<String>> decorated =
          cb.decorateAsyncCallable(() -> originalFuture);
      CompletableFuture<String> resultFuture = decorated.call().toCompletableFuture();

      // Then
      assertThat(decorated)
          .isInstanceOfSatisfying(Wrapper.class, wrapper -> {
            long chainId = wrapper.chainId();
            String layerDescription = wrapper.layerDescription();
            long currentCallId = wrapper.currentCallId();
            assertThat(layerDescription).isEqualTo("CIRCUIT_BREAKER(test-service)");
            assertThat(wrapper.toStringHierarchy()).isEqualToIgnoringNewLines(
                "Chain-ID: " + chainId + " (current call-ID: " + currentCallId + ")" + layerDescription);
          });
      assertThat(originalFuture).isSameAs(resultFuture);
      assertThat(resultFuture.join()).isEqualTo("async-callable");
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ================================================================
  // Successful Execution
  // ================================================================

  @Nested
  @DisplayName("Successful Execution")
  class SuccessfulExecution {

    @Test
    @DisplayName("should return the result of a successful callable")
    void should_return_the_result_of_a_successful_callable() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      String result = cb.execute(() -> "hello");

      // Then
      assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("should remain in CLOSED state after successful calls")
    void should_remain_in_closed_state_after_successful_calls() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      for (int i = 0; i < 10; i++) {
        cb.execute(() -> "ok");
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should execute a runnable without throwing")
    void should_execute_a_runnable_without_throwing() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      AtomicInteger counter = new AtomicInteger(0);

      // When
      cb.execute(counter::incrementAndGet);

      // Then
      assertThat(counter.get()).isEqualTo(1);
    }
  }

  // ================================================================
  // Failure Handling
  // ================================================================

  @Nested
  @DisplayName("Failure Handling")
  class FailureHandling {

    @Test
    @DisplayName("should propagate the original exception on failure")
    void should_propagate_the_original_exception_on_failure() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When / Then
      assertThatThrownBy(() -> cb.execute(() -> {
        throw new IllegalStateException("service down");
      })).isInstanceOf(IllegalStateException.class)
          .hasMessage("service down");
    }

    @Test
    @DisplayName("should transition to OPEN after reaching the failure threshold")
    void should_transition_to_open_after_reaching_the_failure_threshold() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(); // 50% threshold, min 3 calls

      // When — record 3 failures (100% error rate, >= 3 calls)
      for (int i = 0; i < 3; i++) {
        assertThatThrownBy(() -> cb.execute(() -> {
          throw new RuntimeException("fail");
        })).isInstanceOf(RuntimeException.class);
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should reject calls with CircuitBreakerException when open")
    void should_reject_calls_with_circuit_breaker_exception_when_open() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception e) {
        }
      }

      // When / Then
      assertThatThrownBy(() -> cb.execute(() -> "should not reach"))
          .isInstanceOf(CircuitBreakerException.class)
          .hasMessageContaining("test-service");
    }

    @Test
    @DisplayName("should not trip if interleaved successes keep the error rate below the threshold")
    void should_not_trip_if_interleaved_successes_keep_the_error_rate_below_the_threshold() throws Exception {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("rate-test")
          .failureThreshold(60) // 60% Error Rate
          .minimumNumberOfCalls(4)
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);

      // When — 2 failures, then 2 successes
      for (int i = 0; i < 2; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }
      cb.execute(() -> "success");
      cb.execute(() -> "success");

      // Then — Error rate is 50% (2 failures out of 4 calls). Threshold is 60%.
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should trip if failure rate exceeds the threshold after minimum calls")
    void should_trip_if_failure_rate_exceeds_the_threshold_after_minimum_calls() throws Exception {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("rate-test")
          .failureThreshold(60) // 60% Error Rate
          .minimumNumberOfCalls(4)
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);

      // When — 3 failures, then 1 success
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }
      cb.execute(() -> "success");

      // Then — Error rate is 75% (3 failures out of 4 calls). Exceeds 60% threshold.
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ================================================================
  // Fallback Execution
  // ================================================================

  @Nested
  @DisplayName("Fallback Execution")
  class FallbackExecution {

    @Test
    @DisplayName("should return the primary result when the circuit is closed")
    void should_return_the_primary_result_when_the_circuit_is_closed() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When
      String result = cb.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("primary");
    }

    @Test
    @DisplayName("should return the fallback value when the circuit is open")
    void should_return_the_fallback_value_when_the_circuit_is_open() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception e) {
        }
      }

      // When
      String result = cb.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("should still propagate non-circuit-breaker exceptions with fallback")
    void should_still_propagate_non_circuit_breaker_exceptions_with_fallback() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();

      // When / Then
      assertThatThrownBy(() -> cb.executeWithFallback(
          () -> {
            throw new IllegalArgumentException("bad input");
          },
          () -> "fallback"
      )).isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ================================================================
  // State Transitions with Time
  // ================================================================

  @Nested
  @DisplayName("State Transitions with Time")
  class StateTransitionsWithTime {

    @Test
    @DisplayName("should transition from OPEN to HALF_OPEN after wait duration expires")
    void should_transition_from_open_to_half_open_after_wait_duration_expires() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception e) {
        }
      }
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When — advance time past the wait duration
      clock.advance(Duration.ofSeconds(31));
      String result = cb.execute(() -> "probe-success");

      // Then
      assertThat(result).isEqualTo("probe-success");
    }

    @Test
    @DisplayName("should transition from HALF_OPEN back to CLOSED after sufficient successes")
    void should_transition_from_half_open_back_to_closed_after_sufficient_successes() throws Exception {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("half-open-test")
          .failureThreshold(50)
          .minimumNumberOfCalls(2)
          .successThresholdInHalfOpen(2)
          .permittedCallsInHalfOpen(3)
          .waitDurationInOpenState(Duration.ofSeconds(10))
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);

      // Open the circuit (2 fails reach the min calls and 100% rate)
      for (int i = 0; i < 2; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception e) {
        }
      }

      // When — wait for timeout and record 2 successes
      clock.advance(Duration.ofSeconds(11));
      cb.execute(() -> "success-1");
      cb.execute(() -> "success-2");

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should transition from HALF_OPEN back to OPEN on a probe failure")
    void should_transition_from_half_open_back_to_open_on_a_probe_failure() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception e) {
        }
      }

      // When — wait and then fail the probe
      clock.advance(Duration.ofSeconds(31));
      try {
        cb.execute(() -> {
          throw new RuntimeException("probe-fail");
        });
      } catch (Exception e) {
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ================================================================
  // Exception Filtering
  // ================================================================

  @Nested
  @DisplayName("Exception Filtering")
  class ExceptionFiltering {

    @Test
    @DisplayName("should not count ignored exceptions as failures")
    void should_not_count_ignored_exceptions_as_failures() {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("filter-test")
          .failureThreshold(50)
          .minimumNumberOfCalls(2)
          .waitDurationInOpenState(Duration.ofSeconds(10))
          .ignoreExceptions(IllegalArgumentException.class)
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);

      // When — throw ignored exceptions
      for (int i = 0; i < 5; i++) {
        try {
          cb.execute(() -> {
            throw new IllegalArgumentException("ignored");
          });
        } catch (Exception e) {
        }
      }

      // Then — should still be CLOSED (ignored calls don't fill the error bucket)
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should only count configured exception types as failures")
    void should_only_count_configured_exception_types_as_failures() {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("record-test")
          .failureThreshold(50)
          .minimumNumberOfCalls(2)
          .waitDurationInOpenState(Duration.ofSeconds(10))
          .recordExceptions(java.io.IOException.class)
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);

      // When — throw a non-recorded exception
      for (int i = 0; i < 5; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("not recorded");
          });
        } catch (Exception e) {
        }
      }

      // Then — should still be CLOSED
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ================================================================
  // State Transition Listeners
  // ================================================================

  @Nested
  @DisplayName("State Transition Listeners")
  class StateTransitionListeners {

    @Test
    @DisplayName("should notify listeners when the state changes to OPEN")
    void should_notify_listeners_when_the_state_changes_to_open() {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      List<StateTransition> transitions = new ArrayList<>();
      cb.onStateTransition(transitions::add);

      // When — trigger 3 failures
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception e) {
        }
      }

      // Then
      assertThat(transitions).hasSize(1);
      assertThat(transitions.getFirst().fromState()).isEqualTo(CircuitState.CLOSED);
      assertThat(transitions.getFirst().toState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("should notify listeners on each state transition in a full cycle")
    void should_notify_listeners_on_each_state_transition_in_a_full_cycle() throws Exception {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("listener-cycle")
          .failureThreshold(50)
          .minimumNumberOfCalls(1)
          .successThresholdInHalfOpen(1)
          .permittedCallsInHalfOpen(1)
          .waitDurationInOpenState(Duration.ofSeconds(5))
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);

      // When — CLOSED → OPEN
      try {
        cb.execute(() -> {
          throw new RuntimeException("fail");
        });
      } catch (Exception e) {
      }
      // When — OPEN → HALF_OPEN → CLOSED
      clock.advance(Duration.ofSeconds(6));
      cb.execute(() -> "success");

      // Then — expect 3 transitions: CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED
      assertThat(transitions).hasSize(3);
      assertThat(transitions.get(0).toState()).isEqualTo(CircuitState.OPEN);
      assertThat(transitions.get(1).toState()).isEqualTo(CircuitState.HALF_OPEN);
      assertThat(transitions.get(2).toState()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ================================================================
  // Manual Reset
  // ================================================================

  @Nested
  @DisplayName("Manual Reset")
  class ManualReset {

    @Test
    @DisplayName("should reset an open circuit breaker to CLOSED state")
    void should_reset_an_open_circuit_breaker_to_closed_state() throws Exception {
      // Given
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker();
      for (int i = 0; i < 3; i++) {
        try {
          cb.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception e) {
        }
      }
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When
      cb.reset();

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
      assertThat(cb.execute(() -> "after-reset")).isEqualTo("after-reset");
    }
  }

  // ================================================================
  // Concurrency with Virtual Threads
  // ================================================================

  @Nested
  @DisplayName("Concurrency with Virtual Threads")
  class ConcurrencyWithVirtualThreads {

    @Test
    @DisplayName("should handle concurrent calls safely using virtual threads")
    void should_handle_concurrent_calls_safely_using_virtual_threads() throws Exception {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("concurrency-test")
          .failureThreshold(100) // 100% threshold
          .minimumNumberOfCalls(10)
          .waitDurationInOpenState(Duration.ofSeconds(30))
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);
      int threadCount = 1000;
      AtomicInteger successCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      // When
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              cb.execute(() -> {
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
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("should correctly transition under concurrent failures with virtual threads")
    void should_correctly_transition_under_concurrent_failures_with_virtual_threads() throws Exception {
      // Given
      CircuitBreakerConfig config = CircuitBreakerConfig.builder("concurrent-fail")
          .failureThreshold(50)
          .minimumNumberOfCalls(10)
          .waitDurationInOpenState(Duration.ofSeconds(30))
          .build();
      ImperativeCircuitBreaker<Void, Object>  cb = createBreaker(config);
      int threadCount = 50;
      CountDownLatch latch = new CountDownLatch(threadCount);

      // When — all threads fail
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              cb.execute(() -> {
                throw new RuntimeException("fail");
              });
            } catch (Exception e) {
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(10, TimeUnit.SECONDS);
      }

      // Then — circuit should be OPEN
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ================================================================
  // Configuration Validation
  // ================================================================

  @Nested
  @DisplayName("Configuration Validation")
  class ConfigurationValidation {

    @Test
    @DisplayName("should reject a failure threshold below one")
    void should_reject_a_failure_threshold_below_one() {
      // Given / When / Then
      assertThatThrownBy(() -> CircuitBreakerConfig.builder("bad")
          .failureThreshold(0)
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a negative wait duration")
    void should_reject_a_negative_wait_duration() {
      // Given / When / Then
      assertThatThrownBy(() -> CircuitBreakerConfig.builder("bad")
          .waitDurationInOpenState(Duration.ofSeconds(-1))
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a null name")
    void should_reject_a_null_name() {
      // Given / When / Then
      assertThatThrownBy(() -> CircuitBreakerConfig.builder(null))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
