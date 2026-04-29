package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CompositeFailureMetricsTest {

    private static final long NOW = 1_000_000_000L;
    private static final long LATER = 2_000_000_000L;

    // ======================== Factory ========================

    @Nested
    class Factory {

        @Test
        void should_reject_null_delegates() {
            assertThatThrownBy(() -> CompositeFailureMetrics.of((FailureMetrics[]) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_empty_delegates() {
            assertThatThrownBy(CompositeFailureMetrics::of)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_accept_a_single_delegate() {
            // Given / When
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(3, 0)
            );

            // Then
            assertThat(composite.delegates()).hasSize(1);
        }

        @Test
        void should_accept_multiple_delegates() {
            // Given / When
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(3, 0),
                    GradualDecayMetrics.initial(5, 0)
            );

            // Then
            assertThat(composite.delegates()).hasSize(2);
        }
    }

    // ======================== OR-Logic Threshold ========================

    @Nested
    class OrLogicThreshold {

        @Test
        void should_not_trip_when_no_delegate_has_reached_its_threshold() {
            // Given — consecutive threshold=5, gradual threshold=5
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(5, 0),
                    GradualDecayMetrics.initial(5, 0)
            );

            // When — 2 failures (below both thresholds)
            var updated = composite.recordFailure(NOW).recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isFalse();
        }

        @Test
        void should_trip_when_first_delegate_reaches_threshold() {
            // Given — consecutive threshold=2, gradual threshold=10
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(2, 0),
                    GradualDecayMetrics.initial(10, 0)
            );

            // When — 2 consecutive failures (trips first, not second)
            var updated = composite.recordFailure(NOW).recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isTrue();
        }

        @Test
        void should_trip_when_second_delegate_reaches_threshold() {
            // Given — consecutive threshold=10, gradual threshold=2
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(10, 0),
                    GradualDecayMetrics.initial(2, 0)
            );

            // When — 2 failures (trips gradual, not consecutive)
            var updated = composite.recordFailure(NOW).recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isTrue();
        }

        @Test
        void should_trip_when_both_delegates_reach_threshold_simultaneously() {
            // Given — both threshold=3
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(3, 0),
                    GradualDecayMetrics.initial(3, 0)
            );

            // When — 3 failures
            var updated = composite.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isTrue();
        }
    }

    // ======================== Success Propagation ========================

    @Nested
    class SuccessPropagation {

        @Test
        void should_propagate_success_to_all_delegates() {
            // Given — consecutive=3 resets on success, gradual=5 decrements by 1
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(3, 0),
                    GradualDecayMetrics.initial(5, 0)
            );
            var withFailures = composite.recordFailure(NOW).recordFailure(NOW);

            // When
            var afterSuccess = withFailures.recordSuccess(NOW);

            // Then — consecutive reset to 0, gradual decremented to 1
            var delegates = ((CompositeFailureMetrics) afterSuccess).delegates();
            assertThat(((ConsecutiveFailuresMetrics) delegates.get(0)).consecutiveFailures()).isZero();
            assertThat(((GradualDecayMetrics) delegates.get(1)).failureCount()).isEqualTo(1);
        }

        @Test
        void should_recover_composite_after_success_breaks_consecutive_streak() {
            // Given — consecutive threshold=2 is the only one that could trip
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(2, 0),
                    GradualDecayMetrics.initial(100, 0)
            );
            var tripped = composite.recordFailure(NOW).recordFailure(NOW);
            assertThat(tripped.isThresholdReached(NOW)).isTrue();

            // When — one success resets consecutive counter
            var recovered = tripped.recordSuccess(NOW);

            // Then
            assertThat(recovered.isThresholdReached(NOW)).isFalse();
        }
    }

    // ======================== Reset ========================

    @Nested
    class Resetting {

        @Test
        void should_reset_all_delegates() {
            // Given
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(3, 0),
                    GradualDecayMetrics.initial(5, 0)
            );
            var filled = composite.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);
            assertThat(filled.isThresholdReached(NOW)).isTrue();

            // When
            var afterReset = filled.reset(LATER);

            // Then
            assertThat(afterReset.isThresholdReached(LATER)).isFalse();
        }

        @Test
        void should_preserve_delegate_count_after_reset() {
            // Given
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(3, 0),
                    GradualDecayMetrics.initial(5, 0),
                    ConsecutiveFailuresMetrics.initial(10, 0)
            );

            // When
            var afterReset = (CompositeFailureMetrics) composite.reset(LATER);

            // Then
            assertThat(afterReset.delegates()).hasSize(3);
        }
    }

    // ======================== Immutability ========================

    @Nested
    class Immutability {

        @Test
        void should_not_modify_original_when_recording() {
            // Given
            var original = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(2, 0)
            );

            // When
            original.recordFailure(NOW);

            // Then
            assertThat(original.isThresholdReached(NOW)).isFalse();
        }

        @Test
        void should_have_immutable_delegate_list() {
            // Given
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(3, 0)
            );

            // When / Then
            assertThatThrownBy(() -> composite.delegates().add(
                    GradualDecayMetrics.initial(5, 0))
            )
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ======================== Trip Reason ========================

    @Nested
    class TripReason {

        @Test
        void should_list_only_triggering_components_in_reason() {
            // Given — consecutive threshold=2 trips, gradual threshold=100 does not
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(2, 0),
                    GradualDecayMetrics.initial(100, 0)
            );
            var tripped = composite.recordFailure(NOW).recordFailure(NOW);

            // When
            String reason = tripped.getTripReason(NOW);

            // Then
            assertThat(reason)
                    .containsIgnoringCase("Composite")
                    .contains("ConsecutiveFailuresMetrics")
                    .doesNotContain("GradualDecayMetrics");
        }

        @Test
        void should_list_all_triggering_components_when_multiple_trip() {
            // Given — both threshold=2
            var composite = CompositeFailureMetrics.of(
                    ConsecutiveFailuresMetrics.initial(2, 0),
                    GradualDecayMetrics.initial(2, 0)
            );
            var tripped = composite.recordFailure(NOW).recordFailure(NOW);

            // When
            String reason = tripped.getTripReason(NOW);

            // Then
            assertThat(reason)
                    .contains("ConsecutiveFailuresMetrics")
                    .contains("GradualDecayMetrics");
        }
    }

    // ======================== Mixed Delegate Types ========================

    @Nested
    class MixedDelegateTypes {

        @Test
        void should_work_with_time_based_and_count_based_delegates_together() {
            // Given — time-based window + consecutive failures
            var timeBased = TimeBasedSlidingWindowMetrics.initial(3, 5, NOW);
            var consecutive = ConsecutiveFailuresMetrics.initial(10, 0);
            var composite = CompositeFailureMetrics.of(timeBased, consecutive);

            // When — 3 failures (trips time-based but not consecutive)
            var updated = composite.recordFailure(NOW).recordFailure(NOW).recordFailure(NOW);

            // Then
            assertThat(updated.isThresholdReached(NOW)).isTrue();
        }
    }
}
