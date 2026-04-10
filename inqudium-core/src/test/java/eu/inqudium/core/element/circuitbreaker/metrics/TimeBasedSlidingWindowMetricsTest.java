package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TimeBasedSlidingWindowMetricsTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long T0 = 100 * NANOS_PER_SECOND;

    // ======================== Initial State ========================

    @Nested
    class InitialState {

        @Test
        void should_start_with_empty_buckets_and_no_threshold_reached() {
            // Given / When
            var metrics = TimeBasedSlidingWindowMetrics.initial(5, 10, T0);

            // Then
            assertThat(metrics.isThresholdReached(T0)).isFalse();
        }

        @Test
        void should_reject_window_size_of_zero() {
            assertThatThrownBy(() -> TimeBasedSlidingWindowMetrics.initial(5, 0, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ======================== Recording Failures ========================

    @Nested
    class RecordingFailures {

        @Test
        void should_accumulate_failures_in_the_same_second_bucket() {
            // Given — threshold 5, window 10s
            var metrics = TimeBasedSlidingWindowMetrics.initial(5, 10, T0);

            // When — 3 failures in the same second
            var updated = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isFalse(); // 3 < 5
        }

        @Test
        void should_reach_threshold_when_failures_across_seconds_sum_up() {
            // Given — threshold 3, window 10s
            var metrics = TimeBasedSlidingWindowMetrics.initial(3, 10, T0);

            // When — 1 failure per second for 3 seconds
            var updated = metrics
                    .recordFailure(T0)
                    .recordFailure(T0 + NANOS_PER_SECOND)
                    .recordFailure(T0 + 2 * NANOS_PER_SECOND);

            // Then
            assertThat(updated.isThresholdReached(T0 + 2 * NANOS_PER_SECOND)).isTrue();
        }
    }

    // ======================== Time-Based Expiration ========================

    @Nested
    class TimeBasedExpiration {

        @Test
        void should_clear_old_buckets_when_time_advances_within_window() {
            // Given — threshold 3, window 5s, failures at T0 and T0+1
            var metrics = TimeBasedSlidingWindowMetrics.initial(3, 5, T0);
            var withFailures = metrics
                    .recordFailure(T0)
                    .recordFailure(T0)
                    .recordFailure(T0 + NANOS_PER_SECOND);
            assertThat(withFailures.isThresholdReached(T0 + NANOS_PER_SECOND)).isTrue();

            // When — evaluate at T0+6s: T0 bucket (with 2 failures) has expired
            long afterExpiry = T0 + 6 * NANOS_PER_SECOND;

            // Then — only 1 failure remains (from T0+1s bucket)
            assertThat(withFailures.isThresholdReached(afterExpiry)).isFalse();
        }

        @Test
        void should_wipe_all_buckets_when_entire_window_has_elapsed() {
            // Given — window 5s, fill with failures
            var metrics = TimeBasedSlidingWindowMetrics.initial(1, 5, T0);
            var filled = metrics.recordFailure(T0).recordFailure(T0 + NANOS_PER_SECOND);
            assertThat(filled.isThresholdReached(T0 + NANOS_PER_SECOND)).isTrue();

            // When — evaluate 10s later (entire window expired)
            long longAfter = T0 + 10 * NANOS_PER_SECOND;

            // Then
            assertThat(filled.isThresholdReached(longAfter)).isFalse();
        }

        @Test
        void should_handle_time_going_backwards_gracefully() {
            // Given
            var metrics = TimeBasedSlidingWindowMetrics.initial(3, 5, T0);
            var updated = metrics.recordFailure(T0 + 2 * NANOS_PER_SECOND);

            // When — record failure at earlier time
            var withPastFailure = updated.recordFailure(T0);

            // Then — should not throw, state should remain consistent
            assertThat(withPastFailure).isNotNull();
        }
    }

    // ======================== Recording Successes ========================

    @Nested
    class RecordingSuccesses {

        @Test
        void should_not_add_failures_on_success_but_still_advance_time() {
            // Given — threshold 2, window 3s
            var metrics = TimeBasedSlidingWindowMetrics.initial(2, 3, T0);
            var withFailures = metrics.recordFailure(T0).recordFailure(T0);
            assertThat(withFailures.isThresholdReached(T0)).isTrue();

            // When — success at T0+4s triggers fast-forward, expiring T0 bucket
            var afterSuccess = withFailures.recordSuccess(T0 + 4 * NANOS_PER_SECOND);

            // Then
            assertThat(afterSuccess.isThresholdReached(T0 + 4 * NANOS_PER_SECOND)).isFalse();
        }
    }

    // ======================== Reset ========================

    @Nested
    class Resetting {

        @Test
        void should_clear_all_buckets_on_reset() {
            // Given
            var metrics = TimeBasedSlidingWindowMetrics.initial(2, 5, T0);
            var filled = metrics.recordFailure(T0).recordFailure(T0);
            assertThat(filled.isThresholdReached(T0)).isTrue();

            // When
            long resetTime = T0 + NANOS_PER_SECOND;
            var afterReset = filled.reset(resetTime);

            // Then
            assertThat(afterReset.isThresholdReached(resetTime)).isFalse();
        }

        @Test
        void should_preserve_configuration_after_reset() {
            // Given
            var metrics = TimeBasedSlidingWindowMetrics.initial(7, 15, T0);

            // When
            var afterReset = (TimeBasedSlidingWindowMetrics) metrics.reset(T0 + NANOS_PER_SECOND);

            // Then
            assertThat(afterReset.maxFailuresInWindow()).isEqualTo(7);
            assertThat(afterReset.windowSizeInSeconds()).isEqualTo(15);
        }
    }

    // ======================== Immutability ========================

    @Nested
    class Immutability {

        @Test
        void should_not_modify_original_when_recording_failure() {
            // Given
            var original = TimeBasedSlidingWindowMetrics.initial(1, 5, T0);

            // When
            original.recordFailure(T0);

            // Then
            assertThat(original.isThresholdReached(T0)).isFalse();
        }
    }

    // ======================== Trip Reason ========================

    @Nested
    class TripReason {

        @Test
        void should_include_failure_count_and_window_and_threshold_in_reason() {
            // Given
            var metrics = TimeBasedSlidingWindowMetrics.initial(2, 5, T0);
            var tripped = metrics.recordFailure(T0).recordFailure(T0);

            // When
            String reason = tripped.getTripReason(T0);

            // Then
            assertThat(reason).contains("2").contains("5").contains("Threshold");
        }
    }

    // ======================== Edge Cases ========================

    @Nested
    class EdgeCases {

        @Test
        void should_work_with_window_size_of_one_second() {
            // Given
            var metrics = TimeBasedSlidingWindowMetrics.initial(2, 1, T0);

            // When — 2 failures in the same second
            var updated = metrics.recordFailure(T0).recordFailure(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isTrue();

            // And — 1 second later the bucket has been cleared
            assertThat(updated.isThresholdReached(T0 + NANOS_PER_SECOND)).isFalse();
        }

        @Test
        void should_correctly_wrap_bucket_indices() {
            // Given — window 3s
            var metrics = TimeBasedSlidingWindowMetrics.initial(2, 3, T0);

            // When — record failures spread across many seconds to exercise wrapping
            var updated = metrics;
            for (int i = 0; i < 10; i++) {
                updated = (TimeBasedSlidingWindowMetrics) updated.recordFailure(T0 + i * NANOS_PER_SECOND);
            }

            // Then — at the latest time, should only see last 3 seconds of failures
            long evalTime = T0 + 9 * NANOS_PER_SECOND;
            assertThat(updated.isThresholdReached(evalTime)).isTrue();
        }
    }
}
