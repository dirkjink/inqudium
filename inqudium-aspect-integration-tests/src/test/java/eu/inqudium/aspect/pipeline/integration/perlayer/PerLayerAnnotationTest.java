package eu.inqudium.aspect.pipeline.integration.perlayer;

import eu.inqudium.aspect.pipeline.ResolvedPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-layer annotation pattern: each layer has its own
 * annotation, and methods compose their pipeline by combining annotations.
 *
 * <pre>
 *   charge()     @Authorized @Logged @Timed  →  [AUTH, LOG, TIME]
 *   refund()     @Authorized @Logged         →  [AUTH, LOG]
 *   lookup()     @Logged                     →  [LOG]
 *   validate()   @Authorized @Timed          →  [AUTH, TIME]
 *   internal()   (none)                      →  not intercepted
 * </pre>
 */
@DisplayName("Per-layer annotation pattern")
class PerLayerAnnotationTest {

    private PerLayerAspect aspect;

    @BeforeEach
    void setUp() {
        // Same production providers as the AspectJ singleton
        aspect = new PerLayerAspect();
    }

    // =========================================================================
    // Pipeline structure per method
    // =========================================================================

    @Nested
    @DisplayName("Pipeline structure per annotation combination")
    class PipelineStructure {

        @Test
        void all_three_annotations_produce_full_pipeline() throws NoSuchMethodException {
            // Given — charge() has @Authorized @Logged @Timed
            Method method = PaymentService.class.getMethod("charge", String.class, int.class);

            // When
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

            // Then
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING", "TIMING");
            assertThat(pipeline.depth()).isEqualTo(3);
        }

        @Test
        void authorized_and_logged_produce_two_layer_pipeline() throws NoSuchMethodException {
            // Given — refund() has @Authorized @Logged (no @Timed)
            Method method = PaymentService.class.getMethod("refund", String.class, int.class);

            // When
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

            // Then
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING");
        }

        @Test
        void single_annotation_produces_single_layer_pipeline() throws NoSuchMethodException {
            // Given — lookup() has only @Logged
            Method method = PaymentService.class.getMethod("lookup", String.class);

            // When
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

            // Then
            assertThat(pipeline.layerNames())
                    .containsExactly("LOGGING");
            assertThat(pipeline.depth()).isEqualTo(1);
        }

        @Test
        void non_adjacent_annotations_skip_middle_layer() throws NoSuchMethodException {
            // Given — validate() has @Authorized @Timed (no @Logged)
            Method method = PaymentService.class.getMethod("validate", String.class);

            // When
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

            // Then — LOG is skipped, order is preserved
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "TIMING");
        }

        @Test
        void no_annotations_produce_empty_pipeline() throws NoSuchMethodException {
            // Given — internal() has no layer annotations
            Method method = PaymentService.class.getMethod("internal", String.class);

            // When
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

            // Then — all providers filtered out by canHandle
            assertThat(pipeline.layerNames()).isEmpty();
            assertThat(pipeline.depth()).isZero();
        }
    }

    // =========================================================================
    // Execution through the pipeline
    // =========================================================================

    @Nested
    @DisplayName("Execution through per-layer pipeline")
    class Execution {

        @Test
        void full_pipeline_produces_correct_result() throws Throwable {
            // Given
            Method method = PaymentService.class.getMethod("charge", String.class, int.class);

            // When — execute through AUTH → LOG → TIME
            Object result = aspect.execute(() -> "Charged 100 from ACC-1", method);

            // Then
            assertThat(result).isEqualTo("Charged 100 from ACC-1");
        }

        @Test
        void single_layer_pipeline_produces_correct_result() throws Throwable {
            // Given
            Method method = PaymentService.class.getMethod("lookup", String.class);

            // When — execute through LOG only
            Object result = aspect.execute(() -> "Transaction TX-1: completed", method);

            // Then
            assertThat(result).isEqualTo("Transaction TX-1: completed");
        }

        @Test
        void empty_pipeline_passes_through_directly() throws Throwable {
            // Given — internal() has no annotations → all layers filtered out
            Method method = PaymentService.class.getMethod("internal", String.class);

            // When — empty pipeline: terminal is called directly
            Object result = aspect.execute(() -> "Internal: note", method);

            // Then
            assertThat(result).isEqualTo("Internal: note");
        }
    }

    // =========================================================================
    // CTW integration (woven method calls)
    // =========================================================================

    @Nested
    @DisplayName("CTW woven execution")
    class WovenExecution {

        private PaymentService service;

        @BeforeEach
        void setUp() {
            service = new PaymentService();
        }

        @Test
        void charge_executes_through_woven_full_pipeline() {
            // When — all three annotations → AUTH → LOG → TIME
            String result = service.charge("ACC-1", 100);

            // Then
            assertThat(result).isEqualTo("Charged 100 from ACC-1");
        }

        @Test
        void refund_executes_through_woven_two_layer_pipeline() {
            // When — @Authorized @Logged → AUTH → LOG
            String result = service.refund("ACC-1", 50);

            // Then
            assertThat(result).isEqualTo("Refunded 50 to ACC-1");
        }

        @Test
        void lookup_executes_through_woven_single_layer_pipeline() {
            // When — @Logged only → LOG
            String result = service.lookup("TX-42");

            // Then
            assertThat(result).isEqualTo("Transaction TX-42: completed");
        }

        @Test
        void validate_executes_through_woven_skipped_middle_pipeline() {
            // When — @Authorized @Timed → AUTH → TIME (LOG skipped)
            String result = service.validate("ACC-1");

            // Then
            assertThat(result).isEqualTo("Account ACC-1 is valid");
        }

        @Test
        void internal_executes_without_pipeline_interception() {
            // When — no annotations → not intercepted
            String result = service.internal("test");

            // Then
            assertThat(result).isEqualTo("Internal: test");
        }
    }

    // =========================================================================
    // Diagnostic summary
    // =========================================================================

    @Nested
    @DisplayName("Diagnostic output")
    class DiagnosticOutput {

        @Test
        void print_per_method_layer_summary() {
            System.out.println("=== Per-layer annotation: PaymentService ===");
            System.out.println();

            for (Method method : PaymentService.class.getDeclaredMethods()) {
                ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);

                String annotations = formatAnnotations(method);
                System.out.printf("  %s(%s)%n", method.getName(), formatParams(method));
                System.out.printf("    Annotations: %s%n",
                        annotations.isEmpty() ? "(none)" : annotations);
                System.out.printf("    Layers:      %s%n", pipeline.layerNames());
                System.out.printf("    Depth:       %d%n", pipeline.depth());
                System.out.println();
            }

            // Expected output:
            //   charge(String, int)
            //     Annotations: @Authorized @Logged @Timed
            //     Layers:      [AUTH, LOG, TIME]
            //     Depth:       3
            //
            //   refund(String, int)
            //     Annotations: @Authorized @Logged
            //     Layers:      [AUTH, LOG]
            //     Depth:       2
            //
            //   lookup(String)
            //     Annotations: @Logged
            //     Layers:      [LOG]
            //     Depth:       1
            //
            //   validate(String)
            //     Annotations: @Authorized @Timed
            //     Layers:      [AUTH, TIME]
            //     Depth:       2
            //
            //   internal(String)
            //     Annotations: (none)
            //     Layers:      []
            //     Depth:       0
        }

        private String formatAnnotations(Method method) {
            StringBuilder sb = new StringBuilder();
            if (method.isAnnotationPresent(Authorized.class)) sb.append("@Authorized ");
            if (method.isAnnotationPresent(Logged.class)) sb.append("@Logged ");
            if (method.isAnnotationPresent(Timed.class)) sb.append("@Timed ");
            return sb.toString().trim();
        }

        private String formatParams(Method method) {
            Class<?>[] params = method.getParameterTypes();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            return sb.toString();
        }
    }
}
