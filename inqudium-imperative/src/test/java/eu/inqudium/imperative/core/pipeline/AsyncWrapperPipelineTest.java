package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Comprehensive tests for the async wrapper pipeline, mirroring the structure
 * of {@code WrapperPipelineTest}.
 */
@DisplayName("Async Wrapper Pipeline")
class AsyncWrapperPipelineTest {

    // =========================================================================
    // Test Infrastructure
    // =========================================================================

    static class ExecutionLog {
        final List<String> layerNames = Collections.synchronizedList(new ArrayList<>());
        final List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean coreInvoked = new AtomicBoolean(false);
    }

    /**
     * Creates a tracking {@link AsyncLayerAction} that records layer name and call ID,
     * then forwards to the next async step.
     */
    static <A, R> AsyncLayerAction<A, R> trackingAction(String layerName, ExecutionLog log) {
        return (chainId, callId, argument, next) -> {
            log.layerNames.add(layerName);
            log.callIds.add(callId);
            return next.executeAsync(chainId, callId, argument);
        };
    }

    /** Completes a CompletionStage and returns the result, propagating exceptions. */
    @SuppressWarnings("unchecked")
    static <T> T join(CompletionStage<T> stage) {
        return ((CompletableFuture<T>) stage).join();
    }

    // =========================================================================
    // Parameterized Scenarios
    // =========================================================================

    static abstract class AsyncWrapperScenario {
        abstract String displayName();
        abstract AsyncBaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log);
        abstract AsyncBaseWrapper<?, ?, ?, ?> wrapAround(String name, AsyncBaseWrapper<?, ?, ?, ?> inner, ExecutionLog log);
        abstract CompletionStage<?> invoke(AsyncBaseWrapper<?, ?, ?, ?> wrapper) throws Throwable;
        @Override public String toString() { return displayName(); }
    }

    static final AsyncWrapperScenario ASYNC_RUNNABLE_SCENARIO = new AsyncWrapperScenario() {
        @Override String displayName() { return "AsyncRunnableWrapper"; }
        @Override AsyncBaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Runnable core = () -> log.coreInvoked.set(true);
            return new AsyncRunnableWrapper(name, core, trackingAction(name, log));
        }
        @Override AsyncBaseWrapper<?, ?, ?, ?> wrapAround(String name, AsyncBaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new AsyncRunnableWrapper(name, (AsyncRunnableWrapper) inner, trackingAction(name, log));
        }
        @Override CompletionStage<?> invoke(AsyncBaseWrapper<?, ?, ?, ?> wrapper) {
            return ((Supplier<CompletionStage<Void>>) wrapper).get();
        }
    };

    static final AsyncWrapperScenario ASYNC_SUPPLIER_SCENARIO = new AsyncWrapperScenario() {
        @Override String displayName() { return "AsyncSupplierWrapper"; }
        @Override AsyncBaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Supplier<CompletionStage<String>> core = () -> {
                log.coreInvoked.set(true);
                return CompletableFuture.completedFuture("result");
            };
            return new AsyncSupplierWrapper<>(name, core, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        AsyncBaseWrapper<?, ?, ?, ?> wrapAround(String name, AsyncBaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new AsyncSupplierWrapper<>(name, (AsyncSupplierWrapper<String>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        CompletionStage<?> invoke(AsyncBaseWrapper<?, ?, ?, ?> wrapper) {
            return ((Supplier<CompletionStage<String>>) wrapper).get();
        }
    };

    static final AsyncWrapperScenario ASYNC_CALLABLE_SCENARIO = new AsyncWrapperScenario() {
        @Override String displayName() { return "AsyncCallableWrapper"; }
        @Override AsyncBaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Callable<CompletionStage<String>> core = () -> {
                log.coreInvoked.set(true);
                return CompletableFuture.completedFuture("result");
            };
            return new AsyncCallableWrapper<>(name, core, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        AsyncBaseWrapper<?, ?, ?, ?> wrapAround(String name, AsyncBaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new AsyncCallableWrapper<>(name, (AsyncCallableWrapper<String>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        CompletionStage<?> invoke(AsyncBaseWrapper<?, ?, ?, ?> wrapper) throws Exception {
            return ((Callable<CompletionStage<String>>) wrapper).call();
        }
    };

    static final AsyncWrapperScenario ASYNC_FUNCTION_SCENARIO = new AsyncWrapperScenario() {
        @Override String displayName() { return "AsyncFunctionWrapper"; }
        @Override AsyncBaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            Function<String, CompletionStage<Integer>> core = s -> {
                log.coreInvoked.set(true);
                return CompletableFuture.completedFuture(s.length());
            };
            return new AsyncFunctionWrapper<>(name, core, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        AsyncBaseWrapper<?, ?, ?, ?> wrapAround(String name, AsyncBaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new AsyncFunctionWrapper<>(name, (AsyncFunctionWrapper<String, Integer>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        CompletionStage<?> invoke(AsyncBaseWrapper<?, ?, ?, ?> wrapper) {
            return ((Function<String, CompletionStage<Integer>>) wrapper).apply("hello");
        }
    };

    static final AsyncWrapperScenario ASYNC_JOINPOINT_SCENARIO = new AsyncWrapperScenario() {
        @Override String displayName() { return "AsyncJoinPointWrapper"; }
        @Override AsyncBaseWrapper<?, ?, ?, ?> createSingle(String name, ExecutionLog log) {
            JoinPointExecutor<CompletionStage<String>> core;
          core = () -> {
              log.coreInvoked.set(true);
              return CompletableFuture.completedFuture("result");
          };
          return new AsyncJoinPointWrapper<>(name, core, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        AsyncBaseWrapper<?, ?, ?, ?> wrapAround(String name, AsyncBaseWrapper<?, ?, ?, ?> inner, ExecutionLog log) {
            return new AsyncJoinPointWrapper<>(name, (AsyncJoinPointWrapper<String>) inner, trackingAction(name, log));
        }
        @Override @SuppressWarnings("unchecked")
        CompletionStage<?> invoke(AsyncBaseWrapper<?, ?, ?, ?> wrapper) throws Throwable {
            return ((JoinPointExecutor<CompletionStage<String>>) wrapper).proceed();
        }
    };

    static Stream<Arguments> allAsyncWrapperTypes() {
        return Stream.of(
            Arguments.of(ASYNC_RUNNABLE_SCENARIO),
            Arguments.of(ASYNC_SUPPLIER_SCENARIO),
            Arguments.of(ASYNC_CALLABLE_SCENARIO),
            Arguments.of(ASYNC_FUNCTION_SCENARIO),
            Arguments.of(ASYNC_JOINPOINT_SCENARIO)
        );
    }

    // =========================================================================
    // Test Categories
    // =========================================================================

    @Nested
    @DisplayName("Construction")
    class Construction {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should generate a positive chain id for a single wrapper")
        void should_generate_a_positive_chain_id_for_a_single_wrapper(AsyncWrapperScenario scenario) {
            // Given / When
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("layer", log);

            // Then
            assertThat(wrapper.getChainId()).isGreaterThan(0L);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should inherit the chain id from the inner wrapper")
        void should_inherit_the_chain_id_from_the_inner_wrapper(AsyncWrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);

            // When
            AsyncBaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // Then
            assertThat(outer.getChainId()).isEqualTo(inner.getChainId());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should assign different chain ids to independent chains")
        void should_assign_different_chain_ids_to_independent_chains(AsyncWrapperScenario scenario) {
            // Given / When
            ExecutionLog log1 = new ExecutionLog();
            ExecutionLog log2 = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> chain1 = scenario.createSingle("chain1", log1);
            AsyncBaseWrapper<?, ?, ?, ?> chain2 = scenario.createSingle("chain2", log2);

            // Then
            assertThat(chain1.getChainId()).isNotEqualTo(chain2.getChainId());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should store the layer description provided at construction")
        void should_store_the_layer_description_provided_at_construction(AsyncWrapperScenario scenario) {
            // Given / When
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("my-async-layer", log);

            // Then
            assertThat(wrapper.getLayerDescription()).isEqualTo("my-async-layer");
        }
    }

    @Nested
    @DisplayName("Chain Execution")
    class ChainExecution {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should invoke the core delegate when called on a single wrapper")
        void should_invoke_the_core_delegate_when_called_on_a_single_wrapper(
            AsyncWrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("layer", log);

            // When
            join(scenario.invoke(wrapper));

            // Then
            assertThat(log.coreInvoked).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should call layer actions in outer-to-inner order")
        void should_call_layer_actions_in_outer_to_inner_order(
            AsyncWrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            AsyncBaseWrapper<?, ?, ?, ?> middle = scenario.wrapAround("middle", inner, log);
            AsyncBaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", middle, log);

            // When
            join(scenario.invoke(outer));

            // Then
            assertThat(log.layerNames).containsExactly("outer", "middle", "inner");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should pass the same call id to all layers in a single invocation")
        void should_pass_the_same_call_id_to_all_layers_in_a_single_invocation(
            AsyncWrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            AsyncBaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // When
            join(scenario.invoke(outer));

            // Then
            assertThat(log.callIds).hasSize(2);
            assertThat(log.callIds.get(0)).isEqualTo(log.callIds.get(1));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should generate a different call id for each invocation")
        void should_generate_a_different_call_id_for_each_invocation(
            AsyncWrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("layer", log);

            // When
            join(scenario.invoke(wrapper));
            long firstCallId = log.callIds.get(0);
            log.callIds.clear();
            join(scenario.invoke(wrapper));
            long secondCallId = log.callIds.get(0);

            // Then
            assertThat(firstCallId).isNotEqualTo(secondCallId);
        }

        @Test
        @DisplayName("should return the core async supplier value through the chain")
        void should_return_the_core_async_supplier_value_through_the_chain() {
            // Given
            AsyncSupplierWrapper<String> inner = new AsyncSupplierWrapper<>(
                "inner", () -> CompletableFuture.completedFuture("payload"));
            AsyncSupplierWrapper<String> outer = new AsyncSupplierWrapper<>("outer", inner);

            // When / Then
            assertThat(join(outer.get())).isEqualTo("payload");
        }

        @Test
        @DisplayName("should pass the function argument through all layers to the core delegate")
        void should_pass_the_function_argument_through_all_layers_to_the_core_delegate() {
            // Given
            AtomicReference<String> receivedInput = new AtomicReference<>();
            Function<String, CompletionStage<Integer>> core = s -> {
                receivedInput.set(s);
                return CompletableFuture.completedFuture(s.length());
            };
            AsyncFunctionWrapper<String, Integer> inner = new AsyncFunctionWrapper<>("inner", core);
            AsyncFunctionWrapper<String, Integer> outer = new AsyncFunctionWrapper<>("outer", inner);

            // When
            int result = join(outer.apply("hello"));

            // Then
            assertThat(receivedInput.get()).isEqualTo("hello");
            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("should return the core async callable value through the chain")
        void should_return_the_core_async_callable_value_through_the_chain() throws Exception {
            // Given
            AsyncCallableWrapper<Integer> inner = new AsyncCallableWrapper<>(
                "inner", () -> CompletableFuture.completedFuture(42));
            AsyncCallableWrapper<Integer> outer = new AsyncCallableWrapper<>("outer", inner);

            // When / Then
            assertThat(join(outer.call())).isEqualTo(42);
        }

        @Test
        @DisplayName("should return the core async join point value through the chain")
        void should_return_the_core_async_join_point_value_through_the_chain() throws Throwable {
            // Given
            AsyncJoinPointWrapper<String> inner = new AsyncJoinPointWrapper<>(
                "inner", () -> CompletableFuture.completedFuture("aop-result"));
            AsyncJoinPointWrapper<String> outer = new AsyncJoinPointWrapper<>("outer", inner);

            // When / Then
            assertThat(join(outer.proceed())).isEqualTo("aop-result");
        }
    }

    @Nested
    @DisplayName("Async Around Semantics")
    class AsyncAroundSemantics {

        @Test
        @DisplayName("should execute start phase synchronously and end phase on completion")
        void should_execute_start_phase_synchronously_and_end_phase_on_completion() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("two-phase",
                () -> {
                    events.add("core");
                    return CompletableFuture.completedFuture("value");
                },
                (chainId, callId, arg, next) -> {
                    events.add("start-phase");
                    CompletionStage<String> stage = next.executeAsync(chainId, callId, arg);
                    return stage.thenApply(result -> {
                        events.add("end-phase");
                        return result;
                    });
                }
            );

            // When
            String result = join(wrapper.get());

            // Then
            assertThat(result).isEqualTo("value");
            assertThat(events).containsExactly("start-phase", "core", "end-phase");
        }

        @Test
        @DisplayName("should allow a layer to attach cleanup via whenComplete")
        void should_allow_a_layer_to_attach_cleanup_via_whenComplete() {
            // Given
            AtomicBoolean released = new AtomicBoolean(false);
            AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("bulkhead-sim",
                () -> CompletableFuture.completedFuture("ok"),
                (chainId, callId, arg, next) -> {
                    // Simulate acquire
                    CompletionStage<String> stage;
                    try {
                        stage = next.executeAsync(chainId, callId, arg);
                    } catch (Throwable t) {
                        released.set(true);
                        throw t;
                    }
                    // Simulate release on completion
                    return stage.whenComplete((r, e) -> released.set(true));
                }
            );

            // When
            join(wrapper.get());

            // Then
            assertThat(released).isTrue();
        }

        @Test
        @DisplayName("should release resources even when the async operation fails")
        void should_release_resources_even_when_the_async_operation_fails() {
            // Given
            AtomicBoolean released = new AtomicBoolean(false);
            AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("resilient",
                () -> CompletableFuture.failedFuture(new RuntimeException("async-boom")),
                (chainId, callId, arg, next) -> {
                    return next.executeAsync(chainId, callId, arg)
                        .whenComplete((r, e) -> released.set(true));
                }
            );

            // When
            Throwable thrown = catchThrowable(() -> join(wrapper.get()));

            // Then
            assertThat(thrown).isInstanceOf(CompletionException.class);
            assertThat(released).isTrue();
        }

        @Test
        @DisplayName("should allow a layer to transform the async result")
        void should_allow_a_layer_to_transform_the_async_result() {
            // Given
            AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("transform",
                () -> CompletableFuture.completedFuture("hello"),
                (chainId, callId, arg, next) -> {
                    return next.executeAsync(chainId, callId, arg)
                        .thenApply(String::toUpperCase);
                }
            );

            // When / Then
            assertThat(join(wrapper.get())).isEqualTo("HELLO");
        }

        @Test
        @DisplayName("should allow a layer to recover from async failures with a fallback")
        void should_allow_a_layer_to_recover_from_async_failures_with_a_fallback() {
            // Given
            AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("fallback",
                () -> CompletableFuture.failedFuture(new RuntimeException("fail")),
                (chainId, callId, arg, next) -> {
                    return next.executeAsync(chainId, callId, arg)
                        .exceptionally(e -> "recovered");
                }
            );

            // When / Then
            assertThat(join(wrapper.get())).isEqualTo("recovered");
        }

        @Test
        @DisplayName("should compose multiple async layers in onion order")
        void should_compose_multiple_async_layers_in_onion_order() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());

            AsyncSupplierWrapper<String> core = new AsyncSupplierWrapper<>("core", () -> {
                events.add("core-exec");
                return CompletableFuture.completedFuture("value");
            });

            AsyncSupplierWrapper<String> inner = new AsyncSupplierWrapper<>("metrics", core,
                (chainId, callId, arg, next) -> {
                    events.add("metrics-start");
                    return next.executeAsync(chainId, callId, arg)
                        .whenComplete((r, e) -> events.add("metrics-end"));
                }
            );

            AsyncSupplierWrapper<String> outer = new AsyncSupplierWrapper<>("logging", inner,
                (chainId, callId, arg, next) -> {
                    events.add("logging-start");
                    return next.executeAsync(chainId, callId, arg)
                        .whenComplete((r, e) -> events.add("logging-end"));
                }
            );

            // When
            String result = join(outer.get());

            // Then — classic onion: start outer→inner, end inner→outer
            assertThat(result).isEqualTo("value");
            assertThat(events).containsExactly(
                "logging-start", "metrics-start", "core-exec", "metrics-end", "logging-end"
            );
        }

        @Test
        @DisplayName("should provide chain id and call id to the async layer action")
        void should_provide_chain_id_and_call_id_to_the_async_layer_action() {
            // Given
            AtomicReference<Long> capturedChainId = new AtomicReference<>();
            AtomicReference<Long> capturedCallId = new AtomicReference<>();

            AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("tracing",
                () -> CompletableFuture.completedFuture("ok"),
                (chainId, callId, arg, next) -> {
                    capturedChainId.set(chainId);
                    capturedCallId.set(callId);
                    return next.executeAsync(chainId, callId, arg);
                }
            );

            // When
            join(wrapper.get());

            // Then
            assertThat(capturedChainId.get()).isEqualTo(wrapper.getChainId());
            assertThat(capturedCallId.get()).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("Delegate Reuse")
    class DelegateReuse {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should allow the same inner wrapper to be used in multiple independent chains")
        void should_allow_the_same_inner_wrapper_to_be_used_in_multiple_independent_chains(
            AsyncWrapperScenario scenario) throws Throwable {
            // Given
            ExecutionLog sharedLog = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> shared = scenario.createSingle("shared-inner", sharedLog);

            ExecutionLog logA = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> outerA = scenario.wrapAround("chain-A", shared, logA);

            ExecutionLog logB = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> outerB = scenario.wrapAround("chain-B", shared, logB);

            // When
            join(scenario.invoke(outerA));
            sharedLog.layerNames.clear();
            join(scenario.invoke(outerB));

            // Then
            assertThat(logA.layerNames).contains("chain-A");
            assertThat(logB.layerNames).contains("chain-B");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should share the same chain id when wrapping the same inner delegate")
        void should_share_the_same_chain_id_when_wrapping_the_same_inner_delegate(
            AsyncWrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> shared = scenario.createSingle("shared", log);

            // When
            AsyncBaseWrapper<?, ?, ?, ?> outerA = scenario.wrapAround("A", shared, log);
            AsyncBaseWrapper<?, ?, ?, ?> outerB = scenario.wrapAround("B", shared, log);

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
        @DisplayName("Sync Failures (before CompletionStage is created)")
        class SyncFailures {

            @Test
            @DisplayName("should propagate sync RuntimeException from async supplier")
            void should_propagate_sync_RuntimeException_from_async_supplier() {
                // Given — the supplier throws before creating a stage
                IllegalStateException expected = new IllegalStateException("sync-boom");
                AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("layer",
                    () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::get)).isSameAs(expected);
            }

            @Test
            @DisplayName("should preserve checked exception from async callable start phase")
            void should_preserve_checked_exception_from_async_callable_start_phase() {
                // Given — the callable throws a checked exception before creating a stage
                IOException expected = new IOException("connection refused");
                AsyncCallableWrapper<String> wrapper = new AsyncCallableWrapper<>("layer",
                    () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::call)).isSameAs(expected);
            }

            @Test
            @DisplayName("should preserve checked throwable from async join point start phase")
            void should_preserve_checked_throwable_from_async_join_point_start_phase() {
                // Given
                IOException expected = new IOException("proxy start error");
                AsyncJoinPointWrapper<String> wrapper = new AsyncJoinPointWrapper<>("layer",
                    () -> { throw expected; });

                // When / Then
                assertThat(catchThrowable(wrapper::proceed)).isSameAs(expected);
            }
        }

        @Nested
        @DisplayName("Async Failures (inside the CompletionStage)")
        class AsyncFailures {

            @Test
            @DisplayName("should propagate async failure through the stage")
            void should_propagate_async_failure_through_the_stage() {
                // Given — the stage completes exceptionally
                AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("layer",
                    () -> CompletableFuture.failedFuture(new RuntimeException("async-fail")));

                // When
                Throwable thrown = catchThrowable(() -> join(wrapper.get()));

                // Then
                assertThat(thrown)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
            }

            @Test
            @DisplayName("should propagate async failure through a multi-layer chain")
            void should_propagate_async_failure_through_a_multi_layer_chain() {
                // Given
                ExecutionLog log = new ExecutionLog();
                AsyncSupplierWrapper<String> inner = new AsyncSupplierWrapper<>("inner",
                    () -> CompletableFuture.failedFuture(new RuntimeException("deep-fail")),
                    trackingAction("inner", log));
                AsyncSupplierWrapper<String> outer = new AsyncSupplierWrapper<>("outer",
                    inner, trackingAction("outer", log));

                // When
                Throwable thrown = catchThrowable(() -> join(outer.get()));

                // Then — both layers were traversed before the failure
                assertThat(thrown).isInstanceOf(CompletionException.class);
                assertThat(log.layerNames).containsExactly("outer", "inner");
            }
        }
    }

    @Nested
    @DisplayName("Hierarchy Visualization")
    class HierarchyVisualization {

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should render a single layer hierarchy with chain id and layer name")
        void should_render_a_single_layer_hierarchy_with_chain_id_and_layer_name(
            AsyncWrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("my-async-layer", log);

            // When
            String hierarchy = wrapper.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                .startsWith("Chain-ID: ")
                .contains(Long.toString(wrapper.getChainId()))
                .contains("my-async-layer");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should render a multi-layer hierarchy in outer-to-inner order")
        void should_render_a_multi_layer_hierarchy_in_outer_to_inner_order(
            AsyncWrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("Security", log);
            AsyncBaseWrapper<?, ?, ?, ?> middle = scenario.wrapAround("Metrics", inner, log);
            AsyncBaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("Logging", middle, log);

            // When
            String hierarchy = outer.toStringHierarchy();

            // Then
            assertThat(hierarchy.indexOf("Logging")).isLessThan(hierarchy.indexOf("Metrics"));
            assertThat(hierarchy.indexOf("Metrics")).isLessThan(hierarchy.indexOf("Security"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should return null for getInner when there is no inner wrapper")
        void should_return_null_for_getInner_when_there_is_no_inner_wrapper(
            AsyncWrapperScenario scenario) {
            // Given / When
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> wrapper = scenario.createSingle("leaf", log);

            // Then
            assertThat(wrapper.getInner()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("eu.inqudium.imperative.core.pipeline.AsyncWrapperPipelineTest#allAsyncWrapperTypes")
        @DisplayName("should return the correct inner wrapper via getInner in a chain")
        void should_return_the_correct_inner_wrapper_via_getInner_in_a_chain(
            AsyncWrapperScenario scenario) {
            // Given
            ExecutionLog log = new ExecutionLog();
            AsyncBaseWrapper<?, ?, ?, ?> inner = scenario.createSingle("inner", log);
            AsyncBaseWrapper<?, ?, ?, ?> outer = scenario.wrapAround("outer", inner, log);

            // When / Then
            assertThat(outer.getInner()).isSameAs(inner);
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
            long customId = 888_001L;
            AsyncSupplierWrapper<String> wrapper = new AsyncSupplierWrapper<>("layer",
                () -> CompletableFuture.completedFuture("ok"),
                trackingAction("layer", log)) {
                @Override protected long generateCallId() { return customId; }
            };

            // When
            join(wrapper.get());

            // Then
            assertThat(log.callIds).containsExactly(customId);
        }

        @Test
        @DisplayName("should propagate the custom call id through all layers in a chain")
        void should_propagate_the_custom_call_id_through_all_layers_in_a_chain() {
            // Given
            ExecutionLog log = new ExecutionLog();
            long customId = 77L;
            AsyncSupplierWrapper<String> inner = new AsyncSupplierWrapper<>("inner",
                () -> CompletableFuture.completedFuture("ok"),
                trackingAction("inner", log));
            AsyncSupplierWrapper<String> outer = new AsyncSupplierWrapper<>("outer",
                inner, trackingAction("outer", log)) {
                @Override protected long generateCallId() { return customId; }
            };

            // When
            join(outer.get());

            // Then
            assertThat(log.callIds).hasSize(2).containsOnly(customId);
        }
    }
}
