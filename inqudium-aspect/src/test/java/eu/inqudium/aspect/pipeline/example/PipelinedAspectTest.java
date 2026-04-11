package eu.inqudium.aspect.pipeline.example;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PipelinedAspect — three-layer pipeline with Wrapper introspection")
class PipelinedAspectTest {

    private List<String> trace;
    private PipelinedAspect aspect;

    @BeforeEach
    void setUp() {
        trace = new ArrayList<>();
    }

    /**
     * Creates the aspect with the standard three-layer stack.
     * Authorization is granted by default.
     */
    private PipelinedAspect aspectWithAuthorization(boolean authorized) {
        List<AspectLayerProvider<Object>> providers = List.of(
                new AuthorizationLayerProvider(trace, authorized),
                new LoggingLayerProvider(trace),
                new TimingLayerProvider(trace)
        );
        return new PipelinedAspect(providers);
    }

    // =========================================================================
    // Execution flow
    // =========================================================================

    @Nested
    @DisplayName("Execution flow through the three-layer pipeline")
    class ExecutionFlow {

        @Test
        void all_three_layers_execute_in_correct_order_around_the_core_method() throws Throwable {
            // Given
            aspect = aspectWithAuthorization(true);

            // When — simulate what AspectJ would do: call execute with the
            //        real method as a JoinPointExecutor (here: a lambda standing
            //        in for pjp::proceed)
            Object result = aspect.execute(() -> {
                trace.add("core:greet");
                return "Hello, World!";
            });

            // Then — layers wrap in order: AUTH(10) → LOG(20) → TIMING(30) → core
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(trace).startsWith(
                    "auth:check",
                    "auth:granted",
                    // Logging entry includes chain/call IDs (values vary, just check prefix)
                    trace.get(2),   // "log:enter[chain=...,call=...]"
                    "timer:start",
                    "core:greet"
            );
            // Verify the logging entry format
            assertThat(trace.get(2)).startsWith("log:enter[chain=");
            // End phases fire in reverse: timer:stop → log:exit → (auth has no post-phase)
            assertThat(trace).element(5).asString().startsWith("timer:stop[");
            assertThat(trace).element(6).asString().isEqualTo("log:exit[result=Hello, World!]");
        }

        @Test
        void authorization_denial_short_circuits_before_logging_and_timing() {
            // Given
            aspect = aspectWithAuthorization(false);

            // When / Then
            assertThatThrownBy(() -> aspect.execute(() -> {
                trace.add("core:should-not-run");
                return "unreachable";
            }))
                    .isInstanceOf(SecurityException.class)
                    .hasMessage("Access denied");

            // Then — only auth traces, no logging, no timing, no core
            assertThat(trace).containsExactly("auth:check", "auth:denied");
        }

        @Test
        void core_exception_is_observed_by_logging_layer_and_propagated() {
            // Given
            aspect = aspectWithAuthorization(true);

            // When / Then
            assertThatThrownBy(() -> aspect.execute(() -> {
                trace.add("core:fail");
                throw new RuntimeException("db-timeout");
            }))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("db-timeout");

            // Then — logging layer records the error
            assertThat(trace).contains("log:error[db-timeout]");
            // Timer still stops (finally block)
            assertThat(trace).anyMatch(s -> s.startsWith("timer:stop["));
        }
    }

    // =========================================================================
    // Wrapper introspection
    // =========================================================================

    @Nested
    @DisplayName("Wrapper interface introspection of the chain structure")
    class WrapperIntrospection {

        private JoinPointWrapper<Object> chain;

        @BeforeEach
        void buildChain() {
            // Given — build the pipeline without executing it
            aspect = aspectWithAuthorization(true);
            chain = aspect.inspectPipeline(() -> "dummy");
        }

        @Test
        void the_outermost_layer_is_authorization() {
            // When
            String outermost = chain.layerDescription();

            // Then
            assertThat(outermost).isEqualTo("AUTHORIZATION");
        }

        @Test
        void walking_inner_reveals_all_three_layers_in_order() {
            // When — traverse the chain via inner()
            List<String> layerNames = new ArrayList<>();
            Wrapper<?> current = chain;
            while (current != null) {
                layerNames.add(current.layerDescription());
                current = current.inner();
            }

            // Then — outermost to innermost
            assertThat(layerNames).containsExactly(
                    "AUTHORIZATION",
                    "LOGGING",
                    "TIMING"
            );
        }

        @Test
        void inner_returns_null_after_the_innermost_wrapper() {
            // When — navigate to the innermost layer
            JoinPointWrapper<Object> logging = chain.inner();
            JoinPointWrapper<Object> timing = logging.inner();
            JoinPointWrapper<Object> beyondTiming = timing.inner();

            // Then — no more wrappers after TIMING
            assertThat(beyondTiming).isNull();
        }

        @Test
        void all_layers_share_the_same_chain_id() {
            // Given
            long expectedChainId = chain.chainId();

            // When / Then
            Wrapper<?> current = chain;
            while (current != null) {
                assertThat(current.chainId())
                        .as("chainId of layer '%s'", current.layerDescription())
                        .isEqualTo(expectedChainId);
                current = current.inner();
            }
        }

        @Test
        void chain_id_is_a_positive_value() {
            // When / Then
            assertThat(chain.chainId()).isPositive();
        }

        @Test
        void current_call_id_is_zero_before_any_execution() {
            // When — no proceed() has been called yet
            long callId = chain.currentCallId();

            // Then
            assertThat(callId).isZero();
        }

        @Test
        void current_call_id_increments_after_each_execution() throws Throwable {
            // Given — build a real executable chain
            JoinPointWrapper<Object> executableChain =
                    aspect.inspectPipeline(() -> "result");

            // When
            executableChain.proceed();
            long afterFirst = executableChain.currentCallId();
            executableChain.proceed();
            long afterSecond = executableChain.currentCallId();

            // Then
            assertThat(afterFirst).isEqualTo(1);
            assertThat(afterSecond).isEqualTo(2);
        }

        @Test
        void call_id_is_consistent_across_all_layers_after_execution() throws Throwable {
            // Given
            JoinPointWrapper<Object> executableChain =
                    aspect.inspectPipeline(() -> "result");

            // When
            executableChain.proceed();

            // Then — all layers report the same current call ID
            long expectedCallId = executableChain.currentCallId();
            Wrapper<?> current = executableChain;
            while (current != null) {
                assertThat(current.currentCallId())
                        .as("currentCallId of layer '%s'", current.layerDescription())
                        .isEqualTo(expectedCallId);
                current = current.inner();
            }
        }

        @Test
        void to_string_hierarchy_contains_chain_id_and_all_layer_names() {
            // When
            String hierarchy = chain.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                    .contains("Chain-ID: " + chain.chainId())
                    .contains("AUTHORIZATION")
                    .contains("LOGGING")
                    .contains("TIMING");
        }

        @Test
        void to_string_hierarchy_shows_layers_indented_as_a_tree() {
            // When
            String hierarchy = chain.toStringHierarchy();

            // Then — verify the tree structure with indentation
            String[] lines = hierarchy.split("\n");

            // Line 0: header with chain ID
            assertThat(lines[0]).startsWith("Chain-ID:");

            // Line 1: outermost layer (no indentation)
            assertThat(lines[1]).isEqualTo("AUTHORIZATION");

            // Line 2: middle layer (indented once)
            assertThat(lines[2]).contains("└── LOGGING");

            // Line 3: innermost layer (indented twice)
            assertThat(lines[3]).contains("└── TIMING");
        }

        @Test
        void two_independent_chains_have_different_chain_ids() {
            // Given
            JoinPointWrapper<Object> chainA = aspect.inspectPipeline(() -> "a");
            JoinPointWrapper<Object> chainB = aspect.inspectPipeline(() -> "b");

            // When / Then
            assertThat(chainA.chainId()).isNotEqualTo(chainB.chainId());
        }

        @Test
        void chain_depth_is_exactly_three() {
            // When — count layers by walking inner()
            int depth = 0;
            Wrapper<?> current = chain;
            while (current != null) {
                depth++;
                current = current.inner();
            }

            // Then
            assertThat(depth).isEqualTo(3);
        }
    }

    // =========================================================================
    // Production layer stack inspection
    // =========================================================================

    @Nested
    @DisplayName("Production layer stack inspection")
    class ProductionLayerStack {

        @Test
        void the_production_aspect_exposes_the_full_layer_structure() throws NoSuchMethodException {
            // Given — the no-arg constructor wires the production providers
            //         (same as the AspectJ-managed singleton after CTW)
            PipelinedAspect production = new PipelinedAspect();
            Method greetMethod = GreetingService.class.getMethod("greet", String.class);

            // When
            JoinPointWrapper<Object> chain = production.inspectPipeline(
                    () -> "dummy", greetMethod);

            // Then
            assertThat(chain.layerDescription()).isEqualTo("AUTHORIZATION");
            assertThat(chain.inner().layerDescription()).isEqualTo("LOGGING");
            assertThat(chain.inner().inner().layerDescription()).isEqualTo("TIMING");
            assertThat(chain.inner().inner().inner()).isNull();
        }

        @Test
        void farewell_excludes_timing_in_the_production_aspect() throws NoSuchMethodException {
            // Given
            PipelinedAspect production = new PipelinedAspect();
            Method farewellMethod = GreetingService.class.getMethod("farewell", String.class);

            // When
            JoinPointWrapper<Object> chain = production.inspectPipeline(
                    () -> "dummy", farewellMethod);

            // Then — TIMING excluded (farewell has no @Pipelined)
            assertThat(chain.layerDescription()).isEqualTo("AUTHORIZATION");
            assertThat(chain.inner().layerDescription()).isEqualTo("LOGGING");
            assertThat(chain.inner().inner()).isNull();
        }
    }

    // =========================================================================
    // canHandle filtering
    // =========================================================================

    @Nested
    @DisplayName("canHandle-based method filtering")
    class CanHandleFiltering {

        private Method greetMethod;
        private Method farewellMethod;

        @BeforeEach
        void resolveMethods() throws NoSuchMethodException {
            greetMethod = GreetingService.class.getMethod("greet", String.class);
            farewellMethod = GreetingService.class.getMethod("farewell", String.class);
        }

        @Test
        void pipelined_method_includes_all_three_layers() {
            // Given
            aspect = aspectWithAuthorization(true);

            // When — greet() is annotated with @Pipelined, so TimingLayerProvider accepts it
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(() -> "dummy", greetMethod);

            // Then
            List<String> layerNames = new ArrayList<>();
            Wrapper<?> current = chain;
            while (current != null) {
                layerNames.add(current.layerDescription());
                current = current.inner();
            }
            assertThat(layerNames).containsExactly("AUTHORIZATION", "LOGGING", "TIMING");
        }

        @Test
        void non_pipelined_method_excludes_timing_layer() {
            // Given
            aspect = aspectWithAuthorization(true);

            // When — farewell() is NOT annotated with @Pipelined,
            //        so TimingLayerProvider.canHandle returns false
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(() -> "dummy", farewellMethod);

            // Then — only AUTHORIZATION and LOGGING remain
            List<String> layerNames = new ArrayList<>();
            Wrapper<?> current = chain;
            while (current != null) {
                layerNames.add(current.layerDescription());
                current = current.inner();
            }
            assertThat(layerNames).containsExactly("AUTHORIZATION", "LOGGING");
        }

        @Test
        void hierarchy_string_differs_between_pipelined_and_non_pipelined_methods() {
            // Given
            aspect = aspectWithAuthorization(true);

            // When
            String greetHierarchy = aspect.inspectPipeline(() -> "g", greetMethod)
                    .toStringHierarchy();
            String farewellHierarchy = aspect.inspectPipeline(() -> "f", farewellMethod)
                    .toStringHierarchy();

            // Then
            assertThat(greetHierarchy).contains("TIMING");
            assertThat(farewellHierarchy).doesNotContain("TIMING");
        }

        @Test
        void non_pipelined_method_executes_with_only_two_layers() throws Throwable {
            // Given — use plain lambdas to avoid triggering woven bytecode
            aspect = aspectWithAuthorization(true);

            // When
            Object result = aspect.execute(
                    () -> "Goodbye, World!", farewellMethod);

            // Then
            assertThat(result).isEqualTo("Goodbye, World!");
            assertThat(trace).contains("auth:check", "auth:granted");
            assertThat(trace).anyMatch(s -> s.startsWith("log:enter[chain="));
            assertThat(trace).noneMatch(s -> s.startsWith("timer:"));
        }

        @Test
        void pipelined_method_executes_with_all_three_layers() throws Throwable {
            // Given — use plain lambdas to avoid triggering woven bytecode
            aspect = aspectWithAuthorization(true);

            // When
            Object result = aspect.execute(
                    () -> "Hello, World!", greetMethod);

            // Then
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(trace).anyMatch(s -> s.startsWith("timer:start"));
            assertThat(trace).anyMatch(s -> s.startsWith("timer:stop["));
        }

        @Test
        void chain_depth_is_two_for_non_pipelined_method() {
            // Given
            aspect = aspectWithAuthorization(true);

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(
                    () -> "dummy", farewellMethod);
            int depth = 0;
            Wrapper<?> current = chain;
            while (current != null) {
                depth++;
                current = current.inner();
            }

            // Then
            assertThat(depth).isEqualTo(2);
        }

        @Test
        void both_methods_get_independent_chain_ids() {
            // Given
            aspect = aspectWithAuthorization(true);

            // When
            JoinPointWrapper<Object> greetChain = aspect.inspectPipeline(
                    () -> "g", greetMethod);
            JoinPointWrapper<Object> farewellChain = aspect.inspectPipeline(
                    () -> "f", farewellMethod);

            // Then
            assertThat(greetChain.chainId()).isNotEqualTo(farewellChain.chainId());
        }
    }
}
