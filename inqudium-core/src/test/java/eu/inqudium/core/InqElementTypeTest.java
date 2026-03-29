package eu.inqudium.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqElementType")
class InqElementTypeTest {

    @Nested
    @DisplayName("Enum values")
    class EnumValues {

        @Test
        void should_contain_exactly_six_element_types() {
            // Given
            var values = InqElementType.values();

            // Then
            assertThat(values).hasSize(6);
        }

        @Test
        void should_contain_all_resilience_element_types() {
            // Given
            var values = InqElementType.values();

            // Then
            assertThat(values).containsExactly(
                    InqElementType.CIRCUIT_BREAKER,
                    InqElementType.RETRY,
                    InqElementType.RATE_LIMITER,
                    InqElementType.BULKHEAD,
                    InqElementType.TIME_LIMITER,
                    InqElementType.CACHE
            );
        }
    }

    @Nested
    @DisplayName("Element symbols")
    class ElementSymbols {

        @Test
        void should_return_two_character_symbol_for_each_element() {
            // Then
            assertThat(InqElementType.CIRCUIT_BREAKER.symbol()).isEqualTo("CB");
            assertThat(InqElementType.RETRY.symbol()).isEqualTo("RT");
            assertThat(InqElementType.RATE_LIMITER.symbol()).isEqualTo("RL");
            assertThat(InqElementType.BULKHEAD.symbol()).isEqualTo("BH");
            assertThat(InqElementType.TIME_LIMITER.symbol()).isEqualTo("TL");
            assertThat(InqElementType.CACHE.symbol()).isEqualTo("CA");
        }

        @Test
        void should_have_exactly_two_characters_per_symbol() {
            // Then
            for (var type : InqElementType.values()) {
                assertThat(type.symbol()).hasSize(2);
            }
        }
    }

    @Nested
    @DisplayName("Error code generation")
    class ErrorCodeGeneration {

        @Test
        void should_generate_error_code_with_zero_padded_number() {
            // When / Then
            assertThat(InqElementType.CIRCUIT_BREAKER.errorCode(1)).isEqualTo("INQ-CB-001");
            assertThat(InqElementType.RETRY.errorCode(1)).isEqualTo("INQ-RT-001");
            assertThat(InqElementType.RATE_LIMITER.errorCode(1)).isEqualTo("INQ-RL-001");
            assertThat(InqElementType.BULKHEAD.errorCode(1)).isEqualTo("INQ-BH-001");
            assertThat(InqElementType.TIME_LIMITER.errorCode(1)).isEqualTo("INQ-TL-001");
            assertThat(InqElementType.CACHE.errorCode(1)).isEqualTo("INQ-CA-001");
        }

        @Test
        void should_generate_code_000_for_wrapped_checked_exceptions() {
            // When / Then
            assertThat(InqElementType.CIRCUIT_BREAKER.errorCode(0)).isEqualTo("INQ-CB-000");
            assertThat(InqElementType.RETRY.errorCode(0)).isEqualTo("INQ-RT-000");
        }

        @Test
        void should_pad_single_digit_numbers_with_two_zeros() {
            assertThat(InqElementType.CIRCUIT_BREAKER.errorCode(5)).isEqualTo("INQ-CB-005");
        }

        @Test
        void should_pad_two_digit_numbers_with_one_zero() {
            assertThat(InqElementType.CIRCUIT_BREAKER.errorCode(42)).isEqualTo("INQ-CB-042");
        }

        @Test
        void should_not_pad_three_digit_numbers() {
            assertThat(InqElementType.CIRCUIT_BREAKER.errorCode(999)).isEqualTo("INQ-CB-999");
        }

        @Test
        void should_reject_negative_numbers() {
            assertThatThrownBy(() -> InqElementType.CIRCUIT_BREAKER.errorCode(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_numbers_above_999() {
            assertThatThrownBy(() -> InqElementType.CIRCUIT_BREAKER.errorCode(1000))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("String representation")
    class StringRepresentation {

        @Test
        void should_produce_uppercase_names_matching_enum_constants() {
            // When / Then
            assertThat(InqElementType.CIRCUIT_BREAKER.name()).isEqualTo("CIRCUIT_BREAKER");
            assertThat(InqElementType.RETRY.name()).isEqualTo("RETRY");
            assertThat(InqElementType.RATE_LIMITER.name()).isEqualTo("RATE_LIMITER");
            assertThat(InqElementType.BULKHEAD.name()).isEqualTo("BULKHEAD");
            assertThat(InqElementType.TIME_LIMITER.name()).isEqualTo("TIME_LIMITER");
            assertThat(InqElementType.CACHE.name()).isEqualTo("CACHE");
        }

        @Test
        void should_resolve_from_name_string() {
            // When
            var resolved = InqElementType.valueOf("CIRCUIT_BREAKER");

            // Then
            assertThat(resolved).isEqualTo(InqElementType.CIRCUIT_BREAKER);
        }
    }
}
