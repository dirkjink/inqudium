package eu.inqudium.annotation.processor;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRateLimiter;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqShield;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.annotation.InqTrafficShaper;
import eu.inqudium.annotation.processor.InqAnnotationScanner.ScanResult;
import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InqAnnotationScanner")
class InqAnnotationScannerTest {

    // =========================================================================
    // Test fixtures — annotated classes and methods
    // =========================================================================

    // --- METHOD-level only ---

    static class MethodLevelOnly {
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        public void protectedMethod() {}

        public void unannotatedMethod() {}

        @InqCircuitBreaker("cb")
        public void singleAnnotation() {}
    }

    // --- TYPE-level only ---

    @InqCircuitBreaker("typeCb")
    @InqRetry("typeRt")
    static class TypeLevelOnly {
        public void inheritedMethod() {}

        public void anotherMethod() {}
    }

    // --- METHOD overrides TYPE ---

    @InqCircuitBreaker("defaultCb")
    @InqRetry("defaultRt")
    static class MethodOverridesType {
        @InqCircuitBreaker("specialCb")
        public void overriddenMethod() {}

        public void inheritedMethod() {}

        @InqBulkhead("methodBh")
        public void methodAddsNew() {}
    }

    // --- @InqShield ordering ---

    static class NoShield {
        @InqCircuitBreaker("cb")
        public void defaultOrdering() {}
    }

    @InqShield(order = "RESILIENCE4J")
    static class TypeLevelShield {
        @InqCircuitBreaker("cb")
        public void inheritsShield() {}

        @InqShield(order = "CUSTOM")
        @InqCircuitBreaker("cb")
        public void overridesShield() {}
    }

    static class MethodLevelShield {
        @InqShield(order = "RESILIENCE4J")
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        public void withShield() {}
    }

    // --- Full pipeline ---

    @InqTimeLimiter("tl")
    static class FullPipeline {
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        @InqRateLimiter("rl")
        @InqBulkhead("bh")
        @InqTrafficShaper("ts")
        public void allElements() {}
    }

    // --- All six on method ---

    static class AllOnMethod {
        @InqTimeLimiter("tl")
        @InqTrafficShaper("ts")
        @InqRateLimiter("rl")
        @InqBulkhead("bh")
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        public void sixElements() {}
    }

    // --- Fallback ---

    static class WithFallback {
        @InqCircuitBreaker(value = "cb", fallbackMethod = "onFailure")
        @InqRetry(value = "rt", fallbackMethod = "")
        public void withFallback() {}
    }

    // =========================================================================
    // METHOD-level annotations
    // =========================================================================

    @Nested
    @DisplayName("METHOD-level annotations")
    class MethodLevel {

        @Test
        void scans_multiple_element_annotations_from_the_method() throws Exception {
            // Given
            Method method = MethodLevelOnly.class.getMethod("protectedMethod");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            assertThat(result.elements())
                    .extracting(ScannedElement::type)
                    .containsExactlyInAnyOrder(
                            InqElementType.CIRCUIT_BREAKER,
                            InqElementType.RETRY);

            assertThat(result.elements())
                    .extracting(ScannedElement::name)
                    .containsExactlyInAnyOrder("cb", "rt");
        }

        @Test
        void unannotated_method_produces_empty_result() throws Exception {
            // Given
            Method method = MethodLevelOnly.class.getMethod("unannotatedMethod");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            assertThat(result.isEmpty()).isTrue();
            assertThat(result.size()).isZero();
        }

        @Test
        void single_annotation_produces_single_element() throws Exception {
            // Given
            Method method = MethodLevelOnly.class.getMethod("singleAnnotation");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            assertThat(result.size()).isEqualTo(1);
            assertThat(result.elements().getFirst().type())
                    .isEqualTo(InqElementType.CIRCUIT_BREAKER);
            assertThat(result.elements().getFirst().name()).isEqualTo("cb");
        }
    }

    // =========================================================================
    // TYPE-level annotations (inheritance)
    // =========================================================================

    @Nested
    @DisplayName("TYPE-level annotations")
    class TypeLevel {

        @Test
        void method_inherits_all_type_level_annotations() throws Exception {
            // Given
            Method method = TypeLevelOnly.class.getMethod("inheritedMethod");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            assertThat(result.elements())
                    .extracting(ScannedElement::name)
                    .containsExactlyInAnyOrder("typeCb", "typeRt");
        }

        @Test
        void different_methods_on_the_same_class_inherit_the_same_annotations()
                throws Exception {
            // Given
            Method m1 = TypeLevelOnly.class.getMethod("inheritedMethod");
            Method m2 = TypeLevelOnly.class.getMethod("anotherMethod");

            // When
            ScanResult r1 = InqAnnotationScanner.scan(m1);
            ScanResult r2 = InqAnnotationScanner.scan(m2);

            // Then — identical
            assertThat(r1.elements()).hasSameSizeAs(r2.elements());
            assertThat(r1.elements())
                    .extracting(ScannedElement::name)
                    .containsExactlyInAnyOrderElementsOf(
                            r2.elements().stream().map(ScannedElement::name).toList());
        }
    }

    // =========================================================================
    // METHOD overrides TYPE (merge)
    // =========================================================================

    @Nested
    @DisplayName("METHOD overrides TYPE (merge)")
    class MergeSemantics {

        @Test
        void method_level_annotation_overrides_type_level_for_same_element_type()
                throws Exception {
            // Given — TYPE: defaultCb + defaultRt, METHOD: specialCb
            Method method = MethodOverridesType.class.getMethod("overriddenMethod");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then — CB overridden to "specialCb", RT inherited as "defaultRt"
            assertThat(result.size()).isEqualTo(2);

            ScannedElement cb = result.elements().stream()
                    .filter(e -> e.type() == InqElementType.CIRCUIT_BREAKER)
                    .findFirst().orElseThrow();
            assertThat(cb.name()).isEqualTo("specialCb");

            ScannedElement rt = result.elements().stream()
                    .filter(e -> e.type() == InqElementType.RETRY)
                    .findFirst().orElseThrow();
            assertThat(rt.name()).isEqualTo("defaultRt");
        }

        @Test
        void unannotated_method_inherits_all_type_level_annotations() throws Exception {
            // Given
            Method method = MethodOverridesType.class.getMethod("inheritedMethod");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then — inherits both: defaultCb + defaultRt
            assertThat(result.elements())
                    .extracting(ScannedElement::name)
                    .containsExactlyInAnyOrder("defaultCb", "defaultRt");
        }

        @Test
        void method_can_add_new_element_types_not_present_on_the_class() throws Exception {
            // Given — TYPE: defaultCb + defaultRt, METHOD: methodBh (new type)
            Method method = MethodOverridesType.class.getMethod("methodAddsNew");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then — all three: defaultCb + defaultRt + methodBh
            assertThat(result.size()).isEqualTo(3);
            assertThat(result.elements())
                    .extracting(ScannedElement::type)
                    .containsExactlyInAnyOrder(
                            InqElementType.CIRCUIT_BREAKER,
                            InqElementType.RETRY,
                            InqElementType.BULKHEAD);
        }
    }

    // =========================================================================
    // @InqShield ordering
    // =========================================================================

    @Nested
    @DisplayName("@InqShield ordering")
    class ShieldOrdering {

        @Test
        void default_ordering_when_no_shield_is_present() throws Exception {
            // Given
            Method method = NoShield.class.getMethod("defaultOrdering");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            assertThat(result.ordering()).isEqualTo("INQUDIUM");
            assertThat(result.isDefaultOrdering()).isTrue();
        }

        @Test
        void method_inherits_type_level_shield() throws Exception {
            // Given — TYPE: @InqShield(order = "RESILIENCE4J")
            Method method = TypeLevelShield.class.getMethod("inheritsShield");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then — inherited from class
            assertThat(result.ordering()).isEqualTo("RESILIENCE4J");
        }

        @Test
        void method_level_shield_overrides_type_level_shield() throws Exception {
            // Given — TYPE: RESILIENCE4J, METHOD: CUSTOM
            Method method = TypeLevelShield.class.getMethod("overridesShield");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then — method wins
            assertThat(result.ordering()).isEqualTo("CUSTOM");
            assertThat(result.isCustomOrdering()).isTrue();
        }

        @Test
        void method_level_shield_without_type_level() throws Exception {
            // Given
            Method method = MethodLevelShield.class.getMethod("withShield");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            assertThat(result.ordering()).isEqualTo("RESILIENCE4J");
        }
    }

    // =========================================================================
    // All six element types
    // =========================================================================

    @Nested
    @DisplayName("Full pipeline scanning")
    class FullPipelineScanning {

        @Test
        void scans_all_six_element_types_from_method_and_type() throws Exception {
            // Given — TYPE: @InqTimeLimiter, METHOD: CB + RT + RL + BH + TS
            Method method = FullPipeline.class.getMethod("allElements");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then — all six types present
            assertThat(result.size()).isEqualTo(6);
            assertThat(result.elements())
                    .extracting(ScannedElement::type)
                    .containsExactlyInAnyOrder(
                            InqElementType.TIME_LIMITER,
                            InqElementType.TRAFFIC_SHAPER,
                            InqElementType.RATE_LIMITER,
                            InqElementType.BULKHEAD,
                            InqElementType.CIRCUIT_BREAKER,
                            InqElementType.RETRY);
        }

        @Test
        void scans_all_six_element_types_from_method_only() throws Exception {
            // Given
            Method method = AllOnMethod.class.getMethod("sixElements");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            assertThat(result.size()).isEqualTo(6);
            assertThat(result.elements())
                    .extracting(ScannedElement::name)
                    .containsExactlyInAnyOrder("tl", "ts", "rl", "bh", "cb", "rt");
        }
    }

    // =========================================================================
    // Fallback method
    // =========================================================================

    @Nested
    @DisplayName("Fallback method")
    class FallbackMethod {

        @Test
        void captures_fallback_method_from_annotation() throws Exception {
            // Given
            Method method = WithFallback.class.getMethod("withFallback");

            // When
            ScanResult result = InqAnnotationScanner.scan(method);

            // Then
            ScannedElement cb = result.elements().stream()
                    .filter(e -> e.type() == InqElementType.CIRCUIT_BREAKER)
                    .findFirst().orElseThrow();
            assertThat(cb.fallbackMethod()).isEqualTo("onFailure");
            assertThat(cb.hasFallback()).isTrue();

            ScannedElement rt = result.elements().stream()
                    .filter(e -> e.type() == InqElementType.RETRY)
                    .findFirst().orElseThrow();
            assertThat(rt.fallbackMethod()).isEmpty();
            assertThat(rt.hasFallback()).isFalse();
        }
    }

    // =========================================================================
    // hasInqAnnotations pre-filter
    // =========================================================================

    @Nested
    @DisplayName("hasInqAnnotations")
    class HasAnnotations {

        @Test
        void returns_true_for_method_level_annotations() throws Exception {
            Method method = MethodLevelOnly.class.getMethod("protectedMethod");
            assertThat(InqAnnotationScanner.hasInqAnnotations(method)).isTrue();
        }

        @Test
        void returns_true_for_type_level_annotations() throws Exception {
            Method method = TypeLevelOnly.class.getMethod("inheritedMethod");
            assertThat(InqAnnotationScanner.hasInqAnnotations(method)).isTrue();
        }

        @Test
        void returns_false_for_unannotated_method_on_unannotated_class() throws Exception {
            Method method = MethodLevelOnly.class.getMethod("unannotatedMethod");
            assertThat(InqAnnotationScanner.hasInqAnnotations(method)).isFalse();
        }
    }
}
