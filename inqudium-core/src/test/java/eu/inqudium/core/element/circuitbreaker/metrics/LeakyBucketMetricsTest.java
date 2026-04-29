package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LeakyBucketMetricsTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long T0 = 100 * NANOS_PER_SECOND;

    // ======================== Initial State ========================

    @Nested
    class InitialState {

        @Test
        void should_start_with_zero_level() {
            // Given / When
            var metrics = LeakyBucketMetrics.initial(5, 1.0, T0);

            // Then
            assertThat(metrics.currentLevel()).isCloseTo(0.0, within(0.001));
            assertThat(metrics.isThresholdReached(T0)).isFalse();
        }

        @Test
        void should_reject_negative_leak_rate() {
            assertThatThrownBy(() -> LeakyBucketMetrics.initial(5, -1.0, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_store_configured_capacity_and_leak_rate() {
            // Given / When
            var metrics = LeakyBucketMetrics.initial(10, 2.5, T0);

            // Then
            assertThat(metrics.bucketCapacity()).isEqualTo(10);
            assertThat(metrics.leakRatePerSecond()).isCloseTo(2.5, within(0.001));
        }
    }

    // ======================== Recording Failures ========================

    @Nested
    class RecordingFailures {

        @Test
        void should_add_one_unit_per_failure() {
            // Given — leak rate 0 so nothing drains
            var metrics = LeakyBucketMetrics.initial(5, 0.0, T0);

            // When
            var updated = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);

            // Then
            assertThat(((LeakyBucketMetrics) updated).currentLevel()).isCloseTo(3.0, within(0.001));
        }

        @Test
        void should_reach_threshold_when_bucket_fills_to_capacity() {
            // Given — capacity 3, no leak
            var metrics = LeakyBucketMetrics.initial(3, 0.0, T0);

            // When
            var updated = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isTrue();
        }

        @Test
        void should_not_trip_with_one_fewer_failure_than_capacity() {
            // Given — capacity 3, no leak
            var metrics = LeakyBucketMetrics.initial(3, 0.0, T0);

            // When
            var updated = metrics.recordFailure(T0).recordFailure(T0);

            // Then
            assertThat(updated.isThresholdReached(T0)).isFalse();
        }

        @Test
        void should_allow_level_to_exceed_capacity() {
            // Given — capacity 2, no leak
            var metrics = LeakyBucketMetrics.initial(2, 0.0, T0);

            // When
            var updated = metrics.recordFailure(T0).recordFailure(T0)
                    .recordFailure(T0).recordFailure(T0);

            // Then
            assertThat(((LeakyBucketMetrics) updated).currentLevel()).isCloseTo(4.0, within(0.001));
        }
    }

    // ======================== Leak Behavior ========================

    @Nested
    class LeakBehavior {

        @Test
        void should_drain_bucket_over_time() {
            // Given — capacity 5, leak rate 1.0/sec
            var metrics = LeakyBucketMetrics.initial(5, 1.0, T0);
            var filled = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);
            // Level is 3.0

            // When — 2 seconds pass (drains 2.0)
            long twoSecsLater = T0 + 2 * NANOS_PER_SECOND;

            // Then — evaluate at twoSecsLater: level should be ~1.0
            assertThat(filled.isThresholdReached(twoSecsLater)).isFalse();
        }

        @Test
        void should_never_drain_below_zero() {
            // Given — capacity 5, leak rate 10.0/sec, level 1.0
            var metrics = LeakyBucketMetrics.initial(5, 10.0, T0);
            var withOneFailure = metrics.recordFailure(T0);

            // When — 10 seconds pass (drains 100, but level is only 1)
            var updated = (LeakyBucketMetrics) withOneFailure.recordSuccess(T0 + 10 * NANOS_PER_SECOND);

            // Then
            assertThat(updated.currentLevel()).isCloseTo(0.0, within(0.001));
        }

        @Test
        void should_apply_leak_before_adding_failure() {
            // Given — capacity 3, leak 1.0/sec, level starts at 2
            var metrics = LeakyBucketMetrics.initial(3, 1.0, T0);
            var atTwo = metrics.recordFailure(T0).recordFailure(T0);

            // When — failure after 2 seconds: leak 2.0 first (2→0), then add 1.0
            var updated = (LeakyBucketMetrics) atTwo.recordFailure(T0 + 2 * NANOS_PER_SECOND);

            // Then — level should be ~1.0
            assertThat(updated.currentLevel()).isCloseTo(1.0, within(0.01));
        }

        @Test
        void should_apply_leak_on_success_recording() {
            // Given — capacity 5, leak 2.0/sec, level 4
            var metrics = LeakyBucketMetrics.initial(5, 2.0, T0);
            var filled = metrics;
            for (int i = 0; i < 4; i++) filled = (LeakyBucketMetrics) filled.recordFailure(T0);

            // When — success after 1 second (drains 2.0)
            var afterSuccess = (LeakyBucketMetrics) filled.recordSuccess(T0 + NANOS_PER_SECOND);

            // Then — level should be ~2.0
            assertThat(afterSuccess.currentLevel()).isCloseTo(2.0, within(0.01));
        }

        @Test
        void should_evaluate_threshold_with_leaked_state() {
            // Given — capacity 3, leak 1.0/sec, 3 failures at T0 → exactly at threshold
            var metrics = LeakyBucketMetrics.initial(3, 1.0, T0);
            var atThreshold = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);
            assertThat(atThreshold.isThresholdReached(T0)).isTrue();

            // When — evaluate 1 second later (drains 1.0 → level 2.0)
            boolean stillReached = atThreshold.isThresholdReached(T0 + NANOS_PER_SECOND);

            // Then
            assertThat(stillReached).isFalse();
        }
    }

    // ======================== Zero Leak Rate ========================

    @Nested
    class ZeroLeakRate {

        @Test
        void should_never_drain_when_leak_rate_is_zero() {
            // Given
            var metrics = LeakyBucketMetrics.initial(5, 0.0, T0);
            var filled = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);

            // When — evaluate far in the future
            boolean reached = filled.isThresholdReached(T0 + 1000 * NANOS_PER_SECOND);

            // Then — level is still 3, below threshold 5
            assertThat(reached).isFalse();
            assertThat(((LeakyBucketMetrics) filled).currentLevel()).isCloseTo(3.0, within(0.001));
        }
    }

    // ======================== Reset ========================

    @Nested
    class Resetting {

        @Test
        void should_reset_level_to_zero() {
            // Given
            var metrics = LeakyBucketMetrics.initial(3, 1.0, T0);
            var filled = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);

            // When
            var afterReset = (LeakyBucketMetrics) filled.reset(T0 + NANOS_PER_SECOND);

            // Then
            assertThat(afterReset.currentLevel()).isCloseTo(0.0, within(0.001));
            assertThat(afterReset.isThresholdReached(T0 + NANOS_PER_SECOND)).isFalse();
        }

        @Test
        void should_preserve_configuration_after_reset() {
            // Given
            var metrics = LeakyBucketMetrics.initial(7, 3.5, T0);

            // When
            var afterReset = (LeakyBucketMetrics) metrics.reset(T0 + NANOS_PER_SECOND);

            // Then
            assertThat(afterReset.bucketCapacity()).isEqualTo(7);
            assertThat(afterReset.leakRatePerSecond()).isCloseTo(3.5, within(0.001));
        }
    }

    // ======================== Immutability ========================

    @Nested
    class Immutability {

        @Test
        void should_not_modify_original_when_recording() {
            // Given
            var original = LeakyBucketMetrics.initial(5, 1.0, T0);

            // When
            original.recordFailure(T0);

            // Then
            assertThat(original.currentLevel()).isCloseTo(0.0, within(0.001));
        }
    }

    // ======================== Trip Reason ========================

    @Nested
    class TripReason {

        @Test
        void should_include_level_and_capacity_and_leak_rate_in_reason() {
            // Given
            var metrics = LeakyBucketMetrics.initial(3, 1.0, T0);
            var tripped = metrics.recordFailure(T0).recordFailure(T0).recordFailure(T0);

            // When
            String reason = tripped.getTripReason(T0);

            // Then
            assertThat(reason)
                    .containsIgnoringCase("bucket")
                    .contains("Capacity: 3")
                    .containsIgnoringCase("Leak Rate");
        }
    }
}
