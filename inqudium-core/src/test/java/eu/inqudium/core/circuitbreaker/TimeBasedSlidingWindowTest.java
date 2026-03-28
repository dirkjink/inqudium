package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.InqClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TimeBasedSlidingWindow")
class TimeBasedSlidingWindowTest {

    private static final long SLOW_THRESHOLD_NANOS = 3_000_000_000L;

    private AtomicReference<Instant> timeRef(String instant) {
        return new AtomicReference<>(Instant.parse(instant));
    }

    @Nested
    @DisplayName("Recording within one bucket")
    class SingleBucket {

        @Test
        void should_record_outcomes_in_the_current_time_bucket() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            InqClock clock = time::get;
            var window = new TimeBasedSlidingWindow(10, SLOW_THRESHOLD_NANOS, clock);

            // When — all within the same second
            window.record(CallOutcome.success(1_000_000L, clock.instant()));
            window.record(CallOutcome.failure(1_000_000L, clock.instant()));
            var snapshot = window.record(CallOutcome.failure(1_000_000L, clock.instant()));

            // Then
            assertThat(snapshot.totalCalls()).isEqualTo(3);
            assertThat(snapshot.failedCalls()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Bucket rotation over time")
    class BucketRotation {

        @Test
        void should_separate_calls_into_different_time_buckets() {
            // Given — 5-second window
            var time = timeRef("2026-01-01T00:00:00Z");
            InqClock clock = time::get;
            var window = new TimeBasedSlidingWindow(5, SLOW_THRESHOLD_NANOS, clock);

            // When — record failure at t=0, then advance to t=1 and record success
            window.record(CallOutcome.failure(1_000_000L, clock.instant()));

            time.set(Instant.parse("2026-01-01T00:00:01Z"));
            window.record(CallOutcome.success(1_000_000L, clock.instant()));
            var snapshot = window.snapshot();

            // Then — both calls are within the 5-second window
            assertThat(snapshot.totalCalls()).isEqualTo(2);
            assertThat(snapshot.failedCalls()).isEqualTo(1);
        }

        @Test
        void should_evict_old_buckets_when_window_slides_forward() {
            // Given — 3-second window
            var time = timeRef("2026-01-01T00:00:00Z");
            InqClock clock = time::get;
            var window = new TimeBasedSlidingWindow(3, SLOW_THRESHOLD_NANOS, clock);

            // When — record 3 failures at t=0
            window.record(CallOutcome.failure(1_000_000L, clock.instant()));
            window.record(CallOutcome.failure(1_000_000L, clock.instant()));
            window.record(CallOutcome.failure(1_000_000L, clock.instant()));

            // Advance time by 3 seconds — old bucket should be evicted
            time.set(Instant.parse("2026-01-01T00:00:03Z"));
            window.record(CallOutcome.success(1_000_000L, clock.instant()));
            var snapshot = window.snapshot();

            // Then — only the new success is in the window (old buckets evicted)
            assertThat(snapshot.totalCalls()).isEqualTo(1);
            assertThat(snapshot.failedCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("Window expiry")
    class WindowExpiry {

        @Test
        void should_clear_all_buckets_when_entire_window_has_elapsed() {
            // Given — 5-second window
            var time = timeRef("2026-01-01T00:00:00Z");
            InqClock clock = time::get;
            var window = new TimeBasedSlidingWindow(5, SLOW_THRESHOLD_NANOS, clock);

            // When — fill with failures, then jump 10 seconds ahead
            for (int i = 0; i < 5; i++) {
                window.record(CallOutcome.failure(1_000_000L, clock.instant()));
            }

            time.set(Instant.parse("2026-01-01T00:00:10Z"));
            var snapshot = window.snapshot();

            // Then — everything evicted
            assertThat(snapshot.totalCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("Slow call tracking")
    class SlowCallTracking {

        @Test
        void should_track_slow_calls_across_time_buckets() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            InqClock clock = time::get;
            var window = new TimeBasedSlidingWindow(10, SLOW_THRESHOLD_NANOS, clock);

            // When — slow call at t=0, fast call at t=1
            window.record(CallOutcome.success(4_000_000_000L, clock.instant())); // slow

            time.set(Instant.parse("2026-01-01T00:00:01Z"));
            window.record(CallOutcome.success(1_000_000L, clock.instant())); // fast
            var snapshot = window.snapshot();

            // Then
            assertThat(snapshot.slowCalls()).isEqualTo(1);
            assertThat(snapshot.totalCalls()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Reset")
    class Reset {

        @Test
        void should_clear_all_buckets_on_reset() {
            // Given
            var time = timeRef("2026-01-01T00:00:00Z");
            InqClock clock = time::get;
            var window = new TimeBasedSlidingWindow(5, SLOW_THRESHOLD_NANOS, clock);
            window.record(CallOutcome.failure(4_000_000_000L, clock.instant()));

            // When
            window.reset();
            var snapshot = window.snapshot();

            // Then
            assertThat(snapshot.totalCalls()).isZero();
        }
    }
}
