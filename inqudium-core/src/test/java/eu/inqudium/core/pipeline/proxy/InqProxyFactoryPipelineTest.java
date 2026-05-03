package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.Wrapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the contract of the {@link InqProxyFactory#of(InqPipeline)} family of
 * factory methods. The pipeline-driven factory must produce
 * {@link Wrapper}-conforming proxies (the property that the bulkhead-logging
 * refactor 6.D depends on), route every method through the pipeline via the
 * single catch-all {@link PipelineDispatchExtension}, and use the documented
 * default layer name for {@code toString} consistency with the predecessor
 * terminal-based mechanism.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InqProxyFactoryPipelineTest {

    // ======================== Test doubles ========================

    interface GreetingService {
        String greet(String name);

        int countLetters(String text);
    }

    static class RealGreetingService implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public int countLetters(String text) {
            return text.length();
        }
    }

    /**
     * Real {@link InqDecorator} that records enter/exit events. Used in lieu
     * of mocks because the routing through the pipeline fold is part of what
     * these tests verify.
     */
    static class TracingDecorator implements InqDecorator<Void, Object> {

        private final String name;
        private final List<String> trace;

        TracingDecorator(String name, List<String> trace) {
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
            trace.add(name + ":enter");
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                trace.add(name + ":exit");
            }
        }
    }

    // ======================== Tests ========================

    @Nested
    class OfPipeline {

        @Test
        void of_pipeline_produces_a_Wrapper_conforming_proxy() {
            // Given — empty pipeline is enough; we only assert the proxy shape
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            GreetingService proxy = InqProxyFactory.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // Then — Wrapper-conformance is the property 6.D will rely on:
            // every pipeline-driven proxy must be cast-able to Wrapper for
            // chainId / inner / toStringHierarchy introspection.
            assertThat(proxy).isInstanceOf(Wrapper.class);
            assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        }

        @Test
        void of_pipeline_proxy_has_a_positive_chainId() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            GreetingService proxy = InqProxyFactory.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // Then — PipelineIds.nextChainId allocates monotonically increasing
            // positive longs; each fresh proxy gets a unique positive value.
            assertThat(((Wrapper<?>) proxy).chainId()).isPositive();
        }

        @Test
        void of_pipeline_routes_calls_through_the_pipeline() {
            // Given — single-element pipeline so any breakage in routing
            // shows up immediately in the trace
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", trace))
                    .build();

            // When
            GreetingService proxy = InqProxyFactory.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());
            String result = proxy.greet("World");

            // Then — element fired around the call, target was reached
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }

        @Test
        void of_pipeline_with_default_name_uses_InqPipelineProxy_label() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            GreetingService proxy = InqProxyFactory.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // Then — the default name pins the layerDescription that appears
            // as the toString prefix in AbstractProxyWrapper.handleObjectMethod's
            // "<layerDescription> -> <realTarget>" format.
            assertThat(((Wrapper<?>) proxy).layerDescription()).isEqualTo("InqPipelineProxy");
        }

        @Test
        void of_pipeline_with_explicit_name_uses_that_name() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            GreetingService proxy = InqProxyFactory.of("custom-layer", pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // Then
            assertThat(((Wrapper<?>) proxy).layerDescription()).isEqualTo("custom-layer");
        }

        @Test
        void of_pipeline_throws_when_pipeline_is_null() {
            // When / Then — defensive null check on both overloads
            assertThatThrownBy(() -> InqProxyFactory.of((InqPipeline) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Pipeline must not be null");
            assertThatThrownBy(() -> InqProxyFactory.of("any-name", (InqPipeline) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Pipeline must not be null");
        }

        @Test
        void two_pipeline_proxies_around_the_same_target_are_equal() {
            // What is being tested?
            //   The Real-Target equals contract on pipeline-driven proxies:
            //   two distinct proxy instances wrapping the same real target
            //   compare equal via the AbstractProxyWrapper.handleEquals
            //   semantics.
            // How is success deemed?
            //   proxy1.equals(proxy2) is true even though proxy1 != proxy2
            //   as object references; the symmetric proxy2.equals(proxy1)
            //   also holds.
            // Why is this important?
            //   The previous ProxyPipelineTerminal used identity equals,
            //   meaning two proxy instances around the same target compared
            //   unequal. The consolidation intentionally changed this to
            //   real-target equals — which is the correct semantics for
            //   service-method proxies. This test pins the new behavior on
            //   the pipeline-factory surface specifically; without it, a
            //   future refactor could revert the property here without any
            //   factory-level test failing.

            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            GreetingService target = new RealGreetingService();

            // When — two separate factories, same target
            GreetingService proxy1 = InqProxyFactory.of(pipeline)
                    .protect(GreetingService.class, target);
            GreetingService proxy2 = InqProxyFactory.of(pipeline)
                    .protect(GreetingService.class, target);

            // Then
            assertThat(proxy1).isNotSameAs(proxy2);  // distinct instances
            assertThat(proxy1).isEqualTo(proxy2);    // but real-target equal
            assertThat(proxy2).isEqualTo(proxy1);    // and symmetric
            assertThat(proxy1.hashCode()).isEqualTo(proxy2.hashCode());
        }

        @Test
        void toString_uses_layerDescription_then_realTarget_format() {
            // What is being tested?
            //   The exact toString format produced by
            //   AbstractProxyWrapper.handleObjectMethod on pipeline-driven
            //   proxies: "<layerDescription> -> <realTarget.toString()>".
            // How is success deemed?
            //   The proxy's toString matches the format exactly, with the
            //   default layer name and the real target's own toString.
            // Why is this important?
            //   The previous ProxyPipelineTerminal produced a custom summary
            //   format from ProxyInvocationSupport.buildSummary. The
            //   consolidation deletes that mechanism and uses
            //   AbstractProxyWrapper's standard format. This test pins the
            //   new format on the factory surface so a future change to
            //   AbstractProxyWrapper.handleObjectMethod that subtly altered
            //   the format would surface in the factory tests.

            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            RealGreetingService target = new RealGreetingService();

            // When
            GreetingService proxy = InqProxyFactory.of(pipeline)
                    .protect(GreetingService.class, target);

            // Then — the exact format from AbstractProxyWrapper.handleObjectMethod
            assertThat(proxy.toString())
                    .isEqualTo("InqPipelineProxy -> " + target.toString());
        }

        @Test
        void of_pipeline_proxy_supports_inner_and_toStringHierarchy() {
            // What is being tested?
            //   The Wrapper-introspection contract on pipeline-driven proxies:
            //   inner() and toStringHierarchy() must work on every instance
            //   produced by InqProxyFactory.of(pipeline).
            // How is success deemed?
            //   inner() returns null on a single proxy (no inner wrapper);
            //   toStringHierarchy() begins with the standard "Chain-ID: "
            //   prefix and contains the layer name.
            // Why is this important?
            //   These are the two introspection methods 6.D's topology log
            //   relies on — losing either property would block 6.D's resumption.

            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            GreetingService proxy = InqProxyFactory.of("layer", pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // When
            Wrapper<?> wrapper = (Wrapper<?>) proxy;

            // Then
            assertThat(wrapper.inner()).isNull();
            assertThat(wrapper.toStringHierarchy())
                    .startsWith("Chain-ID: ")
                    .contains("layer");
        }
    }
}
