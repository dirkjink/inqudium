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
 * for a specific service method — without executing the pipeline.
 *
 * <p>This is the test you would write when asking:
 * "Which layers are active for {@code GreetingService.greet()}?"</p>
 */
@DisplayName("Layer inspection for GreetingService")
class GreetingServiceLayerInspectionTest {

    private PipelinedAspect aspect;
    private GreetingService service;

    private Method greetMethod;
    private Method farewellMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        // Set up the aspect with the standard three-layer stack
        List<AspectLayerProvider<Object>> providers = List.of(
                new AuthorizationLayerProvider(new ArrayList<>(), true),
                new LoggingLayerProvider(new ArrayList<>()),
                new TimingLayerProvider(new ArrayList<>())
        );
        aspect = new PipelinedAspect(providers);
        service = new GreetingService();

        // Resolve the Method references for the two service methods
        greetMethod = GreetingService.class.getMethod("greet", String.class);
        farewellMethod = GreetingService.class.getMethod("farewell", String.class);
    }

    // =========================================================================
    // Approach 1: ResolvedPipeline diagnostics (hot-path object, lightweight)
    // =========================================================================

    @Nested
    @DisplayName("Approach 1: ResolvedPipeline diagnostics")
    class ResolvedPipelineDiagnostics {

        @Test
        void inspect_layer_names_for_greet() {
            // Given — resolve the cached pipeline for greet()
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(greetMethod);

            // When / Then — all three layers are active (greet has @Pipelined)
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING", "TIMING");

            assertThat(pipeline.depth()).isEqualTo(3);
        }

        @Test
        void inspect_layer_names_for_farewell() {
            // Given — resolve the cached pipeline for farewell()
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(farewellMethod);

            // When / Then — only two layers (farewell has no @Pipelined → TIMING excluded)
            assertThat(pipeline.layerNames())
                    .containsExactly("AUTHORIZATION", "LOGGING");

            assertThat(pipeline.depth()).isEqualTo(2);
        }

        @Test
        void print_hierarchy_for_greet() {
            // Given
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(greetMethod);

            // When
            String hierarchy = pipeline.toStringHierarchy();

            // Then — visualize the chain structure
            System.out.println("=== ResolvedPipeline hierarchy for greet() ===");
            System.out.println(hierarchy);

            // Output:
            // Chain-ID: 42 (current call-ID: 0)
            // AUTHORIZATION
            //   └── LOGGING
            //     └── TIMING

            assertThat(hierarchy)
                    .contains("Chain-ID:")
                    .contains("AUTHORIZATION")
                    .contains("└── LOGGING")
                    .contains("└── TIMING");
        }

        @Test
        void same_pipeline_is_reused_across_multiple_lookups() {
            // Given — resolve twice for the same method
            ResolvedPipeline first = aspect.getResolvedPipeline(greetMethod);
            ResolvedPipeline second = aspect.getResolvedPipeline(greetMethod);

            // Then — same cached instance
            assertThat(first).isSameAs(second);
        }

        @Test
        void different_methods_get_independent_pipelines() {
            // Given
            ResolvedPipeline greetPipeline = aspect.getResolvedPipeline(greetMethod);
            ResolvedPipeline farewellPipeline = aspect.getResolvedPipeline(farewellMethod);

            // Then — different instances with different chain IDs
            assertThat(greetPipeline).isNotSameAs(farewellPipeline);
            assertThat(greetPipeline.chainId()).isNotEqualTo(farewellPipeline.chainId());
        }

        @Test
        void call_id_tracks_invocations() throws Throwable {
            // Given
            ResolvedPipeline pipeline = aspect.getResolvedPipeline(greetMethod);
            assertThat(pipeline.currentCallId()).isZero();

            // When — execute twice
            pipeline.execute(() -> service.greet("Alice"));
            pipeline.execute(() -> service.greet("Bob"));

            // Then — call ID incremented per execution
            assertThat(pipeline.currentCallId()).isEqualTo(2);
        }
    }

    // =========================================================================
    // Approach 2: Wrapper interface introspection (cold-path, full traversal)
    // =========================================================================

    @Nested
    @DisplayName("Approach 2: Wrapper interface introspection")
    class WrapperIntrospection {

        @Test
        void walk_the_chain_layer_by_layer_for_greet() {
            // Given — build a JoinPointWrapper chain (cold path)
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(
                    () -> service.greet("test"), greetMethod);

            // When — traverse the chain via inner()
            List<String> layers = new ArrayList<>();
            Wrapper<?> current = chain;
            while (current != null) {
                layers.add(current.layerDescription());
                current = current.inner();
            }

            // Then
            System.out.println("=== Wrapper chain for greet() ===");
            layers.forEach(l -> System.out.println("  - " + l));

            assertThat(layers).containsExactly(
                    "AUTHORIZATION", "LOGGING", "TIMING");
        }

        @Test
        void walk_the_chain_layer_by_layer_for_farewell() {
            // Given
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(
                    () -> service.farewell("test"), farewellMethod);

            // When
            List<String> layers = new ArrayList<>();
            Wrapper<?> current = chain;
            while (current != null) {
                layers.add(current.layerDescription());
                current = current.inner();
            }

            // Then — TIMING is filtered out by canHandle
            assertThat(layers).containsExactly("AUTHORIZATION", "LOGGING");
        }

        @Test
        void print_full_hierarchy_tree() {
            // Given
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(
                    () -> service.greet("test"), greetMethod);

            // When
            String hierarchy = chain.toStringHierarchy();

            // Then
            System.out.println("=== Full Wrapper hierarchy for greet() ===");
            System.out.println(hierarchy);

            // Output:
            // Chain-ID: 42 (current call-ID: 0)
            // AUTHORIZATION
            //   └── LOGGING
            //     └── TIMING

            assertThat(hierarchy)
                    .contains("Chain-ID:")
                    .contains("current call-ID:")
                    .contains("AUTHORIZATION")
                    .contains("LOGGING")
                    .contains("TIMING");
        }

        @Test
        void inspect_individual_layer_properties() {
            // Given
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(
                    () -> service.greet("test"), greetMethod);

            // When — navigate to each layer
            JoinPointWrapper<Object> authLayer = chain;
            JoinPointWrapper<Object> logLayer = chain.inner();
            JoinPointWrapper<Object> timingLayer = chain.inner().inner();

            // Then — each layer exposes its description and shared chain ID
            assertThat(authLayer.layerDescription()).isEqualTo("AUTHORIZATION");
            assertThat(logLayer.layerDescription()).isEqualTo("LOGGING");
            assertThat(timingLayer.layerDescription()).isEqualTo("TIMING");

            // All layers share the same chain ID
            long sharedChainId = authLayer.chainId();
            assertThat(logLayer.chainId()).isEqualTo(sharedChainId);
            assertThat(timingLayer.chainId()).isEqualTo(sharedChainId);

            // No more layers after TIMING
            assertThat(timingLayer.inner()).isNull();
        }

        @Test
        void count_chain_depth() {
            // Given
            JoinPointWrapper<Object> greetChain = aspect.inspectPipeline(
                    () -> "dummy", greetMethod);
            JoinPointWrapper<Object> farewellChain = aspect.inspectPipeline(
                    () -> "dummy", farewellMethod);

            // When
            int greetDepth = countLayers(greetChain);
            int farewellDepth = countLayers(farewellChain);

            // Then
            assertThat(greetDepth).as("greet() has 3 layers").isEqualTo(3);
            assertThat(farewellDepth).as("farewell() has 2 layers").isEqualTo(2);
        }

        private int countLayers(Wrapper<?> chain) {
            int depth = 0;
            Wrapper<?> current = chain;
            while (current != null) {
                depth++;
                current = current.inner();
            }
            return depth;
        }
    }

    // =========================================================================
    // Approach 3: Comparing both approaches side by side
    // =========================================================================

    @Nested
    @DisplayName("Approach 3: Side-by-side comparison")
    class SideBySide {

        @Test
        void both_approaches_report_the_same_layers() {
            // Given — same method, two inspection paths
            ResolvedPipeline resolved = aspect.getResolvedPipeline(greetMethod);
            JoinPointWrapper<Object> wrapper = aspect.inspectPipeline(
                    () -> "dummy", greetMethod);

            // When — extract layer names from both
            List<String> fromResolved = resolved.layerNames();

            List<String> fromWrapper = new ArrayList<>();
            Wrapper<?> current = wrapper;
            while (current != null) {
                fromWrapper.add(current.layerDescription());
                current = current.inner();
            }

            // Then — identical layer order
            assertThat(fromResolved).isEqualTo(fromWrapper);
        }

        @Test
        void print_diagnostic_summary_for_all_service_methods() {
            // Utility: print a summary of which layers apply to each method
            System.out.println("=== Layer summary for GreetingService ===");
            System.out.println();

            for (Method method : GreetingService.class.getDeclaredMethods()) {
                ResolvedPipeline pipeline = aspect.getResolvedPipeline(method);
                System.out.printf("  %s.%s()%n", method.getDeclaringClass().getSimpleName(),
                        method.getName());
                System.out.printf("    Layers: %s%n", pipeline.layerNames());
                System.out.printf("    Depth:  %d%n", pipeline.depth());
                System.out.println();
            }

            // Output:
            // === Layer summary for GreetingService ===
            //
            //   GreetingService.greet()
            //     Layers: [AUTHORIZATION, LOGGING, TIMING]
            //     Depth:  3
            //
            //   GreetingService.farewell()
            //     Layers: [AUTHORIZATION, LOGGING]
            //     Depth:  2
        }
    }
}
