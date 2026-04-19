package eu.inqudium.annotation.support;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqShield;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementRegistry.InqElementNotFoundException;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PipelineFactory")
class PipelineFactoryTest {

    // =========================================================================
    // Stub element
    // =========================================================================

    private InqElementRegistry registry;

    // =========================================================================
    // Shared registry
    // =========================================================================

    @BeforeEach
    void setUp() {
        registry = InqElementRegistry.builder()
                .register("paymentCb", new StubElement("paymentCb", InqElementType.CIRCUIT_BREAKER))
                .register("paymentRetry", new StubElement("paymentRetry", InqElementType.RETRY))
                .register("paymentBh", new StubElement("paymentBh", InqElementType.BULKHEAD))
                .register("globalTl", new StubElement("globalTl", InqElementType.TIME_LIMITER))
                .build();
    }

    static class StubElement implements InqElement {
        private final String name;
        private final InqElementType type;

        StubElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public InqElementType getElementType() {
            return type;
        }

        @Override
        public InqEventPublisher getEventPublisher() {
            return null;
        }
    }

    // =========================================================================
    // Test fixtures — annotated classes and methods
    // =========================================================================

    static class SimpleService {
        @InqCircuitBreaker("paymentCb")
        @InqRetry("paymentRetry")
        public void protectedMethod() {
        }

        public void unannotatedMethod() {
        }
    }

    @InqCircuitBreaker("paymentCb")
    static class TypeLevelService {
        public void inheritedMethod() {
        }

        @InqRetry("paymentRetry")
        public void methodAddsRetry() {
        }
    }

    @InqShield(order = "RESILIENCE4J")
    static class R4jService {
        @InqCircuitBreaker("paymentCb")
        @InqRetry("paymentRetry")
        @InqBulkhead("paymentBh")
        public void r4jOrder() {
        }
    }

    static class CustomOrderService {
        @InqShield(order = "CUSTOM")
        @InqRetry("paymentRetry")
        @InqCircuitBreaker("paymentCb")
        public void customOrder() {
        }
    }

    @InqTimeLimiter("globalTl")
    static class MergeService {
        @InqCircuitBreaker("paymentCb")
        @InqRetry("paymentRetry")
        public void mergedMethod() {
        }
    }

    static class UnknownElementService {
        @InqCircuitBreaker("unknownCb")
        public void missingElement() {
        }
    }

    // =========================================================================
    // create(Method, Registry)
    // =========================================================================

    @Nested
    @DisplayName("create(Method, Registry)")
    class CreateFromMethod {

        @Test
        void builds_pipeline_from_annotated_method() throws Exception {
            // Given
            Method method = SimpleService.class.getMethod("protectedMethod");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then
            assertThat(pipeline.depth()).isEqualTo(2);
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactlyInAnyOrder("paymentCb", "paymentRetry");
        }

        @Test
        void builds_empty_pipeline_for_unannotated_method() throws Exception {
            // Given
            Method method = SimpleService.class.getMethod("unannotatedMethod");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then
            assertThat(pipeline.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // TYPE-level inheritance
    // =========================================================================

    @Nested
    @DisplayName("TYPE-level inheritance")
    class TypeLevelInheritance {

        @Test
        void inherits_type_level_annotations_for_unannotated_method() throws Exception {
            // Given — TYPE: @InqCircuitBreaker("paymentCb")
            Method method = TypeLevelService.class.getMethod("inheritedMethod");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then
            assertThat(pipeline.depth()).isEqualTo(1);
            assertThat(pipeline.elements().getFirst().getName()).isEqualTo("paymentCb");
        }

        @Test
        void merges_type_level_and_method_level_annotations() throws Exception {
            // Given — TYPE: CB, METHOD: RT
            Method method = TypeLevelService.class.getMethod("methodAddsRetry");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then — both present
            assertThat(pipeline.depth()).isEqualTo(2);
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactlyInAnyOrder("paymentCb", "paymentRetry");
        }

        @Test
        void merges_type_and_method_annotations_into_correct_sorted_order() throws Exception {
            // Given — TYPE: @InqTimeLimiter("globalTl"), METHOD: CB + RT
            Method method = MergeService.class.getMethod("mergedMethod");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then — standard order: TL(100) → CB(500) → RT(600)
            assertThat(pipeline.depth()).isEqualTo(3);
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactly("globalTl", "paymentCb", "paymentRetry");
        }
    }

    // =========================================================================
    // Ordering
    // =========================================================================

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        @Test
        void standard_ordering_sorts_bh_before_cb_before_rt() throws Exception {
            // Given
            Method method = SimpleService.class.getMethod("protectedMethod");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then — standard: CB(500) → RT(600)
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactly("paymentCb", "paymentRetry");
        }

        @Test
        void resilience4j_ordering_sorts_rt_before_cb_before_bh() throws Exception {
            // Given — TYPE: @InqShield(order = "RESILIENCE4J")
            Method method = R4jService.class.getMethod("r4jOrder");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then — R4J: RT(100) → CB(200) → BH(600)
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactly("paymentRetry", "paymentCb", "paymentBh");
        }

        @Test
        void custom_ordering_preserves_annotation_declaration_order() throws Exception {
            // Given — @InqShield(order = "CUSTOM"), then @InqRetry, then @InqCircuitBreaker
            Method method = CustomOrderService.class.getMethod("customOrder");

            // When
            InqPipeline pipeline = PipelineFactory.create(method, registry);

            // Then — declaration order: RT first, CB second
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactly("paymentRetry", "paymentCb");
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void throws_when_scanned_element_not_found_in_registry() throws Exception {
            // Given — annotation references "unknownCb" which is not registered
            Method method = UnknownElementService.class.getMethod("missingElement");

            // When / Then
            assertThatThrownBy(() -> PipelineFactory.create(method, registry))
                    .isInstanceOf(InqElementNotFoundException.class)
                    .hasMessageContaining("unknownCb");
        }

        @Test
        void rejects_null_method() {
            assertThatThrownBy(() -> PipelineFactory.create((Method) null, registry))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_null_registry() throws Exception {
            Method method = SimpleService.class.getMethod("protectedMethod");
            assertThatThrownBy(() -> PipelineFactory.create(method, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // isProtected
    // =========================================================================

    @Nested
    @DisplayName("isProtected")
    class IsProtected {

        @Test
        void returns_true_for_annotated_method() throws Exception {
            Method method = SimpleService.class.getMethod("protectedMethod");
            assertThat(PipelineFactory.isProtected(method)).isTrue();
        }

        @Test
        void returns_true_for_method_on_annotated_class() throws Exception {
            Method method = TypeLevelService.class.getMethod("inheritedMethod");
            assertThat(PipelineFactory.isProtected(method)).isTrue();
        }

        @Test
        void returns_false_for_unannotated_method_on_unannotated_class() throws Exception {
            Method method = SimpleService.class.getMethod("unannotatedMethod");
            assertThat(PipelineFactory.isProtected(method)).isFalse();
        }
    }

    // =========================================================================
    // create(ScanResult, Registry)
    // =========================================================================

    @Nested
    @DisplayName("create(ScanResult, Registry)")
    class CreateFromScanResult {

        @Test
        void builds_pipeline_from_pre_scanned_result() throws Exception {
            // Given — scan separately
            Method method = SimpleService.class.getMethod("protectedMethod");
            InqAnnotationScanner.ScanResult scan = InqAnnotationScanner.scan(method);

            // When — build from scan result
            InqPipeline pipeline = PipelineFactory.create(scan, registry);

            // Then — identical to create(method, registry)
            InqPipeline direct = PipelineFactory.create(method, registry);
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactlyElementsOf(
                            direct.elements().stream().map(InqElement::getName).toList());
        }

        @Test
        void empty_scan_result_produces_empty_pipeline() {
            // Given
            var emptyScan = new InqAnnotationScanner.ScanResult(
                    java.util.List.of(), "INQUDIUM");

            // When
            InqPipeline pipeline = PipelineFactory.create(emptyScan, registry);

            // Then
            assertThat(pipeline.isEmpty()).isTrue();
        }
    }
}
