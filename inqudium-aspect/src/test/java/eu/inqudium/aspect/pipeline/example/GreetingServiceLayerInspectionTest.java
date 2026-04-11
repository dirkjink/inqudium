package eu.inqudium.aspect.pipeline.example;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
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

/**
 * Demonstrates how to inspect the layer structure of a pipeline
 * for a specific service method.
 *
 * <h3>Two inspection approaches</h3>
 * <ul>
 *   <li><strong>Production instance</strong> — use {@code new PipelinedAspect()}
 *       (no-arg constructor) which wires the same production providers as the
 *       AspectJ-managed singleton. No special setup needed.</li>
 *   <li><strong>Test instance</strong> — create a {@code PipelinedAspect} with
 *       injectable providers (e.g. with trace lists) for fine-grained assertions
 *       on execution order.</li>
 * </ul>
 */
@DisplayName("Layer inspection for GreetingService")
class GreetingServiceLayerInspectionTest {

    private Method greetMethod;
    private Method farewellMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        greetMethod = GreetingService.class.getMethod("greet", String.class);
        farewellMethod = GreetingService.class.getMethod("farewell", String.class);
    }

    // =========================================================================
    // Inspecting the production layer stack — no special setup
    // =========================================================================

    @Nested
    @DisplayName("Production layer stack inspection")
    class ProductionLayerStack {

        private PipelinedAspect aspect;

        @BeforeEach
        void setUp() {
            // The no-arg constructor wires the same production providers
            // as the AspectJ-managed singleton (AUTH, LOGGING, TIMING).
            aspect = new PipelinedAspect();
        }

        @Test
        void the_singleton_has_the_production_layer_stack() {
            // Given — resolve the pipeline for greet()
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(greetMethod);

            // Then — all three production layers are active
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING", "TIMING");
        }

        @Test
        void farewell_has_no_timing_layer_because_can_handle_filters_it() {
            // Given
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(farewellMethod);

            // Then — TIMING excluded (farewell has no @Pipelined)
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING");
        }

        @Test
        void print_diagnostic_summary_for_all_methods() {
            // Utility: discover which layers apply to each method
            System.out.println("=== Layer summary (production singleton) ===");
            System.out.println();

            for (Method method : GreetingService.class.getDeclaredMethods()) {
                ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);
                System.out.printf("  %s.%s()%n",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName());
                System.out.printf("    Layers: %s%n", pipeline.layerNames());
                System.out.printf("    Depth:  %d%n", pipeline.depth());
                System.out.printf("    Chain-ID: %d%n", pipeline.chainId());
                System.out.println();
            }

            // Output:
            // === Layer summary (production singleton) ===
            //
            //   GreetingService.greet()
            //     Layers: [AUTHORIZATION, LOGGING, TIMING]
            //     Depth:  3
            //     Chain-ID: 42
            //
            //   GreetingService.farewell()
            //     Layers: [AUTHORIZATION, LOGGING]
            //     Depth:  2
            //     Chain-ID: 43
        }

        @Test
        void print_hierarchy_tree() {
            // Given
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(greetMethod);

            // When
            System.out.println("=== Hierarchy tree for greet() ===");
            System.out.println(pipeline.toStringHierarchy());

            // Then
            assertThat(pipeline.toStringHierarchy())
                    .contains("AUTHORIZATION")
                    .contains("└── LOGGING")
                    .contains("└── TIMING");
        }

        @Test
        void same_pipeline_is_returned_on_repeated_lookups() {
            // Given
            ResolvedPipeline first = aspect.getResolvedPipeline(greetMethod);
            ResolvedPipeline second = aspect.getResolvedPipeline(greetMethod);

            // Then — cached, same instance
            assertThat(first).isSameAs(second);
        }

        @Test
        void wrapper_introspection_on_the_singleton() {
            // Given — build a Wrapper chain from the singleton (cold path)
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(
                    () -> "dummy", greetMethod);

            // When — traverse layer by layer
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
    }

    // =========================================================================
    // Inspecting with a test instance — trace-enabled providers
    // =========================================================================

    @Nested
    @DisplayName("Test instance inspection (with trace recording)")
    class TestInstance {

        private List<String> trace;
        private PipelinedAspect aspect;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
            aspect = new PipelinedAspect(List.of(
                    new AuthorizationLayerProvider(trace, true),
                    new LoggingLayerProvider(trace),
                    new TimingLayerProvider(trace)
            ));
        }

        @Test
        void the_test_instance_has_the_same_layer_structure() {
            // Given
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(greetMethod);

            // Then — same names as the production singleton
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING", "TIMING");
        }

        @Test
        void execute_and_observe_the_trace() throws Throwable {
            // When — use a plain lambda to avoid triggering woven bytecode.
            //        The lambda simulates what pjp.proceed() would return.
            Object result = aspect.execute(
                    () -> "Hello, World!", greetMethod);

            // Then — the trace records the execution order
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(trace).contains(
                    "auth:check",
                    "auth:granted",
                    "timer:start"
            );
            assertThat(trace).anyMatch(s -> s.startsWith("log:enter[chain="));
            assertThat(trace).anyMatch(s -> s.startsWith("timer:stop["));
            assertThat(trace).anyMatch(s -> s.startsWith("log:exit[result=Hello, World!]"));
        }

        @Test
        void farewell_trace_has_no_timing_entries() throws Throwable {
            // When — plain lambda, no woven method call
            aspect.execute(() -> "Goodbye, World!", farewellMethod);

            // Then — auth and logging present, timing absent
            assertThat(trace).contains("auth:check", "auth:granted");
            assertThat(trace).anyMatch(s -> s.startsWith("log:enter[chain="));
            assertThat(trace).noneMatch(s -> s.startsWith("timer:"));
        }
    }
}
