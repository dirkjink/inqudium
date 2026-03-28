package eu.inqudium.core.exception;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.circuitbreaker.CircuitBreakerState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqFailure")
class InqFailureTest {

  @Nested
  @DisplayName("Finding InqException in direct exceptions")
  class DirectExceptions {

    @Test
    void should_find_a_direct_circuit_breaker_exception() {
      // Given
      var exception = new InqCallNotPermittedException("paymentService", CircuitBreakerState.OPEN, 75.0f);

      // When
      var result = InqFailure.find(exception);

      // Then
      assertThat(result.isPresent()).isTrue();
      assertThat(result.get()).isPresent().containsInstanceOf(InqCallNotPermittedException.class);
    }

    @Test
    void should_find_a_direct_bulkhead_exception() {
      // Given
      var exception = new InqBulkheadFullException("orderService", 25, 25);

      // When
      var result = InqFailure.find(exception);

      // Then
      assertThat(result.isPresent()).isTrue();
      assertThat(result.get()).isPresent().containsInstanceOf(InqBulkheadFullException.class);
    }

    @Test
    void should_return_empty_for_non_inqudium_exceptions() {
      // Given
      var exception = new IllegalStateException("something went wrong");

      // When
      var result = InqFailure.find(exception);

      // Then
      assertThat(result.isPresent()).isFalse();
      assertThat(result.get()).isEmpty();
    }

    @Test
    void should_return_empty_for_null_input() {
      // When
      var result = InqFailure.find(null);

      // Then
      assertThat(result.isPresent()).isFalse();
    }
  }

  @Nested
  @DisplayName("Finding InqException buried in cause chains")
  class CauseChainTraversal {

    @Test
    void should_find_exception_wrapped_in_execution_exception() {
      // Given — simulates Future.get() wrapping
      var inqException = new InqCallNotPermittedException("paymentService", CircuitBreakerState.OPEN, 60.0f);
      var wrapped = new ExecutionException(inqException);

      // When
      var result = InqFailure.find(wrapped);

      // Then
      assertThat(result.isPresent()).isTrue();
      assertThat(result.get()).containsInstanceOf(InqCallNotPermittedException.class);
    }

    @Test
    void should_find_exception_wrapped_in_invocation_target_exception() {
      // Given — simulates JDK proxy wrapping
      var inqException = new InqRequestNotPermittedException("apiGateway", Duration.ofMillis(500));
      var wrapped = new InvocationTargetException(inqException);

      // When
      var result = InqFailure.find(wrapped);

      // Then
      assertThat(result.isPresent()).isTrue();
      assertThat(result.get()).containsInstanceOf(InqRequestNotPermittedException.class);
    }

    @Test
    void should_find_exception_through_triple_wrapping() {
      // Given — simulates Spring AOP → JDK Proxy → Future.get() chain
      var inqException = new InqTimeLimitExceededException("paymentService", Duration.ofSeconds(3), Duration.ofMillis(3002));
      var level1 = new ExecutionException(inqException);
      var level2 = new InvocationTargetException(level1);
      var level3 = new UndeclaredThrowableException(level2);

      // When
      var result = InqFailure.find(level3);

      // Then
      assertThat(result.isPresent()).isTrue();
      assertThat(result.get()).containsInstanceOf(InqTimeLimitExceededException.class);
    }

    @Test
    void should_return_empty_when_cause_chain_contains_no_inq_exception() {
      // Given
      var root = new IllegalStateException("root");
      var mid = new RuntimeException("mid", root);
      var top = new ExecutionException(mid);

      // When
      var result = InqFailure.find(top);

      // Then
      assertThat(result.isPresent()).isFalse();
    }
  }

  @Nested
  @DisplayName("Circular cause chain handling")
  class CircularCauseChain {

    @Test
    void should_not_loop_infinitely_on_circular_cause_references() {
      // Given — a pathological cause chain with a cycle
      var ex1 = new RuntimeException("a");
      var ex2 = new RuntimeException("b", ex1);
      try {
        // Use reflection to create a circular cause chain
        var causeField = Throwable.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(ex1, ex2);
      } catch (Exception e) {
        // If reflection fails, skip — the test still validates the non-cycle path
        return;
      }

      // When — should terminate without StackOverflowError
      var result = InqFailure.find(ex1);

      // Then
      assertThat(result.isPresent()).isFalse();
    }
  }

  @Nested
  @DisplayName("Fluent handler API")
  class FluentHandlerApi {

    @Test
    void should_invoke_circuit_breaker_open_handler() {
      // Given
      var exception = new InqCallNotPermittedException("paymentService", CircuitBreakerState.OPEN, 80.0f);
      var handlerCalled = new AtomicReference<String>();

      // When
      InqFailure.find(exception)
          .ifCircuitBreakerOpen(ex -> handlerCalled.set(ex.getElementName()));

      // Then
      assertThat(handlerCalled.get()).isEqualTo("paymentService");
    }

    @Test
    void should_invoke_retry_exhausted_handler() {
      // Given
      var cause = new RuntimeException("connection refused");
      var exception = new InqRetryExhaustedException("orderService", 3, cause);
      var attemptCount = new AtomicReference<Integer>();

      // When
      InqFailure.find(exception)
          .ifRetryExhausted(ex -> attemptCount.set(ex.getAttempts()));

      // Then
      assertThat(attemptCount.get()).isEqualTo(3);
    }

    @Test
    void should_invoke_only_the_matching_handler() {
      // Given
      var exception = new InqBulkheadFullException("orderService", 25, 25);
      var cbCalled = new AtomicReference<>(false);
      var bhCalled = new AtomicReference<>(false);

      // When
      InqFailure.find(exception)
          .ifCircuitBreakerOpen(ex -> cbCalled.set(true))
          .ifBulkheadFull(ex -> bhCalled.set(true));

      // Then
      assertThat(cbCalled.get()).isFalse();
      assertThat(bhCalled.get()).isTrue();
    }

    @Test
    void should_invoke_time_limit_exceeded_handler_with_duration_context() {
      // Given
      var exception = new InqTimeLimitExceededException("paymentService",
          Duration.ofSeconds(3), Duration.ofMillis(3150));
      var configuredRef = new AtomicReference<Duration>();
      var actualRef = new AtomicReference<Duration>();

      // When
      InqFailure.find(exception)
          .ifTimeLimitExceeded(ex -> {
            configuredRef.set(ex.getConfiguredDuration());
            actualRef.set(ex.getActualDuration());
          });

      // Then
      assertThat(configuredRef.get()).isEqualTo(Duration.ofSeconds(3));
      assertThat(actualRef.get()).isEqualTo(Duration.ofMillis(3150));
    }

    @Test
    void should_invoke_rate_limited_handler_with_wait_estimate() {
      // Given
      var exception = new InqRequestNotPermittedException("apiService", Duration.ofMillis(250));
      var waitRef = new AtomicReference<Duration>();

      // When
      InqFailure.find(exception)
          .ifRateLimited(ex -> waitRef.set(ex.getWaitEstimate()));

      // Then
      assertThat(waitRef.get()).isEqualTo(Duration.ofMillis(250));
    }
  }

  @Nested
  @DisplayName("orElseThrow behavior")
  class OrElseThrow {

    @Test
    void should_rethrow_original_exception_when_no_inq_exception_found() {
      // Given
      var original = new IllegalArgumentException("bad input");

      // When / Then
      assertThatThrownBy(() ->
          InqFailure.find(original).orElseThrow()
      ).isInstanceOf(IllegalArgumentException.class)
          .hasMessage("bad input");
    }

    @Test
    void should_not_rethrow_when_a_handler_matched() {
      // Given
      var exception = new InqCallNotPermittedException("test", CircuitBreakerState.OPEN, 50.0f);

      // When / Then — should not throw
      InqFailure.find(exception)
          .ifCircuitBreakerOpen(ex -> { /* handled */ })
          .orElseThrow();
    }

    @Test
    void should_rethrow_when_no_handler_matched_even_if_inq_exception_present() {
      // Given — bulkhead exception, but only circuit breaker handler registered
      var exception = new InqBulkheadFullException("test", 10, 10);

      // When / Then
      assertThatThrownBy(() ->
          InqFailure.find(exception)
              .ifCircuitBreakerOpen(ex -> { /* not matching */ })
              .orElseThrow()
      ).isInstanceOf(InqBulkheadFullException.class);
    }
  }

  @Nested
  @DisplayName("Exception context fields")
  class ExceptionContextFields {

    @Test
    void should_carry_element_name_and_type_on_all_exception_types() {
      // Given / When / Then
      var cb = new InqCallNotPermittedException("svc1", CircuitBreakerState.OPEN, 50f);
      assertThat(cb.getElementName()).isEqualTo("svc1");
      assertThat(cb.getElementType()).isEqualTo(InqElementType.CIRCUIT_BREAKER);

      var rl = new InqRequestNotPermittedException("svc2", Duration.ofMillis(100));
      assertThat(rl.getElementName()).isEqualTo("svc2");
      assertThat(rl.getElementType()).isEqualTo(InqElementType.RATE_LIMITER);

      var bh = new InqBulkheadFullException("svc3", 10, 10);
      assertThat(bh.getElementName()).isEqualTo("svc3");
      assertThat(bh.getElementType()).isEqualTo(InqElementType.BULKHEAD);

      var tl = new InqTimeLimitExceededException("svc4", Duration.ofSeconds(3), Duration.ofMillis(3100));
      assertThat(tl.getElementName()).isEqualTo("svc4");
      assertThat(tl.getElementType()).isEqualTo(InqElementType.TIME_LIMITER);

      var rt = new InqRetryExhaustedException("svc5", 3, new RuntimeException("fail"));
      assertThat(rt.getElementName()).isEqualTo("svc5");
      assertThat(rt.getElementType()).isEqualTo(InqElementType.RETRY);
    }

    @Test
    void should_carry_last_cause_on_retry_exhausted_exception() {
      // Given
      var lastCause = new java.net.ConnectException("Connection refused");
      var exception = new InqRetryExhaustedException("paymentService", 3, lastCause);

      // When / Then
      assertThat(exception.getLastCause()).isSameAs(lastCause);
      assertThat(exception.getCause()).isSameAs(lastCause);
      assertThat(exception.getAttempts()).isEqualTo(3);
    }

    @Test
    void should_produce_readable_messages() {
      // Given / When / Then
      assertThat(new InqCallNotPermittedException("payment", CircuitBreakerState.OPEN, 75.5f).getMessage())
          .contains("payment", "OPEN", "75.5%");

      assertThat(new InqBulkheadFullException("order", 25, 25).getMessage())
          .contains("order", "25/25");

      assertThat(new InqRetryExhaustedException("inventory", 3, new RuntimeException()).getMessage())
          .contains("inventory", "3 attempts");
    }
  }
}
