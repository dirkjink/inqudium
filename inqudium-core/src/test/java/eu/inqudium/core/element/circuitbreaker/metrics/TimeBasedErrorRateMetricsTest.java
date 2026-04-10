package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TimeBasedErrorRateMetricsTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long T0 = 100 * NANOS_PER_SECOND;

    // ======================== Initial State ========================

    @Nested
    class InitialState {

        @Test
        void should_start_with_no_threshold_reached() {
            // Given / When
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 5, T0);

            // Then
            assertThat(metrics.isThresholdReached(T0)).isFalse();
        }

        @Test
        void should_reject_window_size_of_zero() {
            assertThatThrownBy(() -> TimeBasedErrorRateMetrics.initial(50, 0, 5, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_minimum_calls_of_zero() {
            assertThatThrownBy(() -> TimeBasedErrorRateMetrics.initial(50, 10, 0, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_store_the_configured_failure_rate_percent() {
            // Given / When
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 5, T0);

            // Then
            assertThat(metrics.failureRatePercent()).isEqualTo(50.0);
        }
    }

    // ======================== Minimum Number of Calls ========================

    @Nested
    class MinimumNumberOfCalls {

        @Test
        void should_not_trip_before_minimum_calls_even_with_100_percent_failure_rate() {
            // Given — min=5, threshold=50%
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 5, T0);

            // When — 4 failures (one short of minimum)
            var updated = metrics;
            for (int i = 0; i < 4; i++) {
                updated = (TimeBasedErrorRateMetrics) updated.recordFailure(T0);
            }

            // Then
            assertThat(updated.isThresholdReached(T0)).isFalse();
        }

        @Test
        void should_trip_once_minimum_calls_are_met_and_rate_exceeds_threshold() {
            // Given — min=5, threshold=50%
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 5, T0);

            // When — 5 failures = 100% failure rate
            var updated = metrics;
            for (int i = 0; i < 5; i++) {
                updated = (TimeBasedErrorRateMetrics) updated.recordFailure(T0);
            }

            // Then
            assertThat(updated.isThresholdReached(T0)).isTrue();
        }

        @Test
        void should_consider_mixed_calls_toward_minimum() {
            // Given — min=4, threshold=50%
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 4, T0);

            // When — 3 failures + 1 success = 4 calls, 75% failure rate
            var updated = metrics
                    .recordFailure(T0).recordFailure(T0).recordFailure(T0)
                    .recordSuccess(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isTrue();
        }
    }

    // ======================== Error Rate Calculation ========================

    @Nested
    class ErrorRateCalculation {

        @Test
        void should_interpret_threshold_as_percentage() {
            // Given — threshold=50 means 50%, min=2
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 2, T0);

            // When — 1 failure + 1 success = 50% failure rate
            var updated = metrics.recordFailure(T0).recordSuccess(T0);

            // Then — 50% >= 50% → tripped
            assertThat(updated.isThresholdReached(T0)).isTrue();
        }

        @Test
        void should_not_trip_when_failure_rate_is_just_below_threshold() {
            // Given — threshold=50%, min=3
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 3, T0);

            // When — 1 failure + 2 successes = 33.3% failure rate
            var updated = metrics.recordFailure(T0).recordSuccess(T0).recordSuccess(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isFalse();
        }

        @Test
        void should_trip_at_100_percent_failure_rate_with_any_threshold() {
            // Given — threshold=90%
            var metrics = TimeBasedErrorRateMetrics.initial(90, 10, 3, T0);

            // When — 3 failures
            var updated = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isTrue();
        }
    }

    // ======================== Time-Based Bucket Expiration ========================

    @Nested
    class BucketExpiration {

        @Test
        void should_expire_old_buckets_when_time_advances() {
            // Given — threshold=50%, window=5s, min=2
            var metrics = TimeBasedErrorRateMetrics.initial(50, 5, 2, T0);
            // 2 failures at T0
            var withFailures = metrics.recordFailure(T0).recordFailure(T0);
            assertThat(withFailures.isThresholdReached(T0)).isTrue();

            // When — evaluate at T0+6s: T0 bucket expired
            long afterExpiry = T0 + 6 * NANOS_PER_SECOND;

            // Then — no calls in window → min not met → not reached
            assertThat(withFailures.isThresholdReached(afterExpiry)).isFalse();
        }

        @Test
        void should_wipe_all_data_when_entire_window_elapses() {
            // Given — window 3s
            var metrics = TimeBasedErrorRateMetrics.initial(50, 3, 1, T0);
            var filled = metrics.recordFailure(T0).recordFailure(T0 + NANOS_PER_SECOND);

            // When — 10 seconds later
            long longAfter = T0 + 10 * NANOS_PER_SECOND;

            // Then
            assertThat(filled.isThresholdReached(longAfter)).isFalse();
        }

        @Test
        void should_only_count_calls_within_the_active_window() {
            // Given — threshold=50%, window=3s, min=2
            var metrics = TimeBasedErrorRateMetrics.initial(50, 3, 2, T0);

            // When — 2 failures at T0, then 2 successes at T0+4s (old failures expired)
            var updated = metrics.recordFailure(T0).recordFailure(T0)
                    .recordSuccess(T0 + 4 * NANOS_PER_SECOND)
                    .recordSuccess(T0 + 4 * NANOS_PER_SECOND);

            // Then — only 2 successes in window → 0% failure rate
            assertThat(updated.isThresholdReached(T0 + 4 * NANOS_PER_SECOND)).isFalse();
        }
    }

    // ======================== Successes Recorded After Failure Spike ========================

    @Nested
    class RecoveryScenarios {

        @Test
        void should_drop_below_threshold_after_recording_enough_successes() {
            // Given — threshold=50%, window=10s, min=2
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 2, T0);
            var tripped = metrics.recordFailure(T0).recordFailure(T0);
            assertThat(tripped.isThresholdReached(T0)).isTrue();

            // When — add 3 successes in same window
            var recovered = tripped
                    .recordSuccess(T0 + NANOS_PER_SECOND)
                    .recordSuccess(T0 + NANOS_PER_SECOND)
                    .recordSuccess(T0 + NANOS_PER_SECOND);

            // Then — 2 failures / 5 total = 40% < 50%
            assertThat(recovered.isThresholdReached(T0 + NANOS_PER_SECOND)).isFalse();
        }
    }

    // ======================== Success Can Trigger Threshold ========================

    @Nested
    class SuccessCanTriggerThreshold {

        @Test
        void should_reach_threshold_after_success_if_it_meets_minimum_calls() {
            // Given — threshold=50%, min=4, 3 failures recorded so far (min not met)
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 4, T0);
            var threeFailures = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);
            assertThat(threeFailures.isThresholdReached(T0)).isFalse();

            // When — 1 success makes it 4 calls total (3/4=75% failure rate)
            var updated = threeFailures.recordSuccess(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isTrue();
        }
    }

    // ======================== Reset ========================

    @Nested
    class Resetting {

        @Test
        void should_clear_all_buckets_on_reset() {
            // Given
            var metrics = TimeBasedErrorRateMetrics.initial(50, 5, 2, T0);
            var filled = metrics.recordFailure(T0).recordFailure(T0);

            // When
            var afterReset = filled.reset(T0 + NANOS_PER_SECOND);

            // Then
            assertThat(afterReset.isThresholdReached(T0 + NANOS_PER_SECOND)).isFalse();
        }

        @Test
        void should_preserve_configuration_after_reset() {
            // Given
            var metrics = TimeBasedErrorRateMetrics.initial(60, 15, 8, T0);

            // When
            var afterReset = (TimeBasedErrorRateMetrics) metrics.reset(T0 + NANOS_PER_SECOND);

            // Then
            assertThat(afterReset.failureRatePercent()).isEqualTo(60.0);
            assertThat(afterReset.windowSizeInSeconds()).isEqualTo(15);
            assertThat(afterReset.minimumNumberOfCalls()).isEqualTo(8);
        }
    }

    // ======================== Immutability ========================

    @Nested
    class Immutability {

        @Test
        void should_not_modify_original_when_recording() {
            // Given
            var original = TimeBasedErrorRateMetrics.initial(50, 10, 2, T0);

            // When
            original.recordFailure(T0);
            original.recordSuccess(T0);

            // Then
            assertThat(original.isThresholdReached(T0)).isFalse();
        }

        @Test
        void should_return_defensive_copies_of_bucket_arrays() {
            // Given
            var metrics = TimeBasedErrorRateMetrics.initial(50, 5, 2, T0);
            var updated = (TimeBasedErrorRateMetrics) metrics.recordFailure(T0);

            // When — mutate the returned array
            int[] buckets = updated.failureBuckets();
            buckets[0] = 999;

            // Then — original should be unchanged
            assertThat(updated.failureBuckets()[0]).isNotEqualTo(999);
        }
    }

    // ======================== Trip Reason ========================

    @Nested
    class TripReason {

        @Test
        void should_include_rate_and_counts_and_window_and_threshold_in_reason() {
            // Given
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 2, T0);
            var tripped = metrics.recordFailure(T0).recordFailure(T0);

            // When
            String reason = tripped.getTripReason(T0);

            // Then
            assertThat(reason)
                    .contains("100")  // 100% rate
                    .contains("2")    // failures
                    .contains("10")   // window
                    .contains("50");  // threshold
        }

        @Test
        void should_handle_zero_calls_gracefully_in_trip_reason() {
            // Given
            var metrics = TimeBasedErrorRateMetrics.initial(50, 10, 2, T0);

            // When
            String reason = metrics.getTripReason(T0);

            // Then
            assertThat(reason).containsIgnoringCase("no calls");
        }
    }

    // ======================== Equality ========================

    @Nested
    class Equality {

        @Test
        void should_be_equal_for_identical_state() {
            // Given
            var a = TimeBasedErrorRateMetrics.initial(50, 10, 5, T0);
            var b = TimeBasedErrorRateMetrics.initial(50, 10, 5, T0);

            // Then
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void should_not_be_equal_after_different_recordings() {
            // Given
            var a = TimeBasedErrorRateMetrics.initial(50, 10, 5, T0);
            var b = (TimeBasedErrorRateMetrics) a.recordFailure(T0);

            // Then
            assertThat(a).isNotEqualTo(b);
        }
    }
}
