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

            // Then — the default name pins the toString hierarchy prefix
            // produced by ProxyInvocationSupport.buildSummary(...) for the
            // old terminal mechanism, keeping diagnostics familiar.
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
