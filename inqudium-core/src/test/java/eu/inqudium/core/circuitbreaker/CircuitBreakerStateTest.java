package eu.inqudium.core.circuitbreaker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreakerState")
class CircuitBreakerStateTest {

    @Nested
    @DisplayName("State values")
    class StateValues {

        @Test
        void should_contain_exactly_three_states() {
            assertThat(CircuitBreakerState.values()).hasSize(3);
        }

        @Test
        void should_define_closed_open_and_half_open_in_order() {
            assertThat(CircuitBreakerState.values()).containsExactly(
                    CircuitBreakerState.CLOSED,
                    CircuitBreakerState.OPEN,
                    CircuitBreakerState.HALF_OPEN
            );
        }
    }

    @Nested
    @DisplayName("Ordinal values")
    class OrdinalValues {

        @Test
        void should_assign_closed_as_ordinal_zero() {
            assertThat(CircuitBreakerState.CLOSED.ordinal()).isZero();
        }

        @Test
        void should_assign_open_as_ordinal_one() {
            assertThat(CircuitBreakerState.OPEN.ordinal()).isEqualTo(1);
        }

        @Test
        void should_assign_half_open_as_ordinal_two() {
            assertThat(CircuitBreakerState.HALF_OPEN.ordinal()).isEqualTo(2);
        }
    }
}
