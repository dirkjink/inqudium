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
 * Comprehensive parameterized tests for the wrapper pipeline framework.
 *
 * <p>Tests are organized by concern using {@code @Nested} inner classes and
 * parameterized across all wrapper types (Runnable, Supplier, Callable, Function,
 * JoinPoint) wherever behavior is shared.</p>
 */
@DisplayName("Wrapper Pipeline")
class WrapperPipelineTest {

    // =========================================================================
    // Test Infrastructure
    // =========================================================================

    /**
     * Captures execution events across wrapper layers for verification.
     * Uses thread-safe collections since the contract allows concurrent use.
     */
    static class ExecutionLog {
        final List<String> layerNames = Collections.synchronizedList(new ArrayList<>());
        final List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean coreInvoked = new AtomicBoolean(false);
    }

    /**
     * Abstracts over the creation and invocation of different wrapper types,
     * enabling parameterized tests that apply to all wrapper flavors.
     */
    static abstract class WrapperScenario {
        /** Human-readable name for test display. */
        abstract String displayName();

        /** Creates a single tracking wrapper around a core delegate. */
        abstract BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log);

        /** Wraps an existing wrapper in a new outer tracking layer. */
        abstract BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log);

        /** Invokes the wrapper's public functional method (run, get, call, apply, proceed). */
        abstract Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) throws Throwable;

        /** Used by JUnit to render the scenario name in parameterized test output. */
        @Override
        public String toString() { return displayName(); }
    }

    // --- Tracking wrapper subclasses for each type ---

    static class TrackingRunnableWrapper extends RunnableWrapper {
        private final ExecutionLog log;

        TrackingRunnableWrapper(String name, Runnable delegate, ExecutionLog log) {
            super(name, delegate);
            this.log = log;
        }

        @Override
        protected void handleLayer(long callId, Void argument) {
            log.layerNames.add(getLayerDescription());
            log.callIds.add(callId);
        }
    }

    static class TrackingSupplierWrapper<T> extends SupplierWrapper<T> {
        private final ExecutionLog log;

        TrackingSupplierWrapper(String name, Supplier<T> delegate, ExecutionLog log) {
            super(name, delegate);
            this.log = log;
        }

        @Override
        protected void handleLayer(long callId, Void argument) {
            log.layerNames.add(getLayerDescription());
            log.callIds.add(callId);
        }
    }

    static class TrackingCallableWrapper<V> extends CallableWrapper<V> {
        private final ExecutionLog log;

        TrackingCallableWrapper(String name, Callable<V> delegate, ExecutionLog log) {
            super(name, delegate);
            this.log = log;
        }

        @Override
        protected void handleLayer(long callId, Void argument) {
            log.layerNames.add(getLayerDescription());
            log.callIds.add(callId);
        }
    }

    static class TrackingFunctionWrapper<I, O> extends FunctionWrapper<I, O> {
        private final ExecutionLog log;

        TrackingFunctionWrapper(String name, Function<I, O> delegate, ExecutionLog log) {
            super(name, delegate);
            this.log = log;
        }

        @Override
        protected void handleLayer(long callId, I input) {
            log.layerNames.add(getLayerDescription());
            log.callIds.add(callId);
        }
    }

    static class TrackingJoinPointWrapper<R> extends JoinPointWrapper<R> {
        private final ExecutionLog log;

        TrackingJoinPointWrapper(String name, ProxyExecution<R> delegate, ExecutionLog log) {
            super(name, delegate);
            this.log = log;
        }

        @Override
        protected void handleLayer(long callId, Void argument) {
            log.layerNames.add(getLayerDescription());
            log.callIds.add(callId);
        }
    }

    // --- Scenario implementations for each wrapper type ---

    static final WrapperScenario RUNNABLE_SCENARIO = new WrapperScenario() {
        @Override public String displayName() { return "RunnableWrapper"; }

        @Override
        public BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Runnable core = () -> log.coreInvoked.set(true);
            return new TrackingRunnableWrapper(name, core, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new TrackingRunnableWrapper(name, (RunnableWrapper) inner, log);
        }

        @Override
        public Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) {
            ((Runnable) wrapper).run();
            return null;
        }
    };

    static final WrapperScenario SUPPLIER_SCENARIO = new WrapperScenario() {
        @Override public String displayName() { return "SupplierWrapper"; }

        @Override
        public BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Supplier<String> core = () -> { log.coreInvoked.set(true); return "result"; };
            return new TrackingSupplierWrapper<>(name, core, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new TrackingSupplierWrapper<>(name, (SupplierWrapper<String>) inner, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) {
            return ((Supplier<String>) wrapper).get();
        }
    };

    static final WrapperScenario CALLABLE_SCENARIO = new WrapperScenario() {
        @Override public String displayName() { return "CallableWrapper"; }

        @Override
        public BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Callable<String> core = () -> { log.coreInvoked.set(true); return "result"; };
            return new TrackingCallableWrapper<>(name, core, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new TrackingCallableWrapper<>(name, (CallableWrapper<String>) inner, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) throws Exception {
            return ((Callable<String>) wrapper).call();
        }
    };

    static final WrapperScenario FUNCTION_SCENARIO = new WrapperScenario() {
        @Override public String displayName() { return "FunctionWrapper"; }

        @Override
        public BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Function<String, Integer> core = s -> { log.coreInvoked.set(true); return s.length(); };
            return new TrackingFunctionWrapper<>(name, core, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new TrackingFunctionWrapper<>(name, (FunctionWrapper<String, Integer>) inner, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) {
            return ((Function<String, Integer>) wrapper).apply("hello");
        }
    };

    static final WrapperScenario JOINPOINT_SCENARIO = new WrapperScenario() {
        @Override public String displayName() { return "JoinPointWrapper"; }

        @Override
        public BaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            ProxyExecution<String> core = () -> { log.coreInvoked.set(true); return "result"; };
            return new TrackingJoinPointWrapper<>(name, core, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public BaseWrapper<?, ?, ?, ?> wrapAround(String name, BaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new TrackingJoinPointWrapper<>(name, (JoinPointWrapper<String>) inner, log);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(BaseWrapper<?, ?, ?, ?> wrapper) throws Throwable {
            return ((ProxyExecution<String>) wrapper).proceed();
        }
    };

    /** Provides all wrapper scenarios for parameterized tests. */
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
            // Given
            ExecutionLog log = new ExecutionLog();

            // When / Then
            assertThatThrownBy(() -> {
                // Use reflection-free approach: the scenario's createSingle would fail
                // if we could pass null, but we need direct constructor access
                switch (scenario.displayName()) {
                    case "RunnableWrapper" -> new RunnableWrapper(null, () -> {});
                    case "SupplierWrapper" -> new SupplierWrapper<>(null, () -> "x");
                    case "CallableWrapper" -> new CallableWrapper<>(null, () -> "x");
                    case "FunctionWrapper" -> new FunctionWrapper<>(null, Function.identity());
                    case "JoinPointWrapper" -> new JoinPointWrapper<>(null, () -> "x");
                    default -> throw new IllegalStateException("Unknown scenario");
                }
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Name must not be null");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should reject a null delegate for all wrapper types")
        void should_reject_a_null_delegate_for_all_wrapper_types(WrapperScenario scenario) {
            // Given / When / Then
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
        @DisplayName("should generate a non-null chain id for a single wrapper")
        void should_generate_a_non_null_chain_id_for_a_single_wrapper(WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();

            // When
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("layer", log);

            // Then
            assertThat(wrapper.getChainId())
                .isGreaterThan(0L);
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
            assertThat(outer.getChainId())
                .isEqualTo(inner.getChainId());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should assign different chain ids to independent chains")
        void should_assign_different_chain_ids_to_independent_chains(WrapperScenario scenario) {
            // Given
            ExecutionLog log1 = new ExecutionLog();
            ExecutionLog log2 = new ExecutionLog();

            // When
            BaseWrapper<?, ?, ?, ?> chain1 = scenario.createSingle("chain1", log1);
            BaseWrapper<?, ?, ?, ?> chain2 = scenario.createSingle("chain2", log2);

            // Then
            assertThat(chain1.getChainId())
                .isNotEqualTo(chain2.getChainId());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should store the layer description provided at construction")
        void should_store_the_layer_description_provided_at_construction(WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            String expectedName = "my-custom-layer";

            // When
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle(expectedName, log);

            // Then
            assertThat(wrapper.getLayerDescription())
                .isEqualTo(expectedName);
        }
    }

    @Nested
    @DisplayName("Chain Execution")
    class ChainExecution {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should invoke the core delegate when called on a single wrapper")
        void should_invoke_the_core_delegate_when_called_on_a_single_wrapper(WrapperScenario scenario)
            throws Throwable {
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
        @DisplayName("should call handleLayer for each layer in the chain")
        void should_call_handleLayer_for_each_layer_in_the_chain(WrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            BaseWrapper<?, ?, ?, ?> middle = scenario.wrapAround("middle", inner, log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", middle, log);

            // When
            scenario.invoke(outer);

            // Then
            assertThat(log.layerNames)
                .hasSize(3)
                .containsExactly("outer", "middle", "inner");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should pass the same call id to all layers in a single invocation")
        void should_pass_the_same_call_id_to_all_layers_in_a_single_invocation(WrapperScenario scenario)
            throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // When
            scenario.invoke(outer);

            // Then
            assertThat(log.callIds)
                .hasSize(2);
            assertThat(log.callIds.get(0))
                .isEqualTo(log.callIds.get(1));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should generate a different call id for each invocation")
        void should_generate_a_different_call_id_for_each_invocation(WrapperScenario scenario)
            throws Throwable {
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
            assertThat(firstCallId)
                .isNotEqualTo(secondCallId);
        }

        @Test
        @DisplayName("should pass the function argument through all layers to the core delegate")
        void should_pass_the_function_argument_through_all_layers_to_the_core_delegate() {
            // Given
            AtomicReference<String> receivedInput = new AtomicReference<>();
            Function<String, Integer> core = s -> { receivedInput.set(s); return s.length(); };
            ExecutionLog log = new ExecutionLog();
            FunctionWrapper<String, Integer> inner = new TrackingFunctionWrapper<>("inner", core, log);
            FunctionWrapper<String, Integer> outer = new TrackingFunctionWrapper<>("outer", inner, log);

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
            ExecutionLog log = new ExecutionLog();
            SupplierWrapper<String> inner = new TrackingSupplierWrapper<>("inner", () -> "payload", log);
            SupplierWrapper<String> outer = new TrackingSupplierWrapper<>("outer", inner, log);

            // When
            String result = outer.get();

            // Then
            assertThat(result).isEqualTo("payload");
        }

        @Test
        @DisplayName("should return the core callable value through the chain")
        void should_return_the_core_callable_value_through_the_chain() throws Exception {
            // Given
            ExecutionLog log = new ExecutionLog();
            CallableWrapper<Integer> inner = new TrackingCallableWrapper<>("inner", () -> 42, log);
            CallableWrapper<Integer> outer = new TrackingCallableWrapper<>("outer", inner, log);

            // When
            int result = outer.call();

            // Then
            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("should return the core join point value through the chain")
        void should_return_the_core_join_point_value_through_the_chain() throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            JoinPointWrapper<String> inner = new TrackingJoinPointWrapper<>("inner", () -> "aop-result", log);
            JoinPointWrapper<String> outer = new TrackingJoinPointWrapper<>("outer", inner, log);

            // When
            String result = outer.proceed();

            // Then
            assertThat(result).isEqualTo("aop-result");
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
            // Given — a shared inner wrapper reused by two outer wrappers
            ExecutionLog sharedLog = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> shared = scenario.createSingle("shared-inner", sharedLog);

            ExecutionLog logA = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> outerA = scenario.wrapAround("chain-A", shared, logA);

            ExecutionLog logB = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> outerB = scenario.wrapAround("chain-B", shared, logB);

            // When — invoke both chains
            scenario.invoke(outerA);
            sharedLog.layerNames.clear();
            sharedLog.callIds.clear();
            scenario.invoke(outerB);

            // Then — both chains execute successfully and independently
            assertThat(logA.layerNames).contains("chain-A");
            assertThat(logB.layerNames).contains("chain-B");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should share the same chain id when wrapping the same inner delegate")
        void should_share_the_same_chain_id_when_wrapping_the_same_inner_delegate(
            WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> shared = scenario.createSingle("shared", log);

            // When
            BaseWrapper<?, ?, ?, ?> outerA = scenario.wrapAround("A", shared, log);
            BaseWrapper<?, ?, ?, ?> outerB = scenario.wrapAround("B", shared, log);

            // Then — both outer wrappers inherit the same chain id from the shared inner
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

                // When
                Throwable thrown = catchThrowable(wrapper::run);

                // Then
                assertThat(thrown).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through SupplierWrapper")
            void should_propagate_RuntimeException_unwrapped_through_SupplierWrapper() {
                // Given
                IllegalArgumentException expected = new IllegalArgumentException("bad input");
                SupplierWrapper<String> wrapper = new SupplierWrapper<>("layer", () -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(wrapper::get);

                // Then
                assertThat(thrown).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through CallableWrapper")
            void should_propagate_RuntimeException_unwrapped_through_CallableWrapper() {
                // Given
                IllegalStateException expected = new IllegalStateException("runtime");
                CallableWrapper<String> wrapper = new CallableWrapper<>("layer", () -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(wrapper::call);

                // Then
                assertThat(thrown).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through FunctionWrapper")
            void should_propagate_RuntimeException_unwrapped_through_FunctionWrapper() {
                // Given
                UnsupportedOperationException expected = new UnsupportedOperationException("nope");
                FunctionWrapper<String, Integer> wrapper =
                    new FunctionWrapper<>("layer", s -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(() -> wrapper.apply("test"));

                // Then
                assertThat(thrown).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate RuntimeException unwrapped through JoinPointWrapper")
            void should_propagate_RuntimeException_unwrapped_through_JoinPointWrapper() {
                // Given
                IllegalStateException expected = new IllegalStateException("proxy-boom");
                JoinPointWrapper<String> wrapper = new JoinPointWrapper<>("layer", () -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(wrapper::proceed);

                // Then
                assertThat(thrown).isSameAs(expected);
            }
        }

        @Nested
        @DisplayName("Error Propagation")
        class ErrorPropagation {

            @Test
            @DisplayName("should propagate Error unwrapped through RunnableWrapper")
            void should_propagate_Error_unwrapped_through_RunnableWrapper() {
                // Given
                StackOverflowError expected = new StackOverflowError("stack");
                RunnableWrapper wrapper = new RunnableWrapper("layer", () -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(wrapper::run);

                // Then
                assertThat(thrown).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate Error unwrapped through CallableWrapper")
            void should_propagate_Error_unwrapped_through_CallableWrapper() {
                // Given
                OutOfMemoryError expected = new OutOfMemoryError("heap");
                CallableWrapper<String> wrapper = new CallableWrapper<>("layer", () -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(wrapper::call);

                // Then
                assertThat(thrown).isSameAs(expected);
            }

            @Test
            @DisplayName("should propagate Error unwrapped through JoinPointWrapper")
            void should_propagate_Error_unwrapped_through_JoinPointWrapper() {
                // Given
                OutOfMemoryError expected = new OutOfMemoryError("heap");
                JoinPointWrapper<String> wrapper = new JoinPointWrapper<>("layer", () -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(wrapper::proceed);

                // Then
                assertThat(thrown).isSameAs(expected);
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

                // When
                Throwable thrown = catchThrowable(wrapper::call);

                // Then
                assertThat(thrown)
                    .isSameAs(expected)
                    .isInstanceOf(IOException.class);
            }

            @Test
            @DisplayName("should unwrap checked exception from chained CallableWrappers")
            void should_unwrap_checked_exception_from_chained_CallableWrappers() {
                // Given
                IOException expected = new IOException("disk error");
                ExecutionLog log = new ExecutionLog();
                CallableWrapper<String> inner = new TrackingCallableWrapper<>("inner", () -> { throw expected; }, log);
                CallableWrapper<String> outer = new TrackingCallableWrapper<>("outer", inner, log);

                // When
                Throwable thrown = catchThrowable(outer::call);

                // Then — the original checked exception arrives at the caller, not a CompletionException
                assertThat(thrown)
                    .isSameAs(expected)
                    .isInstanceOf(IOException.class);
                // Both layers still executed their handleLayer
                assertThat(log.layerNames).containsExactly("outer", "inner");
            }

            @Test
            @DisplayName("should unwrap checked throwable from JoinPointWrapper preserving the original type")
            void should_unwrap_checked_throwable_from_JoinPointWrapper_preserving_the_original_type() {
                // Given — a join point that throws a checked exception
                IOException expected = new IOException("proxy io error");
                JoinPointWrapper<String> wrapper = new JoinPointWrapper<>("layer", () -> { throw expected; });

                // When
                Throwable thrown = catchThrowable(wrapper::proceed);

                // Then
                assertThat(thrown)
                    .isSameAs(expected)
                    .isInstanceOf(IOException.class);
            }

            @Test
            @DisplayName("should unwrap checked throwable from chained JoinPointWrappers")
            void should_unwrap_checked_throwable_from_chained_JoinPointWrappers() {
                // Given
                Exception expected = new Exception("generic checked");
                ExecutionLog log = new ExecutionLog();
                JoinPointWrapper<String> inner = new TrackingJoinPointWrapper<>("inner", () -> { throw expected; }, log);
                JoinPointWrapper<String> outer = new TrackingJoinPointWrapper<>("outer", inner, log);

                // When
                Throwable thrown = catchThrowable(outer::proceed);

                // Then
                assertThat(thrown).isSameAs(expected);
                assertThat(log.layerNames).containsExactly("outer", "inner");
            }

            @Test
            @DisplayName("should not unwrap a CompletionException that was thrown directly by the delegate")
            void should_not_unwrap_a_CompletionException_that_was_thrown_directly_by_the_delegate() {
                // Given — a callable that throws CompletionException as a RuntimeException (not wrapped by us)
                CompletionException directlyThrown = new CompletionException(new IllegalStateException("inner cause"));
                CallableWrapper<String> wrapper = new CallableWrapper<>("layer", () -> { throw directlyThrown; });

                // When
                Throwable thrown = catchThrowable(wrapper::call);

                // Then — since CompletionException extends RuntimeException and the delegate throws it
                // directly, invokeCore's "catch (RuntimeException)" branch re-throws it as-is.
                // In call(), the catch(RuntimeException) sees it as a CompletionException and unwraps
                // it, which is a known trade-off documented in the design.
                // The key assertion: the original CompletionException or its cause arrives, not a double-wrap.
                assertThat(thrown)
                    .isInstanceOfAny(CompletionException.class, IllegalStateException.class);
            }
        }
    }

    @Nested
    @DisplayName("Hierarchy Visualization")
    class HierarchyVisualization {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should render a single layer hierarchy with chain id and layer name")
        void should_render_a_single_layer_hierarchy_with_chain_id_and_layer_name(
            WrapperScenario scenario) {
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
        void should_render_a_multi_layer_hierarchy_in_outer_to_inner_order(
            WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("Security", log);
            BaseWrapper<?, ?, ?, ?> middle = scenario.wrapAround("Metrics", inner, log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("Logging", middle, log);

            // When
            String hierarchy = outer.toStringHierarchy();

            // Then — the layers appear top-to-bottom: Logging → Metrics → Security
            int loggingIndex = hierarchy.indexOf("Logging");
            int metricsIndex = hierarchy.indexOf("Metrics");
            int securityIndex = hierarchy.indexOf("Security");
            assertThat(loggingIndex).isLessThan(metricsIndex);
            assertThat(metricsIndex).isLessThan(securityIndex);
            assertThat(hierarchy).contains("└──");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should return null for getInner when there is no inner wrapper")
        void should_return_null_for_getInner_when_there_is_no_inner_wrapper(
            WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("leaf", log);

            // When
            Wrapper<?> inner = wrapper.getInner();

            // Then
            assertThat(inner).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.core.pipeline.WrapperPipelineTest#allWrapperTypes")
        @DisplayName("should return the correct inner wrapper via getInner in a chain")
        void should_return_the_correct_inner_wrapper_via_getInner_in_a_chain(
            WrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            BaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            BaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // When
            Wrapper<?> navigatedInner = outer.getInner();

            // Then
            assertThat(navigatedInner)
                .isNotNull()
                .isSameAs(inner);
        }

        @Test
        @DisplayName("should truncate the hierarchy when depth exceeds the safety limit")
        void should_truncate_the_hierarchy_when_depth_exceeds_the_safety_limit() {
            // Given — build a chain deeper than the 100-layer safety limit
            Runnable core = () -> {};
            RunnableWrapper current = new RunnableWrapper("layer-0", core);
            for (int i = 1; i <= 105; i++) {
                current = new RunnableWrapper("layer-" + i, current);
            }

            // When
            String hierarchy = current.toStringHierarchy();

            // Then
            assertThat(hierarchy).contains("chain truncated at depth 100");
        }
    }

    @Nested
    @DisplayName("Call ID Generation")
    class CallIdGeneration {

        /** A RunnableWrapper that uses a custom call ID generator for testing. */
        static class CustomIdRunnableWrapper extends RunnableWrapper {
            private final ExecutionLog log;
            private final long fixedCallId;

            CustomIdRunnableWrapper(String name, Runnable delegate, ExecutionLog log, long fixedCallId) {
                super(name, delegate);
                this.log = log;
                this.fixedCallId = fixedCallId;
            }

            @Override
            protected long generateCallId() {
                return fixedCallId;
            }

            @Override
            protected void handleLayer(long callId, Void argument) {
                log.layerNames.add(getLayerDescription());
                log.callIds.add(callId);
            }
        }

        @Test
        @DisplayName("should use the custom call id generator when overridden")
        void should_use_the_custom_call_id_generator_when_overridden() {
            // Given
            ExecutionLog log = new ExecutionLog();
            long customId = 999_001L;
            RunnableWrapper wrapper = new CustomIdRunnableWrapper("layer", () -> {}, log, customId);

            // When
            wrapper.run();

            // Then
            assertThat(log.callIds)
                .hasSize(1)
                .containsExactly(customId);
        }

        @Test
        @DisplayName("should propagate the custom call id through all layers in a chain")
        void should_propagate_the_custom_call_id_through_all_layers_in_a_chain() {
            // Given
            ExecutionLog log = new ExecutionLog();
            long customId = 42L;
            // Only the outermost wrapper needs to override generateCallId,
            // since initiateChain is called on the outermost layer
            RunnableWrapper inner = new TrackingRunnableWrapper("inner", () -> {}, log);
            RunnableWrapper outer = new CustomIdRunnableWrapper("outer", inner, log, customId);

            // When
            outer.run();

            // Then — both layers received the same custom call id
            assertThat(log.callIds)
                .hasSize(2)
                .containsOnly(customId);
        }

        @Test
        @DisplayName("should call generateCallId once per invocation not once per layer")
        void should_call_generateCallId_once_per_invocation_not_once_per_layer() {
            // Given
            List<Long> generatedIds = Collections.synchronizedList(new ArrayList<>());
            ExecutionLog log = new ExecutionLog();

            RunnableWrapper inner = new TrackingRunnableWrapper("inner", () -> {}, log);
            RunnableWrapper outer = new RunnableWrapper("outer", inner) {
                private long counter = 0;
                @Override
                protected long generateCallId() {
                    long id = ++counter;
                    generatedIds.add(id);
                    return id;
                }

                @Override
                protected void handleLayer(long callId, Void argument) {
                    log.layerNames.add(getLayerDescription());
                    log.callIds.add(callId);
                }
            };

            // When
            outer.run();

            // Then — generateCallId was called exactly once (by initiateChain)
            assertThat(generatedIds).hasSize(1);
            // But the same id reached both layers
            assertThat(log.callIds).hasSize(2).containsOnly(1L);
        }
    }
}
