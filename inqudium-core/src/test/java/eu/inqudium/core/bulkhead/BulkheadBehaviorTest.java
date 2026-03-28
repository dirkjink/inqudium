package eu.inqudium.core.bulkhead;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BulkheadBehavior")
class BulkheadBehaviorTest {

    private final BulkheadBehavior behavior = BulkheadBehavior.defaultBehavior();
    private final BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(3).build();

    @Nested
    @DisplayName("Permit acquisition")
    class PermitAcquisition {

        @Test
        void should_permit_when_below_max_concurrent_calls() {
            // Given
            var state = BulkheadState.initial(); // 0 concurrent

            // When
            var result = behavior.tryAcquire(state, config);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.updatedState().concurrentCalls()).isEqualTo(1);
        }

        @Test
        void should_deny_when_at_max_concurrent_calls() {
            // Given
            var state = new BulkheadState(3); // at max

            // When
            var result = behavior.tryAcquire(state, config);

            // Then
            assertThat(result.permitted()).isFalse();
            assertThat(result.updatedState().concurrentCalls()).isEqualTo(3);
        }

        @Test
        void should_fill_up_exactly_to_max_then_deny() {
            // Given
            var state = BulkheadState.initial();

            // When — acquire 3 permits
            for (int i = 0; i < 3; i++) {
                var result = behavior.tryAcquire(state, config);
                assertThat(result.permitted()).isTrue();
                state = result.updatedState();
            }

            // Then — 4th should be denied
            var denied = behavior.tryAcquire(state, config);
            assertThat(denied.permitted()).isFalse();
        }
    }

    @Nested
    @DisplayName("Permit release")
    class PermitRelease {

        @Test
        void should_decrement_concurrent_calls_on_release() {
            // Given
            var state = new BulkheadState(2);

            // When
            var released = behavior.release(state);

            // Then
            assertThat(released.concurrentCalls()).isEqualTo(1);
        }

        @Test
        void should_not_go_below_zero_on_release() {
            // Given — pathological case: release without acquire
            var state = BulkheadState.initial(); // 0 concurrent

            // When
            var released = behavior.release(state);

            // Then
            assertThat(released.concurrentCalls()).isZero();
        }

        @Test
        void should_allow_new_acquisition_after_release() {
            // Given — bulkhead at max
            var state = new BulkheadState(3);
            assertThat(behavior.tryAcquire(state, config).permitted()).isFalse();

            // When — release one
            state = behavior.release(state);

            // Then — now one slot is available
            var result = behavior.tryAcquire(state, config);
            assertThat(result.permitted()).isTrue();
        }
    }
}
