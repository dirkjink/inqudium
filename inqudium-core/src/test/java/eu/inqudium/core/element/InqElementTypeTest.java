package eu.inqudium.core.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqElementType")
class InqElementTypeTest {

    // ======================== Helpers ========================

    /**
     * Returns true for element types that participate in pipeline ordering.
     * CACHE and NO_ELEMENT have order 0 and are excluded from pipeline
     * composition.
     */
    private static boolean isPipelineElement(InqElementType type) {
        return type != InqElementType.NO_ELEMENT
                && type != InqElementType.CACHE;
    }

    // ======================== Enum constants ========================

    @Nested
    @DisplayName("Enum constants")
    class EnumConstants {

        @Test
        void all_expected_element_types_are_defined() {
            // Given
            var expectedNames = List.of(
                    "CIRCUIT_BREAKER", "RETRY", "RATE_LIMITER", "BULKHEAD",
                    "TIME_LIMITER", "TRAFFIC_SHAPER", "CACHE", "NO_ELEMENT"
            );

            // When
            var actualNames = Arrays.stream(InqElementType.values())
                    .map(Enum::name)
                    .toList();

            // Then
            assertThat(actualNames).containsExactlyInAnyOrderElementsOf(expectedNames);
        }

        @Test
        void exactly_eight_element_types_exist() {
            // Given / When
            var values = InqElementType.values();

            // Then
            assertThat(values).hasSize(8);
        }
    }

    // ======================== Symbol ========================

    @Nested
    @DisplayName("symbol()")
    class Symbol {

        @ParameterizedTest(name = "{0} → \"{1}\"")
        @CsvSource({
                "CIRCUIT_BREAKER, CB",
                "RETRY,           RT",
                "RATE_LIMITER,    RL",
                "BULKHEAD,        BH",
                "TIME_LIMITER,    TL",
                "TRAFFIC_SHAPER,  TS",
                "CACHE,           CA",
                "NO_ELEMENT,      XX"
        })
        void each_element_type_returns_its_expected_symbol(InqElementType type, String expectedSymbol) {
            // Given — element type from parameter

            // When
            var symbol = type.symbol();

            // Then
            assertThat(symbol).isEqualTo(expectedSymbol);
        }

        @ParameterizedTest
        @EnumSource(InqElementType.class)
        void every_symbol_is_exactly_two_characters(InqElementType type) {
            // Given — element type from parameter

            // When
            var symbol = type.symbol();

            // Then
            assertThat(symbol)
                    .hasSize(2)
                    .matches("[A-Z]{2}");
        }

        @Test
        void all_symbols_are_unique() {
            // Given
            var allSymbols = Arrays.stream(InqElementType.values())
                    .map(InqElementType::symbol)
                    .toList();

            // When / Then
            assertThat(allSymbols).doesNotHaveDuplicates();
        }
    }

    // ======================== Error code ========================

    @Nested
    @DisplayName("errorCode()")
    class ErrorCode {

        @ParameterizedTest(name = "{0}.errorCode({1}) → \"{2}\"")
        @CsvSource({
                "CIRCUIT_BREAKER, 1,   INQ-CB-001",
                "CIRCUIT_BREAKER, 0,   INQ-CB-000",
                "RETRY,           42,  INQ-RT-042",
                "RATE_LIMITER,    100, INQ-RL-100",
                "BULKHEAD,        999, INQ-BH-999",
                "TIME_LIMITER,    7,   INQ-TL-007",
                "TRAFFIC_SHAPER,  50,  INQ-TS-050",
                "CACHE,           1,   INQ-CA-001",
                "NO_ELEMENT,      0,   INQ-XX-000"
        })
        void generates_correctly_formatted_error_code(InqElementType type, int number, String expected) {
            // Given — element type and number from parameters

            // When
            var errorCode = type.errorCode(number);

            // Then
            assertThat(errorCode).isEqualTo(expected);
        }

        @Test
        void error_code_zero_pads_single_digit_numbers() {
            // Given
            var type = InqElementType.CIRCUIT_BREAKER;

            // When
            var errorCode = type.errorCode(5);

            // Then
            assertThat(errorCode).isEqualTo("INQ-CB-005");
        }

        @Test
        void error_code_zero_pads_two_digit_numbers() {
            // Given
            var type = InqElementType.RETRY;

            // When
            var errorCode = type.errorCode(42);

            // Then
            assertThat(errorCode).isEqualTo("INQ-RT-042");
        }

        @Test
        void error_code_does_not_pad_three_digit_numbers() {
            // Given
            var type = InqElementType.BULKHEAD;

            // When
            var errorCode = type.errorCode(123);

            // Then
            assertThat(errorCode).isEqualTo("INQ-BH-123");
        }

        @ParameterizedTest
        @EnumSource(InqElementType.class)
        void error_code_follows_inq_prefix_format_for_all_types(InqElementType type) {
            // Given
            int number = 1;

            // When
            var errorCode = type.errorCode(number);

            // Then — format: INQ-XX-NNN where XX is the symbol
            assertThat(errorCode)
                    .startsWith("INQ-" + type.symbol() + "-")
                    .hasSize(10); // "INQ-" (4) + symbol (2) + "-" (1) + number (3)
        }

        @Nested
        @DisplayName("Boundary values")
        class BoundaryValues {

            @Test
            void accepts_zero_as_lower_bound() {
                // Given
                var type = InqElementType.CIRCUIT_BREAKER;

                // When
                var errorCode = type.errorCode(0);

                // Then
                assertThat(errorCode).isEqualTo("INQ-CB-000");
            }

            @Test
            void accepts_999_as_upper_bound() {
                // Given
                var type = InqElementType.CIRCUIT_BREAKER;

                // When
                var errorCode = type.errorCode(999);

                // Then
                assertThat(errorCode).isEqualTo("INQ-CB-999");
            }
        }

        @Nested
        @DisplayName("Invalid input")
        class InvalidInput {

            @Test
            void rejects_negative_number() {
                // Given
                var type = InqElementType.RETRY;

                // When / Then
                assertThatThrownBy(() -> type.errorCode(-1))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("-1");
            }

            @Test
            void rejects_number_above_999() {
                // Given
                var type = InqElementType.RETRY;

                // When / Then
                assertThatThrownBy(() -> type.errorCode(1000))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("1000");
            }

            @ParameterizedTest(name = "rejects {0}")
            @ValueSource(ints = {-100, -1, 1000, 1001, Integer.MAX_VALUE, Integer.MIN_VALUE})
            void rejects_all_out_of_range_numbers(int invalidNumber) {
                // Given
                var type = InqElementType.CIRCUIT_BREAKER;

                // When / Then
                assertThatThrownBy(() -> type.errorCode(invalidNumber))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    // ======================== Pipeline ordering ========================

    @Nested
    @DisplayName("defaultPipelineOrder()")
    class DefaultPipelineOrder {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "TIME_LIMITER,     100",
                "TRAFFIC_SHAPER,   200",
                "RATE_LIMITER,     300",
                "BULKHEAD,         400",
                "CIRCUIT_BREAKER,  500",
                "RETRY,            600",
                "CACHE,            0",
                "NO_ELEMENT,       0"
        })
        void each_type_returns_its_expected_pipeline_order(InqElementType type, int expectedOrder) {
            // Given — type from parameter

            // When
            var order = type.defaultPipelineOrder();

            // Then
            assertThat(order).isEqualTo(expectedOrder);
        }

        @Nested
        @DisplayName("Spacing invariants")
        class SpacingInvariants {

            @Test
            void all_pipeline_element_orders_are_spaced_by_100() {
                // Given — only elements intended for pipeline composition
                // (excluding CACHE and NO_ELEMENT which have order 0)
                var pipelineTypes = Arrays.stream(InqElementType.values())
                        .filter(InqElementTypeTest::isPipelineElement)
                        .sorted(Comparator.comparingInt(InqElementType::defaultPipelineOrder))
                        .toList();

                // When
                for (int i = 1; i < pipelineTypes.size(); i++) {
                    int previous = pipelineTypes.get(i - 1).defaultPipelineOrder();
                    int current = pipelineTypes.get(i).defaultPipelineOrder();

                    // Then — each step is exactly 100
                    assertThat(current - previous)
                            .as("gap between %s(%d) and %s(%d)",
                                    pipelineTypes.get(i - 1), previous,
                                    pipelineTypes.get(i), current)
                            .isEqualTo(100);
                }
            }

            @Test
            void all_pipeline_element_orders_are_unique() {
                // Given — only pipeline elements (CACHE and NO_ELEMENT share order 0)
                var orders = Arrays.stream(InqElementType.values())
                        .filter(InqElementTypeTest::isPipelineElement)
                        .map(InqElementType::defaultPipelineOrder)
                        .toList();

                // When / Then
                assertThat(orders).doesNotHaveDuplicates();
            }

            @Test
            void all_pipeline_orders_are_non_negative() {
                // Given / When / Then
                Arrays.stream(InqElementType.values()).forEach(type ->
                        assertThat(type.defaultPipelineOrder())
                                .as("%s.defaultPipelineOrder()", type)
                                .isGreaterThanOrEqualTo(0));
            }
        }

        @Nested
        @DisplayName("ADR-017 canonical ordering")
        class CanonicalOrdering {

            @Test
            void time_limiter_is_the_outermost_pipeline_element() {
                // Given
                var timeLimiter = InqElementType.TIME_LIMITER;

                // When
                int timeLimiterOrder = timeLimiter.defaultPipelineOrder();

                // Then — lowest order among all pipeline elements
                Arrays.stream(InqElementType.values())
                        .filter(InqElementTypeTest::isPipelineElement)
                        .forEach(t -> assertThat(timeLimiterOrder)
                                .as("TIME_LIMITER should be outermost, but %s has lower order", t)
                                .isLessThanOrEqualTo(t.defaultPipelineOrder()));
            }

            @Test
            void retry_is_the_innermost_pipeline_element() {
                // Given
                var retry = InqElementType.RETRY;

                // When
                int retryOrder = retry.defaultPipelineOrder();

                // Then — highest order among all pipeline elements
                Arrays.stream(InqElementType.values())
                        .filter(InqElementTypeTest::isPipelineElement)
                        .forEach(t -> assertThat(retryOrder)
                                .as("RETRY should be innermost, but %s has higher order", t)
                                .isGreaterThanOrEqualTo(t.defaultPipelineOrder()));
            }

            @Test
            void time_limiter_wraps_retry_to_bound_total_caller_wait_time() {
                // Given — ADR-010: TimeLimiter outside Retry bounds total time

                // When / Then
                assertThat(InqElementType.TIME_LIMITER.defaultPipelineOrder())
                        .isLessThan(InqElementType.RETRY.defaultPipelineOrder());
            }

            @Test
            void circuit_breaker_wraps_retry_to_see_each_attempt_individually() {
                // Given — ADR-017: CB outside Retry for fastest failure detection

                // When / Then
                assertThat(InqElementType.CIRCUIT_BREAKER.defaultPipelineOrder())
                        .isLessThan(InqElementType.RETRY.defaultPipelineOrder());
            }

            @Test
            void bulkhead_wraps_circuit_breaker_for_pipeline_level_concurrency() {
                // Given — ADR-017: concurrency bounded at pipeline level

                // When / Then
                assertThat(InqElementType.BULKHEAD.defaultPipelineOrder())
                        .isLessThan(InqElementType.CIRCUIT_BREAKER.defaultPipelineOrder());
            }

            @Test
            void rate_limiter_wraps_bulkhead_to_gate_before_breaker() {
                // Given — ADR-017: rate limit is a global constraint

                // When / Then
                assertThat(InqElementType.RATE_LIMITER.defaultPipelineOrder())
                        .isLessThan(InqElementType.BULKHEAD.defaultPipelineOrder());
            }

            @Test
            void traffic_shaper_sits_between_time_limiter_and_rate_limiter() {
                // Given — shaping delays covered by time budget, bursts smoothed before rate tokens

                // When
                int shaperOrder = InqElementType.TRAFFIC_SHAPER.defaultPipelineOrder();

                // Then
                assertThat(shaperOrder)
                        .isGreaterThan(InqElementType.TIME_LIMITER.defaultPipelineOrder())
                        .isLessThan(InqElementType.RATE_LIMITER.defaultPipelineOrder());
            }

            @Test
            void time_limiter_wraps_traffic_shaper_to_cover_shaping_delays() {
                // Given — ADR-017: shaper cannot cause unbounded wait

                // When / Then
                assertThat(InqElementType.TIME_LIMITER.defaultPipelineOrder())
                        .isLessThan(InqElementType.TRAFFIC_SHAPER.defaultPipelineOrder());
            }

            @Test
            void full_canonical_order_matches_adr_017() {
                // Given — ADR-017 canonical order (outermost → innermost)
                var expectedOrder = List.of(
                        InqElementType.TIME_LIMITER,
                        InqElementType.TRAFFIC_SHAPER,
                        InqElementType.RATE_LIMITER,
                        InqElementType.BULKHEAD,
                        InqElementType.CIRCUIT_BREAKER,
                        InqElementType.RETRY
                );

                // When — sort all pipeline types by their default order
                var actualOrder = Arrays.stream(InqElementType.values())
                        .filter(InqElementTypeTest::isPipelineElement)
                        .sorted(Comparator.comparingInt(InqElementType::defaultPipelineOrder))
                        .toList();

                // Then
                assertThat(actualOrder).containsExactlyElementsOf(expectedOrder);
            }
        }

        @Nested
        @DisplayName("Non-pipeline types (CACHE, NO_ELEMENT)")
        class NonPipelineTypes {

            @Test
            void no_element_has_order_zero() {
                // Given / When / Then
                assertThat(InqElementType.NO_ELEMENT.defaultPipelineOrder()).isZero();
            }

            @Test
            void cache_has_order_zero() {
                // Given — Cache is not a pipeline element (ADR-017, ADR-024):
                // it is a separate interceptor that short-circuits the entire
                // call on a hit, so the pipeline is never entered.

                // When / Then
                assertThat(InqElementType.CACHE.defaultPipelineOrder()).isZero();
            }

            @Test
            void cache_and_no_element_share_order_zero() {
                // Given — both are non-pipeline types

                // When / Then
                assertThat(InqElementType.CACHE.defaultPipelineOrder())
                        .isEqualTo(InqElementType.NO_ELEMENT.defaultPipelineOrder())
                        .isZero();
            }

            @Test
            void cache_still_has_a_valid_symbol_and_error_code() {
                // Given — CACHE retains its identity for events and error codes

                // When / Then
                assertThat(InqElementType.CACHE.symbol()).isEqualTo("CA");
                assertThat(InqElementType.CACHE.errorCode(1)).isEqualTo("INQ-CA-001");
            }
        }
    }
}
