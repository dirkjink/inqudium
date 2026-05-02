package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the contract of {@link PipelineDispatchExtension}: catch-all sync
 * dispatch driven by an {@link InqPipeline}, exact-type linking with
 * cache inheritance, and one-time chain-factory composition.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PipelineDispatchExtensionTest {

    // ======================== Test doubles ========================

    interface GreetingService {
        String greet(String name);

        String farewell(String name);
    }

    static class RealGreetingService implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public String farewell(String name) {
            return "Goodbye, " + name + "!";
        }
    }

    /**
     * Real {@link InqDecorator} that records enter/exit events plus the
     * chain-id and call-id observed at each layer. Used in lieu of pure
     * mocks because the pipeline-fold logic itself is part of what these
     * tests verify.
     */
    static class TracingDecorator implements InqDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;
        private final List<String> trace;

        TracingDecorator(String name, InqElementType type, List<String> trace) {
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
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            trace.add(name + ":enter");
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                trace.add(name + ":exit");
            }
        }
    }

    // ======================== Reflection helpers ========================

    private static MethodHandleCache readHandleCache(PipelineDispatchExtension ext) throws Exception {
        Field f = PipelineDispatchExtension.class.getDeclaredField("handleCache");
        f.setAccessible(true);
        return (MethodHandleCache) f.get(ext);
    }

    private static Object readOverrideTarget(PipelineDispatchExtension ext) throws Exception {
        Field f = PipelineDispatchExtension.class.getDeclaredField("overrideTarget");
        f.setAccessible(true);
        return f.get(ext);
    }

    private static Function<?, ?> readChainFactory(PipelineDispatchExtension ext) throws Exception {
        Field f = PipelineDispatchExtension.class.getDeclaredField("chainFactory");
        f.setAccessible(true);
        return (Function<?, ?>) f.get(ext);
    }

    private static DispatchExtension[] readProxyExtensions(Object proxy) throws Exception {
        Object handler = Proxy.getInvocationHandler(proxy);
        Field f = ProxyWrapper.class.getDeclaredField("extensions");
        f.setAccessible(true);
        return (DispatchExtension[]) f.get(handler);
    }

    // ======================== Tests ========================

    @Nested
    class BasicDispatch {

        @Test
        void dispatches_a_method_through_the_pipeline() {
            // Given — a single-element pipeline drives the catch-all extension
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            PipelineDispatchExtension extension = new PipelineDispatchExtension(pipeline);
            GreetingService proxy = ProxyWrapper.createProxy(
                    GreetingService.class, new RealGreetingService(), "pipeline-layer", extension);

            // When
            String result = proxy.greet("World");

            // Then — element fired around the call, target was reached
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }

        @Test
        void multi_element_pipeline_runs_in_outermost_first_order() {
            // Given — three elements added in arbitrary order; standard ordering
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            GreetingService proxy = ProxyWrapper.createProxy(
                    GreetingService.class, new RealGreetingService(), "pipeline",
                    new PipelineDispatchExtension(pipeline));

            // When
            proxy.greet("World");

            // Then — standard order: BH(400) → CB(500) → RT(600) → target
            assertThat(trace).containsExactly(
                    "BH:enter", "CB:enter", "RT:enter",
                    "RT:exit", "CB:exit", "BH:exit");
        }

        @Test
        void is_catch_all_and_handles_every_method() throws NoSuchMethodException {
            // Given
            PipelineDispatchExtension extension = new PipelineDispatchExtension(
                    InqPipeline.builder().build());

            // Then — both methods on the test interface are accepted, and
            // isCatchAll signals the extension's role to ProxyWrapper
            Method greet = GreetingService.class.getMethod("greet", String.class);
            Method farewell = GreetingService.class.getMethod("farewell", String.class);
            assertThat(extension.canHandle(greet)).isTrue();
            assertThat(extension.canHandle(farewell)).isTrue();
            assertThat(extension.isCatchAll()).isTrue();
        }

        @Test
        void null_pipeline_throws_npe_at_construction() {
            // When / Then — defensive check on the public root constructor
            assertThatThrownBy(() -> new PipelineDispatchExtension(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Pipeline must not be null");
        }
    }

    @Nested
    class CacheSharing {

        @Test
        void linked_extension_inherits_handle_cache_from_outer() throws Exception {
            // Given — a root outer and a root inner each have their own caches
            PipelineDispatchExtension outer = new PipelineDispatchExtension(
                    InqPipeline.builder().build());
            PipelineDispatchExtension inner = new PipelineDispatchExtension(
                    InqPipeline.builder().build());
            MethodHandleCache outerCache = readHandleCache(outer);
            MethodHandleCache innerCache = readHandleCache(inner);
            assertThat(outerCache)
                    .as("Sanity: independent root extensions have independent caches")
                    .isNotSameAs(innerCache);

            // When — the outer is linked against an array containing the inner
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{inner}, new RealGreetingService());

            // Then — the linked instance is a new object that *inherits* the
            // outer's cache rather than allocating a fresh one. This is the
            // property that breaks if someone "fixes" the linked constructor
            // to allocate a new MethodHandleCache.
            assertThat(linked).isInstanceOf(PipelineDispatchExtension.class);
            assertThat(linked).isNotSameAs(outer);
            assertThat(readHandleCache((PipelineDispatchExtension) linked))
                    .as("Linked instance must inherit the outer extension's MethodHandleCache, "
                            + "not allocate a fresh one")
                    .isSameAs(outerCache);
        }

        @Test
        void standalone_fallback_preserves_existing_cache() throws Exception {
            // Given — outer with no compatible inner counterpart available
            PipelineDispatchExtension outer = new PipelineDispatchExtension(
                    InqPipeline.builder().build());
            MethodHandleCache outerCache = readHandleCache(outer);

            // When — linkInner with only a SyncDispatchExtension (different type, no match)
            SyncDispatchExtension syncExt = new SyncDispatchExtension(
                    (cid, caid, a, next) -> next.execute(cid, caid, a));
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{syncExt}, new RealGreetingService());

            // Then — a fresh standalone instance is returned; the existing handle
            // cache is preserved (no fresh allocation in the no-match path either)
            assertThat(linked).isInstanceOf(PipelineDispatchExtension.class);
            assertThat(linked).isNotSameAs(outer);
            assertThat(readHandleCache((PipelineDispatchExtension) linked))
                    .as("Standalone-fallback must preserve the existing handle cache")
                    .isSameAs(outerCache);
            assertThat(readOverrideTarget((PipelineDispatchExtension) linked))
                    .as("Without an inner match, overrideTarget stays null")
                    .isNull();
        }
    }

    @Nested
    class ChainWalkOptimization {

        @Test
        void linked_outer_invokes_inner_executeChain_directly() {
            // What is being tested?
            //   When two PipelineDispatchExtension proxies are stacked, the outer
            //   proxy's linked extension calls the inner extension's executeChain
            //   directly — both pipelines run, both decorators fire, in correct
            //   nesting order around a single target invocation.
            // How is success deemed?
            //   The trace shows outer-element enter/exit wrapping inner-element
            //   enter/exit, and the final result is produced by the deep target.
            //   If the chain-walk were broken (inner skipped, or outer skipped,
            //   or order inverted), the trace would diverge.
            // Why is this important?
            //   This is the property that makes chain-walk linking valuable —
            //   correctness of the bypass-proxy-re-entry optimization. A regression
            //   here would silently drop a layer or change ordering.

            // Given — stack two PipelineDispatchExtension-driven proxies
            List<String> trace = new ArrayList<>();
            InqPipeline innerPipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .build();
            InqPipeline outerPipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            GreetingService innerProxy = ProxyWrapper.createProxy(
                    GreetingService.class, new RealGreetingService(), "inner",
                    new PipelineDispatchExtension(innerPipeline));
            GreetingService outerProxy = ProxyWrapper.createProxy(
                    GreetingService.class, innerProxy, "outer",
                    new PipelineDispatchExtension(outerPipeline));

            // When
            String result = outerProxy.greet("World");

            // Then — outer pipeline wraps inner pipeline, both fire, target runs once
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(trace).containsExactly(
                    "CB:enter", "RT:enter",
                    "RT:exit", "CB:exit");
        }

        @Test
        void linked_outer_uses_realTarget_as_terminal() throws Exception {
            // Given — stack outer over inner; both pipelines empty so the linked
            // walk simplifies to a direct invocation against realTarget
            RealGreetingService realTarget = new RealGreetingService();
            GreetingService innerProxy = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget, "inner",
                    new PipelineDispatchExtension(InqPipeline.builder().build()));
            GreetingService outerProxy = ProxyWrapper.createProxy(
                    GreetingService.class, innerProxy, "outer",
                    new PipelineDispatchExtension(InqPipeline.builder().build()));

            // When — read the outer ProxyWrapper's actual (linked) extensions
            DispatchExtension[] outerExts = readProxyExtensions(outerProxy);

            // Then — the outer extension was linked, so its terminal target
            // is the deep realTarget instance, not the inner JDK proxy
            PipelineDispatchExtension outerLinked = (PipelineDispatchExtension) outerExts[0];
            assertThat(readOverrideTarget(outerLinked))
                    .as("Linked outer's terminal target must be the deep real target, "
                            + "not the inner proxy")
                    .isSameAs(realTarget);

            // Sanity: end-to-end dispatch through the linked walk still works
            assertThat(outerProxy.greet("World")).isEqualTo("Hello, World!");
        }
    }

    @Nested
    class TypeStrictFindInner {

        @Test
        void findInner_does_not_match_SyncDispatchExtension() throws Exception {
            // Given — outer is PipelineDispatchExtension; inner array has only a SyncDispatchExtension
            PipelineDispatchExtension outer = new PipelineDispatchExtension(
                    InqPipeline.builder().build());
            SyncDispatchExtension syncInner = new SyncDispatchExtension(
                    (cid, caid, a, next) -> next.execute(cid, caid, a));

            // When
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{syncInner}, new RealGreetingService());

            // Then — exact-type match fails; the no-inner-found branch is taken,
            // so overrideTarget stays null even though realTarget was supplied.
            assertThat(linked).isInstanceOf(PipelineDispatchExtension.class);
            assertThat(readOverrideTarget((PipelineDispatchExtension) linked))
                    .as("findInner must not match SyncDispatchExtension — different "
                            + "chain-composition strategies cannot share a chain walk")
                    .isNull();
        }

        @Test
        void findInner_does_not_match_other_extension_types() throws Exception {
            // Given — a generic catch-all DispatchExtension that is NOT a PipelineDispatchExtension
            PipelineDispatchExtension outer = new PipelineDispatchExtension(
                    InqPipeline.builder().build());
            DispatchExtension other = new DispatchExtension() {
                @Override
                public boolean canHandle(Method method) {
                    return true;
                }

                @Override
                public boolean isCatchAll() {
                    return true;
                }

                @Override
                public Object dispatch(long chainId, long callId,
                                       Method method, Object[] args, Object target) {
                    return null;
                }
            };

            // When
            DispatchExtension linked = outer.linkInner(
                    new DispatchExtension[]{other}, new RealGreetingService());

            // Then — no exact-type match; the no-inner-found branch is taken
            assertThat(readOverrideTarget((PipelineDispatchExtension) linked)).isNull();
        }
    }

    @Nested
    class LayerDescriptions {

        @Test
        void layerDescriptions_returns_outermost_first_for_a_three_element_pipeline() {
            // Given — three elements added in deliberately mixed order; the
            // pipeline's standard ordering puts BH(400) outside CB(500) outside RT(600)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("orderRt", InqElementType.RETRY, new ArrayList<>()))
                    .shield(new TracingDecorator("orderBh", InqElementType.BULKHEAD, new ArrayList<>()))
                    .shield(new TracingDecorator("orderCb", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .build();
            PipelineDispatchExtension extension = new PipelineDispatchExtension(pipeline);

            // When
            List<String> descriptions = extension.layerDescriptions();

            // Then — outermost-first, formatted as ELEMENT_TYPE(name)
            assertThat(descriptions).containsExactly(
                    "BULKHEAD(orderBh)",
                    "CIRCUIT_BREAKER(orderCb)",
                    "RETRY(orderRt)");
        }

        @Test
        void layerDescriptions_returns_a_single_element_list_for_a_one_element_pipeline() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("loneCb", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .build();
            PipelineDispatchExtension extension = new PipelineDispatchExtension(pipeline);

            // When / Then
            assertThat(extension.layerDescriptions()).containsExactly("CIRCUIT_BREAKER(loneCb)");
        }

        @Test
        void layerDescriptions_returns_an_empty_list_for_an_empty_pipeline() {
            // What is being tested?
            //   The empty-pipeline corner: InqPipeline accepts a no-element
            //   build (validated by isEmpty() and depth() == 0), so the
            //   extension's diagnostic must not throw and must reflect that
            //   emptiness as an empty list rather than null or a placeholder.
            // How is success deemed?
            //   layerDescriptions() returns a non-null, empty list.
            // Why is this important?
            //   Consumers (startup logging, topology inspectors) can iterate
            //   the result without null-checking. An empty pipeline is a
            //   pass-through and should be reported as "zero layers", not as
            //   an error or as a synthetic placeholder layer.

            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            PipelineDispatchExtension extension = new PipelineDispatchExtension(pipeline);

            // When / Then
            assertThat(extension.layerDescriptions()).isNotNull().isEmpty();
        }

        @Test
        void layerDescriptions_format_is_ELEMENT_TYPE_paren_name_paren() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("checkoutBh", InqElementType.BULKHEAD, new ArrayList<>()))
                    .build();
            PipelineDispatchExtension extension = new PipelineDispatchExtension(pipeline);

            // When
            String entry = extension.layerDescriptions().get(0);

            // Then — format is exactly the enum name plus "(name)"
            assertThat(entry).isEqualTo("BULKHEAD(checkoutBh)");
        }

        @Test
        void layerDescriptions_returns_immutable_list() {
            // What is being tested?
            //   The contract that the returned list cannot be mutated by a
            //   caller — pinning the immutability promise so that consumers
            //   never accidentally corrupt a future-cached snapshot or
            //   surprise another reader by adding a synthetic entry.
            // How is success deemed?
            //   Calls to mutating List operations throw UnsupportedOperationException.
            // Why is this important?
            //   The JavaDoc promises an immutable list. Future consumers
            //   (startup logging, the bulkhead-logging refactor) will share
            //   these descriptions across threads and assume they cannot
            //   change underneath them. A regression that returned a mutable
            //   list would break that promise silently.

            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("orderBh", InqElementType.BULKHEAD, new ArrayList<>()))
                    .build();
            PipelineDispatchExtension extension = new PipelineDispatchExtension(pipeline);

            // When
            List<String> descriptions = extension.layerDescriptions();

            // Then — every mutation surface throws
            assertThatThrownBy(() -> descriptions.add("BULKHEAD(injected)"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> descriptions.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(descriptions::clear)
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Idempotence {

        @Test
        void chain_factory_is_built_once_and_reused_across_dispatches() {
            // What is being tested?
            //   The chain factory is composed exactly once at construction
            //   time and reused for every method invocation; rebuilding it
            //   per call would defeat the caching that justifies the design.
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
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .build();
            PipelineDispatchExtension extension = new PipelineDispatchExtension(pipeline);
            Function<?, ?> factoryAtConstruction;
            try {
                factoryAtConstruction = readChainFactory(extension);
            } catch (Exception e) {
                throw new AssertionError("Failed to read chainFactory field", e);
            }
            GreetingService proxy = ProxyWrapper.createProxy(
                    GreetingService.class, new RealGreetingService(),
                    "pipeline", extension);

            // When — dispatch multiple times, multiple methods
            proxy.greet("Alice");
            proxy.farewell("Bob");
            proxy.greet("Carol");

            // Then — the factory reference is unchanged (final field, never reassigned)
            Function<?, ?> factoryAfterDispatches;
            try {
                factoryAfterDispatches = readChainFactory(extension);
            } catch (Exception e) {
                throw new AssertionError("Failed to read chainFactory field", e);
            }
            assertThat(factoryAfterDispatches)
                    .as("ChainFactory must be built once at construction and reused; "
                            + "rebuilding per dispatch would defeat the caching")
                    .isSameAs(factoryAtConstruction);

            // And the trace confirms each dispatch ran the full element stack:
            // 3 calls × 2 elements × 2 events (enter + exit) = 12 records
            assertThat(trace).hasSize(12);
        }
    }
}
