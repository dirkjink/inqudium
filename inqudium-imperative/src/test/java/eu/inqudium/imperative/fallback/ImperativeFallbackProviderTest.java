package eu.inqudium.imperative.fallback;

import eu.inqudium.core.element.fallback.FallbackConfig;
import eu.inqudium.core.element.fallback.FallbackEvent;
import eu.inqudium.core.element.fallback.FallbackException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeFallbackProvider")
class ImperativeFallbackProviderTest {

  // ================================================================
  // Primary Success (No Fallback Needed)
  // ================================================================

  @Nested
  @DisplayName("Primary Success — No Fallback Needed")
  class PrimarySuccess {

    @Test
    @DisplayName("Should return the primary result when the callable succeeds")
    void should_return_the_primary_result_when_the_callable_succeeds() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("test").withDefault("fallback").build());

      // When
      String result = provider.execute(() -> "primary-value");

      // Then
      assertThat(result).isEqualTo("primary-value");
    }

    @Test
    @DisplayName("Should not invoke any fallback handler when the primary succeeds")
    void should_not_invoke_any_fallback_handler_when_the_primary_succeeds() throws Exception {
      // Given
      AtomicInteger fallbackCounter = new AtomicInteger(0);
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("test")
              .onAnyException(e -> {
                fallbackCounter.incrementAndGet();
                return "fallback";
              })
              .build());

      // When
      provider.execute(() -> "ok");

      // Then
      assertThat(fallbackCounter.get()).isZero();
    }
  }

  // ================================================================
  // Exception-Type Routing
  // ================================================================

  @Nested
  @DisplayName("Exception-Type Routing")
  class ExceptionTypeRouting {

    @Test
    @DisplayName("Should route to the correct handler based on exception type")
    void should_route_to_the_correct_handler_based_on_exception_type() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("router")
              .onException(IOException.class, e -> "io-fallback")
              .onException(TimeoutException.class, e -> "timeout-fallback")
              .onAnyException(e -> "catch-all")
              .build());

      // When — IOException is thrown
      String ioResult = provider.execute(() -> {
        throw new IOException("conn refused");
      });

      // Then
      assertThat(ioResult).isEqualTo("io-fallback");
    }

    @Test
    @DisplayName("Should route to the timeout handler for timeout exceptions")
    void should_route_to_the_timeout_handler_for_timeout_exceptions() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("router")
              .onException(IOException.class, e -> "io-fallback")
              .onException(TimeoutException.class, e -> "timeout-fallback")
              .build());

      // When
      String result = provider.execute(() -> {
        throw new TimeoutException("timed out");
      });

      // Then
      assertThat(result).isEqualTo("timeout-fallback");
    }

    @Test
    @DisplayName("Should route to the catch all handler when no specific handler matches")
    void should_route_to_the_catch_all_handler_when_no_specific_handler_matches() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("router")
              .onException(IOException.class, e -> "io-fallback")
              .onAnyException(e -> "catch-all-value")
              .build());

      // When — IllegalStateException doesn't match IOException
      String result = provider.execute(() -> {
        throw new IllegalStateException("unexpected");
      });

      // Then
      assertThat(result).isEqualTo("catch-all-value");
    }

    @Test
    @DisplayName("Should provide the exception to the handler function")
    void should_provide_the_exception_to_the_handler_function() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("test")
              .onException(IOException.class, IOException::getMessage)
              .build());

      // When
      String result = provider.execute(() -> {
        throw new IOException("disk full");
      });

      // Then
      assertThat(result).isEqualTo("disk full");
    }

    @Test
    @DisplayName("Should match subclasses of the registered exception type")
    void should_match_subclasses_of_the_registered_exception_type() throws Exception {
      // Given — register for RuntimeException, throw IllegalArgumentException (subclass)
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("subclass")
              .onException(RuntimeException.class, e -> "runtime-fallback")
              .build());

      // When
      String result = provider.execute(() -> {
        throw new IllegalArgumentException("bad arg");
      });

      // Then
      assertThat(result).isEqualTo("runtime-fallback");
    }
  }

  // ================================================================
  // Predicate-Based Routing
  // ================================================================

  @Nested
  @DisplayName("Predicate-Based Routing")
  class PredicateBasedRouting {

    @Test
    @DisplayName("Should route to the handler when the exception matches the predicate")
    void should_route_to_the_handler_when_the_exception_matches_the_predicate() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("predicate")
              .onExceptionMatching(
                  ex -> ex.getMessage() != null && ex.getMessage().contains("transient"),
                  e -> "transient-fallback")
              .build());

      // When
      String result = provider.execute(() -> {
        throw new RuntimeException("transient error");
      });

      // Then
      assertThat(result).isEqualTo("transient-fallback");
    }

    @Test
    @DisplayName("Should not match when the predicate returns false")
    void should_not_match_when_the_predicate_returns_false() {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("predicate")
              .onExceptionMatching(
                  ex -> ex.getMessage() != null && ex.getMessage().contains("transient"),
                  e -> "transient-fallback")
              .build());

      // When / Then — "permanent" doesn't match "transient"
      assertThatThrownBy(() -> provider.execute(() -> {
        throw new RuntimeException("permanent error");
      })).isInstanceOf(RuntimeException.class)
          .hasMessage("permanent error");
    }
  }

  // ================================================================
  // Constant Value Fallback
  // ================================================================

  @Nested
  @DisplayName("Constant Value Fallback")
  class ConstantValueFallback {

    @Test
    @DisplayName("Should return the constant value for any exception")
    void should_return_the_constant_value_for_any_exception() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("constant").withDefault("default-value").build());

      // When
      String result = provider.execute(() -> {
        throw new RuntimeException("any error");
      });

      // Then
      assertThat(result).isEqualTo("default-value");
    }

    @Test
    @DisplayName("Should return null as a constant value when configured")
    void should_return_null_as_a_constant_value_when_configured() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("null-default").withDefault(null).build());

      // When
      String result = provider.execute(() -> {
        throw new RuntimeException("fail");
      });

      // Then
      assertThat(result).isNull();
    }
  }

  // ================================================================
  // Unhandled Exception (No Handler Matches)
  // ================================================================

  @Nested
  @DisplayName("Unhandled Exception — No Handler Matches")
  class UnhandledException {

    @Test
    @DisplayName("Should propagate the original exception when no handler matches")
    void should_propagate_the_original_exception_when_no_handler_matches() {
      // Given — only handles IOException
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("unhandled")
              .onException(IOException.class, e -> "io-fallback")
              .build());

      // When / Then
      assertThatThrownBy(() -> provider.execute(() -> {
        throw new IllegalStateException("not handled");
      })).isInstanceOf(IllegalStateException.class)
          .hasMessage("not handled");
    }
  }

  // ================================================================
  // Interruption Handling
  // ================================================================

  @Nested
  @DisplayName("Interruption Handling")
  class InterruptionHandling {

    @Test
    @DisplayName("Should bypass fallback chain and rethrow interrupted exception while restoring interrupt flag")
    void should_bypass_fallback_chain_and_rethrow_interrupted_exception_while_restoring_interrupt_flag() {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("interrupt")
              .onAnyException(e -> "catch-all-value") // Should not be invoked
              .build());

      // When / Then
      assertThatThrownBy(() -> provider.execute(() -> {
        throw new InterruptedException("thread interrupted");
      })).isInstanceOf(InterruptedException.class)
          .hasMessage("thread interrupted");

      // Verify interrupt status was restored
      assertThat(Thread.currentThread().isInterrupted()).isTrue();

      // Clear the interrupt status so it doesn't leak into other tests
      Thread.interrupted();
    }
  }

  // ================================================================
  // Fallback Handler Failure
  // ================================================================

  @Nested
  @DisplayName("Fallback Handler Failure")
  class FallbackHandlerFailure {

    @Test
    @DisplayName("Should throw FallbackException when the exception fallback handler itself throws")
    void should_throw_fallback_exception_when_the_exception_fallback_handler_itself_throws() {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("failing-handler")
              .onAnyException(e -> {
                throw new RuntimeException("handler crashed");
              })
              .build());

      // When / Then
      assertThatThrownBy(() -> provider.execute(() -> {
        throw new RuntimeException("primary fail");
      })).isInstanceOf(FallbackException.class)
          .satisfies(e -> {
            FallbackException fe = (FallbackException) e;
            assertThat(fe.getCause().getMessage()).isEqualTo("handler crashed");
          });
    }

    @Test
    @DisplayName("Should propagate result fallback exception transparently without wrapping")
    void should_propagate_result_fallback_exception_transparently_without_wrapping() {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("transparent-result-ex")
              .onResult(result -> result == null, rejectedResult -> {
                throw new IllegalStateException("result was explicitly rejected");
              })
              .onAnyException(e -> "catch-all")
              .build());

      // When / Then
      assertThatThrownBy(() -> provider.execute(() -> null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("result was explicitly rejected");
    }
  }

  // ================================================================
  // Result-Based Fallback
  // ================================================================

  @Nested
  @DisplayName("Result-Based Fallback")
  class ResultBasedFallback {

    @Test
    @DisplayName("Should replace a null result with the fallback value using the function")
    void should_replace_a_null_result_with_the_fallback_value_using_the_function() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("null-check")
              .onResult(result -> result == null, rejectedResult -> "default-value")
              .onAnyException(e -> "error-fallback")
              .build());

      // When
      String result = provider.execute(() -> null);

      // Then
      assertThat(result).isEqualTo("default-value");
    }

    @Test
    @DisplayName("Should allow the result handler to inspect the rejected result")
    void should_allow_the_result_handler_to_inspect_the_rejected_result() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("inspect-result")
              .onResult(
                  result -> result.equals("bad-state"),
                  rejectedResult -> rejectedResult + "-fixed"
              )
              .build());

      // When
      String result = provider.execute(() -> "bad-state");

      // Then
      assertThat(result).isEqualTo("bad-state-fixed");
    }

    @Test
    @DisplayName("Should not invoke the result handler when the result is acceptable")
    void should_not_invoke_the_result_handler_when_the_result_is_acceptable() throws Exception {
      // Given
      AtomicInteger fallbackCounter = new AtomicInteger(0);
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("acceptable")
              .onResult(result -> result == null, rejectedResult -> {
                fallbackCounter.incrementAndGet();
                return "default";
              })
              .onAnyException(e -> "error-fallback")
              .build());

      // When
      String result = provider.execute(() -> "valid");

      // Then
      assertThat(result).isEqualTo("valid");
      assertThat(fallbackCounter.get()).isZero();
    }
  }

  // ================================================================
  // Event Listeners
  // ================================================================

  @Nested
  @DisplayName("Event Listeners")
  class EventListeners {

    @Test
    @DisplayName("Should emit PRIMARY_STARTED and PRIMARY_SUCCEEDED for a successful execution")
    void should_emit_primary_started_and_primary_succeeded_for_a_successful_execution() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("events").withDefault("fb").build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.onEvent(events::add);

      // When
      provider.execute(() -> "ok");

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.PRIMARY_SUCCEEDED
      );
    }

    @Test
    @DisplayName("Should emit the full event sequence for an exception recovery")
    void should_emit_the_full_event_sequence_for_an_exception_recovery() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("events")
              .onAnyException("my-handler", e -> "recovered")
              .build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.onEvent(events::add);

      // When
      provider.execute(() -> {
        throw new RuntimeException("fail");
      });

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.PRIMARY_FAILED,
          FallbackEvent.Type.FALLBACK_INVOKED,
          FallbackEvent.Type.FALLBACK_RECOVERED
      );
      assertThat(events.get(2).handlerName()).isEqualTo("my-handler");
    }

    @Test
    @DisplayName("Should emit NO_HANDLER_MATCHED when no handler is found")
    void should_emit_no_handler_matched_when_no_handler_is_found() {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("events")
              .onException(IOException.class, e -> "io")
              .build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.onEvent(events::add);

      // When
      try {
        provider.execute(() -> {
          throw new IllegalStateException("no match");
        });
      } catch (Exception e) {
        // Ignored for event verification
      }

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.PRIMARY_FAILED,
          FallbackEvent.Type.NO_HANDLER_MATCHED
      );
    }

    @Test
    @DisplayName("Should emit FALLBACK_FAILED when the handler itself throws")
    void should_emit_fallback_failed_when_the_handler_itself_throws() {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("events")
              .onAnyException(e -> {
                throw new RuntimeException("handler crash");
              })
              .build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.onEvent(events::add);

      // When
      try {
        provider.execute(() -> {
          throw new RuntimeException("primary");
        });
      } catch (Exception e) {
        // Ignored for event verification
      }

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.PRIMARY_FAILED,
          FallbackEvent.Type.FALLBACK_INVOKED,
          FallbackEvent.Type.FALLBACK_FAILED
      );
    }

    @Test
    @DisplayName("Should emit RESULT_FALLBACK_INVOKED and RESULT_FALLBACK_RECOVERED for result based fallback")
    void should_emit_result_events_for_result_based_fallback() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("result-events")
              .onResult(result -> result == null, rejectedResult -> "default")
              .onAnyException(e -> "error")
              .build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.onEvent(events::add);

      // When
      provider.execute(() -> null);

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.RESULT_FALLBACK_INVOKED,
          FallbackEvent.Type.RESULT_FALLBACK_RECOVERED
      );
    }

    @Test
    @DisplayName("Should not crash execution when an event listener throws a fatal error")
    void should_not_crash_execution_when_an_event_listener_throws_a_fatal_error() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("listener-crash")
              .withDefault("fallback-value")
              .build());

      // Register a failing listener that throws an Error (e.g. NoClassDefFoundError simulation)
      provider.onEvent(event -> {
        throw new Error("listener unexpectedly crashed");
      });

      // When
      String result = provider.execute(() -> "primary-value");

      // Then — The provider completes successfully despite the listener crash
      assertThat(result).isEqualTo("primary-value");
    }
  }

  // ================================================================
  // Concurrency with Virtual Threads
  // ================================================================

  @Nested
  @DisplayName("Concurrency with Virtual Threads")
  class ConcurrencyWithVirtualThreads {

    @Test
    @DisplayName("Should handle many concurrent fallback executions using virtual threads")
    void should_handle_many_concurrent_fallback_executions_using_virtual_threads() throws Exception {
      // Given
      var provider = new ImperativeFallbackProvider<>(
          FallbackConfig.<String>builder("concurrent")
              .onAnyException(e -> "fallback-value")
              .build());
      int threadCount = 200;
      AtomicInteger recoveredCount = new AtomicInteger(0);
      java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

      // When — half succeed, half fail
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          final int idx = i;
          executor.submit(() -> {
            try {
              String result = provider.execute(() -> {
                if (idx % 2 == 0) {
                  throw new RuntimeException("fail-" + idx);
                }
                return "primary-" + idx;
              });
              if (result.startsWith("fallback") || result.startsWith("primary")) {
                recoveredCount.incrementAndGet();
              }
            } catch (Exception e) {
              // Ignored
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
      }

      // Then — all 200 should have produced a value
      assertThat(recoveredCount.get()).isEqualTo(threadCount);
    }
  }

  // ================================================================
  // Introspection
  // ================================================================

  @Nested
  @DisplayName("Introspection")
  class Introspection {

    @Test
    @DisplayName("Should return the configuration")
    void should_return_the_configuration() {
      // Given
      var config = FallbackConfig.<String>builder("inspect").withDefault("fb").build();
      var provider = new ImperativeFallbackProvider<>(config);

      // Then
      assertThat(provider.getConfig()).isEqualTo(config);
      assertThat(provider.getConfig().name()).isEqualTo("inspect");
    }

    @Test
    @DisplayName("Should report the correct number of registered handlers")
    void should_report_the_correct_number_of_registered_handlers() {
      // Given
      var config = FallbackConfig.<String>builder("handlers")
          .onException(IOException.class, e -> "io")
          .onException(TimeoutException.class, e -> "timeout")
          .onAnyException(e -> "catch-all")
          .build();

      // Then
      assertThat(config.exceptionHandlers()).hasSize(3);
      assertThat(config.resultHandlers()).isEmpty();
    }
  }
}