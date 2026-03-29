package eu.inqudium.bulkhead;

import eu.inqudium.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.event.InqEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bulkhead")
class BulkheadTest {

    @Nested
    @DisplayName("Permit management")
    class PermitManagement {

        @Test
        void should_permit_calls_below_max_concurrent() {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(5).build());

            // When / Then
            var result = bh.executeSupplier(() -> "ok");
            assertThat(result).isEqualTo("ok");
        }

        @Test
        void should_release_permit_after_successful_call() {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(1).build());

            // When — call completes, permit should be released
            bh.executeSupplier(() -> "first");

            // Then — second call should succeed (permit was released)
            var result = bh.executeSupplier(() -> "second");
            assertThat(result).isEqualTo("second");
        }

        @Test
        void should_release_permit_after_failed_call() {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(1).build());

            // When — call fails, permit should still be released
            try {
                bh.executeSupplier(() -> { throw new RuntimeException("boom"); });
            } catch (RuntimeException ignored) {}

            // Then — next call should succeed
            var result = bh.executeSupplier(() -> "recovered");
            assertThat(result).isEqualTo("recovered");
        }
    }

    @Nested
    @DisplayName("Rejection")
    class Rejection {

        @Test
        void should_reject_when_all_permits_are_held() throws Exception {
            // Given — 1 permit, held by a blocking call
            var config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
            var bh = Bulkhead.of("test", config);

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);

            // Hold the single permit
            var executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> bh.executeSupplier(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return "blocking";
            }));

            entered.await(2, TimeUnit.SECONDS);

            // When / Then — second call should be rejected
            assertThatThrownBy(() -> bh.executeSupplier(() -> "rejected"))
                    .isInstanceOf(InqBulkheadFullException.class)
                    .satisfies(ex -> {
                        var bfe = (InqBulkheadFullException) ex;
                        assertThat(bfe.getCode()).isEqualTo("INQ-BH-001");
                        assertThat(bfe.getMaxConcurrentCalls()).isEqualTo(1);
                    });

            release.countDown();
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("Metrics")
    class Metrics {

        @Test
        void should_report_zero_concurrent_calls_when_idle() {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(10).build());

            // Then
            assertThat(bh.getConcurrentCalls()).isZero();
            assertThat(bh.getAvailablePermits()).isEqualTo(10);
        }

        @Test
        void should_report_correct_available_permits_after_call() {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(5).build());

            // When — call completes (permit acquired and released)
            bh.executeSupplier(() -> "ok");

            // Then — all permits restored
            assertThat(bh.getAvailablePermits()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        void should_emit_acquire_and_release_events_for_successful_call() {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(5).build());
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            bh.getEventPublisher().onEvent(events::add);

            // When
            bh.executeSupplier(() -> "ok");

            // Then
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(BulkheadOnAcquireEvent.class);
            assertThat(events.get(1)).isInstanceOf(BulkheadOnReleaseEvent.class);
        }

        @Test
        void should_emit_reject_event_when_full() throws Exception {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(1).build());
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            bh.getEventPublisher().onEvent(events::add);

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();

            executor.submit(() -> bh.executeSupplier(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return "blocking";
            }));

            entered.await(2, TimeUnit.SECONDS);

            // When
            try { bh.executeSupplier(() -> "rejected"); } catch (InqBulkheadFullException ignored) {}

            // Then — acquire + reject (release comes later when blocking call finishes)
            assertThat(events.stream().filter(e -> e instanceof BulkheadOnRejectEvent).count()).isEqualTo(1);

            release.countDown();
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("Runnable decoration")
    class RunnableDecoration {

        @Test
        void should_decorate_runnable_with_acquire_and_release() {
            // Given
            var bh = Bulkhead.of("test", BulkheadConfig.builder().maxConcurrentCalls(5).build());
            var executed = new AtomicBoolean(false);

            // When
            bh.executeRunnable(() -> executed.set(true));

            // Then
            assertThat(executed.get()).isTrue();
            assertThat(bh.getAvailablePermits()).isEqualTo(5);
        }
    }
}
