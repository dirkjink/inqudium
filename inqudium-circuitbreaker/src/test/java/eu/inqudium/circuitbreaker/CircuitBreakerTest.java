package eu.inqudium.circuitbreaker;

import eu.inqudium.circuitbreaker.event.CircuitBreakerOnErrorEvent;
import eu.inqudium.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import eu.inqudium.circuitbreaker.event.CircuitBreakerOnSuccessEvent;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.circuitbreaker.CircuitBreakerState;
import eu.inqudium.core.circuitbreaker.InqCallNotPermittedException;
import eu.inqudium.core.event.InqEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("CircuitBreaker")
class CircuitBreakerTest {

    private AtomicReference<Instant> timeRef(String instant) {
        return new AtomicReference<>(Instant.parse(instant));
    }

    private CircuitBreakerConfig smallWindowConfig(InqClock clock) {
        return CircuitBreakerConfig.builder()
                .failureRateThreshold(50)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .clock(clock)
                .build();
    }

    @Nested
    @DisplayName("Initial state")
    class InitialState {

        @Test
        void should_start_in_closed_state() {
            // Given
            var cb = CircuitBreaker.ofDefaults("test");

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void should_have_the_configured_name() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");

            // Then
            assertThat(cb.getName()).isEqualTo("paymentService");
        }
    }

    @Nested
    @DisplayName("CLOSED state behavior")
    class ClosedState {

        @Test
        void should_permit_calls_when_closed() {
            // Given
            var cb = CircuitBreaker.ofDefaults("test");

            // When / Then — should not throw
            var result = cb.executeSupplier(() -> "ok");
            assertThat(result).isEqualTo("ok");
        }

        @Test
        void should_record_successful_calls_in_the_sliding_window() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);

            // When
            cb.executeSupplier(() -> "ok");
            cb.executeSupplier(() -> "ok");

            // Then
            var snapshot = cb.getSnapshot();
            assertThat(snapshot.totalCalls()).isEqualTo(2);
            assertThat(snapshot.successfulCalls()).isEqualTo(2);
        }

        @Test
        void should_propagate_exceptions_from_the_decorated_call() {
            // Given
            var cb = CircuitBreaker.ofDefaults("test");

            // When / Then
            assertThatThrownBy(() ->
                    cb.executeSupplier(() -> { throw new RuntimeException("boom"); })
            ).isInstanceOf(RuntimeException.class).hasMessage("boom");
        }
    }

    @Nested
    @DisplayName("CLOSED → OPEN transition")
    class ClosedToOpen {

        @Test
        void should_open_when_failure_rate_exceeds_threshold() {
            // Given — 50% threshold, window of 4, minimum 4 calls
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);

            // When — 4 calls: 2 success + 2 failures = 50% failure rate
            cb.executeSupplier(() -> "ok");
            cb.executeSupplier(() -> "ok");
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException("fail"); }));
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException("fail"); }));

            // Then — 50% >= 50% threshold → OPEN
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
        }

        @Test
        void should_not_open_before_minimum_calls_are_reached() {
            // Given — minimum 4 calls
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);

            // When — only 3 calls, all failures (100% failure rate but below minimum)
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException(); }));
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException(); }));
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException(); }));

            // Then — still CLOSED because minimumNumberOfCalls=4 not reached
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }
    }

    @Nested
    @DisplayName("OPEN state behavior")
    class OpenState {

        @Test
        void should_reject_calls_with_inq_call_not_permitted_exception() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);

            // Force open
            cb.transitionToOpenState();

            // When / Then
            assertThatThrownBy(() -> cb.executeSupplier(() -> "ok"))
                    .isInstanceOf(InqCallNotPermittedException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqCallNotPermittedException) ex;
                        assertThat(inqEx.getCallId()).isNotNull().isNotBlank();
                        assertThat(inqEx.getCode()).isEqualTo("INQ-CB-001");
                        assertThat(inqEx.getElementName()).isEqualTo("test");
                        assertThat(inqEx.getState()).isEqualTo(CircuitBreakerState.OPEN);
                    });
        }
    }

    @Nested
    @DisplayName("OPEN → HALF_OPEN transition")
    class OpenToHalfOpen {

        @Test
        void should_transition_to_half_open_after_wait_duration_elapses() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);
            cb.transitionToOpenState();

            // When — advance time past waitDurationInOpenState (10s)
            time.set(Instant.parse("2026-01-01T00:00:11Z"));

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        }

        @Test
        void should_remain_open_before_wait_duration_elapses() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);
            cb.transitionToOpenState();

            // When — only 5 seconds, need 10
            time.set(Instant.parse("2026-01-01T00:00:05Z"));

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
        }
    }

    @Nested
    @DisplayName("HALF_OPEN state behavior")
    class HalfOpenState {

        @Test
        void should_permit_limited_probe_calls() {
            // Given — permits 2 calls in half-open
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);
            cb.transitionToHalfOpenState();

            // When / Then — both probe calls succeed
            assertThat(cb.executeSupplier(() -> "ok1")).isEqualTo("ok1");
            assertThat(cb.executeSupplier(() -> "ok2")).isEqualTo("ok2");
        }

        @Test
        void should_reject_calls_beyond_the_probe_limit_while_probes_are_in_flight() throws Exception {
            // Given — permits 2 calls in half-open, 1 will block
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);
            cb.transitionToHalfOpenState();

            var entered = new java.util.concurrent.CountDownLatch(2);
            var release = new java.util.concurrent.CountDownLatch(1);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);

            // Hold both probe permits with blocking calls
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> cb.executeSupplier(() -> {
                    entered.countDown();
                    try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) {}
                    return "blocking";
                }));
            }

            entered.await(2, java.util.concurrent.TimeUnit.SECONDS);

            // When / Then — 3rd call is rejected because both probe slots are occupied
            assertThatThrownBy(() -> cb.executeSupplier(() -> "rejected"))
                    .isInstanceOf(InqCallNotPermittedException.class);

            release.countDown();
            executor.shutdown();
        }

        @Test
        void should_close_after_all_probes_succeed() {
            // Given — permits 2 probes, needs all to complete before evaluation
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);
            cb.transitionToHalfOpenState();

            // When — first probe: not enough data yet, stays HALF_OPEN
            cb.executeSupplier(() -> "ok1");
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

            // Second probe: now 2/2 probes, 0% failure rate → CLOSED
            cb.executeSupplier(() -> "ok2");

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void should_reopen_when_probe_failure_rate_exceeds_threshold() {
            // Given — permits 2 probes, 50% failure threshold
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);
            cb.transitionToHalfOpenState();

            // When — 1 success + 1 failure = 50% failure rate
            cb.executeSupplier(() -> "ok");
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException("fail"); }));

            // Then — 50% >= 50% threshold → OPEN
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
        }
    }

    @Nested
    @DisplayName("Manual state transitions")
    class ManualTransitions {

        @Test
        void should_support_manual_reset_to_closed() {
            // Given
            var cb = CircuitBreaker.ofDefaults("test");
            cb.transitionToOpenState();
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // When
            cb.reset();

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(cb.getSnapshot().totalCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        void should_emit_success_event_on_successful_call() {
            // Given
            var cb = CircuitBreaker.ofDefaults("test");
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            cb.getEventPublisher().onEvent(events::add);

            // When
            cb.executeSupplier(() -> "ok");

            // Then
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(CircuitBreakerOnSuccessEvent.class);
        }

        @Test
        void should_emit_error_event_on_failed_call() {
            // Given
            var cb = CircuitBreaker.ofDefaults("test");
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            cb.getEventPublisher().onEvent(events::add);

            // When
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException("boom"); }));

            // Then
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(CircuitBreakerOnErrorEvent.class);
        }

        @Test
        void should_emit_state_transition_event_when_opening() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            var config = smallWindowConfig(time::get);
            var cb = CircuitBreaker.of("test", config);
            var transitions = Collections.synchronizedList(new ArrayList<CircuitBreakerOnStateTransitionEvent>());
            cb.getEventPublisher().onEvent(CircuitBreakerOnStateTransitionEvent.class, transitions::add);

            // When — trigger CLOSED → OPEN
            cb.executeSupplier(() -> "ok");
            cb.executeSupplier(() -> "ok");
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException(); }));
            catchThrowable(() -> cb.executeSupplier(() -> { throw new RuntimeException(); }));

            // Then
            assertThat(transitions).hasSize(1);
            assertThat(transitions.getFirst().getFromState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(transitions.getFirst().getToState()).isEqualTo(CircuitBreakerState.OPEN);
        }
    }

    @Nested
    @DisplayName("Decoration methods")
    class DecorationMethods {

        @Test
        void should_decorate_runnable() {
            // Given
            var cb = CircuitBreaker.ofDefaults("test");
            var executed = new AtomicReference<>(false);

            // When
            cb.decorateRunnable(() -> executed.set(true)).run();

            // Then
            assertThat(executed.get()).isTrue();
        }

        @Test
        void should_decorate_callable_wrapping_checked_exceptions_in_inq_runtime_exception() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");

            // When / Then — checked exception wrapped in InqRuntimeException with element context
            assertThatThrownBy(() ->
                    cb.decorateCallable(() -> { throw new Exception("checked"); }).get()
            ).isInstanceOf(eu.inqudium.core.exception.InqRuntimeException.class)
                    .hasCauseInstanceOf(Exception.class)
                    .satisfies(ex -> {
                        var ire = (eu.inqudium.core.exception.InqRuntimeException) ex;
                        assertThat(ire.hasElementContext()).isTrue();
                        assertThat(ire.getCallId()).isNotNull().isNotBlank();
                        assertThat(ire.getElementName()).isEqualTo("paymentService");
                        assertThat(ire.getElementType()).isEqualTo(InqElementType.CIRCUIT_BREAKER);
                        assertThat(ire.getCode()).isEqualTo("INQ-CB-000");
                        assertThat(ire.getMessage()).contains("CIRCUIT_BREAKER", "paymentService", "checked");
                    });
        }
    }

    @Nested
    @DisplayName("Registry")
    class RegistryTests {

        @Test
        void should_return_same_instance_for_same_name() {
            // Given
            var registry = new CircuitBreakerRegistry();

            // When
            var cb1 = registry.get("payment");
            var cb2 = registry.get("payment");

            // Then
            assertThat(cb1).isSameAs(cb2);
        }

        @Test
        void should_return_different_instances_for_different_names() {
            // Given
            var registry = new CircuitBreakerRegistry();

            // When
            var cb1 = registry.get("payment");
            var cb2 = registry.get("order");

            // Then
            assertThat(cb1).isNotSameAs(cb2);
        }

        @Test
        void should_find_registered_instance() {
            // Given
            var registry = new CircuitBreakerRegistry();
            registry.get("payment");

            // When
            var found = registry.find("payment");

            // Then
            assertThat(found).isPresent();
        }

        @Test
        void should_return_empty_for_unregistered_name() {
            // Given
            var registry = new CircuitBreakerRegistry();

            // When
            var found = registry.find("nonexistent");

            // Then
            assertThat(found).isEmpty();
        }
    }
}
