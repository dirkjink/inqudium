package eu.inqudium.core.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FallbackCore — Functional Per-Execution State Machine")
class FallbackCoreTest {

  // Base arbitrary nanosecond time for testing
  private static final long NOW = 1_000_000_000L;

  // ================================================================
  // Initial State
  // ================================================================

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("An idle snapshot should be in IDLE state with no failure information")
    void an_idle_snapshot_should_be_in_idle_state_with_no_failure_information() {
      // Given / When
      FallbackSnapshot snapshot = FallbackSnapshot.idle();

      // Then
      assertThat(snapshot.state()).isEqualTo(FallbackState.IDLE);
      assertThat(snapshot.primaryFailure()).isNull();
      assertThat(snapshot.fallbackFailure()).isNull();
      assertThat(snapshot.handlerName()).isNull();
      assertThat(snapshot.startNanos()).isZero();
      assertThat(snapshot.fallbackStartNanos()).isZero();
      assertThat(snapshot.endNanos()).isZero();
    }
  }

  // ================================================================
  // Start Transition
  // ================================================================

  @Nested
  @DisplayName("Start Transition (IDLE → EXECUTING)")
  class StartTransition {

    @Test
    @DisplayName("Should transition to EXECUTING state with the start time")
    void should_transition_to_executing_state_with_the_start_time() {
      // Given / When
      FallbackSnapshot snapshot = FallbackCore.start(NOW);

      // Then
      assertThat(snapshot.state()).isEqualTo(FallbackState.EXECUTING);
      assertThat(snapshot.startNanos()).isEqualTo(NOW);
    }
  }

  // ================================================================
  // Primary Success
  // ================================================================

  @Nested
  @DisplayName("Primary Success (EXECUTING → SUCCEEDED)")
  class PrimarySuccess {

    @Test
    @DisplayName("Should transition to SUCCEEDED state with end time")
    void should_transition_to_succeeded_state_with_end_time() {
      // Given
      FallbackSnapshot executing = FallbackCore.start(NOW);
      long completedAt = NOW + Duration.ofMillis(50).toNanos();

      // When
      FallbackSnapshot succeeded = FallbackCore.recordPrimarySuccess(executing, completedAt);

      // Then
      assertThat(succeeded.state()).isEqualTo(FallbackState.SUCCEEDED);
      assertThat(succeeded.state().isTerminal()).isTrue();
      assertThat(succeeded.state().isSuccess()).isTrue();
      assertThat(succeeded.endNanos()).isEqualTo(completedAt);
      assertThat(succeeded.fallbackInvoked()).isFalse();
    }

    @Test
    @DisplayName("Should report the correct elapsed duration")
    void should_report_the_correct_elapsed_duration() {
      // Given
      FallbackSnapshot executing = FallbackCore.start(NOW);
      long completedAt = NOW + Duration.ofMillis(200).toNanos();

      // When
      FallbackSnapshot succeeded = FallbackCore.recordPrimarySuccess(executing, completedAt);

      // Then
      assertThat(succeeded.elapsed(completedAt)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("Should reject recording primary success on a non EXECUTING snapshot")
    void should_reject_recording_primary_success_on_a_non_executing_snapshot() {
      // Given
      FallbackSnapshot idle = FallbackSnapshot.idle();

      // When / Then
      assertThatThrownBy(() -> FallbackCore.recordPrimarySuccess(idle, NOW))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("IDLE");
    }
  }

  // ================================================================
  // Handler Resolution — Exception-Based (Testing Configuration)
  // ================================================================

  @Nested
  @DisplayName("Handler Resolution — Exception-Based")
  class ExceptionHandlerResolution {

    @Test
    @DisplayName("Should resolve the correct handler for a specific exception type")
    void should_resolve_the_correct_handler_for_a_specific_exception_type() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onException(IOException.class, e -> "io-fallback")
          .onException(TimeoutException.class, e -> "timeout-fallback")
          .build();
      IOException ioError = new IOException("connection refused");

      // When
      FallbackExceptionHandler<String> handler = config.findHandlerForException(ioError);

      // Then
      assertThat(handler).isNotNull();
      assertThat(handler.name()).isEqualTo("IOException");
    }

    @Test
    @DisplayName("Should resolve the first matching handler when multiple could match")
    void should_resolve_the_first_matching_handler_when_multiple_could_match() {
      // Given — IOException extends Exception, both handlers could match
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onException("specific", IOException.class, e -> "specific-handler")
          .onAnyException("catch-all", e -> "catch-all-handler")
          .build();

      // When
      FallbackExceptionHandler<String> handler = config.findHandlerForException(new IOException());

      // Then — First registered handler wins
      assertThat(handler).isNotNull();
      assertThat(handler.name()).isEqualTo("specific");
    }

    @Test
    @DisplayName("Should fall through to the catch all handler when no specific handler matches")
    void should_fall_through_to_the_catch_all_handler_when_no_specific_handler_matches() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onException(IOException.class, e -> "io-handler")
          .onAnyException("catch-all", e -> "catch-all-value")
          .build();

      // When — IllegalStateException does not match IOException
      FallbackExceptionHandler<String> handler = config.findHandlerForException(new IllegalStateException());

      // Then
      assertThat(handler).isNotNull();
      assertThat(handler.name()).isEqualTo("catch-all");
    }

    @Test
    @DisplayName("Should return null when no handler matches the exception")
    void should_return_null_when_no_handler_matches_the_exception() {
      // Given — Only handles IOException
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onException(IOException.class, e -> "io-handler")
          .build();

      // When
      FallbackExceptionHandler<String> handler = config.findHandlerForException(
          new IllegalArgumentException("no match"));

      // Then
      assertThat(handler).isNull();
    }

    @Test
    @DisplayName("Should resolve a predicate based handler when the predicate matches")
    void should_resolve_a_predicate_based_handler_when_the_predicate_matches() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onExceptionMatching("timeout-msg",
              ex -> ex.getMessage() != null && ex.getMessage().contains("timeout"),
              e -> "timeout-fallback")
          .build();

      // When
      FallbackExceptionHandler<String> handler = config.findHandlerForException(
          new RuntimeException("connection timeout"));

      // Then
      assertThat(handler).isNotNull();
      assertThat(handler.name()).isEqualTo("timeout-msg");
    }

    @Test
    @DisplayName("Should resolve a constant value handler for any exception")
    void should_resolve_a_constant_value_handler_for_any_exception() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .withDefault("constant-fallback")
          .build();

      // When
      FallbackExceptionHandler<String> handler = config.findHandlerForException(new RuntimeException());

      // Then
      assertThat(handler).isNotNull();
      String value = FallbackCore.invokeExceptionHandler(handler, new RuntimeException());
      assertThat(value).isEqualTo("constant-fallback");
    }
  }

  // ================================================================
  // Handler Resolution — Result-Based (Testing Configuration)
  // ================================================================

  @Nested
  @DisplayName("Handler Resolution — Result-Based")
  class ResultHandlerResolution {

    @Test
    @DisplayName("Should return null when the result is acceptable")
    void should_return_null_when_the_result_is_acceptable() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onResult(result -> result == null, rejectedResult -> "default")
          .onAnyException(e -> "error-fallback")
          .build();

      // When
      FallbackResultHandler<String> handler = config.findHandlerForResult("valid");

      // Then
      assertThat(handler).isNull();
    }

    @Test
    @DisplayName("Should resolve the result handler when the result matches the predicate")
    void should_resolve_the_result_handler_when_the_result_matches_the_predicate() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onResult("null-check", result -> result == null, rejectedResult -> "default-value")
          .onAnyException(e -> "error-fallback")
          .build();

      // When
      FallbackResultHandler<String> handler = config.findHandlerForResult(null);

      // Then
      assertThat(handler).isNotNull();
      assertThat(handler.name()).isEqualTo("null-check");
    }

    @Test
    @DisplayName("Should return null when no result handler is registered")
    void should_return_null_when_no_result_handler_is_registered() {
      // Given — Only exception handlers
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onAnyException(e -> "error-fallback")
          .build();

      // When
      FallbackResultHandler<String> handler = config.findHandlerForResult(null);

      // Then
      assertThat(handler).isNull();
    }
  }

  // ================================================================
  // Fallback Outcome — Success
  // ================================================================

  @Nested
  @DisplayName("Fallback Outcome — Recovery")
  class FallbackRecovery {

    @Test
    @DisplayName("Should transition to RECOVERED when the fallback handler succeeds")
    void should_transition_to_recovered_when_the_fallback_handler_succeeds() {
      // Given
      FallbackSnapshot fallingBack = FallbackCore.start(NOW)
          .withFallingBack(new RuntimeException(), "test-handler", NOW);
      long recoveredAt = NOW + Duration.ofMillis(10).toNanos();

      // When
      FallbackSnapshot recovered = FallbackCore.recordFallbackSuccess(fallingBack, recoveredAt);

      // Then
      assertThat(recovered.state()).isEqualTo(FallbackState.RECOVERED);
      assertThat(recovered.state().isTerminal()).isTrue();
      assertThat(recovered.state().isSuccess()).isTrue();
      assertThat(recovered.handlerName()).isEqualTo("test-handler");
    }

    @Test
    @DisplayName("Should reject recording fallback success on a non FALLING_BACK snapshot")
    void should_reject_recording_fallback_success_on_a_non_falling_back_snapshot() {
      // Given
      FallbackSnapshot executing = FallbackCore.start(NOW);

      // When / Then
      assertThatThrownBy(() -> FallbackCore.recordFallbackSuccess(executing, NOW))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ================================================================
  // Fallback Outcome — Failure
  // ================================================================

  @Nested
  @DisplayName("Fallback Outcome — Failure")
  class FallbackFailure {

    @Test
    @DisplayName("Should transition to FALLBACK_FAILED when the handler throws")
    void should_transition_to_fallback_failed_when_the_handler_throws() {
      // Given
      RuntimeException primary = new RuntimeException("primary");
      RuntimeException fallbackError = new RuntimeException("fallback failed");
      FallbackSnapshot fallingBack = FallbackCore.start(NOW)
          .withFallingBack(primary, "failing-handler", NOW);
      long failedAt = NOW + Duration.ofMillis(5).toNanos();

      // When
      FallbackSnapshot failed = FallbackCore.recordFallbackFailure(fallingBack, fallbackError, failedAt);

      // Then
      assertThat(failed.state()).isEqualTo(FallbackState.FALLBACK_FAILED);
      assertThat(failed.state().isTerminal()).isTrue();
      assertThat(failed.state().isSuccess()).isFalse();
      assertThat(failed.primaryFailure()).isSameAs(primary);
      assertThat(failed.fallbackFailure()).isSameAs(fallbackError);
    }
  }

  // ================================================================
  // Handler Invocation
  // ================================================================

  @Nested
  @DisplayName("Handler Invocation")
  class HandlerInvocation {

    @Test
    @DisplayName("Should invoke a typed exception handler with the correct exception")
    void should_invoke_a_typed_exception_handler_with_the_correct_exception() {
      // Given
      FallbackExceptionHandler<String> handler = new FallbackExceptionHandler.ForExceptionType<>(
          "io-handler", IOException.class, IOException::getMessage);
      IOException error = new IOException("disk full");

      // When
      String result = FallbackCore.invokeExceptionHandler(handler, error);

      // Then
      assertThat(result).isEqualTo("disk full");
    }

    @Test
    @DisplayName("Should invoke a predicate based handler with the exception")
    void should_invoke_a_predicate_based_handler_with_the_exception() {
      // Given
      FallbackExceptionHandler<String> handler = new FallbackExceptionHandler.ForExceptionPredicate<>(
          "msg-handler", e -> true, Throwable::getMessage);

      // When
      String result = FallbackCore.invokeExceptionHandler(handler, new RuntimeException("test-msg"));

      // Then
      assertThat(result).isEqualTo("test-msg");
    }

    @Test
    @DisplayName("Should invoke a catch all handler with any exception")
    void should_invoke_a_catch_all_handler_with_any_exception() {
      // Given
      FallbackExceptionHandler<String> handler = new FallbackExceptionHandler.CatchAll<>("catch-all", e -> "caught");

      // When
      String result = FallbackCore.invokeExceptionHandler(handler, new Error("anything"));

      // Then
      assertThat(result).isEqualTo("caught");
    }

    @Test
    @DisplayName("Should invoke a constant value handler regardless of exception")
    void should_invoke_a_constant_value_handler_regardless_of_exception() {
      // Given
      FallbackExceptionHandler<String> handler = new FallbackExceptionHandler.ConstantValue<>("const", "fixed-value");

      // When
      String result = FallbackCore.invokeExceptionHandler(handler, new RuntimeException());

      // Then
      assertThat(result).isEqualTo("fixed-value");
    }

    @Test
    @DisplayName("Should invoke a result handler and return the fallback value")
    void should_invoke_a_result_handler_and_return_the_fallback_value() {
      // Given
      FallbackResultHandler<String> handler = new FallbackResultHandler.ForResult<>(
          "null-handler", result -> result == null, rejectedResult -> "default");

      // When
      String result = FallbackCore.invokeResultHandler(handler, null);

      // Then
      assertThat(result).isEqualTo("default");
    }
  }

  // ================================================================
  // Full Lifecycle (Manually stepping through core transitions)
  // ================================================================

  @Nested
  @DisplayName("Full Lifecycle")
  class FullLifecycle {

    @Test
    @DisplayName("Should complete a primary success lifecycle without invoking any fallback")
    void should_complete_a_primary_success_lifecycle_without_invoking_any_fallback() {
      // Given
      FallbackSnapshot snapshot = FallbackCore.start(NOW);
      long completedAt = NOW + Duration.ofMillis(10).toNanos();

      // When
      FallbackSnapshot completed = FallbackCore.recordPrimarySuccess(snapshot, completedAt);

      // Then
      assertThat(completed.state()).isEqualTo(FallbackState.SUCCEEDED);
      assertThat(completed.fallbackInvoked()).isFalse();
    }

    @Test
    @DisplayName("Should complete a recovery lifecycle through handler resolution and fallback success")
    void should_complete_a_recovery_lifecycle_through_handler_resolution_and_fallback_success() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("lifecycle")
          .onAnyException(e -> "recovered")
          .build();
      FallbackSnapshot executing = FallbackCore.start(NOW);
      RuntimeException primaryError = new RuntimeException("fail");

      // When — Simulating the manual steps performed by the imperative provider
      FallbackExceptionHandler<String> handler = config.findHandlerForException(primaryError);
      FallbackSnapshot fallingBack = executing.withFallingBack(primaryError, handler.name(), NOW);

      String value = FallbackCore.invokeExceptionHandler(handler, primaryError);

      long recoveredAt = NOW + Duration.ofMillis(5).toNanos();
      FallbackSnapshot recovered = FallbackCore.recordFallbackSuccess(fallingBack, recoveredAt);

      // Then
      assertThat(handler).isNotNull();
      assertThat(value).isEqualTo("recovered");
      assertThat(recovered.state()).isEqualTo(FallbackState.RECOVERED);
      assertThat(recovered.fallbackInvoked()).isTrue();
    }

    @Test
    @DisplayName("Should complete an unhandled lifecycle when no handler matches")
    void should_complete_an_unhandled_lifecycle_when_no_handler_matches() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("unhandled")
          .onException(IOException.class, e -> "io-fallback")
          .build();
      FallbackSnapshot executing = FallbackCore.start(NOW);
      IllegalStateException primaryError = new IllegalStateException("no match");

      // When
      FallbackExceptionHandler<String> handler = config.findHandlerForException(primaryError);
      // Simulate imperative shell generating the unhandled state
      FallbackSnapshot unhandled = executing.withUnhandled(primaryError, NOW);

      // Then
      assertThat(handler).isNull();
      assertThat(unhandled.state()).isEqualTo(FallbackState.UNHANDLED);
    }
  }

  // ================================================================
  // FallbackState Properties
  // ================================================================

  @Nested
  @DisplayName("FallbackState Properties")
  class StateProperties {

    @Test
    @DisplayName("Should identify non terminal states correctly")
    void should_identify_non_terminal_states_correctly() {
      assertThat(FallbackState.IDLE.isTerminal()).isFalse();
      assertThat(FallbackState.EXECUTING.isTerminal()).isFalse();
      assertThat(FallbackState.FALLING_BACK.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Should identify terminal states correctly")
    void should_identify_terminal_states_correctly() {
      assertThat(FallbackState.SUCCEEDED.isTerminal()).isTrue();
      assertThat(FallbackState.RECOVERED.isTerminal()).isTrue();
      assertThat(FallbackState.FALLBACK_FAILED.isTerminal()).isTrue();
      assertThat(FallbackState.UNHANDLED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Should identify success states as both SUCCEEDED and RECOVERED")
    void should_identify_success_states_as_both_succeeded_and_recovered() {
      assertThat(FallbackState.SUCCEEDED.isSuccess()).isTrue();
      assertThat(FallbackState.RECOVERED.isSuccess()).isTrue();
      assertThat(FallbackState.FALLBACK_FAILED.isSuccess()).isFalse();
      assertThat(FallbackState.UNHANDLED.isSuccess()).isFalse();
    }
  }

  // ================================================================
  // Snapshot Immutability
  // ================================================================

  @Nested
  @DisplayName("Snapshot Immutability")
  class SnapshotImmutability {

    @Test
    @DisplayName("Should not modify the original snapshot when recording primary success")
    void should_not_modify_the_original_snapshot_when_recording_primary_success() {
      // Given
      FallbackSnapshot executing = FallbackCore.start(NOW);

      // When
      long completedAt = NOW + Duration.ofMillis(10).toNanos();
      FallbackCore.recordPrimarySuccess(executing, completedAt);

      // Then
      assertThat(executing.state()).isEqualTo(FallbackState.EXECUTING);
      assertThat(executing.endNanos()).isZero();
    }
  }

  // ================================================================
  // Configuration Validation
  // ================================================================

  @Nested
  @DisplayName("Configuration Validation")
  class ConfigurationValidation {

    @Test
    @DisplayName("Should reject a configuration with no handlers via builder")
    void should_reject_a_configuration_with_no_handlers_via_builder() {
      // Given
      FallbackConfig.Builder<String> builder = FallbackConfig.<String>builder("test");

      // When / Then — Building without any handler should fail
      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("At least one");
    }

    @Test
    @DisplayName("Should reject a null name")
    void should_reject_a_null_name() {
      // When / Then
      assertThatThrownBy(() -> FallbackConfig.<String>builder(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject a catch all handler that is not the last exception handler")
    void should_reject_a_catch_all_handler_that_is_not_the_last_exception_handler() {
      // When / Then — CatchAll at position 0 shadows the IOException handler at position 1
      assertThatThrownBy(() -> FallbackConfig.<String>builder("test")
          .onAnyException(e -> "catch-all")
          .onException(IOException.class, e -> "io-handler")
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("shadow");
    }

    @Test
    @DisplayName("Should reject a constant value handler that is not the last exception handler")
    void should_reject_a_constant_value_handler_that_is_not_the_last_exception_handler() {
      // When / Then — ConstantValue at position 0 shadows the IOException handler at position 1
      assertThatThrownBy(() -> FallbackConfig.<String>builder("test")
          .withDefault("default")
          .onException(IOException.class, e -> "io-handler")
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("shadow");
    }

    @Test
    @DisplayName("Should reject a Throwable typed handler that is not the last exception handler")
    void should_reject_a_throwable_typed_handler_that_is_not_the_last_exception_handler() {
      // When / Then — ForExceptionType<Throwable> at position 0 shadows everything after it
      assertThatThrownBy(() -> FallbackConfig.<String>builder("test")
          .onException("broad-catch", Throwable.class, e -> "broad")
          .onException(IOException.class, e -> "io-handler")
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Throwable.class");
    }

    @Test
    @DisplayName("Should accept a catch all handler as the last exception handler")
    void should_accept_a_catch_all_handler_as_the_last_exception_handler() {
      // When / Then — CatchAll at the last position is valid
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onException(IOException.class, e -> "io-handler")
          .onAnyException(e -> "catch-all")
          .build();

      assertThat(config.exceptionHandlers()).hasSize(2);
    }

    @Test
    @DisplayName("Should accept a Throwable typed handler as the only exception handler")
    void should_accept_a_throwable_typed_handler_as_the_only_exception_handler() {
      // When / Then — Single Throwable handler is valid (nothing to shadow)
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onException("broad-catch", Throwable.class, e -> "broad")
          .build();

      assertThat(config.exceptionHandlers()).hasSize(1);
    }

    @Test
    @DisplayName("Should accept a Throwable typed handler as the last exception handler")
    void should_accept_a_throwable_typed_handler_as_the_last_exception_handler() {
      // When / Then — Throwable handler at the end is valid
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onException(IOException.class, e -> "io-handler")
          .onException("broad-catch", Throwable.class, e -> "broad")
          .build();

      assertThat(config.exceptionHandlers()).hasSize(2);
    }
  }

  // ================================================================
  // Configuration Immutability
  // ================================================================

  @Nested
  @DisplayName("Configuration Immutability")
  class ConfigurationImmutability {

    @Test
    @DisplayName("Should return an immutable list of exception handlers")
    void should_return_an_immutable_list_of_exception_handlers() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onAnyException(e -> "fallback")
          .build();

      // When / Then — Attempting to modify the returned list should fail
      assertThatThrownBy(() -> config.exceptionHandlers().add(
          new FallbackExceptionHandler.CatchAll<>("rogue", e -> "injected")))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should return an immutable list of result handlers")
    void should_return_an_immutable_list_of_result_handlers() {
      // Given
      FallbackConfig<String> config = FallbackConfig.<String>builder("test")
          .onResult(result -> result == null, rejectedResult -> "default")
          .onAnyException(e -> "fallback")
          .build();

      // When / Then — Attempting to modify the returned list should fail
      assertThatThrownBy(() -> config.resultHandlers().add(
          new FallbackResultHandler.ForResult<>("rogue", r -> true, r -> "injected")))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should support structural equality for configs with the same handlers")
    void should_support_structural_equality_for_configs_with_the_same_handlers() {
      // Given — Two configs built with the same handler setup
      FallbackConfig<String> config1 = FallbackConfig.<String>builder("test")
          .withDefault("fallback")
          .build();
      FallbackConfig<String> config2 = FallbackConfig.<String>builder("test")
          .withDefault("fallback")
          .build();

      // Then — Should be structurally equal (List-based equals, not array identity)
      assertThat(config1).isEqualTo(config2);
      assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    @DisplayName("Should not be equal when names differ")
    void should_not_be_equal_when_names_differ() {
      // Given
      FallbackConfig<String> config1 = FallbackConfig.<String>builder("alpha")
          .withDefault("fallback")
          .build();
      FallbackConfig<String> config2 = FallbackConfig.<String>builder("beta")
          .withDefault("fallback")
          .build();

      // Then
      assertThat(config1).isNotEqualTo(config2);
    }
  }
}
