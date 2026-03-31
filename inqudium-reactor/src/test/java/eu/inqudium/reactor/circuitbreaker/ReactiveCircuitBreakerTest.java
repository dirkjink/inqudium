package eu.inqudium.reactor.circuitbreaker;

import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.circuitbreaker.CircuitBreakerException;
import eu.inqudium.core.circuitbreaker.CircuitState;
import eu.inqudium.core.circuitbreaker.StateTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveCircuitBreaker")
class ReactiveCircuitBreakerTest {

    private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");

    static class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = new TestClock(BASE_TIME);
    }

    private CircuitBreakerConfig defaultConfig() {
        return CircuitBreakerConfig.builder("reactive-test")
                .failureThreshold(3)
                .successThresholdInHalfOpen(2)
                .permittedCallsInHalfOpen(3)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
    }

    private ReactiveCircuitBreaker createBreaker() {
        return new ReactiveCircuitBreaker(defaultConfig(), clock);
    }

    private ReactiveCircuitBreaker createBreaker(CircuitBreakerConfig config) {
        return new ReactiveCircuitBreaker(config, clock);
    }

    private void openCircuit(ReactiveCircuitBreaker cb, int failures) {
        for (int i = 0; i < failures; i++) {
            cb.execute(Mono.error(new RuntimeException("fail")))
              .onErrorResume(e -> Mono.empty())
              .block();
        }
    }

    // ================================================================
    // Mono Execution — Success
    // ================================================================

    @Nested
    @DisplayName("Mono Execution — Success Path")
    class MonoSuccessPath {

        @Test
        @DisplayName("should emit the upstream value when the circuit is closed")
        void should_emit_the_upstream_value_when_the_circuit_is_closed() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            Mono<String> upstream = Mono.just("hello");

            // When
            Mono<String> result = cb.execute(upstream);

            // Then
            StepVerifier.create(result)
                    .expectNext("hello")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should remain in CLOSED state after multiple successful mono emissions")
        void should_remain_in_closed_state_after_multiple_successful_mono_emissions() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();

            // When
            for (int i = 0; i < 10; i++) {
                cb.execute(Mono.just("ok")).block();
            }

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        @DisplayName("should support lazy mono creation via supplier to avoid premature subscription")
        void should_support_lazy_mono_creation_via_supplier_to_avoid_premature_subscription() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();

            // When
            Mono<String> result = cb.execute(() -> Mono.just("lazy"));

            // Then
            StepVerifier.create(result)
                    .expectNext("lazy")
                    .verifyComplete();
        }
    }

    // ================================================================
    // Mono Execution — Failure
    // ================================================================

    @Nested
    @DisplayName("Mono Execution — Failure Path")
    class MonoFailurePath {

        @Test
        @DisplayName("should propagate the upstream error signal when the circuit is closed")
        void should_propagate_the_upstream_error_signal_when_the_circuit_is_closed() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            Mono<String> failing = Mono.error(new IllegalStateException("service down"));

            // When
            Mono<String> result = cb.execute(failing);

            // Then
            StepVerifier.create(result)
                    .expectError(IllegalStateException.class)
                    .verify();
        }

        @Test
        @DisplayName("should transition to OPEN after reaching the failure threshold")
        void should_transition_to_open_after_reaching_the_failure_threshold() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker(); // threshold = 3

            // When
            openCircuit(cb, 3);

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        @DisplayName("should emit CircuitBreakerException when the circuit is open")
        void should_emit_circuit_breaker_exception_when_the_circuit_is_open() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);

            // When
            Mono<String> result = cb.execute(Mono.just("should not emit"));

            // Then
            StepVerifier.create(result)
                    .expectError(CircuitBreakerException.class)
                    .verify();
        }
    }

    // ================================================================
    // Mono Fallback
    // ================================================================

    @Nested
    @DisplayName("Mono Execution — Fallback")
    class MonoFallback {

        @Test
        @DisplayName("should return the primary value when the circuit is closed")
        void should_return_the_primary_value_when_the_circuit_is_closed() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();

            // When
            Mono<String> result = cb.executeWithFallback(
                    Mono.just("primary"),
                    e -> Mono.just("fallback")
            );

            // Then
            StepVerifier.create(result)
                    .expectNext("primary")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return the fallback value when the circuit is open")
        void should_return_the_fallback_value_when_the_circuit_is_open() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);

            // When
            Mono<String> result = cb.executeWithFallback(
                    Mono.just("primary"),
                    e -> Mono.just("fallback")
            );

            // Then
            StepVerifier.create(result)
                    .expectNext("fallback")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should still propagate non-circuit-breaker errors through the fallback")
        void should_still_propagate_non_circuit_breaker_errors_through_the_fallback() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();

            // When
            Mono<String> result = cb.executeWithFallback(
                    Mono.error(new IllegalArgumentException("bad")),
                    e -> Mono.just("fallback")
            );

            // Then — the fallback only handles CircuitBreakerException
            StepVerifier.create(result)
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }
    }

    // ================================================================
    // Flux Execution
    // ================================================================

    @Nested
    @DisplayName("Flux Execution")
    class FluxExecution {

        @Test
        @DisplayName("should emit all upstream elements when the circuit is closed")
        void should_emit_all_upstream_elements_when_the_circuit_is_closed() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            Flux<Integer> upstream = Flux.just(1, 2, 3);

            // When
            Flux<Integer> result = cb.executeMany(upstream);

            // Then
            StepVerifier.create(result)
                    .expectNext(1, 2, 3)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should emit CircuitBreakerException for flux when the circuit is open")
        void should_emit_circuit_breaker_exception_for_flux_when_the_circuit_is_open() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);

            // When
            Flux<String> result = cb.executeMany(Flux.just("a", "b"));

            // Then
            StepVerifier.create(result)
                    .expectError(CircuitBreakerException.class)
                    .verify();
        }

        @Test
        @DisplayName("should record a failure when the flux emits an error")
        void should_record_a_failure_when_the_flux_emits_an_error() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker(); // threshold = 3

            // When — 3 failing fluxes
            for (int i = 0; i < 3; i++) {
                cb.executeMany(Flux.error(new RuntimeException("fail")))
                  .onErrorResume(e -> Flux.empty())
                  .blockLast();
            }

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        @DisplayName("should use fallback publisher when the circuit is open for flux")
        void should_use_fallback_publisher_when_the_circuit_is_open_for_flux() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);

            // When
            Flux<String> result = cb.executeManyWithFallback(
                    Flux.just("primary"),
                    e -> Flux.just("fallback-1", "fallback-2")
            );

            // Then
            StepVerifier.create(result)
                    .expectNext("fallback-1", "fallback-2")
                    .verifyComplete();
        }
    }

    // ================================================================
    // Operator Style (transformDeferred)
    // ================================================================

    @Nested
    @DisplayName("Operator Style Integration")
    class OperatorStyle {

        @Test
        @DisplayName("should work as a mono operator via transformDeferred")
        void should_work_as_a_mono_operator_via_transform_deferred() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();

            // When
            Mono<String> result = Mono.just("transformed")
                    .transformDeferred(cb.monoOperator());

            // Then
            StepVerifier.create(result)
                    .expectNext("transformed")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should work as a flux operator via transformDeferred")
        void should_work_as_a_flux_operator_via_transform_deferred() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();

            // When
            Flux<Integer> result = Flux.range(1, 5)
                    .transformDeferred(cb.fluxOperator());

            // Then
            StepVerifier.create(result)
                    .expectNext(1, 2, 3, 4, 5)
                    .verifyComplete();
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
        void should_transition_from_open_to_half_open_after_wait_duration_expires() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);

            // When — advance time and attempt a call
            clock.advance(Duration.ofSeconds(31));
            Mono<String> result = cb.execute(Mono.just("probe-ok"));

            // Then
            StepVerifier.create(result)
                    .expectNext("probe-ok")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should transition from HALF_OPEN back to CLOSED after sufficient successes")
        void should_transition_from_half_open_back_to_closed_after_sufficient_successes() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker(); // successThresholdInHalfOpen = 2
            openCircuit(cb, 3);
            clock.advance(Duration.ofSeconds(31));

            // When — record 2 successes
            cb.execute(Mono.just("s1")).block();
            cb.execute(Mono.just("s2")).block();

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        @DisplayName("should transition from HALF_OPEN back to OPEN on a probe failure")
        void should_transition_from_half_open_back_to_open_on_a_probe_failure() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);
            clock.advance(Duration.ofSeconds(31));

            // When — probe fails
            cb.execute(Mono.error(new RuntimeException("probe-fail")))
              .onErrorResume(e -> Mono.empty())
              .block();

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
        }
    }

    // ================================================================
    // Transition Events
    // ================================================================

    @Nested
    @DisplayName("Transition Event Stream")
    class TransitionEventStream {

        @Test
        @DisplayName("should emit state transition events via the reactive stream")
        void should_emit_state_transition_events_via_the_reactive_stream() {
            // Given
            CircuitBreakerConfig config = CircuitBreakerConfig.builder("event-test")
                    .failureThreshold(1)
                    .successThresholdInHalfOpen(1)
                    .permittedCallsInHalfOpen(1)
                    .waitDurationInOpenState(Duration.ofSeconds(5))
                    .build();
            ReactiveCircuitBreaker cb = createBreaker(config);
            List<StateTransition> events = new ArrayList<>();
            cb.transitionEvents().subscribe(events::add);

            // When — full cycle: CLOSED → OPEN → HALF_OPEN → CLOSED
            cb.execute(Mono.error(new RuntimeException("fail")))
              .onErrorResume(e -> Mono.empty())
              .block();

            clock.advance(Duration.ofSeconds(6));
            cb.execute(Mono.just("success")).block();

            // Then
            assertThat(events).hasSize(3);
            assertThat(events.get(0).fromState()).isEqualTo(CircuitState.CLOSED);
            assertThat(events.get(0).toState()).isEqualTo(CircuitState.OPEN);
            assertThat(events.get(1).fromState()).isEqualTo(CircuitState.OPEN);
            assertThat(events.get(1).toState()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(events.get(2).fromState()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(events.get(2).toState()).isEqualTo(CircuitState.CLOSED);
        }
    }

    // ================================================================
    // Exception Filtering
    // ================================================================

    @Nested
    @DisplayName("Exception Filtering")
    class ExceptionFiltering {

        @Test
        @DisplayName("should not count ignored exceptions as failures in a reactive context")
        void should_not_count_ignored_exceptions_as_failures_in_a_reactive_context() {
            // Given
            CircuitBreakerConfig config = CircuitBreakerConfig.builder("filter-reactive")
                    .failureThreshold(2)
                    .waitDurationInOpenState(Duration.ofSeconds(10))
                    .ignoreExceptions(IllegalArgumentException.class)
                    .build();
            ReactiveCircuitBreaker cb = createBreaker(config);

            // When — emit ignored exceptions
            for (int i = 0; i < 5; i++) {
                cb.execute(Mono.error(new IllegalArgumentException("ignored")))
                  .onErrorResume(e -> Mono.empty())
                  .block();
            }

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        @DisplayName("should count recorded exceptions as failures in a reactive context")
        void should_count_recorded_exceptions_as_failures_in_a_reactive_context() {
            // Given
            CircuitBreakerConfig config = CircuitBreakerConfig.builder("record-reactive")
                    .failureThreshold(2)
                    .waitDurationInOpenState(Duration.ofSeconds(10))
                    .recordExceptions(java.io.IOException.class)
                    .build();
            ReactiveCircuitBreaker cb = createBreaker(config);

            // When — emit recorded exceptions
            for (int i = 0; i < 2; i++) {
                cb.execute(Mono.error(new java.io.IOException("recorded")))
                  .onErrorResume(e -> Mono.empty())
                  .block();
            }

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
        }
    }

    // ================================================================
    // Reset
    // ================================================================

    @Nested
    @DisplayName("Manual Reset")
    class ManualReset {

        @Test
        @DisplayName("should reset an open reactive circuit breaker to CLOSED state")
        void should_reset_an_open_reactive_circuit_breaker_to_closed_state() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

            // When
            cb.reset();

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
            StepVerifier.create(cb.execute(Mono.just("after-reset")))
                    .expectNext("after-reset")
                    .verifyComplete();
        }
    }

    // ================================================================
    // Deferred Subscription (Laziness)
    // ================================================================

    @Nested
    @DisplayName("Deferred Subscription Semantics")
    class DeferredSubscription {

        @Test
        @DisplayName("should not acquire permission until the mono is subscribed")
        void should_not_acquire_permission_until_the_mono_is_subscribed() {
            // Given
            ReactiveCircuitBreaker cb = createBreaker();
            openCircuit(cb, 3);

            // When — create the mono but do not subscribe
            Mono<String> result = cb.execute(Mono.just("deferred"));

            // Then — the circuit should still be open, no attempt was made
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

            // When — now subscribe after advancing time
            clock.advance(Duration.ofSeconds(31));
            StepVerifier.create(result)
                    .expectNext("deferred")
                    .verifyComplete();
        }
    }
}
