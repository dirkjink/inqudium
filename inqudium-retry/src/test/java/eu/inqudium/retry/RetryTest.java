package eu.inqudium.retry;

import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.core.retry.InqRetryExhaustedException;
import eu.inqudium.core.retry.backoff.BackoffStrategy;
import eu.inqudium.retry.event.RetryOnRetryEvent;
import eu.inqudium.retry.event.RetryOnSuccessEvent;
import eu.inqudium.core.event.InqEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Retry")
class RetryTest {

    private RetryConfig fastConfig() {
        return RetryConfig.builder()
                .maxAttempts(3)
                .initialInterval(Duration.ofMillis(1)) // minimal wait for tests
                .backoffStrategy(BackoffStrategy.fixed())
                .build();
    }

    @Nested
    @DisplayName("Successful calls")
    class SuccessfulCalls {

        @Test
        void should_return_result_on_first_attempt_without_retrying() {
            // Given
            var retry = Retry.of("test", fastConfig());
            var counter = new AtomicInteger(0);

            // When
            var result = retry.executeSupplier(() -> {
                counter.incrementAndGet();
                return "ok";
            });

            // Then
            assertThat(result).isEqualTo("ok");
            assertThat(counter.get()).isEqualTo(1);
        }

        @Test
        void should_succeed_after_transient_failure() {
            // Given
            var retry = Retry.of("test", fastConfig());
            var counter = new AtomicInteger(0);

            // When — fails twice, succeeds on third
            var result = retry.executeSupplier(() -> {
                if (counter.incrementAndGet() < 3) {
                    throw new RuntimeException("transient");
                }
                return "recovered";
            });

            // Then
            assertThat(result).isEqualTo("recovered");
            assertThat(counter.get()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Exhausted retries")
    class ExhaustedRetries {

        @Test
        void should_throw_retry_exhausted_after_max_attempts() {
            // Given
            var retry = Retry.of("test", fastConfig());

            // When / Then
            assertThatThrownBy(() ->
                    retry.executeSupplier(() -> { throw new RuntimeException("always fails"); })
            ).isInstanceOf(InqRetryExhaustedException.class)
                    .satisfies(ex -> {
                        var ire = (InqRetryExhaustedException) ex;
                        assertThat(ire.getAttempts()).isEqualTo(3);
                        assertThat(ire.getCode()).isEqualTo("INQ-RT-001");
                        assertThat(ire.getLastCause()).isInstanceOf(RuntimeException.class);
                        assertThat(ire.getLastCause().getMessage()).isEqualTo("always fails");
                    });
        }
    }

    @Nested
    @DisplayName("Exception filtering")
    class ExceptionFiltering {

        @Test
        void should_not_retry_on_ignored_exception_types() {
            // Given
            var config = RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(1))
                    .backoffStrategy(BackoffStrategy.fixed())
                    .ignoreOn(IllegalArgumentException.class)
                    .build();
            var retry = Retry.of("test", config);
            var counter = new AtomicInteger(0);

            // When / Then — should not retry, throw immediately
            assertThatThrownBy(() ->
                    retry.executeSupplier(() -> {
                        counter.incrementAndGet();
                        throw new IllegalArgumentException("bad input");
                    })
            ).isInstanceOf(InqRetryExhaustedException.class);

            // Only 1 attempt — no retries for ignored exception
            assertThat(counter.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        void should_emit_success_event_on_first_attempt() {
            // Given
            var retry = Retry.of("test", fastConfig());
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            retry.getEventPublisher().onEvent(events::add);

            // When
            retry.executeSupplier(() -> "ok");

            // Then
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(RetryOnSuccessEvent.class);
            assertThat(((RetryOnSuccessEvent) events.getFirst()).getAttemptNumber()).isEqualTo(1);
        }

        @Test
        void should_emit_retry_events_before_each_retry_attempt() {
            // Given
            var retry = Retry.of("test", fastConfig());
            var retryEvents = Collections.synchronizedList(new ArrayList<RetryOnRetryEvent>());
            retry.getEventPublisher().onEvent(RetryOnRetryEvent.class, retryEvents::add);
            var counter = new AtomicInteger(0);

            // When — fails twice, succeeds on third
            retry.executeSupplier(() -> {
                if (counter.incrementAndGet() < 3) throw new RuntimeException("fail");
                return "ok";
            });

            // Then — 2 retry events (before attempt 2 and attempt 3)
            assertThat(retryEvents).hasSize(2);
            assertThat(retryEvents.get(0).getAttemptNumber()).isEqualTo(1);
            assertThat(retryEvents.get(1).getAttemptNumber()).isEqualTo(2);
        }
    }
}
