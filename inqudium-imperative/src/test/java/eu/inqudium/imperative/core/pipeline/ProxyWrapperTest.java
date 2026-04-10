package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.core.pipeline.proxy.InqProxyFactory;
import eu.inqudium.core.pipeline.proxy.ProxyWrapper;
import eu.inqudium.core.pipeline.proxy.SyncDispatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive tests for the proxy dispatch pipeline — covering sync and async
 * single-layer and multi-layer chains, extension composition, chain metadata,
 * exception propagation, and factory integration.
 */
@DisplayName("ProxyWrapper dispatch pipeline")
class ProxyWrapperTest {

    // ======================== Test service interface ========================

    private FakeOrderService target;
    private InvocationLog log;

    @BeforeEach
    void setUp() {
        target = new FakeOrderService();
        log = new InvocationLog();
    }

    // ======================== Reusable test fixtures ========================

    /**
     * Service interface with both sync and async methods for mixed dispatch testing.
     */
    public interface OrderService {
        String getOrder(long id);

        CompletionStage<String> getOrderAsync(long id);

        int count();

        CompletionStage<Integer> countAsync();
    }

    public interface NullableService {
        String process(String input);
    }

    /**
     * Simple in-memory implementation of OrderService.
     */
    static class FakeOrderService implements OrderService {
        @Override
        public String getOrder(long id) {
            return "order-" + id;
        }

        @Override
        public CompletionStage<String> getOrderAsync(long id) {
            return CompletableFuture.completedFuture("async-order-" + id);
        }

        @Override
        public int count() {
            return 42;
        }

        @Override
        public CompletionStage<Integer> countAsync() {
            return CompletableFuture.completedFuture(42);
        }
    }

    /**
     * Records invocation events for verifying execution order and arguments.
     */
    static class InvocationLog {
        final List<String> events = new ArrayList<>();

        LayerAction<Void, Object> syncAction(String label) {
            return (chainId, callId, arg, next) -> {
                events.add(label + ":before");
                Object result = next.execute(chainId, callId, arg);
                events.add(label + ":after");
                return result;
            };
        }

        AsyncLayerAction<Void, Object> asyncAction(String label) {
            return (chainId, callId, arg, next) -> {
                events.add(label + ":before");
                return next.executeAsync(chainId, callId, arg)
                        .thenApply(result -> {
                            events.add(label + ":after");
                            return result;
                        });
            };
        }
    }

    // ======================== Single sync layer ========================

    @Nested
    @DisplayName("Single sync layer")
    class SingleSyncLayer {

        @Test
        @DisplayName("should delegate sync method call to the real target and return its result")
        void should_delegate_sync_method_call_to_the_real_target_and_return_its_result() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "sync-layer",
                    new SyncDispatchExtension(log.syncAction("layer")));

            // When
            String result = proxy.getOrder(7);

            // Then
            assertThat(result).isEqualTo("order-7");
            assertThat(log.events).containsExactly("layer:before", "layer:after");
        }

        @Test
        @DisplayName("should execute sync layer action before and after the target call")
        void should_execute_sync_layer_action_before_and_after_the_target_call() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "timing",
                    new SyncDispatchExtension(log.syncAction("timing")));

            // When
            proxy.count();

            // Then
            assertThat(log.events)
                    .hasSize(2)
                    .first().isEqualTo("timing:before");
            assertThat(log.events).last().isEqualTo("timing:after");
        }

        @Test
        @DisplayName("should pass through async method as opaque object when only sync extension is registered")
        void should_pass_through_async_method_as_opaque_object_when_only_sync_extension_is_registered() {
            // Given — only SyncDispatchExtension, which catches all methods including async ones
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "sync-only",
                    new SyncDispatchExtension(log.syncAction("sync")));

            // When — async method routed through sync extension (CompletionStage treated as Object)
            CompletionStage<String> result = proxy.getOrderAsync(3);

            // Then — the result is still a valid CompletionStage
            assertThat(result.toCompletableFuture().join()).isEqualTo("async-order-3");
            assertThat(log.events).containsExactly("sync:before", "sync:after");
        }

        @Test
        @DisplayName("should pass through when layer action is a no-op passthrough")
        void should_pass_through_when_layer_action_is_a_no_op_passthrough() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "passthrough",
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            String result = proxy.getOrder(99);

            // Then
            assertThat(result).isEqualTo("order-99");
        }
    }

    // ======================== Single async layer ========================

    @Nested
    @DisplayName("Single async layer")
    class SingleAsyncLayer {

        @Test
        @DisplayName("should dispatch CompletionStage method through async extension and return result")
        void should_dispatch_CompletionStage_method_through_async_extension_and_return_result() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "async-layer",
                    new AsyncDispatchExtension(log.asyncAction("async")),
                    new SyncDispatchExtension(log.syncAction("sync")));

            // When
            CompletionStage<String> result = proxy.getOrderAsync(5);

            // Then
            assertThat(result.toCompletableFuture().join()).isEqualTo("async-order-5");
            assertThat(log.events).containsExactly("async:before", "async:after");
        }

        @Test
        @DisplayName("should route sync methods to sync extension even when async extension is present")
        void should_route_sync_methods_to_sync_extension_even_when_async_extension_is_present() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "mixed",
                    new AsyncDispatchExtension(log.asyncAction("async")),
                    new SyncDispatchExtension(log.syncAction("sync")));

            // When
            String result = proxy.getOrder(1);

            // Then
            assertThat(result).isEqualTo("order-1");
            assertThat(log.events).containsExactly("sync:before", "sync:after");
        }

        @Test
        @DisplayName("should execute async passthrough without modifying the result")
        void should_execute_async_passthrough_without_modifying_the_result() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "passthrough",
                    new AsyncDispatchExtension(AsyncLayerAction.passThrough()),
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            CompletionStage<Integer> result = proxy.countAsync();

            // Then
            assertThat(result.toCompletableFuture().join()).isEqualTo(42);
        }
    }

    // ======================== Multiple sync layers ========================

    @Nested
    @DisplayName("Multiple sync layers (chained proxies)")
    class MultipleSyncLayers {

        @Test
        @DisplayName("should execute outer layer before inner layer in a two-layer sync chain")
        void should_execute_outer_layer_before_inner_layer_in_a_two_layer_sync_chain() {
            // Given
            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "inner",
                    new SyncDispatchExtension(log.syncAction("inner")));

            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new SyncDispatchExtension(log.syncAction("outer")));

            // When
            String result = outer.getOrder(10);

            // Then
            assertThat(result).isEqualTo("order-10");
            assertThat(log.events).containsExactly(
                    "outer:before", "inner:before",
                    "inner:after", "outer:after");
        }

        @Test
        @DisplayName("should maintain correct nesting order across three sync layers")
        void should_maintain_correct_nesting_order_across_three_sync_layers() {
            // Given
            OrderService layer1 = ProxyWrapper.createProxy(
                    OrderService.class, target, "layer1",
                    new SyncDispatchExtension(log.syncAction("L1")));
            OrderService layer2 = ProxyWrapper.createProxy(
                    OrderService.class, layer1, "layer2",
                    new SyncDispatchExtension(log.syncAction("L2")));
            OrderService layer3 = ProxyWrapper.createProxy(
                    OrderService.class, layer2, "layer3",
                    new SyncDispatchExtension(log.syncAction("L3")));

            // When
            String result = layer3.getOrder(1);

            // Then
            assertThat(result).isEqualTo("order-1");
            assertThat(log.events).containsExactly(
                    "L3:before", "L2:before", "L1:before",
                    "L1:after", "L2:after", "L3:after");
        }

        @Test
        @DisplayName("should allow each layer to transform the result independently")
        void should_allow_each_layer_to_transform_the_result_independently() {
            // Given — each layer appends to the result
            LayerAction<Void, Object> innerAction = (chainId, callId, arg, next) -> {
                Object result = next.execute(chainId, callId, arg);
                return result + "+inner";
            };
            LayerAction<Void, Object> outerAction = (chainId, callId, arg, next) -> {
                Object result = next.execute(chainId, callId, arg);
                return result + "+outer";
            };

            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "inner",
                    new SyncDispatchExtension(innerAction));
            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new SyncDispatchExtension(outerAction));

            // When
            String result = outer.getOrder(1);

            // Then
            assertThat(result).isEqualTo("order-1+inner+outer");
        }
    }

    // ======================== Multiple async layers ========================

    @Nested
    @DisplayName("Multiple async layers (chained proxies)")
    class MultipleAsyncLayers {

        @Test
        @DisplayName("should execute outer async layer before inner async layer")
        void should_execute_outer_async_layer_before_inner_async_layer() {
            // Given
            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "inner",
                    new AsyncDispatchExtension(log.asyncAction("inner")),
                    new SyncDispatchExtension(log.syncAction("inner-sync")));

            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new AsyncDispatchExtension(log.asyncAction("outer")),
                    new SyncDispatchExtension(log.syncAction("outer-sync")));

            // When
            CompletionStage<String> result = outer.getOrderAsync(20);

            // Then
            assertThat(outer)
                    .isInstanceOfSatisfying(Wrapper.class, wrapper -> {
                        long chainId = wrapper.chainId();
                        String layerDescription = wrapper.layerDescription();
                        long currentCallId = wrapper.currentCallId();
                        assertThat(layerDescription).isEqualTo("outer");
                        assertThat(wrapper.toStringHierarchy()).isEqualToIgnoringNewLines(
                                "Chain-ID: " + chainId + " (current call-ID: " + currentCallId + ")"
                                        + layerDescription + "  └── inner");
                    });
            assertThat(outer)
                    .isInstanceOfSatisfying(Wrapper.class, wrapper -> {
                        Wrapper<?> innerWrapper = wrapper.inner();
                        long chainId = innerWrapper.chainId();
                        String layerDescription = innerWrapper.layerDescription();
                        long currentCallId = innerWrapper.currentCallId();
                        assertThat(layerDescription).isEqualTo("inner");
                        assertThat(innerWrapper.toStringHierarchy()).isEqualToIgnoringNewLines(
                                "Chain-ID: " + chainId + " (current call-ID: " + currentCallId + ")"
                                        + layerDescription);
                    });
            assertThat(result.toCompletableFuture().join()).isEqualTo("async-order-20");
            assertThat(log.events).containsExactly(
                    "outer:before", "inner:before",
                    "inner:after", "outer:after");
        }

        @Test
        @DisplayName("should keep sync and async chains independent in a multi-layer proxy")
        void should_keep_sync_and_async_chains_independent_in_a_multi_layer_proxy() {
            // Given
            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "inner",
                    new AsyncDispatchExtension(log.asyncAction("inner-async")),
                    new SyncDispatchExtension(log.syncAction("inner-sync")));

            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new AsyncDispatchExtension(log.asyncAction("outer-async")),
                    new SyncDispatchExtension(log.syncAction("outer-sync")));

            // When — invoke sync, then async
            outer.getOrder(1);
            List<String> syncEvents = new ArrayList<>(log.events);
            log.events.clear();

            outer.getOrderAsync(2).toCompletableFuture().join();
            List<String> asyncEvents = new ArrayList<>(log.events);

            // Then — each chain uses its own extension path
            assertThat(syncEvents).containsExactly(
                    "outer-sync:before", "inner-sync:before",
                    "inner-sync:after", "outer-sync:after");

            assertThat(asyncEvents).containsExactly(
                    "outer-async:before", "inner-async:before",
                    "inner-async:after", "outer-async:after");
        }

        @Test
        @DisplayName("should maintain correct nesting across three async layers")
        void should_maintain_correct_nesting_across_three_async_layers() {
            // Given
            OrderService l1 = ProxyWrapper.createProxy(
                    OrderService.class, target, "L1",
                    new AsyncDispatchExtension(log.asyncAction("L1")),
                    new SyncDispatchExtension(LayerAction.passThrough()));
            OrderService l2 = ProxyWrapper.createProxy(
                    OrderService.class, l1, "L2",
                    new AsyncDispatchExtension(log.asyncAction("L2")),
                    new SyncDispatchExtension(LayerAction.passThrough()));
            OrderService l3 = ProxyWrapper.createProxy(
                    OrderService.class, l2, "L3",
                    new AsyncDispatchExtension(log.asyncAction("L3")),
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            CompletionStage<String> result = l3.getOrderAsync(1);

            // Then
            assertThat(result.toCompletableFuture().join()).isEqualTo("async-order-1");
            assertThat(log.events).containsExactly(
                    "L3:before", "L2:before", "L1:before",
                    "L1:after", "L2:after", "L3:after");
        }
    }

    // ======================== Mixed dispatch ========================

    @Nested
    @DisplayName("Mixed sync and async dispatch on same chain")
    class MixedDispatch {

        @Test
        @DisplayName("should route sync and async calls to their respective extensions on the same proxy")
        void should_route_sync_and_async_calls_to_their_respective_extensions_on_the_same_proxy() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "mixed",
                    new AsyncDispatchExtension(log.asyncAction("async")),
                    new SyncDispatchExtension(log.syncAction("sync")));

            // When
            String syncResult = proxy.getOrder(1);
            log.events.clear();
            CompletionStage<String> asyncResult = proxy.getOrderAsync(2);

            // Then
            assertThat(syncResult).isEqualTo("order-1");
            assertThat(asyncResult.toCompletableFuture().join()).isEqualTo("async-order-2");
            assertThat(log.events).containsExactly("async:before", "async:after");
        }

        @Test
        @DisplayName("should correctly dispatch multiple different sync and async methods")
        void should_correctly_dispatch_multiple_different_sync_and_async_methods() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "multi",
                    new AsyncDispatchExtension(log.asyncAction("A")),
                    new SyncDispatchExtension(log.syncAction("S")));

            // When
            String order = proxy.getOrder(1);
            int count = proxy.count();
            CompletionStage<String> asyncOrder = proxy.getOrderAsync(2);
            CompletionStage<Integer> asyncCount = proxy.countAsync();

            // Then
            assertThat(order).isEqualTo("order-1");
            assertThat(count).isEqualTo(42);
            assertThat(asyncOrder.toCompletableFuture().join()).isEqualTo("async-order-2");
            assertThat(asyncCount.toCompletableFuture().join()).isEqualTo(42);
        }
    }

    // ======================== Chain metadata ========================

    @Nested
    @DisplayName("Chain metadata (Wrapper interface)")
    class ChainMetadata {

        @Test
        @DisplayName("should share the same chainId across all layers wrapping the same target")
        void should_share_the_same_chainId_across_all_layers_wrapping_the_same_target() {
            // Given
            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "inner",
                    new SyncDispatchExtension(LayerAction.passThrough()));
            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            Wrapper<?> outerWrapper = (Wrapper<?>) outer;
            Wrapper<?> innerWrapper = (Wrapper<?>) inner;

            // Then
            assertThat(outerWrapper.chainId()).isEqualTo(innerWrapper.chainId());
        }

        @Test
        @DisplayName("should assign different chainIds to independent chains")
        void should_assign_different_chainIds_to_independent_chains() {
            // Given
            OrderService proxy1 = ProxyWrapper.createProxy(
                    OrderService.class, target, "chain-a",
                    new SyncDispatchExtension(LayerAction.passThrough()));
            OrderService proxy2 = ProxyWrapper.createProxy(
                    OrderService.class, new FakeOrderService(), "chain-b",
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            Wrapper<?> wrapper1 = (Wrapper<?>) proxy1;
            Wrapper<?> wrapper2 = (Wrapper<?>) proxy2;

            // Then
            assertThat(wrapper1.chainId()).isNotEqualTo(wrapper2.chainId());
        }

        @Test
        @DisplayName("should increment callId with each invocation")
        void should_increment_callId_with_each_invocation() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "counter",
                    new SyncDispatchExtension(LayerAction.passThrough()));
            Wrapper<?> wrapper = (Wrapper<?>) proxy;

            // When
            long callIdBefore = wrapper.currentCallId();
            proxy.getOrder(1);
            long callIdAfterFirst = wrapper.currentCallId();
            proxy.getOrder(2);
            long callIdAfterSecond = wrapper.currentCallId();

            // Then
            assertThat(callIdAfterFirst).isGreaterThan(callIdBefore);
            assertThat(callIdAfterSecond).isGreaterThan(callIdAfterFirst);
        }

        @Test
        @DisplayName("should expose the correct layer description through the Wrapper interface")
        void should_expose_the_correct_layer_description_through_the_Wrapper_interface() {
            // Given
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "bulkhead-layer",
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            Wrapper<?> wrapper = (Wrapper<?>) proxy;

            // Then
            assertThat(wrapper.layerDescription()).isEqualTo("bulkhead-layer");
        }

        @Test
        @DisplayName("should return inner layer from outer and null from innermost")
        void should_return_inner_layer_from_outer_and_null_from_innermost() {
            // Given
            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "inner",
                    new SyncDispatchExtension(LayerAction.passThrough()));
            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            Wrapper<?> outerWrapper = (Wrapper<?>) outer;
            Wrapper<?> innerWrapper = outerWrapper.inner();

            // Then
            assertThat(innerWrapper).isNotNull();
            assertThat(innerWrapper.layerDescription()).isEqualTo("inner");
            assertThat(innerWrapper.inner()).isNull();
        }

        @Test
        @DisplayName("should render a readable hierarchy string for a multi-layer chain")
        void should_render_a_readable_hierarchy_string_for_a_multi_layer_chain() {
            // Given
            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "retry",
                    new SyncDispatchExtension(LayerAction.passThrough()));
            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "bulkhead",
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            String hierarchy = ((Wrapper<?>) outer).toStringHierarchy();

            // Then
            assertThat(hierarchy)
                    .contains("bulkhead")
                    .contains("retry")
                    .contains("Chain-ID:");
        }
    }

    // ======================== Exception propagation ========================

    @Nested
    @DisplayName("Exception propagation")
    class ExceptionPropagation {

        @Test
        @DisplayName("should propagate runtime exception from sync target through the chain")
        void should_propagate_runtime_exception_from_sync_target_through_the_chain() {
            // Given
            OrderService failingTarget = new FakeOrderService() {
                @Override
                public String getOrder(long id) {
                    throw new IllegalStateException("sync-failure");
                }
            };
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, failingTarget, "layer",
                    new SyncDispatchExtension(log.syncAction("layer")));

            // When / Then
            assertThatThrownBy(() -> proxy.getOrder(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("sync-failure");
            assertThat(log.events).containsExactly("layer:before");
        }

        @Test
        @DisplayName("should propagate runtime exception from async target through the chain")
        void should_propagate_runtime_exception_from_async_target_through_the_chain() {
            // Given
            OrderService failingTarget = new FakeOrderService() {
                @Override
                public CompletionStage<String> getOrderAsync(long id) {
                    throw new IllegalStateException("async-launch-failure");
                }
            };
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, failingTarget, "layer",
                    new AsyncDispatchExtension(log.asyncAction("layer")),
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When / Then
            assertThatThrownBy(() -> proxy.getOrderAsync(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("async-launch-failure");
        }

        @Test
        @DisplayName("should propagate completed exceptionally stage from async target")
        void should_propagate_completed_exceptionally_stage_from_async_target() {
            // Given
            OrderService failingTarget = new FakeOrderService() {
                @Override
                public CompletionStage<String> getOrderAsync(long id) {
                    return CompletableFuture.failedFuture(new RuntimeException("async-stage-failure"));
                }
            };
            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, failingTarget, "layer",
                    new AsyncDispatchExtension(log.asyncAction("layer")),
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            CompletionStage<String> result = proxy.getOrderAsync(1);

            // Then
            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("async-stage-failure");
        }

        @Test
        @DisplayName("should propagate exception through multi-layer sync chain")
        void should_propagate_exception_through_multi_layer_sync_chain() {
            // Given
            OrderService failingTarget = new FakeOrderService() {
                @Override
                public String getOrder(long id) {
                    throw new UnsupportedOperationException("not-implemented");
                }
            };

            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, failingTarget, "inner",
                    new SyncDispatchExtension(log.syncAction("inner")));
            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new SyncDispatchExtension(log.syncAction("outer")));

            // When / Then
            assertThatThrownBy(() -> outer.getOrder(1))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("not-implemented");

            // Only the "before" events are logged — exception prevents "after"
            assertThat(log.events).containsExactly("outer:before", "inner:before");
        }
    }

    // ======================== Layer action behavior ========================

    @Nested
    @DisplayName("Layer action behavior")
    class LayerActionBehavior {

        @Test
        @DisplayName("should allow a sync layer to short-circuit and skip the target entirely")
        void should_allow_a_sync_layer_to_short_circuit_and_skip_the_target_entirely() {
            // Given — layer returns cached result without calling next
            LayerAction<Void, Object> cachingAction = (chainId, callId, arg, next) -> "cached-result";

            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "cache",
                    new SyncDispatchExtension(cachingAction));

            // When
            String result = proxy.getOrder(999);

            // Then
            assertThat(result).isEqualTo("cached-result");
        }

        @Test
        @DisplayName("should allow a sync layer to retry the target on failure")
        void should_allow_a_sync_layer_to_retry_the_target_on_failure() {
            // Given
            AtomicInteger attempts = new AtomicInteger(0);
            OrderService flaky = new FakeOrderService() {
                @Override
                public String getOrder(long id) {
                    if (attempts.incrementAndGet() < 3) {
                        throw new RuntimeException("transient");
                    }
                    return "order-" + id;
                }
            };

            LayerAction<Void, Object> retryAction = (chainId, callId, arg, next) -> {
                RuntimeException lastError = null;
                for (int i = 0; i < 3; i++) {
                    try {
                        return next.execute(chainId, callId, arg);
                    } catch (RuntimeException e) {
                        lastError = e;
                    }
                }
                throw lastError;
            };

            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, flaky, "retry",
                    new SyncDispatchExtension(retryAction));

            // When
            String result = proxy.getOrder(5);

            // Then
            assertThat(result).isEqualTo("order-5");
            assertThat(attempts.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should allow an async layer to short-circuit with a completed stage")
        void should_allow_an_async_layer_to_short_circuit_with_a_completed_stage() {
            // Given — async layer returns cached result without calling next
            AsyncLayerAction<Void, Object> cachingAction =
                    (chainId, callId, arg, next) -> CompletableFuture.completedFuture("async-cached");

            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "cache",
                    new AsyncDispatchExtension(cachingAction),
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            CompletionStage<String> result = proxy.getOrderAsync(999);

            // Then
            assertThat(result.toCompletableFuture().join()).isEqualTo("async-cached");
        }

        @Test
        @DisplayName("should receive chainId and callId in the layer action")
        void should_receive_chainId_and_callId_in_the_layer_action() {
            // Given
            List<long[]> captured = new ArrayList<>();
            LayerAction<Void, Object> capturingAction = (chainId, callId, arg, next) -> {
                captured.add(new long[]{chainId, callId});
                return next.execute(chainId, callId, arg);
            };

            OrderService proxy = ProxyWrapper.createProxy(
                    OrderService.class, target, "capture",
                    new SyncDispatchExtension(capturingAction));
            long expectedChainId = ((Wrapper<?>) proxy).chainId();

            // When
            proxy.getOrder(1);

            // Then
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0)[0]).isEqualTo(expectedChainId);
            assertThat(captured.get(0)[1]).isGreaterThan(0);
        }
    }

    // ======================== Factory integration ========================

    @Nested
    @DisplayName("Factory integration")
    class FactoryIntegration {

        @Test
        @DisplayName("should create a working sync proxy via InqProxyFactory")
        void should_create_a_working_sync_proxy_via_InqProxyFactory() {
            // Given
            InqProxyFactory factory = InqProxyFactory.of("sync-factory", log.syncAction("factory"));

            // When
            OrderService proxy = factory.protect(OrderService.class, target);
            String result = proxy.getOrder(7);

            // Then
            assertThat(result).isEqualTo("order-7");
            assertThat(log.events).containsExactly("factory:before", "factory:after");
        }

        @Test
        @DisplayName("should create a working async proxy via InqAsyncProxyFactory")
        void should_create_a_working_async_proxy_via_InqAsyncProxyFactory() {
            // Given
            InqAsyncProxyFactory factory = InqAsyncProxyFactory.of(
                    "async-factory",
                    log.syncAction("sync"),
                    log.asyncAction("async"));

            // When
            OrderService proxy = factory.protect(OrderService.class, target);
            CompletionStage<String> asyncResult = proxy.getOrderAsync(3);
            log.events.clear();
            String syncResult = proxy.getOrder(3);

            // Then
            assertThat(asyncResult.toCompletableFuture().join()).isEqualTo("async-order-3");
            assertThat(syncResult).isEqualTo("order-3");
            assertThat(log.events).containsExactly("sync:before", "sync:after");
        }

        @Test
        @DisplayName("should support chaining proxies created by different factories")
        void should_support_chaining_proxies_created_by_different_factories() {
            // Given
            InqAsyncProxyFactory retryFactory = InqAsyncProxyFactory.of(
                    "retry", log.syncAction("retry-sync"), log.asyncAction("retry-async"));
            InqAsyncProxyFactory bulkheadFactory = InqAsyncProxyFactory.of(
                    "bulkhead", log.syncAction("bh-sync"), log.asyncAction("bh-async"));

            // When — bulkhead wraps retry wraps target
            OrderService retryProxy = retryFactory.protect(OrderService.class, target);
            OrderService bulkheadProxy = bulkheadFactory.protect(OrderService.class, retryProxy);

            bulkheadProxy.getOrderAsync(1).toCompletableFuture().join();

            // Then — outer (bulkhead) before inner (retry)
            assertThat(log.events).containsExactly(
                    "bh-async:before", "retry-async:before",
                    "retry-async:after", "bh-async:after");
        }

        @Test
        @DisplayName("should reject non-interface types in factory protect call")
        void should_reject_non_interface_types_in_factory_protect_call() {
            // Given
            InqProxyFactory factory = InqProxyFactory.of("test", LayerAction.passThrough());

            // When / Then
            assertThatThrownBy(() -> factory.protect(FakeOrderService.class, target))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interface");
        }
    }

    // ======================== Edge cases ========================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should throw No catch-all DispatchExtension found")
        void should_throw_no_catch_all_DispatchExtension_found() {
            // Given / When / Then — sync method has no matching extension
            assertThatThrownBy(() -> ProxyWrapper.createProxy(
                    OrderService.class, target, "async-only",
                    new AsyncDispatchExtension(AsyncLayerAction.passThrough())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No valid catch-all DispatchExtension found at the end of the chain");
        }

        @Test
        @DisplayName("should handle null arguments gracefully")
        void should_handle_null_arguments_gracefully() {
            // Given
            NullableService nullTarget = input -> "got:" + input;

            NullableService proxy = ProxyWrapper.createProxy(
                    NullableService.class, nullTarget, "null-test",
                    new SyncDispatchExtension(LayerAction.passThrough()));

            // When
            String result = proxy.process(null);

            // Then
            assertThat(result).isEqualTo("got:null");
        }

        @Test
        @DisplayName("should support proxy wrapping another proxy with different extension sets")
        void should_support_proxy_wrapping_another_proxy_with_different_extension_sets() {
            // Given — inner has sync+async, outer has only sync
            OrderService inner = ProxyWrapper.createProxy(
                    OrderService.class, target, "inner",
                    new AsyncDispatchExtension(log.asyncAction("inner-async")),
                    new SyncDispatchExtension(log.syncAction("inner-sync")));

            OrderService outer = ProxyWrapper.createProxy(
                    OrderService.class, inner, "outer",
                    new SyncDispatchExtension(log.syncAction("outer-sync")));

            // When — sync method through both layers
            String syncResult = outer.getOrder(1);

            // Then
            assertThat(syncResult).isEqualTo("order-1");
            assertThat(log.events).containsExactly(
                    "outer-sync:before", "inner-sync:before",
                    "inner-sync:after", "outer-sync:after");
        }
    }
}
