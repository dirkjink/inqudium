package eu.inqudium.timelimiter;

import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.timelimiter.InqTimeLimitExceededException;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import eu.inqudium.timelimiter.event.TimeLimiterOnSuccessEvent;
import eu.inqudium.timelimiter.event.TimeLimiterOnTimeoutEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TimeLimiter")
class TimeLimiterTest {

    @Nested
    @DisplayName("Successful calls")
    class SuccessfulCalls {

        @Test
        void should_return_result_when_call_completes_within_timeout() {
            // Given
            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(5))
                    .build());

            // When — immediate completion
            var result = tl.executeFutureSupplier(() ->
                    CompletableFuture.completedFuture("ok"));

            // Then
            assertThat(result).isEqualTo("ok");
        }

        @Test
        void should_return_result_from_synchronous_supplier_within_timeout() {
            // Given
            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(5))
                    .build());

            // When
            var result = tl.executeSupplier(() -> "sync-ok");

            // Then
            assertThat(result).isEqualTo("sync-ok");
        }
    }

    @Nested
    @DisplayName("Timeout behavior")
    class TimeoutBehavior {

        @Test
        void should_throw_time_limit_exceeded_when_future_does_not_complete() {
            // Given — very short timeout
            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(50))
                    .build());

            // When / Then — future that never completes
            assertThatThrownBy(() ->
                    tl.executeFutureSupplier(() -> new CompletableFuture<>()) // never completes
            ).isInstanceOf(InqTimeLimitExceededException.class)
                    .satisfies(ex -> {
                        var tle = (InqTimeLimitExceededException) ex;
                        assertThat(tle.getCode()).isEqualTo("INQ-TL-001");
                        assertThat(tle.getElementName()).isEqualTo("test");
                        assertThat(tle.getConfiguredDuration()).isEqualTo(Duration.ofMillis(50));
                    });
        }

        @Test
        void should_throw_time_limit_exceeded_for_slow_synchronous_supplier() {
            // Given
            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(50))
                    .build());

            // When / Then
            assertThatThrownBy(() ->
                    tl.executeSupplier(() -> {
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                        return "too slow";
                    })
            ).isInstanceOf(InqTimeLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("Error propagation")
    class ErrorPropagation {

        @Test
        void should_propagate_exception_when_future_fails_before_timeout() {
            // Given
            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(5))
                    .build());

            // When / Then
            assertThatThrownBy(() ->
                    tl.executeFutureSupplier(() ->
                            CompletableFuture.failedFuture(new RuntimeException("downstream failure")))
            ).isInstanceOf(RuntimeException.class)
                    .hasMessage("downstream failure");
        }
    }

    @Nested
    @DisplayName("Orphaned call handlers")
    class OrphanedCallHandlers {

        @Test
        void should_invoke_orphaned_result_handler_when_call_completes_after_timeout() throws Exception {
            // Given
            var orphanedResult = new AtomicReference<Object>();
            var orphanedLatch = new CountDownLatch(1);

            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(50))
                    .onOrphanedResult((ctx, result) -> {
                        orphanedResult.set(result);
                        orphanedLatch.countDown();
                    })
                    .build());

            // Create a future that completes after 200ms
            var slowFuture = new CompletableFuture<String>();

            // When — timeout fires after 50ms
            try {
                tl.executeFutureSupplier(() -> slowFuture);
            } catch (InqTimeLimitExceededException ignored) {}

            // Complete the orphaned future
            slowFuture.complete("late result");

            // Then — orphaned handler should be called
            assertThat(orphanedLatch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(orphanedResult.get()).isEqualTo("late result");
        }

        @Test
        void should_invoke_orphaned_error_handler_when_call_fails_after_timeout() throws Exception {
            // Given
            var orphanedError = new AtomicReference<Throwable>();
            var orphanedLatch = new CountDownLatch(1);

            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(50))
                    .onOrphanedError((ctx, error) -> {
                        orphanedError.set(error);
                        orphanedLatch.countDown();
                    })
                    .build());

            var slowFuture = new CompletableFuture<String>();

            // When
            try {
                tl.executeFutureSupplier(() -> slowFuture);
            } catch (InqTimeLimitExceededException ignored) {}

            // Fail the orphaned future
            slowFuture.completeExceptionally(new RuntimeException("late failure"));

            // Then
            assertThat(orphanedLatch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(orphanedError.get()).isInstanceOf(RuntimeException.class)
                    .hasMessage("late failure");
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        void should_emit_success_event_when_call_completes_within_timeout() {
            // Given
            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(5)).build());
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            tl.getEventPublisher().onEvent(events::add);

            // When
            tl.executeFutureSupplier(() -> CompletableFuture.completedFuture("ok"));

            // Then
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(TimeLimiterOnSuccessEvent.class);
        }

        @Test
        void should_emit_timeout_event_when_timeout_fires() {
            // Given
            var tl = TimeLimiter.of("test", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(50)).build());
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            tl.getEventPublisher().onEvent(events::add);

            // When
            try {
                tl.executeFutureSupplier(() -> new CompletableFuture<>());
            } catch (InqTimeLimitExceededException ignored) {}

            // Then
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(TimeLimiterOnTimeoutEvent.class);
        }
    }

    @Nested
    @DisplayName("Registry")
    class RegistryTests {

        @Test
        void should_return_same_instance_for_same_name() {
            // Given
            var registry = new TimeLimiterRegistry();

            // When / Then
            assertThat(registry.get("payment")).isSameAs(registry.get("payment"));
        }
    }
}
