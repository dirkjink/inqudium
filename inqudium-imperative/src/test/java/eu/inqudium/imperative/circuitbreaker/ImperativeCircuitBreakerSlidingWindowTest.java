package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerException;
import eu.inqudium.core.element.circuitbreaker.CircuitState;
import eu.inqudium.core.element.circuitbreaker.StateTransition;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.metrics.SlidingWindowMetrics;
import eu.inqudium.core.element.config.FailurePredicateConfigBuilder;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqNanoTimeSource;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration tests for {@link ImperativeCircuitBreaker} using a count-based
 * {@link SlidingWindowMetrics} strategy.
 *
 * <p>All tests use a deterministic {@link InqNanoTimeSource} backed by an
 * {@link AtomicLong}, ensuring complete control over time progression
 * without {@code Thread.sleep()} (ADR-016).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImperativeCircuitBreakerSlidingWindowTest {

  private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  // Deterministic time source — start at a non-zero value to avoid edge cases
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final InqNanoTimeSource timeSource = clock::get;

  // GeneralConfig wired with the deterministic time source
  private final GeneralConfig generalConfig = new GeneralConfig(
      Instant::now,
      timeSource,
      null,
      LoggerFactory.NO_OP_LOGGER_FACTORY,
      Map.of()
  );

  /**
   * Creates a config with sliding window metrics using the {@link InqCircuitBreakerConfig} record.
   *
   * @param failureThreshold     absolute number of failures to trip the circuit
   * @param windowSize           number of calls tracked in the sliding window
   * @param minimumNumberOfCalls minimum calls before threshold evaluation
   * @param successThreshold     successes required in HALF_OPEN to close
   * @param permittedInHalfOpen  probe calls permitted in HALF_OPEN
   */
  private InqCircuitBreakerConfig slidingWindowConfig(
      int failureThreshold,
      int windowSize,
      int minimumNumberOfCalls,
      int successThreshold,
      int permittedInHalfOpen) {

    return new InqCircuitBreakerConfig(
        generalConfig,
        new InqElementCommonConfig("test-cb", InqElementType.CIRCUIT_BREAKER, null, null),
        WAIT_DURATION.toNanos(),
        successThreshold,
        permittedInHalfOpen,
        WAIT_DURATION,
        t -> true, // record all exceptions as failures by default
        nowNanos -> SlidingWindowMetrics.initial(failureThreshold, windowSize, minimumNumberOfCalls)
    );
  }

  /**
   * Shorthand for the most common config used in tests.
   */
  private InqCircuitBreakerConfig defaultConfig() {
    // threshold=3, window=10, min=3, successThreshold=2, permitted=3
    return slidingWindowConfig(3, 10, 3, 2, 3);
  }

  /**
   * Creates a config that only records specific exception types using
   * {@link FailurePredicateConfigBuilder}.
   */
  @SafeVarargs
  private InqCircuitBreakerConfig configWithRecordExceptions(
      String name,
      int failureThreshold,
      int windowSize,
      int minimumNumberOfCalls,
      int successThreshold,
      int permittedInHalfOpen,
      Class<? extends Throwable>... recordExceptions) {

    var failurePredicate = FailurePredicateConfigBuilder.failurePredicate()
        .recordExceptions(recordExceptions)
        .build()
        .finalPredicate();

    return new InqCircuitBreakerConfig(
        generalConfig,
        new InqElementCommonConfig(name, InqElementType.CIRCUIT_BREAKER, null, null),
        WAIT_DURATION.toNanos(),
        successThreshold,
        permittedInHalfOpen,
        WAIT_DURATION,
        failurePredicate,
        nowNanos -> SlidingWindowMetrics.initial(failureThreshold, windowSize, minimumNumberOfCalls)
    );
  }

  /**
   * Creates an {@link ImperativeCircuitBreaker} from the given config,
   * passing {@code metricsFactory} and {@code recordFailurePredicate}
   * as separate constructor arguments (new constructor signature).
   */
  private <A, R> ImperativeCircuitBreaker<A, R> createCircuitBreaker(InqCircuitBreakerConfig config) {
    return new ImperativeCircuitBreaker<>(
        config,
        config.metricsFactory(),
        config.recordFailurePredicate()
    );
  }

  /**
   * Advances the deterministic clock by the given number of nanoseconds.
   */
  private void advanceTime(long nanos) {
    clock.addAndGet(nanos);
  }

  /**
   * Advances the clock past the OPEN wait duration.
   */
  private void advancePastWaitDuration() {
    advanceTime(WAIT_DURATION.toNanos() + NANOS_PER_SECOND);
  }

  /**
   * Records N successful calls via execute(Callable).
   */
  private void recordSuccesses(ImperativeCircuitBreaker<?, ?> cb, int count) throws Exception {
    for (int i = 0; i < count; i++) {
      cb.execute(() -> "ok");
    }
  }

  /**
   * Records N failed calls via execute(Callable), swallowing the expected exception.
   */
  private void recordFailures(ImperativeCircuitBreaker<?, ?> cb, int count) {
    for (int i = 0; i < count; i++) {
      try {
        cb.execute((Callable<String>) () -> {
          throw new IOException("boom");
        });
      } catch (Exception ignored) {
        // expected
      }
    }
  }

  // ======================== Initial State ========================

  @Nested
  class InitialState {

    @Test
    void should_start_in_closed_state() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When
      CircuitState state = cb.getState();

      // Then
      assertThat(state).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_permit_calls_in_closed_state() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When
      String result = cb.execute(() -> "hello");

      // Then
      assertThat(result).isEqualTo("hello");
    }

    @Test
    void should_return_snapshot_with_initial_values() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When
      var snapshot = cb.getSnapshot();

      // Then
      assertThat(snapshot.state()).isEqualTo(CircuitState.CLOSED);
      assertThat(snapshot.successCount()).isZero();
      assertThat(snapshot.halfOpenAttempts()).isZero();
    }

    @Test
    void should_expose_the_configured_name() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When / Then
      assertThat(cb.getName()).isEqualTo("test-cb");
    }
  }

  // ======================== CLOSED -> OPEN Transition ========================

  @Nested
  class ClosedToOpenTransition {

    @Test
    void should_transition_to_open_when_failure_threshold_is_reached() {
      // Given — threshold=3, min=3
      var cb = createCircuitBreaker(defaultConfig());

      // When — 3 failures
      recordFailures(cb, 3);

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_remain_closed_when_failures_are_below_threshold() {
      // Given — threshold=3, min=3
      var cb = createCircuitBreaker(defaultConfig());

      // When — only 2 failures
      recordFailures(cb, 2);

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_not_trip_before_minimum_number_of_calls_is_reached() {
      // Given — threshold=3, window=10, min=5
      var cb = createCircuitBreaker(slidingWindowConfig(3, 10, 5, 2, 3));

      // When — 3 failures (meets threshold but not minimum of 5)
      recordFailures(cb, 3);

      // Then — still CLOSED because min calls not met
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_trip_once_minimum_calls_are_met_and_threshold_exceeded() throws Exception {
      // Given — threshold=3, window=10, min=5
      var cb = createCircuitBreaker(slidingWindowConfig(3, 10, 5, 2, 3));

      // When — 3 failures + 2 successes = 5 calls (min met), 3 failures >= threshold
      recordFailures(cb, 3);
      recordSuccesses(cb, 2);

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_reject_calls_when_open() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When / Then
      assertThatThrownBy(() -> cb.execute(() -> "blocked"))
          .isInstanceOf(CircuitBreakerException.class);
    }

    @Test
    void should_include_circuit_breaker_name_and_state_in_exception() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);

      // When
      var exception = catchThrowableOfType(
          () -> cb.execute(() -> "blocked"),
          CircuitBreakerException.class);

      // Then
      assertThat(exception.getCircuitBreakerName()).isEqualTo("test-cb");
      assertThat(exception.getState()).isEqualTo(CircuitState.OPEN);
      assertThat(exception.getMessage()).contains("test-cb").contains("OPEN");
    }
  }

  // ======================== OPEN -> HALF_OPEN Transition ========================

  @Nested
  class OpenToHalfOpenTransition {

    @Test
    void should_transition_to_half_open_after_wait_duration_expires() throws Exception {
      // Given — trip the circuit
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When — advance time past wait duration and attempt a call
      advancePastWaitDuration();
      cb.execute(() -> "probe");

      // Then
      assertThat(cb.getState()).isIn(CircuitState.HALF_OPEN, CircuitState.CLOSED);
    }

    @Test
    void should_stay_open_before_wait_duration_expires() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);

      // When — advance only half the wait duration
      advanceTime(WAIT_DURATION.toNanos() / 2);

      // Then
      assertThatThrownBy(() -> cb.execute(() -> "still-blocked"))
          .isInstanceOf(CircuitBreakerException.class);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ======================== HALF_OPEN Behavior ========================

  @Nested
  class HalfOpenBehavior {

    private ImperativeCircuitBreaker<Object, Object> openCircuitBreaker() {
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
      advancePastWaitDuration();
      return cb;
    }

    @Test
    void should_permit_limited_probe_calls_in_half_open() throws Exception {
      // Given — permittedInHalfOpen=3
      var cb = openCircuitBreaker();

      // When — 3 probe calls should all succeed
      for (int i = 0; i < 3; i++) {
        cb.execute(() -> "probe");
      }

      // Then — either transitioned to CLOSED or stayed HALF_OPEN (if 2 successes needed for close)
      assertThat(cb.getState()).isIn(CircuitState.HALF_OPEN, CircuitState.CLOSED);
    }

    @Test
    void should_reject_calls_after_probe_failure_transitions_back_to_open() {
      // Given — trip the circuit, then advance past wait duration
      var cb = createCircuitBreaker(slidingWindowConfig(3, 10, 3, 2, 3));
      recordFailures(cb, 3);
      advancePastWaitDuration();

      // When — first probe call fails -> immediately back to OPEN
      recordFailures(cb, 1);

      // Then — subsequent calls are rejected because circuit is OPEN again
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
      assertThatThrownBy(() -> cb.execute(() -> "blocked"))
          .isInstanceOf(CircuitBreakerException.class);
    }
  }

  // ======================== HALF_OPEN -> CLOSED Transition ========================

  @Nested
  class HalfOpenToClosedTransition {

    @Test
    void should_transition_to_closed_when_success_threshold_is_met() throws Exception {
      // Given — successThreshold=2
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      advancePastWaitDuration();

      // When — 2 successful probe calls
      recordSuccesses(cb, 2);

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_permit_unlimited_calls_after_closing_again() throws Exception {
      // Given — trip -> wait -> recover
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      advancePastWaitDuration();
      recordSuccesses(cb, 2);
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

      // When — many more calls
      for (int i = 0; i < 20; i++) {
        String result = cb.execute(() -> "ok");
        assertThat(result).isEqualTo("ok");
      }

      // Then — still closed
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_reset_failure_metrics_after_transitioning_back_to_closed() throws Exception {
      // Given — trip -> wait -> recover
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      advancePastWaitDuration();
      recordSuccesses(cb, 2);
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

      // When — record 2 failures (below threshold of 3)
      recordFailures(cb, 2);

      // Then — should still be CLOSED because metrics were reset
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ======================== HALF_OPEN -> OPEN Transition ========================

  @Nested
  class HalfOpenToOpenTransition {

    @Test
    void should_transition_back_to_open_on_any_failure_in_half_open() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      advancePastWaitDuration();

      // When — a probe call fails
      recordFailures(cb, 1);

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_require_another_full_wait_after_re_opening_from_half_open() {
      // Given — trip -> wait -> half-open -> fail -> open again
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      advancePastWaitDuration();
      recordFailures(cb, 1);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When — advance only half the wait duration
      advanceTime(WAIT_DURATION.toNanos() / 2);

      // Then — still OPEN
      assertThatThrownBy(() -> cb.execute(() -> "blocked"))
          .isInstanceOf(CircuitBreakerException.class);
    }

    @Test
    void should_eventually_recover_after_re_opening_from_half_open() throws Exception {
      // Given — trip -> wait -> fail again -> open
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      advancePastWaitDuration();
      recordFailures(cb, 1);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When — wait again -> successful probes
      advancePastWaitDuration();
      recordSuccesses(cb, 2);

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ======================== Sliding Window Eviction ========================

  @Nested
  class SlidingWindowEviction {

    @Test
    void should_not_trip_when_old_failures_are_evicted_by_newer_successes() throws Exception {
      // Given — threshold=3, window=5, min=3
      var cb = createCircuitBreaker(slidingWindowConfig(3, 5, 3, 2, 3));

      // When — 2 failures then 5 successes (evicting the failures from the window)
      recordFailures(cb, 2);
      recordSuccesses(cb, 5);

      // Then — failures have been pushed out of the window
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_trip_when_failures_accumulate_within_the_window() throws Exception {
      // Given — threshold=3, window=5, min=3
      var cb = createCircuitBreaker(slidingWindowConfig(3, 5, 3, 2, 3));

      // When — S, F, F, F (3 failures within last 5)
      recordSuccesses(cb, 1);
      recordFailures(cb, 3);

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ======================== Failure Predicate ========================

  @Nested
  class FailurePredicate {

    @Test
    void should_only_count_exceptions_matching_the_predicate_as_failures() throws Exception {
      // Given — only IOException counts as failure
      @SuppressWarnings("unchecked")
      var config = configWithRecordExceptions("predicate-cb", 2, 10, 2, 2, 3, IOException.class);
      var cb = createCircuitBreaker(config);

      // When — throw IllegalArgumentException (not recorded) twice
      for (int i = 0; i < 2; i++) {
        try {
          cb.execute((Callable<String>) () -> {
            throw new IllegalArgumentException("ignored");
          });
        } catch (IllegalArgumentException ignored) {
        }
      }

      // Then — still CLOSED because IAE is not recorded
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_trip_when_matching_exceptions_reach_threshold() {
      // Given — only IOException counts
      @SuppressWarnings("unchecked")
      var config = configWithRecordExceptions("predicate-cb", 2, 10, 2, 2, 3, IOException.class);
      var cb = createCircuitBreaker(config);

      // When — 2 IOExceptions
      for (int i = 0; i < 2; i++) {
        try {
          cb.execute((Callable<String>) () -> {
            throw new IOException("boom");
          });
        } catch (Exception ignored) {
        }
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }
  }

  // ======================== Error Handling (JVM Errors) ========================

  @Nested
  class ErrorHandling {

    @Test
    void should_not_record_jvm_errors_as_failures() {
      // Given — threshold=1, min=1
      var cb = createCircuitBreaker(slidingWindowConfig(1, 10, 1, 2, 3));

      // When — StackOverflowError is thrown
      try {
        cb.execute((Callable<String>) () -> {
          throw new StackOverflowError("test");
        });
      } catch (Error | Exception ignored) {
      }

      // Then — Errors are ignored, circuit stays CLOSED
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }
  }

  // ======================== Execute Runnable ========================

  @Nested
  class ExecuteRunnable {

    @Test
    void should_record_success_for_runnable_that_completes_normally() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<String> sideEffects = new ArrayList<>();

      // When
      cb.execute(() -> sideEffects.add("executed"));

      // Then
      assertThat(sideEffects).containsExactly("executed");
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_record_failure_for_runnable_that_throws() {
      // Given — threshold=1, min=1
      var cb = createCircuitBreaker(slidingWindowConfig(1, 10, 1, 2, 3));

      // When
      try {
        cb.execute((Runnable) () -> {
          throw new RuntimeException("boom");
        });
      } catch (RuntimeException ignored) {
      }

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void should_reject_runnable_when_circuit_is_open() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);

      // When / Then
      assertThatThrownBy(() -> cb.execute(() -> {
      }))
          .isInstanceOf(CircuitBreakerException.class);
    }
  }

  // ======================== Execute with Fallback ========================

  @Nested
  class ExecuteWithFallback {

    @Test
    void should_return_primary_result_when_circuit_is_closed() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When
      String result = cb.executeWithFallback(() -> "primary", () -> "fallback");

      // Then
      assertThat(result).isEqualTo("primary");
    }

    @Test
    void should_return_fallback_when_circuit_is_open() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);

      // When
      String result = cb.executeWithFallback(() -> "primary", () -> "fallback");

      // Then
      assertThat(result).isEqualTo("fallback");
    }

    @Test
    void should_propagate_business_exception_without_triggering_fallback() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When / Then — business exception is NOT caught by executeWithFallback
      assertThatThrownBy(() ->
          cb.executeWithFallback(
              () -> {
                throw new IOException("business error");
              },
              () -> "fallback"))
          .isInstanceOf(IOException.class);
    }
  }

  // ======================== Execute with Fallback on Any ========================

  @Nested
  class ExecuteWithFallbackOnAny {

    @Test
    void should_return_fallback_on_business_exception() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When
      String result = cb.executeWithFallbackOnAny(
          () -> {
            throw new IOException("oops");
          },
          () -> "recovered");

      // Then
      assertThat(result).isEqualTo("recovered");
    }

    @Test
    void should_return_fallback_when_circuit_is_open() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);

      // When
      String result = cb.executeWithFallbackOnAny(() -> "primary", () -> "fallback");

      // Then
      assertThat(result).isEqualTo("fallback");
    }

    @Test
    void should_attach_original_exception_as_suppressed_when_fallback_also_throws() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When / Then
      var thrown = catchThrowableOfType(
          () -> cb.executeWithFallbackOnAny(
              () -> {
                throw new IOException("original");
              },
              () -> {
                throw new RuntimeException("fallback-error");
              }),
          RuntimeException.class);

      assertThat(thrown.getMessage()).isEqualTo("fallback-error");
      assertThat(thrown.getSuppressed()).hasSize(1);
      assertThat(thrown.getSuppressed()[0]).isInstanceOf(IOException.class);
    }

    @Test
    void should_propagate_interrupted_exception_without_triggering_fallback() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When / Then
      assertThatThrownBy(() ->
          cb.executeWithFallbackOnAny(
              () -> {
                throw new InterruptedException("interrupted");
              },
              () -> "should-not-reach"))
          .isInstanceOf(InterruptedException.class);
    }
  }

  // ======================== State Transition Listeners ========================

  @Nested
  class StateTransitionListeners {

    @Test
    void should_notify_listener_on_closed_to_open_transition() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);

      // When
      recordFailures(cb, 3);

      // Then
      assertThat(transitions).hasSize(1);
      assertThat(transitions.getFirst().fromState()).isEqualTo(CircuitState.CLOSED);
      assertThat(transitions.getFirst().toState()).isEqualTo(CircuitState.OPEN);
      assertThat(transitions.getFirst().name()).isEqualTo("test-cb");
      assertThat(transitions.getFirst().reason()).isNotBlank();
    }

    @Test
    void should_notify_listener_on_open_to_half_open_transition() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);
      recordFailures(cb, 3);
      transitions.clear();

      // When
      advancePastWaitDuration();
      cb.execute(() -> "probe");

      // Then
      assertThat(transitions).anySatisfy(t -> {
        assertThat(t.fromState()).isEqualTo(CircuitState.OPEN);
        assertThat(t.toState()).isEqualTo(CircuitState.HALF_OPEN);
      });
    }

    @Test
    void should_notify_listener_on_half_open_to_closed_transition() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);
      recordFailures(cb, 3);
      advancePastWaitDuration();
      transitions.clear();

      // When — enough successful probes to close
      recordSuccesses(cb, 2);

      // Then
      assertThat(transitions).anySatisfy(t -> {
        assertThat(t.fromState()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(t.toState()).isEqualTo(CircuitState.CLOSED);
      });
    }

    @Test
    void should_notify_listener_on_half_open_to_open_transition() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);
      recordFailures(cb, 3);
      advancePastWaitDuration();
      transitions.clear();

      // When — a probe call fails
      recordFailures(cb, 1);

      // Then
      assertThat(transitions).anySatisfy(t -> {
        assertThat(t.fromState()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(t.toState()).isEqualTo(CircuitState.OPEN);
      });
    }

    @Test
    void should_support_multiple_listeners() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> listener1 = Collections.synchronizedList(new ArrayList<>());
      List<StateTransition> listener2 = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(listener1::add);
      cb.onStateTransition(listener2::add);

      // When
      recordFailures(cb, 3);

      // Then
      assertThat(listener1).hasSize(1);
      assertThat(listener2).hasSize(1);
    }

    @Test
    void should_allow_unregistering_a_listener() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      Runnable unregister = cb.onStateTransition(transitions::add);

      // When — unregister before any transition
      unregister.run();
      recordFailures(cb, 3);

      // Then — listener was not notified
      assertThat(transitions).isEmpty();
    }

    @Test
    void should_not_break_circuit_breaker_when_listener_throws() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      cb.onStateTransition(t -> {
        throw new RuntimeException("listener crash");
      });

      // When — trip the circuit (listener will throw)
      recordFailures(cb, 3);

      // Then — circuit breaker still works correctly
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
      advancePastWaitDuration();
      cb.execute(() -> "probe");
    }
  }

  // ======================== Reset ========================

  @Nested
  class ManualReset {

    @Test
    void should_reset_to_closed_from_open_state() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // When
      cb.reset();

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_reset_to_closed_from_half_open_state() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      advancePastWaitDuration();
      cb.execute(() -> "probe"); // triggers HALF_OPEN

      // When
      cb.reset();

      // Then
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_clear_metrics_after_reset() throws Exception {
      // Given — 2 failures (one short of tripping)
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 2);

      // When
      cb.reset();

      // Then — 2 more failures would have tripped without reset, but now we start fresh
      recordFailures(cb, 2);
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_fire_transition_event_on_reset_from_non_closed_state() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);
      recordFailures(cb, 3);
      transitions.clear();

      // When
      cb.reset();

      // Then
      assertThat(transitions).hasSize(1);
      assertThat(transitions.getFirst().fromState()).isEqualTo(CircuitState.OPEN);
      assertThat(transitions.getFirst().toState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void should_not_fire_transition_event_on_reset_from_clean_closed_state() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);

      // When — reset while already in clean CLOSED state
      cb.reset();

      // Then
      assertThat(transitions).isEmpty();
    }

    @Test
    void should_permit_calls_immediately_after_reset() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      recordFailures(cb, 3);
      cb.reset();

      // When
      String result = cb.execute(() -> "after-reset");

      // Then
      assertThat(result).isEqualTo("after-reset");
    }
  }

  // ======================== Full Lifecycle ========================

  @Nested
  class FullLifecycle {

    @Test
    void should_complete_a_full_closed_open_half_open_closed_cycle() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());
      List<StateTransition> transitions = Collections.synchronizedList(new ArrayList<>());
      cb.onStateTransition(transitions::add);

      // Step 1: CLOSED -> OPEN (3 failures)
      recordFailures(cb, 3);
      assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

      // Step 2: OPEN -> HALF_OPEN (wait duration expires)
      advancePastWaitDuration();
      cb.execute(() -> "probe-1");

      // Step 3: HALF_OPEN -> CLOSED (2 successes)
      cb.execute(() -> "probe-2");
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

      // Step 4: Verify the circuit works normally again
      for (int i = 0; i < 10; i++) {
        assertThat(cb.execute(() -> "healthy")).isEqualTo("healthy");
      }
      assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

      // Then — verify all transitions were recorded
      assertThat(transitions).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void should_survive_multiple_trip_and_recovery_cycles() throws Exception {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      for (int cycle = 0; cycle < 5; cycle++) {
        // Trip the circuit
        recordFailures(cb, 3);
        assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

        // Wait and recover
        advancePastWaitDuration();
        recordSuccesses(cb, 2);
        assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
      }
    }
  }

  // ======================== Interrupt Handling ========================

  @Nested
  class InterruptHandling {

    @Test
    void should_restore_interrupt_flag_when_callable_throws_interrupted_exception() {
      // Given
      var cb = createCircuitBreaker(defaultConfig());

      // When
      try {
        cb.execute((Callable<String>) () -> {
          throw new InterruptedException("test");
        });
      } catch (Exception ignored) {
      }

      // Then — interrupt flag should be restored
      assertThat(Thread.currentThread().isInterrupted()).isTrue();

      // Cleanup
      Thread.interrupted();
    }
  }
}
