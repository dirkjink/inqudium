package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SlidingWindowMetricsTest {

    private static final long NOW = 1_000_000_000L;

    // ======================== Initial State ========================

    @Nested
    class InitialState {

        @Test
        void should_start_with_zero_size_and_zero_failures() {
            // Given / When
            var metrics = SlidingWindowMetrics.initial(5, 10, 5);

            // Then
            assertThat(metrics.size()).isZero();
            assertThat(metrics.failureCount()).isZero();
            assertThat(metrics.isThresholdReached(NOW)).isFalse();
        }

        @Test
        void should_reject_window_size_of_zero() {
            assertThatThrownBy(() -> SlidingWindowMetrics.initial(5, 0, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_minimum_calls_greater_than_window_size() {
            assertThatThrownBy(() -> SlidingWindowMetrics.initial(5, 5, 6))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_minimum_calls_of_zero() {
            assertThatThrownBy(() -> SlidingWindowMetrics.initial(5, 10, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ======================== Minimum Number of Calls ========================

    @Nested
    class MinimumNumberOfCalls {

        @Test
        void should_not_reach_threshold_before_minimum_calls_are_recorded() {
            // Given — threshold 1, window 5, minimum 3 — all failures but only 2 calls
            var metrics = SlidingWindowMetrics.initial(1, 5, 3);

            // When
            var updated = metrics.recordFailure(NOW).recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isFalse();
        }

        @Test
        void should_reach_threshold_once_minimum_calls_are_met() {
            // Given — threshold 2, window 5, minimum 3
            var metrics = SlidingWindowMetrics.initial(2, 5, 3);

            // When — 3 failures (meets minimum and exceeds threshold)
            var updated = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isTrue();
        }
    }

    // ======================== Circular Buffer Eviction ========================

    @Nested
    class CircularBufferEviction {

        @Test
        void should_evict_oldest_outcome_when_window_is_full() {
            // Given — window=3, threshold=3, min=1, fill with 3 failures
            var metrics = SlidingWindowMetrics.initial(3, 3, 1);
            var full = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);
            assertThat(full.isThresholdReached(NOW)).isTrue();

            // When — record a success, evicting the oldest failure
            var updated = full.recordSuccess(NOW);

            // Then — 2 failures remain
            assertThat(((SlidingWindowMetrics) updated).failureCount()).isEqualTo(2);
            assertThat(updated.isThresholdReached(NOW)).isFalse();
        }

        @Test
        void should_correctly_track_failure_count_across_multiple_evictions() {
            // Given — window=3, threshold=2, min=1
            var metrics = SlidingWindowMetrics.initial(2, 3, 1);

            // When — F, S, F, S, F
            var updated = metrics
                    .recordFailure(NOW).recordSuccess(NOW).recordFailure(NOW)
                    .recordSuccess(NOW).recordFailure(NOW);

            // Then
            assertThat(((SlidingWindowMetrics) updated).failureCount()).isEqualTo(2);
            assertThat(updated.isThresholdReached(NOW)).isTrue();
        }

        @Test
        void should_fully_replace_window_content_after_enough_new_outcomes() {
            // Given — window=3, fill with failures
            var metrics = SlidingWindowMetrics.initial(1, 3, 1);
            var allFails = metrics.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);
            assertThat(((SlidingWindowMetrics) allFails).failureCount()).isEqualTo(3);

            // When — 3 successes replace all failures
            var allSuccesses = allFails.recordSuccess(NOW).recordSuccess(NOW).recordSuccess(NOW);

            // Then
            assertThat(((SlidingWindowMetrics) allSuccesses).failureCount()).isZero();
            assertThat(allSuccesses.isThresholdReached(NOW)).isFalse();
        }
    }

    // ======================== Threshold Evaluation ========================

    @Nested
    class ThresholdEvaluation {

        @Test
        void should_use_absolute_failure_count_not_percentage() {
            // Given — threshold=3 means 3 absolute failures, window=10, min=1
            var metrics = SlidingWindowMetrics.initial(3, 10, 1);

            // When — 2 failures + 5 successes
            var updated = metrics
                    .recordFailure(NOW).recordFailure(NOW)
                    .recordSuccess(NOW).recordSuccess(NOW).recordSuccess(NOW)
                    .recordSuccess(NOW).recordSuccess(NOW);

            // Then — 2 failures < threshold 3
            assertThat(updated.isThresholdReached(NOW)).isFalse();
        }

        @Test
        void should_trip_when_failures_equal_threshold_regardless_of_successes() {
            // Given
            var metrics = SlidingWindowMetrics.initial(3, 10, 1);

            // When — 3 failures mixed with successes
            var updated = metrics
                    .recordFailure(NOW).recordSuccess(NOW)
                    .recordFailure(NOW).recordSuccess(NOW)
                    .recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isTrue();
        }
    }

    // ======================== Reset ========================

    @Nested
    class Resetting {

        @Test
        void should_clear_all_outcomes_on_reset() {
            // Given
            var metrics = SlidingWindowMetrics.initial(2, 5, 1);
            var filled = metrics.recordFailure(NOW).recordFailure(NOW).recordSuccess(NOW);

            // When
            var afterReset = filled.reset(NOW);

            // Then
            assertThat(((SlidingWindowMetrics) afterReset).size()).isZero();
            assertThat(((SlidingWindowMetrics) afterReset).failureCount()).isZero();
        }

        @Test
        void should_preserve_configuration_after_reset() {
            // Given
            var metrics = SlidingWindowMetrics.initial(4, 8, 3);

            // When
            var afterReset = (SlidingWindowMetrics) metrics.reset(NOW);

            // Then
            assertThat(afterReset.maxFailuresInWindow()).isEqualTo(4);
            assertThat(afterReset.windowSize()).isEqualTo(8);
            assertThat(afterReset.minimumNumberOfCalls()).isEqualTo(3);
        }
    }

    // ======================== Immutability ========================

    @Nested
    class Immutability {

        @Test
        void should_not_modify_original_when_recording_outcome() {
            // Given
            var original = SlidingWindowMetrics.initial(5, 10, 1);

            // When
            original.recordFailure(NOW);
            original.recordSuccess(NOW);

            // Then
            assertThat(original.size()).isZero();
            assertThat(original.failureCount()).isZero();
        }
    }

    // ======================== Trip Reason ========================

    @Nested
    class TripReason {

        @Test
        void should_include_failure_count_and_call_count_and_threshold_in_reason() {
            // Given
            var metrics = SlidingWindowMetrics.initial(2, 5, 1);
            var tripped = metrics.recordFailure(NOW).recordFailure(NOW);

            // When
            String reason = tripped.getTripReason(NOW);

            // Then
            assertThat(reason)
                    .contains("2 failures")
                    .contains("2 calls")
                    .contains("Threshold: 2");
        }
    }
}
