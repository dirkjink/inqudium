package eu.inqudium.reactor.retry;

import eu.inqudium.core.retry.BackoffStrategy;
import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.core.retry.RetryCore;
import eu.inqudium.core.retry.RetryDecision;
import eu.inqudium.core.retry.RetrySnapshot;
import eu.inqudium.core.retry.RetryState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RetryCore — Functional Per-Execution State Machine")
class RetryCoreTest {

  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private static RetryConfig defaultConfig() {
    return RetryConfig.builder("test")
        .maxAttempts(3)
        .fixedDelay(Duration.ofMillis(100))
        .build();
  }

  // ================================================================
  // Initial State
  // ================================================================

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("an idle snapshot should be in IDLE state with zero attempts")
    void an_idle_snapshot_should_be_in_idle_state_with_zero_attempts() {
      // Given / When
      RetrySnapshot snapshot = RetrySnapshot.idle();

      // Then
      assertThat(snapshot.state()).isEqualTo(RetryState.IDLE);
      assertThat(snapshot.attemptNumber()).isZero();
      assertThat(snapshot.totalAttempts()).isZero();
      assertThat(snapshot.lastFailure()).isNull();
      assertThat(snapshot.failures()).isEmpty();
    }
  }

  // ================================================================
  // Start First Attempt
  // ================================================================

  @Nested
  @DisplayName("Start First Attempt (IDLE → ATTEMPTING)")
  class StartFirstAttempt {

    @Test
    @DisplayName("should transition to ATTEMPTING state with attempt number 1")
    void should_transition_to_attempting_state_with_attempt_number_1() {
      // Given / When
      RetrySnapshot snapshot = RetryCore.startFirstAttempt(NOW);

      // Then
      assertThat(snapshot.state()).isEqualTo(RetryState.ATTEMPTING);
      assertThat(snapshot.attemptNumber()).isEqualTo(1);
      assertThat(snapshot.totalAttempts()).isEqualTo(1);
      assertThat(snapshot.startTime()).isEqualTo(NOW);
      assertThat(snapshot.attemptStartTime()).isEqualTo(NOW);
    }
  }

  // ================================================================
  // Record Success
  // ================================================================

  @Nested
  @DisplayName("Record Success (ATTEMPTING → COMPLETED)")
  class RecordSuccess {

    @Test
    @DisplayName("should transition to COMPLETED state")
    void should_transition_to_completed_state() {
      // Given
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When
      RetrySnapshot completed = RetryCore.recordSuccess(attempting);

      // Then
      assertThat(completed.state()).isEqualTo(RetryState.COMPLETED);
      assertThat(completed.state().isTerminal()).isTrue();
      assertThat(completed.state().isSuccess()).isTrue();
    }

    @Test
    @DisplayName("should preserve the attempt count on success")
    void should_preserve_the_attempt_count_on_success() {
      // Given
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When
      RetrySnapshot completed = RetryCore.recordSuccess(attempting);

      // Then
      assertThat(completed.attemptNumber()).isEqualTo(1);
      assertThat(completed.retryCount()).isZero();
    }

    @Test
    @DisplayName("should reject recording success on a non-ATTEMPTING snapshot")
    void should_reject_recording_success_on_a_non_attempting_snapshot() {
      // Given
      RetrySnapshot idle = RetrySnapshot.idle();

      // When / Then
      assertThatThrownBy(() -> RetryCore.recordSuccess(idle))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("IDLE");
    }
  }

  // ================================================================
  // Failure Evaluation — Retryable
  // ================================================================

  @Nested
  @DisplayName("Failure Evaluation — Retryable Exception")
  class RetryableFailure {

    @Test
    @DisplayName("should return DoRetry when retries remain and exception is retryable")
    void should_return_do_retry_when_retries_remain_and_exception_is_retryable() {
      // Given
      RetryConfig config = defaultConfig(); // maxAttempts=3
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);
      RuntimeException failure = new RuntimeException("transient");

      // When
      RetryDecision decision = RetryCore.evaluateFailure(attempting, config, failure);

      // Then
      assertThat(decision).isInstanceOf(RetryDecision.DoRetry.class);
      RetryDecision.DoRetry doRetry = (RetryDecision.DoRetry) decision;
      assertThat(doRetry.delay()).isEqualTo(Duration.ofMillis(100));
      assertThat(doRetry.retryIndex()).isZero();
      assertThat(doRetry.snapshot().state()).isEqualTo(RetryState.WAITING_FOR_RETRY);
    }

    @Test
    @DisplayName("should track the failure in the snapshot when scheduling a retry")
    void should_track_the_failure_in_the_snapshot_when_scheduling_a_retry() {
      // Given
      RetryConfig config = defaultConfig();
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);
      RuntimeException failure = new RuntimeException("first failure");

      // When
      RetryDecision decision = RetryCore.evaluateFailure(attempting, config, failure);

      // Then
      RetryDecision.DoRetry doRetry = (RetryDecision.DoRetry) decision;
      assertThat(doRetry.snapshot().lastFailure()).isSameAs(failure);
      assertThat(doRetry.snapshot().failures()).hasSize(1).contains(failure);
    }

    @Test
    @DisplayName("should accumulate failures across multiple retries")
    void should_accumulate_failures_across_multiple_retries() {
      // Given — maxAttempts=3: attempt 1 fails, retry, attempt 2 fails, retry
      RetryConfig config = defaultConfig();
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);
      RuntimeException failure1 = new RuntimeException("failure 1");
      RuntimeException failure2 = new RuntimeException("failure 2");

      // When — first failure
      RetryDecision.DoRetry retry1 = (RetryDecision.DoRetry)
          RetryCore.evaluateFailure(attempt1, config, failure1);
      RetrySnapshot attempt2 = RetryCore.startNextAttempt(retry1.snapshot(), NOW.plusMillis(100));

      // When — second failure
      RetryDecision decision2 = RetryCore.evaluateFailure(attempt2, config, failure2);

      // Then — still retryable (attempt 2 of 3)
      assertThat(decision2).isInstanceOf(RetryDecision.DoRetry.class);
      RetryDecision.DoRetry retry2 = (RetryDecision.DoRetry) decision2;
      assertThat(retry2.snapshot().failures()).hasSize(2);
      assertThat(retry2.snapshot().lastFailure()).isSameAs(failure2);
      assertThat(retry2.retryIndex()).isEqualTo(1);
    }
  }

  // ================================================================
  // Failure Evaluation — Non-Retryable
  // ================================================================

  @Nested
  @DisplayName("Failure Evaluation — Non-Retryable Exception")
  class NonRetryableFailure {

    @Test
    @DisplayName("should return DoNotRetry for exceptions excluded by the predicate")
    void should_return_do_not_retry_for_exceptions_excluded_by_the_predicate() {
      // Given
      RetryConfig config = RetryConfig.builder("filter-test")
          .maxAttempts(3)
          .retryOnExceptions(java.io.IOException.class)
          .build();
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);
      IllegalArgumentException nonRetryable = new IllegalArgumentException("bad input");

      // When
      RetryDecision decision = RetryCore.evaluateFailure(attempting, config, nonRetryable);

      // Then
      assertThat(decision).isInstanceOf(RetryDecision.DoNotRetry.class);
      RetryDecision.DoNotRetry doNotRetry = (RetryDecision.DoNotRetry) decision;
      assertThat(doNotRetry.failure()).isSameAs(nonRetryable);
      assertThat(doNotRetry.snapshot().state()).isEqualTo(RetryState.FAILED);
    }
  }

  // ================================================================
  // Failure Evaluation — Exhausted
  // ================================================================

  @Nested
  @DisplayName("Failure Evaluation — Retries Exhausted")
  class RetriesExhausted {

    @Test
    @DisplayName("should return RetriesExhausted when all attempts are consumed")
    void should_return_retries_exhausted_when_all_attempts_are_consumed() {
      // Given — maxAttempts=2: attempt 1 fails, retry, attempt 2 fails → exhausted
      RetryConfig config = RetryConfig.builder("exhaust-test")
          .maxAttempts(2)
          .noWait()
          .build();
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);
      RetryDecision.DoRetry retry1 = (RetryDecision.DoRetry)
          RetryCore.evaluateFailure(attempt1, config, new RuntimeException("fail 1"));
      RetrySnapshot attempt2 = RetryCore.startNextAttempt(retry1.snapshot(), NOW.plusMillis(100));

      // When
      RetryDecision decision = RetryCore.evaluateFailure(
          attempt2, config, new RuntimeException("fail 2"));

      // Then
      assertThat(decision).isInstanceOf(RetryDecision.RetriesExhausted.class);
      RetryDecision.RetriesExhausted exhausted = (RetryDecision.RetriesExhausted) decision;
      assertThat(exhausted.snapshot().state()).isEqualTo(RetryState.EXHAUSTED);
      assertThat(exhausted.snapshot().totalAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("should return RetriesExhausted immediately with maxAttempts of 1")
    void should_return_retries_exhausted_immediately_with_max_attempts_of_1() {
      // Given
      RetryConfig config = RetryConfig.builder("one-shot")
          .maxAttempts(1)
          .build();
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);

      // When
      RetryDecision decision = RetryCore.evaluateFailure(
          attempt1, config, new RuntimeException("fail"));

      // Then
      assertThat(decision).isInstanceOf(RetryDecision.RetriesExhausted.class);
    }
  }

  // ================================================================
  // Result-Based Retry
  // ================================================================

  @Nested
  @DisplayName("Result-Based Retry")
  class ResultBasedRetry {

    @Test
    @DisplayName("should return null when the result is acceptable")
    void should_return_null_when_the_result_is_acceptable() {
      // Given
      RetryConfig config = RetryConfig.builder("result-test")
          .maxAttempts(3)
          .<String>retryOnResult(result -> result == null)
          .build();
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When
      RetryDecision decision = RetryCore.evaluateResult(attempting, config, "valid");

      // Then
      assertThat(decision).isNull();
    }

    @Test
    @DisplayName("should return DoRetry when the result matches the retry predicate")
    void should_return_do_retry_when_the_result_matches_the_retry_predicate() {
      // Given
      RetryConfig config = RetryConfig.builder("result-retry")
          .maxAttempts(3)
          .noWait()
          .<String>retryOnResult(result -> result == null || result.isEmpty())
          .build();
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When
      RetryDecision decision = RetryCore.evaluateResult(attempting, config, null);

      // Then
      assertThat(decision).isInstanceOf(RetryDecision.DoRetry.class);
    }

    @Test
    @DisplayName("should return null when no result predicate is configured")
    void should_return_null_when_no_result_predicate_is_configured() {
      // Given
      RetryConfig config = defaultConfig(); // no result predicate
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When
      RetryDecision decision = RetryCore.evaluateResult(attempting, config, null);

      // Then
      assertThat(decision).isNull();
    }
  }

  // ================================================================
  // Start Next Attempt
  // ================================================================

  @Nested
  @DisplayName("Start Next Attempt (WAITING_FOR_RETRY → ATTEMPTING)")
  class StartNextAttempt {

    @Test
    @DisplayName("should transition to ATTEMPTING with incremented attempt number")
    void should_transition_to_attempting_with_incremented_attempt_number() {
      // Given
      RetryConfig config = defaultConfig();
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);
      RetryDecision.DoRetry retry1 = (RetryDecision.DoRetry)
          RetryCore.evaluateFailure(attempt1, config, new RuntimeException("fail"));
      Instant later = NOW.plusMillis(100);

      // When
      RetrySnapshot attempt2 = RetryCore.startNextAttempt(retry1.snapshot(), later);

      // Then
      assertThat(attempt2.state()).isEqualTo(RetryState.ATTEMPTING);
      assertThat(attempt2.attemptNumber()).isEqualTo(2);
      assertThat(attempt2.attemptStartTime()).isEqualTo(later);
    }

    @Test
    @DisplayName("should reject starting a next attempt from a non-WAITING_FOR_RETRY state")
    void should_reject_starting_a_next_attempt_from_a_non_waiting_for_retry_state() {
      // Given
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When / Then
      assertThatThrownBy(() -> RetryCore.startNextAttempt(attempting, NOW))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ================================================================
  // Backoff Strategies
  // ================================================================

  @Nested
  @DisplayName("Backoff Strategies")
  class BackoffStrategies {

    @Test
    @DisplayName("fixed delay should return the same delay for every attempt")
    void fixed_delay_should_return_the_same_delay_for_every_attempt() {
      // Given
      BackoffStrategy strategy = BackoffStrategy.fixedDelay(Duration.ofMillis(500));

      // When / Then
      assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ofMillis(500));
      assertThat(strategy.computeDelay(1)).isEqualTo(Duration.ofMillis(500));
      assertThat(strategy.computeDelay(5)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("exponential backoff should double the delay with each attempt by default")
    void exponential_backoff_should_double_the_delay_with_each_attempt_by_default() {
      // Given
      BackoffStrategy strategy = BackoffStrategy.exponential(Duration.ofMillis(100));

      // When / Then
      assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ofMillis(100)); // 100 * 2^0
      assertThat(strategy.computeDelay(1)).isEqualTo(Duration.ofMillis(200)); // 100 * 2^1
      assertThat(strategy.computeDelay(2)).isEqualTo(Duration.ofMillis(400)); // 100 * 2^2
      assertThat(strategy.computeDelay(3)).isEqualTo(Duration.ofMillis(800)); // 100 * 2^3
    }

    @Test
    @DisplayName("exponential backoff should cap at the max delay")
    void exponential_backoff_should_cap_at_the_max_delay() {
      // Given
      BackoffStrategy strategy = BackoffStrategy.exponential(
          Duration.ofMillis(100), 2.0, Duration.ofMillis(500));

      // When — 100 * 2^3 = 800, but capped at 500
      Duration delay = strategy.computeDelay(3);

      // Then
      assertThat(delay).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("exponential backoff with custom multiplier should scale correctly")
    void exponential_backoff_with_custom_multiplier_should_scale_correctly() {
      // Given
      BackoffStrategy strategy = BackoffStrategy.exponential(
          Duration.ofMillis(100), 3.0, Duration.ofSeconds(30));

      // When / Then
      assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ofMillis(100));  // 100 * 3^0
      assertThat(strategy.computeDelay(1)).isEqualTo(Duration.ofMillis(300));  // 100 * 3^1
      assertThat(strategy.computeDelay(2)).isEqualTo(Duration.ofMillis(900));  // 100 * 3^2
    }

    @Test
    @DisplayName("exponential with jitter should produce delays within the expected range")
    void exponential_with_jitter_should_produce_delays_within_the_expected_range() {
      // Given
      BackoffStrategy strategy = BackoffStrategy.exponentialWithJitter(
          Duration.ofMillis(100), 2.0, Duration.ofSeconds(30));

      // When — run many samples to check the range
      long maxExpected = 100; // 100 * 2^0 = 100ms for retryIndex=0
      for (int i = 0; i < 100; i++) {
        Duration delay = strategy.computeDelay(0);
        assertThat(delay.toMillis())
            .isGreaterThanOrEqualTo(0)
            .isLessThanOrEqualTo(maxExpected);
      }
    }

    @Test
    @DisplayName("no wait strategy should always return zero delay")
    void no_wait_strategy_should_always_return_zero_delay() {
      // Given
      BackoffStrategy strategy = BackoffStrategy.noWait();

      // When / Then
      assertThat(strategy.computeDelay(0)).isEqualTo(Duration.ZERO);
      assertThat(strategy.computeDelay(5)).isEqualTo(Duration.ZERO);
    }
  }

  // ================================================================
  // Query Helpers
  // ================================================================

  @Nested
  @DisplayName("Query Helpers")
  class QueryHelpers {

    @Test
    @DisplayName("should report retries remaining when attempts are left")
    void should_report_retries_remaining_when_attempts_are_left() {
      // Given
      RetryConfig config = defaultConfig(); // maxAttempts=3
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);

      // When / Then
      assertThat(RetryCore.hasRetriesRemaining(attempt1, config)).isTrue();
    }

    @Test
    @DisplayName("should report no retries remaining on the final attempt")
    void should_report_no_retries_remaining_on_the_final_attempt() {
      // Given
      RetryConfig config = RetryConfig.builder("query-test")
          .maxAttempts(2)
          .noWait()
          .build();
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);
      RetryDecision.DoRetry retry1 = (RetryDecision.DoRetry)
          RetryCore.evaluateFailure(attempt1, config, new RuntimeException("fail"));
      RetrySnapshot attempt2 = RetryCore.startNextAttempt(retry1.snapshot(), NOW);

      // When / Then
      assertThat(RetryCore.hasRetriesRemaining(attempt2, config)).isFalse();
    }

    @Test
    @DisplayName("should report the correct total elapsed time")
    void should_report_the_correct_total_elapsed_time() {
      // Given
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);
      Instant later = NOW.plusSeconds(5);

      // When
      Duration elapsed = RetryCore.totalElapsed(attempt1, later);

      // Then
      assertThat(elapsed).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("should correctly count retries after multiple attempts")
    void should_correctly_count_retries_after_multiple_attempts() {
      // Given
      RetryConfig config = defaultConfig();
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);
      RetryDecision.DoRetry retry1 = (RetryDecision.DoRetry)
          RetryCore.evaluateFailure(attempt1, config, new RuntimeException("fail"));
      RetrySnapshot attempt2 = RetryCore.startNextAttempt(retry1.snapshot(), NOW);

      // When / Then
      assertThat(RetryCore.retryCount(attempt2)).isEqualTo(1);
      assertThat(RetryCore.attemptCount(attempt2)).isEqualTo(2);
    }
  }

  // ================================================================
  // Full Lifecycle
  // ================================================================

  @Nested
  @DisplayName("Full Retry Lifecycle")
  class FullLifecycle {

    @Test
    @DisplayName("should complete a lifecycle with success on the first attempt")
    void should_complete_a_lifecycle_with_success_on_the_first_attempt() {
      // Given
      RetrySnapshot attempt = RetryCore.startFirstAttempt(NOW);

      // When
      RetrySnapshot completed = RetryCore.recordSuccess(attempt);

      // Then
      assertThat(completed.state()).isEqualTo(RetryState.COMPLETED);
      assertThat(completed.retryCount()).isZero();
      assertThat(completed.hasRetried()).isFalse();
    }

    @Test
    @DisplayName("should complete a lifecycle with success on a retry")
    void should_complete_a_lifecycle_with_success_on_a_retry() {
      // Given
      RetryConfig config = defaultConfig();
      RetrySnapshot attempt1 = RetryCore.startFirstAttempt(NOW);

      // When — first attempt fails
      RetryDecision.DoRetry retry1 = (RetryDecision.DoRetry)
          RetryCore.evaluateFailure(attempt1, config, new RuntimeException("fail 1"));
      RetrySnapshot attempt2 = RetryCore.startNextAttempt(retry1.snapshot(), NOW.plusMillis(100));

      // When — second attempt succeeds
      RetrySnapshot completed = RetryCore.recordSuccess(attempt2);

      // Then
      assertThat(completed.state()).isEqualTo(RetryState.COMPLETED);
      assertThat(completed.retryCount()).isEqualTo(1);
      assertThat(completed.hasRetried()).isTrue();
      assertThat(completed.failures()).hasSize(1);
    }

    @Test
    @DisplayName("should complete a lifecycle with exhaustion after all attempts fail")
    void should_complete_a_lifecycle_with_exhaustion_after_all_attempts_fail() {
      // Given
      RetryConfig config = RetryConfig.builder("exhaust-lifecycle")
          .maxAttempts(3)
          .noWait()
          .build();
      RetrySnapshot current = RetryCore.startFirstAttempt(NOW);

      // When — all 3 attempts fail
      for (int i = 0; i < 3; i++) {
        RuntimeException failure = new RuntimeException("fail " + (i + 1));
        RetryDecision decision = RetryCore.evaluateFailure(current, config, failure);
        if (decision instanceof RetryDecision.DoRetry doRetry) {
          current = RetryCore.startNextAttempt(doRetry.snapshot(), NOW.plusMillis((i + 1) * 100L));
        } else if (decision instanceof RetryDecision.RetriesExhausted exhausted) {
          current = exhausted.snapshot();
        }
      }

      // Then
      assertThat(current.state()).isEqualTo(RetryState.EXHAUSTED);
      assertThat(current.totalAttempts()).isEqualTo(3);
      assertThat(current.failures()).hasSize(3);
    }
  }

  // ================================================================
  // Snapshot Immutability
  // ================================================================

  @Nested
  @DisplayName("Snapshot Immutability")
  class SnapshotImmutability {

    @Test
    @DisplayName("should not modify the original snapshot when recording success")
    void should_not_modify_the_original_snapshot_when_recording_success() {
      // Given
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When
      RetryCore.recordSuccess(attempting);

      // Then
      assertThat(attempting.state()).isEqualTo(RetryState.ATTEMPTING);
    }

    @Test
    @DisplayName("should not modify the original snapshot when evaluating failure")
    void should_not_modify_the_original_snapshot_when_evaluating_failure() {
      // Given
      RetryConfig config = defaultConfig();
      RetrySnapshot attempting = RetryCore.startFirstAttempt(NOW);

      // When
      RetryCore.evaluateFailure(attempting, config, new RuntimeException("fail"));

      // Then
      assertThat(attempting.state()).isEqualTo(RetryState.ATTEMPTING);
      assertThat(attempting.failures()).isEmpty();
    }
  }

  // ================================================================
  // Configuration Validation
  // ================================================================

  @Nested
  @DisplayName("Configuration Validation")
  class ConfigurationValidation {

    @Test
    @DisplayName("should reject maxAttempts of zero")
    void should_reject_max_attempts_of_zero() {
      assertThatThrownBy(() -> RetryConfig.builder("bad")
          .maxAttempts(0)
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a null name")
    void should_reject_a_null_name() {
      assertThatThrownBy(() -> RetryConfig.builder(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should report the correct max retries for a given maxAttempts")
    void should_report_the_correct_max_retries_for_a_given_max_attempts() {
      // Given
      RetryConfig config = RetryConfig.builder("maxRetries-test")
          .maxAttempts(5)
          .build();

      // When / Then
      assertThat(config.maxRetries()).isEqualTo(4);
    }

    @Test
    @DisplayName("should reject a negative fixed delay")
    void should_reject_a_negative_fixed_delay() {
      assertThatThrownBy(() -> BackoffStrategy.fixedDelay(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject an exponential multiplier below 1.0")
    void should_reject_an_exponential_multiplier_below_1() {
      assertThatThrownBy(() -> BackoffStrategy.exponential(
          Duration.ofMillis(100), 0.5, Duration.ofSeconds(30)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ================================================================
  // RetryState Properties
  // ================================================================

  @Nested
  @DisplayName("RetryState Properties")
  class RetryStateProperties {

    @Test
    @DisplayName("should identify IDLE, ATTEMPTING, and WAITING_FOR_RETRY as non-terminal")
    void should_identify_non_terminal_states() {
      assertThat(RetryState.IDLE.isTerminal()).isFalse();
      assertThat(RetryState.ATTEMPTING.isTerminal()).isFalse();
      assertThat(RetryState.WAITING_FOR_RETRY.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("should identify COMPLETED, FAILED, and EXHAUSTED as terminal")
    void should_identify_terminal_states() {
      assertThat(RetryState.COMPLETED.isTerminal()).isTrue();
      assertThat(RetryState.FAILED.isTerminal()).isTrue();
      assertThat(RetryState.EXHAUSTED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("should identify only COMPLETED as success")
    void should_identify_only_completed_as_success() {
      assertThat(RetryState.COMPLETED.isSuccess()).isTrue();
      assertThat(RetryState.FAILED.isSuccess()).isFalse();
      assertThat(RetryState.EXHAUSTED.isSuccess()).isFalse();
    }
  }
}
