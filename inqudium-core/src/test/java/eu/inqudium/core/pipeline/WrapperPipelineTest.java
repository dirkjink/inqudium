package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Comprehensive tests for the wrapper pipeline framework with {@link LayerAction} support.
 */
@DisplayName("Wrapper Pipeline")
class WrapperPipelineTest {

    // =========================================================================
    // Test Infrastructure
    // =========================================================================

    /**
     * Captures execution events across wrapper layers for verification.
     */
    static class ExecutionLog {
        final List<String> layerNames = Collections.synchronizedList(new ArrayList<>());
        final List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean coreInvoked = new AtomicBoolean(false);
    }

    /**
     * Creates a tracking {@link LayerAction} that records layer name and call ID,
     * then forwards to the next step. Replaces the old TrackingXxxWrapper subclasses.
     */
    static <A, R> LayerAction<A, R> trackingAction(String layerName, ExecutionLog log) {
        return (chainId, callId, argument, next) -> {
            log.layerNames.add(layerName);
            log.callIds.add(callId);
            return next.execute(chainId, callId, argument);
        };
    }

    /**
     * Abstracts over the creation and invocation of different wrapper types,
     * enabling parameterized tests across all wrapper flavors.
     */
    static abstract class WrapperScenario {
        abstract String displayName();
        abstract BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log);
        abstract BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log);
        abstract Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) throws Throwable;
        @Override public String toString() { return displayName(); }
    }

    // --- Scenario implementations ---

    static final WrapperScenario RUNNABLE_SCENARIO = new WrapperScenario() {
        @Override String displayName() { return "RunnableWrapper"; }
        @Override BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            return new RunnableWrapper(name, () -> log.coreInvoked.set(true), trackingAction(name, log));
        }
        @Override BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new RunnableWrapper(name, (RunnableWrapper) inner, trackingAction(name, log));
        }
        @Override Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) { ((Runnable) wrapper).run(); return null; }
    };

    static final WrapperScenario SUPPLIER_SCENARIO = new WrapperScenario() {
        @Override String displayName() { return "SupplierWrapper"; }
        @Override BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            return new SupplierWrapper<>(name, () -> { log.coreInvoked.set(true); return "result"; }, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new SupplierWrapper<>(name, (SupplierWrapper<String>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) { return ((Supplier<String>) wrapper).get(); }
    };

    static final WrapperScenario CALLABLE_SCENARIO = new WrapperScenario() {
        @Override String displayName() { return "CallableWrapper"; }
        @Override BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            return new CallableWrapper<>(name, () -> { log.coreInvoked.set(true); return "result"; }, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new CallableWrapper<>(name, (CallableWrapper<String>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) throws Exception { return ((Callable<String>) wrapper).call(); }
    };

    static final WrapperScenario FUNCTION_SCENARIO = new WrapperScenario() {
        @Override String displayName() { return "FunctionWrapper"; }
        @Override BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            return new FunctionWrapper<>(name, (String s) -> { log.coreInvoked.set(true); return s.length(); }, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new FunctionWrapper<>(name, (FunctionWrapper<String, Integer>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) { return ((Function<String, Integer>) wrapper).apply("hello"); }
    };

    static final WrapperScenario JOINPOINT_SCENARIO = new WrapperScenario() {
        @Override String displayName() { return "JoinPointWrapper"; }
        @Override BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            return new JoinPointWrapper<>(name, () -> { log.coreInvoked.set(true); return "result"; }, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new JoinPointWrapper<>(name, (JoinPointWrapper<String>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) throws Throwable { return ((ProxyExecution<String>) wrapper).proceed(); }
    };

    static Stream<Arguments> allWrapperTypes() {
        return Stream.of(
            Arguments.of(RUNNABLE_SCENARIO),
            Arguments.of(SUPPLIER_SCENARIO),
            Arguments.of(CALLABLE_SCENARIO),
            Arguments.of(FUNCTION_SCENARIO),
            Arguments.of(JOINPOINT_SCENARIO)
        );
    }

    // =========================================================================
    // Test Categories
    // =========================================================================

    @Nested
    @DisplayName("Construction")
    class Construction {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should reject a null name for all wrapper types")
        void should_reject_a_null_name_for_all_wrapper_types(WrapperScenario scenario) {
            assertThatThrownBy(() -> {
                switch (scenario.displayName()) {
                    case "RunnableWrapper" -> new RunnableWrapper((String) null, () -> {});
                    case "SupplierWrapper" -> new SupplierWrapper<>((String) null, () -> "x");
                    case "CallableWrapper" -> new CallableWrapper<>((String) null, () -> "x");
                    case "FunctionWrapper" -> new FunctionWrapper<>((String) null, Function.identity());
                    case "JoinPointWrapper" -> new JoinPointWrapper<>((String) null, () -> "x");
                    default -> throw new IllegalStateException("Unknown scenario");
                }
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Name must not be null");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should reject a null delegate for all wrapper types")
        void should_reject_a_null_delegate_for_all_wrapper_types(WrapperScenario scenario) {
            assertThatThrownBy(() -> {
                switch (scenario.displayName()) {
                    case "RunnableWrapper" -> new RunnableWrapper("test", null);
                    case "SupplierWrapper" -> new SupplierWrapper<String>("test", null);
                    case "CallableWrapper" -> new CallableWrapper<String>("test", null);
                    case "FunctionWrapper" -> new FunctionWrapper<String, String>("test", null);
                    case "JoinPointWrapper" -> new JoinPointWrapper<String>("test", null);
                    default -> throw new IllegalStateException("Unknown scenario");
                }
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Delegate must not be null");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should generate a positive chain id for a single wrapper")
        void should_generate_a_positive_chain_id_for_a_single_wrapper(WrapperScenario scenario) {
            // Given / When
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("layer", log);

            // Then
            assertThat(wrapper.getChainId()).isGreaterThan(0L);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should inherit the chain id from the inner wrapper")
        void should_inherit_the_chain_id_from_the_inner_wrapper(WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);

            // When
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // Then
            assertThat(outer.getChainId()).isEqualTo(inner.getChainId());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should assign different chain ids to independent chains")
        void should_assign_different_chain_ids_to_independent_chains(WrapperScenario scenario) {
            // Given / When
            ExecutionLog log1 = new ExecutionLog();
            ExecutionLog log2 = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> chain1 = scenario.createSingle("chain1", log1);
            BaseWrapper<?, ?, ?, ?> chain2 = scenario.createSingle("chain2", log2);

            // Then
            assertThat(chain1.getChainId()).isNotEqualTo(chain2.getChainId());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should store the layer description provided at construction")
        void should_store_the_layer_description_provided_at_construction(WrapperScenario scenario) {
            // Given / When
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("my-custom-layer", log);

            // Then
            assertThat(wrapper.getLayerDescription()).isEqualTo("my-custom-layer");
        }
    }

    @Nested
    @DisplayName("Chain Execution")
    class ChainExecution {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should invoke the core delegate when called on a single wrapper")
        void should_invoke_the_core_delegate_when_called_on_a_single_wrapper(WrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("layer", log);

            // When
            scenario.invoke(wrapper);

            // Then
            assertThat(log.coreInvoked).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should call layer actions in outer-to-inner order")
        void should_call_layer_actions_in_outer_to_inner_order(WrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            BaseWrapper<?, ?, ?, ?> middle = scenario.wrapAround("middle", inner, log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", middle, log);

            // When
            scenario.invoke(outer);

            // Then
            assertThat(log.layerNames).containsExactly("outer", "middle", "inner");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should pass the same call id to all layers in a single invocation")
        void should_pass_the_same_call_id_to_all_layers_in_a_single_invocation(WrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // When
            scenario.invoke(outer);

            // Then
            assertThat(log.callIds).hasSize(2);
            assertThat(log.callIds.get(0)).isEqualTo(log.callIds.get(1));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should generate a different call id for each invocation")
        void should_generate_a_different_call_id_for_each_invocation(WrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("layer", log);

            // When
            scenario.invoke(wrapper);
            long firstCallId = log.callIds.get(0);
            log.callIds.clear();
            scenario.invoke(wrapper);
            long secondCallId = log.callIds.get(0);

            // Then
            assertThat(firstCallId).isNotEqualTo(secondCallId);
        }

        @Test
        @DisplayName("should pass the function argument through all layers to the core delegate")
        void should_pass_the_function_argument_through_all_layers_to_the_core_delegate() {
            // Given
            AtomicReference<String> receivedInput = new AtomicReference<>();
            Function<String, Integer> core = s -> { receivedInput.set(s); return s.length(); };
            FunctionWrapper<String, Integer> inner = new FunctionWrapper<>("inner", core);
            FunctionWrapper<String, Integer> outer = new FunctionWrapper<>("outer", inner);

            // When
            int result = outer.apply("hello");

            // Then
            assertThat(receivedInput.get()).isEqualTo("hello");
            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("should return the core supplier value through the chain")
        void should_return_the_core_supplier_value_through_the_chain() {
            // Given
            SupplierWrapper<String> inner = new SupplierWrapper<>("inner", () -> "payload");
            SupplierWrapper<String> outer = new SupplierWrapper<>("outer", inner);

            // When / Then
            assertThat(outer.get()).isEqualTo("payload");
        }

        @Test
        @DisplayName("should return the core callable value through the chain")
        void should_return_the_core_callable_value_through_the_chain() throws Exception {
            // Given
            CallableWrapper<Integer> inner = new CallableWrapper<>("inner", () -> 42);
            CallableWrapper<Integer> outer = new CallableWrapper<>("outer", inner);

            // When / Then
            assertThat(outer.call()).isEqualTo(42);
        }

        @Test
        @DisplayName("should return the core join point value through the chain")
        void should_return_the_core_join_point_value_through_the_chain() throws Throwable {
            // Given
            JoinPointWrapper<String> inner = new JoinPointWrapper<>("inner", () -> "aop-result");
            JoinPointWrapper<String> outer = new JoinPointWrapper<>("outer", inner);

            // When / Then
            assertThat(outer.proceed()).isEqualTo("aop-result");
        }
    }

    @Nested
    @DisplayName("Around Semantics")
    class AroundSemantics {

        @Test
        @DisplayName("should execute pre-processing before and post-processing after the core")
        void should_execute_pre_and_post_processing_around_the_core() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            SupplierWrapper<String> wrapper = new SupplierWrapper<>("around", () -> {
                events.add("core");
                return "value";
            }, (chainId, callId, arg, next) -> {
                events.add("before");
                String result = next.execute(chainId, callId, arg);
                events.add("after");
                return result;
            });

            // When
            String result = wrapper.get();

            // Then
            assertThat(result).isEqualTo("value");
            assertThat(events).containsExactly("before", "core", "after");
        }

        @Test
        @DisplayName("should allow a layer to measure execution time of the remaining chain")
        void should_allow_a_layer_to_measure_execution_time_of_the_remaining_chain() {
            // Given
            AtomicReference<Long> elapsed = new AtomicReference<>();
            RunnableWrapper wrapper = new RunnableWrapper("timed", () -> {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }, (chainId, callId, arg, next) -> {
                long start = System.nanoTime();
                Void result = next.execute(chainId, callId, arg);
                elapsed.set(System.nanoTime() - start);
                return result;
            });

            // When
            wrapper.run();

            // Then
            assertThat(elapsed.get()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("should allow a layer to catch exceptions and return a fallback value")
        void should_allow_a_layer_to_catch_exceptions_and_return_a_fallback_value() {
            // Given
            SupplierWrapper<String> wrapper = new SupplierWrapper<>("resilient", () -> {
                throw new RuntimeException("boom");
            }, (chainId, callId, arg, next) -> {
                try {
                    return next.execute(chainId, callId, arg);
                } catch (Exception e) {
                    return "fallback";
                }
            });

            // When
            String result = wrapper.get();

            // Then
            assertThat(result).isEqualTo("fallback");
        }

        @Test
        @DisplayName("should allow a layer to skip the chain entirely and return a cached value")
        void should_allow_a_layer_to_skip_the_chain_entirely_and_return_a_cached_value() {
            // Given
            AtomicBoolean coreInvoked = new AtomicBoolean(false);
            SupplierWrapper<String> wrapper = new SupplierWrapper<>("cached", () -> {
                coreInvoked.set(true);
                return "from-core";
            }, (chainId, callId, arg, next) -> {
                // Skip the chain entirely — return cached value
                return "from-cache";
            });

            // When
            String result = wrapper.get();

            // Then
            assertThat(result).isEqualTo("from-cache");
            assertThat(coreInvoked).isFalse();
        }

        @Test
        @DisplayName("should allow a layer to retry the chain on failure")
        void should_allow_a_layer_to_retry_the_chain_on_failure() {
            // Given
            List<Integer> attempts = Collections.synchronizedList(new ArrayList<>());
            SupplierWrapper<String> wrapper = new SupplierWrapper<>("retry", () -> {
                attempts.add(attempts.size() + 1);
                if (attempts.size() < 3) {
                    throw new RuntimeException("transient failure #" + attempts.size());
                }
                return "success";
            }, (chainId, callId, arg, next) -> {
                // Retry up to 3 times
                RuntimeException lastException = null;
                for (int i = 0; i < 3; i++) {
                    try {
                        return next.execute(chainId, callId, arg);
                    } catch (RuntimeException e) {
                        lastException = e;
                    }
                }
                throw lastException;
            });

            // When
            String result = wrapper.get();

            // Then
            assertThat(result).isEqualTo("success");
            assertThat(attempts).hasSize(3);
        }

        @Test
        @DisplayName("should allow a layer to transform the argument before forwarding")
        void should_allow_a_layer_to_transform_the_argument_before_forwarding() {
            // Given
            FunctionWrapper<String, String> wrapper = new FunctionWrapper<>("normalizer",
                String::toUpperCase,
                (chainId, callId, input, next) -> {
                    // Trim whitespace before passing to the core
                    return next.execute(chainId, callId, input.trim());
                }
            );

            // When
            String result = wrapper.apply("  hello  ");

            // Then
            assertThat(result).isEqualTo("HELLO");
        }

        @Test
        @DisplayName("should allow a layer to transform the result after execution")
        void should_allow_a_layer_to_transform_the_result_after_execution() {
            // Given
            SupplierWrapper<String> wrapper = new SupplierWrapper<>("postprocessor",
                () -> "hello",
                (chainId, callId, arg, next) -> {
                    String result = next.execute(chainId, callId, arg);
                    return result.toUpperCase();
                }
            );

            // When / Then
            assertThat(wrapper.get()).isEqualTo("HELLO");
        }

        @Test
        @DisplayName("should compose multiple layers with independent around-advice")
        void should_compose_multiple_layers_with_independent_around_advice() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());

            SupplierWrapper<String> core = new SupplierWrapper<>("core", () -> {
                events.add("core-exec");
                return "value";
            });

            SupplierWrapper<String> inner = new SupplierWrapper<>("metrics", core,
                (chainId, callId, arg, next) -> {
                    events.add("metrics-before");
                    String result = next.execute(chainId, callId, arg);
                    events.add("metrics-after");
                    return result;
                }
            );

            SupplierWrapper<String> outer = new SupplierWrapper<>("logging", inner,
                (chainId, callId, arg, next) -> {
                    events.add("logging-before");
                    String result = next.execute(chainId, callId, arg);
                    events.add("logging-after");
                    return result;
                }
            );

            // When
            String result = outer.get();

            // Then — classic onion model: logging wraps metrics wraps core
            assertThat(result).isEqualTo("value");
            assertThat(events).containsExactly(
                "logging-before", "metrics-before", "core-exec", "metrics-after", "logging-after"
            );
        }

        @Test
        @DisplayName("should provide chain id and call id to the layer action")
        void should_provide_chain_id_and_call_id_to_the_layer_action() {
            // Given
            AtomicReference<Long> capturedChainId = new AtomicReference<>();
            AtomicReference<Long> capturedCallId = new AtomicReference<>();

            SupplierWrapper<String> wrapper = new SupplierWrapper<>("tracing", () -> "ok",
                (chainId, callId, arg, next) -> {
                    capturedChainId.set(chainId);
                    capturedCallId.set(callId);
                    return next.execute(chainId, callId, arg);
                }
            );

            // When
            wrapper.get();

            // Then
            assertThat(capturedChainId.get()).isEqualTo(wrapper.getChainId());
            assertThat(capturedCallId.get()).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("Delegate Reuse")
    class DelegateReuse {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should allow the same inner wrapper to be used in multiple independent chains")
        void should_allow_the_same_inner_wrapper_to_be_used_in_multiple_independent_chains(
            WrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog sharedLog = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> shared = scenario.createSingle("shared-inner", sharedLog);

            ExecutionLog logA = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> outerA = scenario.wrapAround("chain-A", shared, logA);

            ExecutionLog logB = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> outerB = scenario.wrapAround("chain-B", shared, logB);

            // When
            scenario.invoke(outerA);
            sharedLog.layerNames.clear();
            scenario.invoke(outerB);

            // Then
            assertThat(logA.layerNames).contains("chain-A");
            assertThat(logB.layerNames).contains("chain-B");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should share the same chain id when wrapping the same inner delegate")
        void should_share_the_same_chain_id_when_wrapping_the_same_inner_delegate(WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> shared = scenario.createSingle("shared", log);

            // When
            BaseWrapper<?, ?, ?, ?> outerA = scenario.wrapAround("A", shared, log);
            BaseWrapper<?, ?, ?, ?> outerB = scenario.wrapAround("B", shared, log);

            // Then
            assertThat(outerA.getChainId())
                .isEqualTo(outerB.getChainId())
                .isEqualTo(shared.getChainId());
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandling {

        @Nested
        @DisplayName("RuntimeException Propagation")
        class RuntimeExceptionPropagation {

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through RunnableWrapper")
            void should_propagate_RuntimeException_unwrapped_through_RunnableWrapper() {
                // Given
                IllegalStateException expected = new IllegalStateException("boom");
                RunnableWrapper wrapper = new RunnableWrapper("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::run)).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through SupplierWrapper")
            void should_propagate_RuntimeException_unwrapped_through_SupplierWrapper() {
                // Given
                IllegalArgumentException expected = new IllegalArgumentException("bad input");
                SupplierWrapper<String> wrapper = new SupplierWrapper<>("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::get)).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through CallableWrapper")
            void should_propagate_RuntimeException_unwrapped_through_CallableWrapper() {
                // Given
                IllegalStateException expected = new IllegalStateException("runtime");
                CallableWrapper<String> wrapper = new CallableWrapper<>("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::call)).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through FunctionWrapper")
            void should_propagate_RuntimeException_unwrapped_through_FunctionWrapper() {
                // Given
                UnsupportedOperationException expected = new UnsupportedOperationException("nope");
                FunctionWrapper<String, Integer> wrapper = new FunctionWrapper<>("layer", s -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(() -> wrapper.apply("test"))).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through JoinPointWrapper")
            void should_propagate_RuntimeException_unwrapped_through_JoinPointWrapper() {
                // Given
                IllegalStateException expected = new IllegalStateException("proxy-boom");
                JoinPointWrapper<String> wrapper = new JoinPointWrapper<>("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::proceed)).isSameAs(expected);
            }
        }

        @Nested
        @DisplayName("Checked Exception Handling")
        class CheckedExceptionHandling {

            @Test
            @DisplayName("should unwrap checked exception from CallableWrapper preserving the original type")
            void should_unwrap_checked_exception_from_CallableWrapper_preserving_the_original_type() {
                // Given
                IOException expected = new IOException("file not found");
                CallableWrapper<String> wrapper = new CallableWrapper<>("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::call)).isSameAs(expected);
            }

            @Test
            @DisplayName("should unwrap checked throwable from JoinPointWrapper preserving the original type")
            void should_unwrap_checked_throwable_from_JoinPointWrapper_preserving_the_original_type() {
                // Given
                IOException expected = new IOException("proxy io error");
                JoinPointWrapper<String> wrapper = new JoinPointWrapper<>("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::proceed)).isSameAs(expected);
            }
        }

        @Nested
        @DisplayName("Error Propagation")
        class ErrorPropagation {

            @Test
            @DisplayName("should propagate Error unwrapped through CallableWrapper")
            void should_propagate_Error_unwrapped_through_CallableWrapper() {
                // Given
                OutOfMemoryError expected = new OutOfMemoryError("heap");
                CallableWrapper<String> wrapper = new CallableWrapper<>("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::call)).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate Error unwrapped through JoinPointWrapper")
            void should_propagate_Error_unwrapped_through_JoinPointWrapper() {
                // Given
                OutOfMemoryError expected = new OutOfMemoryError("heap");
                JoinPointWrapper<String> wrapper = new JoinPointWrapper<>("layer", () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::proceed)).isSameAs(expected);
            }
        }
    }

    @Nested
    @DisplayName("Hierarchy Visualization")
    class HierarchyVisualization {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should render a single layer hierarchy with chain id and layer name")
        void should_render_a_single_layer_hierarchy_with_chain_id_and_layer_name(WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("my-layer", log);

            // When
            String hierarchy = wrapper.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                .startsWith("Chain-ID: ")
                .contains(Long.toString(wrapper.getChainId()))
                .contains("my-layer");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should render a multi-layer hierarchy in outer-to-inner order")
        void should_render_a_multi_layer_hierarchy_in_outer_to_inner_order(WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("Security", log);
            BaseWrapper<?, ?, ?, ?> middle = scenario.wrapAround("Metrics", inner, log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("Logging", middle, log);

            // When
            String hierarchy = outer.toStringHierarchy();

            // Then
            assertThat(hierarchy.indexOf("Logging")).isLessThan(hierarchy.indexOf("Metrics"));
            assertThat(hierarchy.indexOf("Metrics")).isLessThan(hierarchy.indexOf("Security"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should return null for getInner when there is no inner wrapper")
        void should_return_null_for_getInner_when_there_is_no_inner_wrapper(WrapperScenario scenario) {
            // Given / When
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("leaf", log);

            // Then
            assertThat(wrapper.getInner()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should return the correct inner wrapper via getInner in a chain")
        void should_return_the_correct_inner_wrapper_via_getInner_in_a_chain(WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // When / Then
            assertThat(outer.getInner()).isSameAs(inner);
        }

        @Test
        @DisplayName("should truncate the hierarchy when depth exceeds the safety limit")
        void should_truncate_the_hierarchy_when_depth_exceeds_the_safety_limit() {
            // Given
            RunnableWrapper current = new RunnableWrapper("layer-0", () -> {});
            for (int i = 1; i <= 105; i++) {
                current = new RunnableWrapper("layer-" + i, current);
            }

            // When / Then
            assertThat(current.toStringHierarchy()).contains("chain truncated at depth 100");
        }
    }

    @Nested
    @DisplayName("Call ID Generation")
    class CallIdGeneration {

        @Test
        @DisplayName("should use the custom call id generator when overridden")
        void should_use_the_custom_call_id_generator_when_overridden() {
            // Given
            ExecutionLog log = new ExecutionLog();
            long customId = 999_001L;
            RunnableWrapper wrapper = new RunnableWrapper("layer", () -> {}, trackingAction("layer", log)) {
                @Override protected long generateCallId() { return customId; }
            };

            // When
            wrapper.run();

            // Then
            assertThat(log.callIds).containsExactly(customId);
        }

        @Test
        @DisplayName("should propagate the custom call id through all layers in a chain")
        void should_propagate_the_custom_call_id_through_all_layers_in_a_chain() {
            // Given
            ExecutionLog log = new ExecutionLog();
            long customId = 42L;
            RunnableWrapper inner = new RunnableWrapper("inner", () -> {}, trackingAction("inner", log));
            RunnableWrapper outer = new RunnableWrapper("outer", inner, trackingAction("outer", log)) {
                @Override protected long generateCallId() { return customId; }
            };

            // When
            outer.run();

            // Then
            assertThat(log.callIds).hasSize(2).containsOnly(customId);
        }
    }
}
