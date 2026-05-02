package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.proxy.DispatchExtension;
import eu.inqudium.core.pipeline.proxy.MethodHandleCache;
import eu.inqudium.core.pipeline.proxy.PipelineDispatchExtension;
import eu.inqudium.core.pipeline.proxy.ProxyWrapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the contract of {@link AsyncPipelineDispatchExtension}: async-only
 * (non-catch-all) dispatch driven by an {@link InqPipeline}, exact-type linking
 * with cache inheritance, one-time async chain-factory composition, and the
 * defensive runtime checks for the {@link CompletionStage} return value.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AsyncPipelineDispatchExtensionTest {

    // ======================== Test doubles ========================

    interface AsyncOrderService {
        CompletionStage<String> placeOrderAsync(String item);

        CompletionStage<String> cancelOrderAsync(String item);

        String syncStatus();
    }

    static class RealAsyncOrderService implements AsyncOrderService {
        @Override
        public CompletionStage<String> placeOrderAsync(String item) {
            return CompletableFuture.completedFuture("ordered:" + item);
        }

        @Override
        public CompletionStage<String> cancelOrderAsync(String item) {
            return CompletableFuture.completedFuture("cancelled:" + item);
        }

        @Override
        public String syncStatus() {
            return "OK";
        }
    }

    /**
     * Real {@link InqAsyncDecorator} that records enter/exit events around an
     * async chain. Uses {@code whenComplete} to record the exit so the trace
     * captures both phases (start phase synchronous, end phase asynchronous).
     */
    static class TracingAsyncDecorator implements InqAsyncDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;
        private final List<String> trace;

        TracingAsyncDecorator(String name, InqElementType type, List<String> trace) {
            this.name = name;
            this.type = type;
            this.trace = trace;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return type;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Void arg,
                                                    InternalAsyncExecutor<Void, Object> next) {
            trace.add(name + ":enter");
            return next.executeAsync(chainId, callId, arg)
                    .whenComplete((r, e) -> trace.add(name + ":exit"));
        }
    }

    // ======================== Reflection helpers ========================

    private static MethodHandleCache readHandleCache(AsyncPipelineDispatchExtension ext) throws Exception {
        Field f = AsyncPipelineDispatchExtension.class.getDeclaredField("handleCache");
        f.setAccessible(true);
        return (MethodHandleCache) f.get(ext);
    }

    private static Object readOverrideTarget(AsyncPipelineDispatchExtension ext) throws Exception {
        Field f = AsyncPipelineDispatchExtension.class.getDeclaredField("overrideTarget");
        f.setAccessible(true);
        return f.get(ext);
    }

    private static Function<?, ?> readChainFactory(AsyncPipelineDispatchExtension ext) throws Exception {
        Field f = AsyncPipelineDispatchExtension.class.getDeclaredField("chainFactory");
        f.setAccessible(true);
        return (Function<?, ?>) f.get(ext);
    }

    private static DispatchExtension[] readProxyExtensions(Object proxy) throws Exception {
        Object handler = Proxy.getInvocationHandler(proxy);
        Field f = ProxyWrapper.class.getDeclaredField("extensions");
        f.setAccessible(true);
        return (DispatchExtension[]) f.get(handler);
    }

    /**
     * Builds a proxy with an {@link AsyncPipelineDispatchExtension} as the
     * specific async handler followed by a {@link PipelineDispatchExtension}
     * as the catch-all sync handler. This is the standard hybrid-dispatch
     * topology — async methods route through the async extension, sync
     * methods fall through to the catch-all.
     */
    private static AsyncOrderService createHybridProxy(InqPipeline asyncPipeline,
                                                       AsyncOrderService target,
                                                       String layerName,
                                                       AsyncPipelineDispatchExtension asyncExt) {
        return ProxyWrapper.createProxy(
                AsyncOrderService.class, target, layerName,
                asyncExt,
                new PipelineDispatchExtension(InqPipeline.builder().build()));
    }

    // ======================== Tests ========================

    @Nested
    class BasicDispatch {

        @Test
        void dispatches_an_async_method_through_the_pipeline() {
            // Given — a single-element async pipeline
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingAsyncDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AsyncPipelineDispatchExtension extension = new AsyncPipelineDispatchExtension(pipeline);
            AsyncOrderService proxy = createHybridProxy(
                    pipeline, new RealAsyncOrderService(), "async-pipeline-layer", extension);

            // When
            CompletionStage<String> stage = proxy.placeOrderAsync("Widget");
            String result = stage.toCompletableFuture().join();

            // Then — async element fired around the call, target reached
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }

        @Test
        void multi_element_pipeline_runs_in_outermost_first_order_for_async() {
            // Given — three async elements added in arbitrary order; standard ordering
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingAsyncDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new TracingAsyncDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingAsyncDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AsyncOrderService proxy = createHybridProxy(
                    pipeline, new RealAsyncOrderService(), "async-pipeline",
                    new AsyncPipelineDispatchExtension(pipeline));

            // When
            String result = proxy.placeOrderAsync("Widget").toCompletableFuture().join();

            // Then — same standard order as sync: BH(400) → CB(500) → RT(600) → target
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(trace).containsExactly(
                    "BH:enter", "CB:enter", "RT:enter",
                    "RT:exit", "CB:exit", "BH:exit");
        }

        @Test
        void canHandle_returns_true_only_for_CompletionStage_returns() throws NoSuchMethodException {
            // Given
            AsyncPipelineDispatchExtension extension = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());

            // When / Then — async method (CompletionStage return) → true
            Method asyncMethod = AsyncOrderService.class.getMethod("placeOrderAsync", String.class);
            assertThat(extension.canHandle(asyncMethod))
                    .as("CompletionStage-returning method must be handled")
                    .isTrue();

            // And — sync method (String return) → false
            Method syncMethod = AsyncOrderService.class.getMethod("syncStatus");
            assertThat(extension.canHandle(syncMethod))
                    .as("Sync-only method must NOT be handled — falls through to catch-all")
                    .isFalse();
        }

        @Test
        void is_not_catch_all() {
            // What is being tested?
            //   AsyncPipelineDispatchExtension must explicitly opt out of the
            //   catch-all role. ProxyWrapper validates that exactly one
            //   catch-all is registered last; if this extension claimed
            //   catch-all, registering it before another catch-all would fail
            //   construction, and registering it alone would route sync-only
            //   methods through the async chain.
            // How is success deemed?
            //   isCatchAll() returns false.
            // Why is this important?
            //   A future regression that flipped this to true would route
            //   sync methods through async dispatch, hitting the runtime
            //   instanceof CompletionStage check and breaking the contract
            //   silently for any service with mixed sync+async methods.

            // Given
            AsyncPipelineDispatchExtension extension = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());

            // Then
            assertThat(extension.isCatchAll())
                    .as("AsyncPipelineDispatchExtension must not be a catch-all — "
                            + "sync-only methods must fall through to the catch-all behind it")
                    .isFalse();
        }

        @Test
        void null_pipeline_throws_npe_at_construction() {
            // When / Then — defensive check on the public root constructor
            assertThatThrownBy(() -> new AsyncPipelineDispatchExtension(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Pipeline must not be null");
        }
    }

    @Nested
    class CacheSharing {

        @Test
        void linked_extension_inherits_handle_cache_from_outer() throws Exception {
            // Given — a root outer and a root inner each have their own caches
            AsyncPipelineDispatchExtension outer = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());
            AsyncPipelineDispatchExtension inner = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());
            MethodHandleCache outerCache = readHandleCache(outer);
            MethodHandleCache innerCache = readHandleCache(inner);
            assertThat(outerCache)
                    .as("Sanity: independent root extensions have independent caches")
                    .isNotSameAs(innerCache);

            // When — the outer is linked against an array containing the inner
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{inner}, new RealAsyncOrderService());

            // Then — the linked instance is a new object that *inherits* the
            // outer's cache rather than allocating a fresh one. This is the
            // property that breaks if someone "fixes" the linked constructor
            // to allocate a new MethodHandleCache.
            assertThat(linked).isInstanceOf(AsyncPipelineDispatchExtension.class);
            assertThat(linked).isNotSameAs(outer);
            assertThat(readHandleCache((AsyncPipelineDispatchExtension) linked))
                    .as("Linked instance must inherit the outer extension's MethodHandleCache, "
                            + "not allocate a fresh one")
                    .isSameAs(outerCache);
        }

        @Test
        void standalone_fallback_preserves_existing_cache() throws Exception {
            // Given — outer with no compatible inner counterpart available
            AsyncPipelineDispatchExtension outer = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());
            MethodHandleCache outerCache = readHandleCache(outer);

            // When — linkInner with only an AsyncDispatchExtension (different type, no match)
            AsyncDispatchExtension legacyAsync = new AsyncDispatchExtension(
                    (cid, caid, a, next) -> next.executeAsync(cid, caid, a));
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{legacyAsync}, new RealAsyncOrderService());

            // Then — a fresh standalone instance is returned; the existing handle
            // cache is preserved (no fresh allocation in the no-match path either)
            assertThat(linked).isInstanceOf(AsyncPipelineDispatchExtension.class);
            assertThat(linked).isNotSameAs(outer);
            assertThat(readHandleCache((AsyncPipelineDispatchExtension) linked))
                    .as("Standalone-fallback must preserve the existing handle cache")
                    .isSameAs(outerCache);
            assertThat(readOverrideTarget((AsyncPipelineDispatchExtension) linked))
                    .as("Without an inner match, overrideTarget stays null")
                    .isNull();
        }
    }

    @Nested
    class ChainWalkOptimization {

        @Test
        void linked_outer_invokes_inner_executeChain_directly() {
            // What is being tested?
            //   When two AsyncPipelineDispatchExtension proxies are stacked,
            //   the outer proxy's linked extension calls the inner extension's
            //   executeChain directly — both async pipelines run, both
            //   decorators fire, in correct nesting order around a single
            //   target invocation.
            // How is success deemed?
            //   The trace shows outer-element enter/exit wrapping inner-element
            //   enter/exit, and the resolved stage value is produced by the
            //   deep target. If chain-walk were broken (inner skipped, outer
            //   skipped, or order inverted), the trace would diverge.
            // Why is this important?
            //   The async chain-walk optimization is the property that makes
            //   stacked proxies cheap. A regression here would silently drop
            //   a layer or change ordering, with no test in the sync path
            //   catching it.

            // Given — stack two AsyncPipelineDispatchExtension-driven proxies
            List<String> trace = new ArrayList<>();
            InqPipeline innerPipeline = InqPipeline.builder()
                    .shield(new TracingAsyncDecorator("RT", InqElementType.RETRY, trace))
                    .build();
            InqPipeline outerPipeline = InqPipeline.builder()
                    .shield(new TracingAsyncDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AsyncOrderService innerProxy = createHybridProxy(
                    innerPipeline, new RealAsyncOrderService(), "inner",
                    new AsyncPipelineDispatchExtension(innerPipeline));
            AsyncOrderService outerProxy = createHybridProxy(
                    outerPipeline, innerProxy, "outer",
                    new AsyncPipelineDispatchExtension(outerPipeline));

            // When
            String result = outerProxy.placeOrderAsync("Widget").toCompletableFuture().join();

            // Then — outer pipeline wraps inner pipeline, both fire, target runs once
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(trace).containsExactly(
                    "CB:enter", "RT:enter",
                    "RT:exit", "CB:exit");
        }

        @Test
        void linked_outer_uses_realTarget_as_terminal() throws Exception {
            // Given — stack outer over inner; both pipelines empty so the linked
            // walk simplifies to a direct invocation against realTarget
            RealAsyncOrderService realTarget = new RealAsyncOrderService();
            AsyncOrderService innerProxy = createHybridProxy(
                    InqPipeline.builder().build(), realTarget, "inner",
                    new AsyncPipelineDispatchExtension(InqPipeline.builder().build()));
            AsyncOrderService outerProxy = createHybridProxy(
                    InqPipeline.builder().build(), innerProxy, "outer",
                    new AsyncPipelineDispatchExtension(InqPipeline.builder().build()));

            // When — read the outer ProxyWrapper's actual (linked) extensions
            DispatchExtension[] outerExts = readProxyExtensions(outerProxy);

            // Then — the outer extension was linked, so its terminal target
            // is the deep realTarget instance, not the inner JDK proxy.
            // (The first extension is the AsyncPipelineDispatchExtension; the
            // second is the catch-all PipelineDispatchExtension.)
            AsyncPipelineDispatchExtension outerLinked = (AsyncPipelineDispatchExtension) outerExts[0];
            assertThat(readOverrideTarget(outerLinked))
                    .as("Linked outer's terminal target must be the deep real target, "
                            + "not the inner proxy")
                    .isSameAs(realTarget);

            // Sanity: end-to-end dispatch through the linked walk still works
            assertThat(outerProxy.placeOrderAsync("Widget").toCompletableFuture().join())
                    .isEqualTo("ordered:Widget");
        }
    }

    @Nested
    class TypeStrictFindInner {

        @Test
        void findInner_does_not_match_AsyncDispatchExtension() throws Exception {
            // What is being tested?
            //   AsyncDispatchExtension (single AsyncLayerAction) and
            //   AsyncPipelineDispatchExtension (full InqPipeline) both handle
            //   CompletionStage methods, but their chain composition differs
            //   fundamentally. A linked walk that mixed the two would compose
            //   one chain on the inner half and a different one on the outer
            //   half, with no shared invariants.
            // How is success deemed?
            //   findInner must NOT match an AsyncDispatchExtension; the
            //   no-match branch fires, overrideTarget stays null.
            // Why is this important?
            //   If findInner used `instanceof AsyncDispatchExtension` (the
            //   wrong supertype, since the two classes are unrelated siblings),
            //   stacked async proxies of mixed types would silently optimize
            //   into a malformed chain walk.

            // Given — outer is AsyncPipelineDispatchExtension; inner array has only an AsyncDispatchExtension
            AsyncPipelineDispatchExtension outer = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());
            AsyncDispatchExtension legacyAsyncInner = new AsyncDispatchExtension(
                    (cid, caid, a, next) -> next.executeAsync(cid, caid, a));

            // When
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{legacyAsyncInner}, new RealAsyncOrderService());

            // Then — exact-type match fails; the no-inner-found branch is taken,
            // so overrideTarget stays null even though realTarget was supplied.
            assertThat(linked).isInstanceOf(AsyncPipelineDispatchExtension.class);
            assertThat(readOverrideTarget((AsyncPipelineDispatchExtension) linked))
                    .as("findInner must not match AsyncDispatchExtension — different "
                            + "chain-composition strategies cannot share a chain walk")
                    .isNull();
        }

        @Test
        void findInner_does_not_match_other_extension_types() throws Exception {
            // Given — a generic non-catch-all DispatchExtension that is NOT an AsyncPipelineDispatchExtension
            AsyncPipelineDispatchExtension outer = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());
            DispatchExtension other = new DispatchExtension() {
                @Override
                public boolean canHandle(Method method) {
                    return CompletionStage.class.isAssignableFrom(method.getReturnType());
                }

                @Override
                public boolean isCatchAll() {
                    return false;
                }

                @Override
                public Object dispatch(long chainId, long callId,
                                       Method method, Object[] args, Object target) {
                    return null;
                }
            };

            // When
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{other}, new RealAsyncOrderService());

            // Then — no exact-type match; the no-inner-found branch is taken
            assertThat(readOverrideTarget((AsyncPipelineDispatchExtension) linked)).isNull();
        }
    }

    @Nested
    class RuntimeReturnValueCheck {

        /**
         * Service whose async method signature returns CompletionStage but
         * the implementation returns null at runtime.
         */
        interface NullReturningAsyncService {
            CompletionStage<String> fetchData();
        }

        @Test
        void dispatch_throws_when_target_method_returns_null() {
            // What is being tested?
            //   buildTerminal's defensive null check fires when a target
            //   method declared to return CompletionStage actually returns
            //   null at runtime. canHandle accepts the method based on the
            //   declared return type — the null check is the safety net for
            //   a misbehaving implementation.
            // How is success deemed?
            //   An IllegalStateException is thrown with a descriptive message
            //   that names the method and the expected type. The error
            //   surfaces immediately rather than as deferred heap pollution.
            // Why is this important?
            //   The async chain expects to receive a CompletionStage to
            //   register completion handlers on; a null target return would
            //   cause an NPE much later in the chain, far from the actual
            //   problem. The descriptive error makes diagnosis trivial.

            // Given — target returns null for an async-declared method
            NullReturningAsyncService target = () -> null;
            AsyncPipelineDispatchExtension asyncExt = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());
            NullReturningAsyncService proxy = ProxyWrapper.createProxy(
                    NullReturningAsyncService.class, target, "null-returning",
                    asyncExt,
                    new PipelineDispatchExtension(InqPipeline.builder().build()));

            // When / Then — failure surfaces via the failed stage (sync throws
            // are lifted by executeChain into a failed CompletionStage)
            CompletionStage<String> stage = proxy.fetchData();
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("returned null")
                    .hasMessageContaining("fetchData")
                    .hasMessageContaining("CompletionStage");
        }

        @Test
        void dispatch_throws_when_target_method_returns_non_CompletionStage() {
            // What is being tested?
            //   buildTerminal's defensive instanceof check catches a target
            //   that violates its declared return type by producing a
            //   non-CompletionStage value at runtime. In practice, the JVM's
            //   own checkcast in the proxy stub catches this first; the
            //   instanceof guard is defense-in-depth for direct invocation
            //   paths and future JVM behavior changes.
            // How is success deemed?
            //   The dispatch surfaces the failure via the returned stage —
            //   either as a ClassCastException (JVM-level) or an
            //   IllegalStateException (our guard) inside the failed stage.
            // Why is this important?
            //   The defensive check exists for the misconfigured-pipeline
            //   case where elements claim a CompletionStage return but
            //   produce something else. The error must surface at invocation
            //   time, not as a deferred ClassCastException.

            // Given — a JDK proxy whose handler returns a String for an
            //         async-declared method. The proxy stub's own checkcast
            //         to CompletionStage is the first line of defense; our
            //         terminal's instanceof check is the second.
            NullReturningAsyncService brokenTarget = (NullReturningAsyncService) Proxy.newProxyInstance(
                    NullReturningAsyncService.class.getClassLoader(),
                    new Class<?>[]{NullReturningAsyncService.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("fetchData")) {
                            return "I am not a CompletionStage";
                        }
                        return null;
                    });

            AsyncPipelineDispatchExtension asyncExt = new AsyncPipelineDispatchExtension(
                    InqPipeline.builder().build());
            NullReturningAsyncService proxy = ProxyWrapper.createProxy(
                    NullReturningAsyncService.class, brokenTarget, "broken",
                    asyncExt,
                    new PipelineDispatchExtension(InqPipeline.builder().build()));

            // When / Then — either the JVM's checkcast (ClassCastException) or
            //               our terminal guard (IllegalStateException) catches
            //               it. Both surface inside the failed stage.
            CompletionStage<String> stage = proxy.fetchData();
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .satisfies(t -> assertThat(t.getCause())
                            .isInstanceOfAny(ClassCastException.class, IllegalStateException.class));
        }
    }

    @Nested
    class Idempotence {

        @Test
        void chain_factory_is_built_once_and_reused_across_dispatches() {
            // What is being tested?
            //   The async chain factory is composed exactly once at
            //   construction time and reused for every method invocation;
            //   rebuilding it per call would defeat the caching that
            //   justifies the design.
            // How is success deemed?
            //   The chainFactory field reference observed before any dispatch
            //   is identical (same Function instance) to the one observed
            //   after several dispatches across multiple methods.
            // Why is this important?
            //   The whole point of pre-composing the chain at construction
            //   time is to make the per-call hot path one factory.apply
            //   plus one chain.proceed. If the factory were rebuilt per
            //   dispatch, the hot path would also pay the pipeline.chain
            //   fold cost on every call.

            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingAsyncDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(new TracingAsyncDecorator("RT", InqElementType.RETRY, trace))
                    .build();
            AsyncPipelineDispatchExtension extension = new AsyncPipelineDispatchExtension(pipeline);
            Function<?, ?> factoryAtConstruction;
            try {
                factoryAtConstruction = readChainFactory(extension);
            } catch (Exception e) {
                throw new AssertionError("Failed to read chainFactory field", e);
            }
            AsyncOrderService proxy = createHybridProxy(
                    pipeline, new RealAsyncOrderService(), "async-pipeline", extension);

            // When — dispatch multiple times, multiple methods
            proxy.placeOrderAsync("Alice").toCompletableFuture().join();
            proxy.cancelOrderAsync("Bob").toCompletableFuture().join();
            proxy.placeOrderAsync("Carol").toCompletableFuture().join();

            // Then — the factory reference is unchanged (final field, never reassigned)
            Function<?, ?> factoryAfterDispatches;
            try {
                factoryAfterDispatches = readChainFactory(extension);
            } catch (Exception e) {
                throw new AssertionError("Failed to read chainFactory field", e);
            }
            assertThat(factoryAfterDispatches)
                    .as("Async chainFactory must be built once at construction and reused; "
                            + "rebuilding per dispatch would defeat the caching")
                    .isSameAs(factoryAtConstruction);

            // And the trace confirms each dispatch ran the full element stack:
            // 3 calls × 2 elements × 2 events (enter + exit) = 12 records
            assertThat(trace).hasSize(12);
        }
    }
}
