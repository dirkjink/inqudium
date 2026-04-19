package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.PipelineValidator.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PipelineValidator} verifying that all four known
 * anti-patterns are detected and that clean pipelines pass validation.
 */
@DisplayName("PipelineValidator")
class PipelineValidatorTest {

    // =========================================================================
    // Stub element — minimal implementation for validation tests
    // =========================================================================

    static class StubElement implements InqDecorator<Void, Object> {
        private final String name;
        private final InqElementType type;

        StubElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return type; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            return next.execute(chainId, callId, arg);
        }
    }

    // =========================================================================
    // Helper — custom ordering that forces a specific element position
    // =========================================================================

    /**
     * Creates a custom ordering where elements appear in the given order
     * (first argument = outermost = lowest order value).
     */
    static PipelineOrdering customOrder(InqElementType... outerToInner) {
        EnumMap<InqElementType, Integer> map = new EnumMap<>(InqElementType.class);
        for (int i = 0; i < outerToInner.length; i++) {
            map.put(outerToInner[i], (i + 1) * 100);
        }
        return PipelineOrdering.of(map);
    }

    // =========================================================================
    // Clean pipelines
    // =========================================================================

    @Nested
    @DisplayName("Clean pipelines")
    class CleanPipelines {

        @Test
        void empty_pipeline_is_clean() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.isClean()).isTrue();
            assertThat(result.count()).isZero();
        }

        @Test
        void single_element_pipeline_is_clean() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("cb", InqElementType.CIRCUIT_BREAKER))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.isClean()).isTrue();
        }

        @Test
        void standard_ordering_with_all_elements_is_clean() {
            // Given — standard: TL → TS → BH → RL → CB → RT (outermost first)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .shield(new StubElement("bh", InqElementType.BULKHEAD))
                    .shield(new StubElement("rl", InqElementType.RATE_LIMITER))
                    .shield(new StubElement("tl", InqElementType.TIME_LIMITER))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then — standard ordering avoids all anti-patterns
            assertThat(result.isClean()).isTrue();
        }

        @Test
        void cb_and_rt_without_other_elements_is_clean_in_standard_order() {
            // Given — standard: CB(500) → RT(600)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.isClean()).isTrue();
        }
    }

    // =========================================================================
    // Retry outside CircuitBreaker
    // =========================================================================

    @Nested
    @DisplayName("Retry outside CircuitBreaker")
    class RetryOutsideCb {

        @Test
        void detected_when_retry_is_outermost() {
            // Given — custom: RT(100) → CB(200) — Retry outside CB
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("myRt", InqElementType.RETRY))
                    .shield(new StubElement("myCb", InqElementType.CIRCUIT_BREAKER))
                    .order(customOrder(InqElementType.RETRY, InqElementType.CIRCUIT_BREAKER))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.isClean()).isFalse();
            assertThat(result.count()).isEqualTo(1);
            assertThat(result.warnings().getFirst())
                    .contains("Retry")
                    .contains("myRt")
                    .contains("CircuitBreaker")
                    .contains("myCb");
        }

        @Test
        void not_detected_when_cb_is_outermost() {
            // Given — standard: CB(500) → RT(600)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.warnings())
                    .noneMatch(w -> w.contains("Retry") && w.contains("CircuitBreaker"));
        }

        @Test
        void not_detected_when_only_retry_present() {
            // Given — no CB at all
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .shield(new StubElement("bh", InqElementType.BULKHEAD))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.warnings())
                    .noneMatch(w -> w.contains("CircuitBreaker"));
        }
    }

    // =========================================================================
    // TimeLimiter inside Retry
    // =========================================================================

    @Nested
    @DisplayName("TimeLimiter inside Retry")
    class TimeLimiterInsideRetry {

        @Test
        void detected_when_tl_is_inside_retry() {
            // Given — custom: RT(100) → TL(200) — TL inside Retry
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("myRt", InqElementType.RETRY))
                    .shield(new StubElement("myTl", InqElementType.TIME_LIMITER))
                    .order(customOrder(InqElementType.RETRY, InqElementType.TIME_LIMITER))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.isClean()).isFalse();
            assertThat(result.warnings().getFirst())
                    .contains("TimeLimiter")
                    .contains("myTl")
                    .contains("Retry")
                    .contains("myRt")
                    .contains("unbounded");
        }

        @Test
        void not_detected_when_tl_is_outermost() {
            // Given — standard: TL(200) → RT(600) — correct
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("tl", InqElementType.TIME_LIMITER))
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.warnings())
                    .noneMatch(w -> w.contains("TimeLimiter"));
        }
    }

    // =========================================================================
    // Bulkhead inside Retry
    // =========================================================================

    @Nested
    @DisplayName("Bulkhead inside Retry")
    class BulkheadInsideRetry {

        @Test
        void detected_when_bh_is_inside_retry() {
            // Given — custom: RT(100) → BH(200) — BH inside Retry
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("myRt", InqElementType.RETRY))
                    .shield(new StubElement("myBh", InqElementType.BULKHEAD))
                    .order(customOrder(InqElementType.RETRY, InqElementType.BULKHEAD))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.isClean()).isFalse();
            assertThat(result.warnings().getFirst())
                    .contains("Bulkhead")
                    .contains("myBh")
                    .contains("Retry")
                    .contains("myRt")
                    .contains("permit");
        }

        @Test
        void not_detected_when_bh_is_outside_retry() {
            // Given — standard: BH(400) → RT(600)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("bh", InqElementType.BULKHEAD))
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.warnings())
                    .noneMatch(w -> w.contains("Bulkhead"));
        }
    }

    // =========================================================================
    // RateLimiter inside Retry
    // =========================================================================

    @Nested
    @DisplayName("RateLimiter inside Retry")
    class RateLimiterInsideRetry {

        @Test
        void detected_when_rl_is_inside_retry() {
            // Given — custom: RT(100) → RL(200) — RL inside Retry
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("myRt", InqElementType.RETRY))
                    .shield(new StubElement("myRl", InqElementType.RATE_LIMITER))
                    .order(customOrder(InqElementType.RETRY, InqElementType.RATE_LIMITER))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.isClean()).isFalse();
            assertThat(result.warnings().getFirst())
                    .contains("RateLimiter")
                    .contains("myRl")
                    .contains("Retry")
                    .contains("myRt")
                    .contains("token");
        }

        @Test
        void not_detected_when_rl_is_outside_retry() {
            // Given — standard: RL(350) → RT(600)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("rl", InqElementType.RATE_LIMITER))
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then
            assertThat(result.warnings())
                    .noneMatch(w -> w.contains("RateLimiter"));
        }
    }

    // =========================================================================
    // Multiple anti-patterns
    // =========================================================================

    @Nested
    @DisplayName("Multiple anti-patterns")
    class MultipleAntiPatterns {

        @Test
        void detects_all_four_anti_patterns_simultaneously() {
            // Given — worst-case custom: RT → CB → BH → RL → TL
            //   RT outside CB ✓, TL inside RT ✓, BH inside RT ✓, RL inside RT ✓
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .shield(new StubElement("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(new StubElement("bh", InqElementType.BULKHEAD))
                    .shield(new StubElement("rl", InqElementType.RATE_LIMITER))
                    .shield(new StubElement("tl", InqElementType.TIME_LIMITER))
                    .order(customOrder(
                            InqElementType.RETRY,
                            InqElementType.CIRCUIT_BREAKER,
                            InqElementType.BULKHEAD,
                            InqElementType.RATE_LIMITER,
                            InqElementType.TIME_LIMITER))
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then — all four anti-patterns detected
            assertThat(result.count()).isEqualTo(4);
            assertThat(result.warnings())
                    .anyMatch(w -> w.contains("Retry") && w.contains("CircuitBreaker"))
                    .anyMatch(w -> w.contains("TimeLimiter") && w.contains("Retry"))
                    .anyMatch(w -> w.contains("Bulkhead") && w.contains("Retry"))
                    .anyMatch(w -> w.contains("RateLimiter") && w.contains("Retry"));
        }

        @Test
        void resilience4j_ordering_triggers_retry_outside_cb_and_bh_inside_retry() {
            // Given — R4J order: RT(100) → CB(200) → BH(500)
            //   RT outside CB ✓, BH inside RT ✓
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .shield(new StubElement("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(new StubElement("bh", InqElementType.BULKHEAD))
                    .order(PipelineOrdering.resilience4j())
                    .build();

            // When
            ValidationResult result = PipelineValidator.validate(pipeline);

            // Then — two warnings (both are intentional in R4J)
            assertThat(result.count()).isEqualTo(2);
            assertThat(result.warnings())
                    .anyMatch(w -> w.contains("Retry") && w.contains("CircuitBreaker"))
                    .anyMatch(w -> w.contains("Bulkhead") && w.contains("Retry"));
        }
    }

    // =========================================================================
    // throwIfWarnings
    // =========================================================================

    @Nested
    @DisplayName("throwIfWarnings")
    class ThrowIfWarnings {

        @Test
        void does_not_throw_on_clean_pipeline() {
            // Given
            ValidationResult result = PipelineValidator.validate(
                    InqPipeline.builder().build());

            // When / Then
            assertThatCode(result::throwIfWarnings)
                    .doesNotThrowAnyException();
        }

        @Test
        void throws_illegal_state_exception_with_all_warnings() {
            // Given — custom ordering with RT outside CB
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("myRt", InqElementType.RETRY))
                    .shield(new StubElement("myCb", InqElementType.CIRCUIT_BREAKER))
                    .order(customOrder(InqElementType.RETRY, InqElementType.CIRCUIT_BREAKER))
                    .build();

            ValidationResult result = PipelineValidator.validate(pipeline);

            // When / Then
            assertThatThrownBy(result::throwIfWarnings)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1 anti-pattern warning")
                    .hasMessageContaining("Retry")
                    .hasMessageContaining("CircuitBreaker");
        }

        @Test
        void exception_message_contains_warning_count() {
            // Given — 4 warnings
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new StubElement("rt", InqElementType.RETRY))
                    .shield(new StubElement("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(new StubElement("bh", InqElementType.BULKHEAD))
                    .shield(new StubElement("rl", InqElementType.RATE_LIMITER))
                    .shield(new StubElement("tl", InqElementType.TIME_LIMITER))
                    .order(customOrder(
                            InqElementType.RETRY,
                            InqElementType.CIRCUIT_BREAKER,
                            InqElementType.BULKHEAD,
                            InqElementType.RATE_LIMITER,
                            InqElementType.TIME_LIMITER))
                    .build();

            ValidationResult result = PipelineValidator.validate(pipeline);

            // When / Then
            assertThatThrownBy(result::throwIfWarnings)
                    .hasMessageContaining("4 anti-pattern warning(s)");
        }
    }

    // =========================================================================
    // Null safety
    // =========================================================================

    @Nested
    @DisplayName("Null safety")
    class NullSafety {

        @Test
        void throws_on_null_pipeline() {
            // When / Then
            assertThatThrownBy(() -> PipelineValidator.validate(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Pipeline");
        }
    }
}
