package eu.inqudium.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
