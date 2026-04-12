package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.ResolvedPipeline;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests that verify AspectJ compile-time weaving (CTW) works
 * end-to-end with the inqudium pipeline.
 *
 * <p>This module is <strong>self-contained</strong> — it brings its own
 * annotation ({@link Resilient}), layer providers ({@link AuthorizationLayer},
 * {@link LoggingLayer}, {@link TimingLayer}), aspect ({@link ResilienceAspect}),
 * and target service ({@link OrderService}). No dependency on the
 * {@code example} package in {@code inqudium-aspect}.</p>
 *
 * <h3>What CTW does</h3>
 * <p>The {@code aspectj-maven-plugin} in this module's POM:</p>
 * <ol>
 *   <li>Reads the {@code @Aspect} class ({@link ResilienceAspect}) from this
 *       module's compiled output.</li>
 *   <li>Generates {@code aspectOf()} and {@code hasAspect()} on
 *       {@link ResilienceAspect}.</li>
 *   <li>Weaves the {@code @Around} advice into every {@code @Resilient}
 *       method in {@link OrderService}.</li>
 * </ol>
 *
 * <h3>How to run</h3>
 * <pre>{@code
 *   mvn clean test -pl inqudium-aspect-integration-tests
 * }</pre>
 */
@DisplayName("AspectJ CTW Integration")
class AspectJCtwIntegrationTest {

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService();
    }

    // =========================================================================
    // Woven method execution
    // =========================================================================

    @Nested
    @DisplayName("Woven method execution")
    class WovenExecution {

        @Test
        void resilient_method_executes_through_the_woven_pipeline() {
            // Given — placeOrder() is @Resilient → woven by CTW.
            //         The call routes through:
            //         aspectOf() → around(pjp) → executeAround(pjp)
            //         → ResolvedPipeline → AUTH → LOG → TIMING → pjp.proceed()

            // When — a normal method call
            String result = service.placeOrder("Widget", 3);

            // Then — the pipeline did not alter the result
            assertThat(result).isEqualTo("Ordered 3x Widget");
        }

        @Test
        void another_resilient_method_also_executes_through_the_pipeline() {
            // When
            String result = service.cancelOrder("ORD-42");

            // Then
            assertThat(result).isEqualTo("Cancelled ORD-42");
        }

        @Test
        void non_resilient_method_executes_directly_without_pipeline() {
            // Given — getStatus() has no @Resilient annotation, so the
            //         @Around pointcut does not match. Bytecode is unmodified.

            // When
            String result = service.getStatus("ORD-42");

            // Then
            assertThat(result).isEqualTo("Status of ORD-42: shipped");
        }

        @Test
        void repeated_calls_produce_consistent_results() {
            // When
            List<String> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(service.placeOrder("Item-" + i, i));
            }

            // Then
            for (int i = 0; i < 100; i++) {
                assertThat(results.get(i)).isEqualTo("Ordered " + i + "x Item-" + i);
            }
        }
    }

    // =========================================================================
    // AspectJ singleton
    // =========================================================================

    @Nested
    @DisplayName("AspectJ singleton access")
    class SingletonAccess {

        @Test
        void aspect_of_returns_a_non_null_singleton() {
            // When — ajc generated aspectOf() during CTW
            ResilienceAspect singleton = ResilienceAspect.aspectOf();

            // Then
            assertThat(singleton).isNotNull();
        }

        @Test
        void has_aspect_returns_true_after_weaving() {
            // Then
            assertThat(ResilienceAspect.hasAspect()).isTrue();
        }

        @Test
        void aspect_of_returns_the_same_instance_on_repeated_calls() {
            // When
            ResilienceAspect first = ResilienceAspect.aspectOf();
            ResilienceAspect second = ResilienceAspect.aspectOf();

            // Then — singleton identity
            assertThat(first).isSameAs(second);
        }
    }

    // =========================================================================
    // Pipeline introspection via the singleton
    // =========================================================================

    @Nested
    @DisplayName("Singleton pipeline introspection")
    class SingletonIntrospection {

        private ResilienceAspect singleton;
        private Method placeOrderMethod;
        private Method getStatusMethod;

        @BeforeEach
        void setUp() throws NoSuchMethodException {
            singleton = ResilienceAspect.aspectOf();
            placeOrderMethod = OrderService.class.getMethod("placeOrder", String.class, int.class);
            getStatusMethod = OrderService.class.getMethod("getStatus", String.class);
        }

        @Test
        void resilient_method_has_three_layers() {
            // Given
            ResolvedPipeline pipeline = singleton.getResolvedPipeline(placeOrderMethod);

            // Then
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING", "TIMING");
            assertThat(pipeline.depth()).isEqualTo(3);
        }

        @Test
        void non_resilient_method_has_two_layers_because_timing_filters_by_annotation() {
            // Given — getStatus() has no @Resilient → TimingLayer.canHandle returns false
            ResolvedPipeline pipeline = singleton.getResolvedPipeline(getStatusMethod);

            // Then
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING");
            assertThat(pipeline.depth()).isEqualTo(2);
        }

        @Test
        void wrapper_chain_can_be_traversed_layer_by_layer() {
            // Given
            JoinPointWrapper<Object> chain = singleton.inspectPipeline(
                    () -> "dummy", placeOrderMethod);

            // When
            List<String> layers = new ArrayList<>();
            Wrapper<?> current = chain;
            while (current != null) {
                layers.add(current.layerDescription());
                current = current.inner();
            }

            // Then
            assertThat(layers).containsExactly(
                    "AUTHORIZATION", "LOGGING", "TIMING");
        }

        @Test
        void hierarchy_string_shows_all_layers() {
            // Given
            ResolvedPipeline pipeline = singleton.getResolvedPipeline(placeOrderMethod);

            // When
            String hierarchy = pipeline.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                    .contains("Chain-ID:")
                    .contains("AUTHORIZATION")
                    .contains("└── LOGGING")
                    .contains("└── TIMING");
        }

        @Test
        void pipeline_is_cached_per_method() {
            // When
            ResolvedPipeline first = singleton.getResolvedPipeline(placeOrderMethod);
            ResolvedPipeline second = singleton.getResolvedPipeline(placeOrderMethod);

            // Then
            assertThat(first).isSameAs(second);
        }

        @Test
        void different_methods_get_independent_pipelines() {
            // When
            ResolvedPipeline orderPipeline = singleton.getResolvedPipeline(placeOrderMethod);
            ResolvedPipeline statusPipeline = singleton.getResolvedPipeline(getStatusMethod);

            // Then
            assertThat(orderPipeline).isNotSameAs(statusPipeline);
            assertThat(orderPipeline.chainId()).isNotEqualTo(statusPipeline.chainId());
        }

        @Test
        void call_id_increments_with_each_woven_invocation() {
            // Given
            ResolvedPipeline pipeline = singleton.getResolvedPipeline(placeOrderMethod);

            // When — measure the increment per single call
            long before = pipeline.currentCallId();
            service.placeOrder("A", 1);
            long afterFirst = pipeline.currentCallId();
            service.placeOrder("B", 2);
            long afterSecond = pipeline.currentCallId();

            // Then — each woven call increments the counter by the same amount
            long incrementPerCall = afterFirst - before;
            assertThat(incrementPerCall).isPositive();
            assertThat(afterSecond - afterFirst).isEqualTo(incrementPerCall);
        }
    }

    // =========================================================================
    // Exception transport
    // =========================================================================

    @Nested
    @DisplayName("Exception transport through woven advice")
    class ExceptionTransport {

        @Test
        void runtime_exception_propagates_through_the_woven_pipeline() {
            // Given — validateOrder() is @Resilient and throws
            //         IllegalArgumentException

            // When / Then — exception passes through unchanged
            assertThatThrownBy(() -> service.validateOrder("BAD-001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid order: BAD-001");
        }
    }

    // =========================================================================
    // Diagnostic output
    // =========================================================================

    @Nested
    @DisplayName("Diagnostic output")
    class DiagnosticOutput {

        @Test
        void print_layer_summary_for_all_service_methods() {
            // Given
            ResilienceAspect singleton = ResilienceAspect.aspectOf();

            // When
            System.out.println("=== CTW Integration: Layer summary for OrderService ===");
            System.out.println();

            for (Method method : OrderService.class.getDeclaredMethods()) {
                ResolvedPipeline pipeline = singleton.getResolvedPipeline(method);
                System.out.printf("  %s(%s)%n",
                        method.getName(),
                        formatParams(method));
                System.out.printf("    @Resilient: %s%n",
                        method.isAnnotationPresent(Resilient.class));
                System.out.printf("    Layers:     %s%n", pipeline.layerNames());
                System.out.printf("    Depth:      %d%n", pipeline.depth());
                System.out.printf("    Chain-ID:   %d%n", pipeline.chainId());
                System.out.println();
            }

            // Expected output:
            //   placeOrder(String, int)
            //     @Resilient: true
            //     Layers:     [AUTHORIZATION, LOGGING, TIMING]
            //     Depth:      3
            //
            //   cancelOrder(String)
            //     @Resilient: true
            //     Layers:     [AUTHORIZATION, LOGGING, TIMING]
            //     Depth:      3
            //
            //   getStatus(String)
            //     @Resilient: false
            //     Layers:     [AUTHORIZATION, LOGGING]
            //     Depth:      2
            //
            //   validateOrder(String)
            //     @Resilient: true
            //     Layers:     [AUTHORIZATION, LOGGING, TIMING]
            //     Depth:      3
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
