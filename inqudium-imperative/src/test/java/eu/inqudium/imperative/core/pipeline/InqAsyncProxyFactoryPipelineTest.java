package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.core.pipeline.proxy.DispatchExtension;
import eu.inqudium.core.pipeline.proxy.PipelineDispatchExtension;
import eu.inqudium.core.pipeline.proxy.ProxyWrapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the contract of the {@link InqAsyncProxyFactory#of(InqPipeline)} family
 * of factory methods. The hybrid factory must produce
 * {@link Wrapper}-conforming proxies, route sync methods through the catch-all
 * {@link PipelineDispatchExtension} and {@link CompletionStage}-returning
 * methods through the specific {@link AsyncPipelineDispatchExtension}, and
 * pin the critical extension order [async, sync] that
 * {@link ProxyWrapper#createProxy} validation requires.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InqAsyncProxyFactoryPipelineTest {

    // ======================== Test doubles ========================

    interface HybridOrderService {
        String placeOrder(String item);

        CompletionStage<String> placeOrderAsync(String item);
    }

    static class RealHybridOrderService implements HybridOrderService {
        @Override
        public String placeOrder(String item) {
            return "ordered:" + item;
        }

        @Override
        public CompletionStage<String> placeOrderAsync(String item) {
            return CompletableFuture.completedFuture("async-ordered:" + item);
        }
    }

    interface AsyncOnlyService {
        CompletionStage<String> fetchAsync(String key);
    }

    static class RealAsyncOnlyService implements AsyncOnlyService {
        @Override
        public CompletionStage<String> fetchAsync(String key) {
            return CompletableFuture.completedFuture("value-" + key);
        }
    }

    interface SyncOnlyService {
        String hello(String name);
    }

    static class RealSyncOnlyService implements SyncOnlyService {
        @Override
        public String hello(String name) {
            return "hi-" + name;
        }
    }

    /**
     * Element that participates in BOTH sync and async dispatch by implementing
     * {@link InqDecorator} and {@link InqAsyncDecorator}. Records each path's
     * enter/exit events into a shared trace so a hybrid test can verify which
     * extension a given call routed through.
     */
    static class HybridTracingElement implements InqDecorator<Void, Object>,
            InqAsyncDecorator<Void, Object> {

        private final String name;
        private final List<String> trace;

        HybridTracingElement(String name, List<String> trace) {
            this.name = name;
            this.trace = trace;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return InqElementType.CIRCUIT_BREAKER;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            trace.add(name + ":sync:enter");
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                trace.add(name + ":sync:exit");
            }
        }

        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Void arg,
                                                    InternalAsyncExecutor<Void, Object> next) {
            trace.add(name + ":async:enter");
            return next.executeAsync(chainId, callId, arg)
                    .whenComplete((r, e) -> trace.add(name + ":async:exit"));
        }
    }

    // ======================== Reflection helpers ========================

    private static DispatchExtension[] readProxyExtensions(Object proxy) throws Exception {
        Object handler = Proxy.getInvocationHandler(proxy);
        Field f = ProxyWrapper.class.getDeclaredField("extensions");
        f.setAccessible(true);
        return (DispatchExtension[]) f.get(handler);
    }

    // ======================== Tests ========================

    @Nested
    class OfPipeline {

        @Test
        void of_pipeline_produces_a_Wrapper_conforming_proxy() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            HybridOrderService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());

            // Then — the property 6.D will rely on for hybrid services
            assertThat(proxy).isInstanceOf(Wrapper.class);
            assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        }

        @Test
        void of_pipeline_proxy_has_a_positive_chainId() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            HybridOrderService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());

            // Then
            assertThat(((Wrapper<?>) proxy).chainId()).isPositive();
        }

        @Test
        void of_pipeline_routes_sync_methods_through_the_sync_extension() {
            // Given — a hybrid element that participates in both paths and
            // tags the trace with "sync" or "async" so we can tell which
            // extension claimed the call
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new HybridTracingElement("CB", trace))
                    .build();

            // When — a sync method on a hybrid service
            HybridOrderService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());
            String result = proxy.placeOrder("Widget");

            // Then — sync entry/exit (NOT async): the catch-all sync extension
            // claimed the call because canHandle on the async extension
            // returned false for the non-CompletionStage return type.
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(trace).containsExactly("CB:sync:enter", "CB:sync:exit");
        }

        @Test
        void of_pipeline_routes_async_methods_through_the_async_extension() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new HybridTracingElement("CB", trace))
                    .build();

            // When — a CompletionStage-returning method on the same hybrid service
            HybridOrderService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());
            CompletionStage<String> stage = proxy.placeOrderAsync("Widget");
            String result = stage.toCompletableFuture().join();

            // Then — async entry/exit (NOT sync): the specific async extension
            // claimed the call because canHandle returned true for the
            // CompletionStage return type.
            assertThat(result).isEqualTo("async-ordered:Widget");
            assertThat(trace).containsExactly("CB:async:enter", "CB:async:exit");
        }

        @Test
        void of_pipeline_dispatches_correctly_on_a_hybrid_interface() {
            // What is being tested?
            //   A single proxy created via InqAsyncProxyFactory.of(pipeline)
            //   correctly routes BOTH a sync and an async method on the same
            //   hybrid service interface — sync method through the sync
            //   chain, async method through the async chain.
            // How is success deemed?
            //   The trace contains exactly four entries in the expected order:
            //   the sync call's enter/exit (sync-tagged) followed by the
            //   async call's enter/exit (async-tagged). If routing were
            //   wrong, the wrong tag would appear for one of the calls.
            // Why is this important?
            //   Hybrid dispatch is the entire point of this factory — it
            //   replaces the deleted HybridProxyPipelineTerminal. A regression
            //   here would silently break hybrid services that previously
            //   relied on the terminal.

            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new HybridTracingElement("CB", trace))
                    .build();
            HybridOrderService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());

            // When — both a sync and an async call on the same proxy
            String syncResult = proxy.placeOrder("Sync");
            String asyncResult = proxy.placeOrderAsync("Async").toCompletableFuture().join();

            // Then — sync call routed sync, async call routed async, both
            // produced the expected business results
            assertThat(syncResult).isEqualTo("ordered:Sync");
            assertThat(asyncResult).isEqualTo("async-ordered:Async");
            assertThat(trace).containsExactly(
                    "CB:sync:enter", "CB:sync:exit",
                    "CB:async:enter", "CB:async:exit");
        }

        @Test
        void of_pipeline_extension_order_async_first_then_sync_catchall() throws Exception {
            // What is being tested?
            //   The ProxyWrapper extensions array on a proxy produced by
            //   InqAsyncProxyFactory.of(pipeline) is exactly:
            //     [AsyncPipelineDispatchExtension, PipelineDispatchExtension]
            //   in this order, with the catch-all (sync) at the end.
            // How is success deemed?
            //   Reflective access to the extensions field shows two entries
            //   in the documented order. ProxyWrapper.validateExtensions
            //   would have thrown at construction if catch-all were not last,
            //   but this test fails earlier (and with a clearer message)
            //   if a future refactor swaps them.
            // Why is this important?
            //   The order is what makes hybrid dispatch work: async first
            //   (specific), sync last (catch-all). Reverse ordering would
            //   route every method through the sync chain — including async
            //   ones, breaking CompletionStage semantics silently.

            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            HybridOrderService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());

            // When
            DispatchExtension[] extensions = readProxyExtensions(proxy);

            // Then
            assertThat(extensions).hasSize(2);
            assertThat(extensions[0])
                    .as("Index 0 must be the specific async extension "
                            + "(canHandle true only for CompletionStage returns)")
                    .isInstanceOf(AsyncPipelineDispatchExtension.class);
            assertThat(extensions[1])
                    .as("Index 1 must be the catch-all sync extension "
                            + "(isCatchAll true; required to be last)")
                    .isInstanceOf(PipelineDispatchExtension.class);
            assertThat(extensions[0].isCatchAll())
                    .as("Async extension must NOT be catch-all")
                    .isFalse();
            assertThat(extensions[1].isCatchAll())
                    .as("Sync extension MUST be catch-all (validates last-position rule)")
                    .isTrue();
        }

        @Test
        void of_pipeline_routes_async_only_service_correctly() {
            // Given — service interface with only CompletionStage methods;
            // every call goes through the async extension. Pipeline is empty
            // so no element-level shaping interferes.
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            AsyncOnlyService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(AsyncOnlyService.class, new RealAsyncOnlyService());
            String result = proxy.fetchAsync("foo").toCompletableFuture().join();

            // Then
            assertThat(result).isEqualTo("value-foo");
            assertThat(((Wrapper<?>) proxy).chainId()).isPositive();
        }

        @Test
        void of_pipeline_routes_sync_only_service_through_catchall() {
            // Given — service with no async methods; the catch-all sync
            // extension claims every call
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            SyncOnlyService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(SyncOnlyService.class, new RealSyncOnlyService());
            String result = proxy.hello("World");

            // Then
            assertThat(result).isEqualTo("hi-World");
            assertThat(proxy).isInstanceOf(Wrapper.class);
        }

        @Test
        void of_pipeline_with_default_name_uses_InqHybridPipelineProxy_label() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            HybridOrderService proxy = InqAsyncProxyFactory.of(pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());

            // Then — pinned to keep toString diagnostics consistent with the
            // predecessor terminal-based mechanism's "HybridPipelineProxy"
            // prefix in ProxyInvocationSupport.buildSummary(...).
            assertThat(((Wrapper<?>) proxy).layerDescription())
                    .isEqualTo("InqHybridPipelineProxy");
        }

        @Test
        void of_pipeline_with_explicit_name_uses_that_name() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            HybridOrderService proxy = InqAsyncProxyFactory.of("custom-hybrid", pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());

            // Then
            assertThat(((Wrapper<?>) proxy).layerDescription()).isEqualTo("custom-hybrid");
        }

        @Test
        void of_pipeline_throws_when_pipeline_is_null() {
            // When / Then — defensive null check on both overloads
            assertThatThrownBy(() -> InqAsyncProxyFactory.of((InqPipeline) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Pipeline must not be null");
            assertThatThrownBy(() -> InqAsyncProxyFactory.of("any-name", (InqPipeline) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Pipeline must not be null");
        }

        @Test
        void of_pipeline_proxy_supports_inner_and_toStringHierarchy() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            HybridOrderService proxy = InqAsyncProxyFactory.of("layer", pipeline)
                    .protect(HybridOrderService.class, new RealHybridOrderService());

            // When
            Wrapper<?> wrapper = (Wrapper<?>) proxy;

            // Then — Wrapper introspection contract on hybrid proxies
            assertThat(wrapper.inner()).isNull();
            assertThat(wrapper.toStringHierarchy())
                    .startsWith("Chain-ID: ")
                    .contains("layer");
        }
    }
}
