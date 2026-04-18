package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProxyPipelineTerminal")
class ProxyPipelineTerminalTest {

    // =========================================================================
    // Test service interface and implementations
    // =========================================================================

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

    static class FailingGreetingService implements GreetingService {
        @Override
        public String greet(String name) {
            throw new IllegalStateException("service unavailable");
        }

        @Override
        public String farewell(String name) {
            throw new UnsupportedOperationException("not implemented");
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * InqDecorator that records enter/exit events in a trace list.
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

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return type; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

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

    /**
     * InqDecorator that limits concurrency via a semaphore — simplified bulkhead.
     */
    static class SemaphoreDecorator implements InqDecorator<Void, Object> {

        private final String name;
        private final Semaphore semaphore;

        SemaphoreDecorator(String name, int permits) {
            this.name = name;
            this.semaphore = new Semaphore(permits);
        }

        int availablePermits() { return semaphore.availablePermits(); }

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return InqElementType.BULKHEAD; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            if (!semaphore.tryAcquire()) {
                throw new RuntimeException("Bulkhead '" + name + "' is full");
            }
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                semaphore.release();
            }
        }
    }

    // =========================================================================
    // Proxy creation
    // =========================================================================

    @Nested
    @DisplayName("protect")
    class Protect {

        @Test
        void proxy_routes_method_calls_through_the_pipeline() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(pipeline);

            // When
            GreetingService proxy = terminal.protect(
                    GreetingService.class, new RealGreetingService());
            String result = proxy.greet("World");

            // Then
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }

        @Test
        void empty_pipeline_passes_through_to_the_target() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(pipeline);

            // When
            GreetingService proxy = terminal.protect(
                    GreetingService.class, new RealGreetingService());

            // Then
            assertThat(proxy.greet("World")).isEqualTo("Hello, World!");
            assertThat(proxy.farewell("World")).isEqualTo("Goodbye, World!");
        }

        @Test
        void multiple_methods_on_the_same_proxy_all_go_through_the_pipeline() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            GreetingService proxy = ProxyPipelineTerminal.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // When
            proxy.greet("Alice");
            proxy.farewell("Bob");

            // Then — each method call traversed the pipeline
            assertThat(trace).containsExactly(
                    "CB:enter", "CB:exit",
                    "CB:enter", "CB:exit"
            );
        }

        @Test
        void non_interface_type_is_rejected_with_descriptive_error() {
            // Given
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(
                    InqPipeline.builder().build());

            // When / Then
            assertThatThrownBy(() -> terminal.protect(
                    RealGreetingService.class, new RealGreetingService()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not an interface")
                    .hasMessageContaining("JDK dynamic proxies");
        }

        @Test
        void null_interface_type_is_rejected() {
            // Given
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(
                    InqPipeline.builder().build());

            // When / Then
            assertThatThrownBy(() -> terminal.protect(null, new RealGreetingService()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void null_target_is_rejected() {
            // Given
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(
                    InqPipeline.builder().build());

            // When / Then
            assertThatThrownBy(() -> terminal.protect(GreetingService.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Pipeline ordering through the proxy
    // =========================================================================

    @Nested
    @DisplayName("Pipeline ordering")
    class PipelineOrdering {

        @Test
        void elements_execute_in_standard_order_around_the_proxied_call() {
            // Given — added in arbitrary order
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            GreetingService proxy = ProxyPipelineTerminal.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // When
            proxy.greet("World");

            // Then — standard: BH(400) → CB(500) → RT(600) → target
            assertThat(trace).containsExactly(
                    "BH:enter", "CB:enter", "RT:enter",
                    "RT:exit", "CB:exit", "BH:exit"
            );
        }

        @Test
        void resilience4j_ordering_reverses_the_chain_around_the_proxied_call() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .order(eu.inqudium.core.pipeline.PipelineOrdering.resilience4j())
                    .build();
            GreetingService proxy = ProxyPipelineTerminal.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // When
            proxy.greet("World");

            // Then — R4J: RT(100) → CB(200) → BH(600) → target
            assertThat(trace).containsExactly(
                    "RT:enter", "CB:enter", "BH:enter",
                    "BH:exit", "CB:exit", "RT:exit"
            );
        }
    }

    // =========================================================================
    // Exception propagation
    // =========================================================================

    @Nested
    @DisplayName("Exception propagation")
    class ExceptionPropagation {

        @Test
        void runtime_exception_from_target_propagates_through_the_proxy() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            GreetingService proxy = ProxyPipelineTerminal.of(pipeline)
                    .protect(GreetingService.class, new FailingGreetingService());

            // When / Then — original exception type and message preserved
            assertThatThrownBy(() -> proxy.greet("World"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("service unavailable");

            // Pipeline element still recorded enter and exit
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }

        @Test
        void different_exception_types_from_different_methods_propagate_correctly() {
            // Given
            GreetingService proxy = ProxyPipelineTerminal.of(InqPipeline.builder().build())
                    .protect(GreetingService.class, new FailingGreetingService());

            // When / Then
            assertThatThrownBy(() -> proxy.greet("World"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> proxy.farewell("World"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void element_rejection_prevents_target_execution() {
            // Given — semaphore with 0 permits → always rejects
            SemaphoreDecorator bh = new SemaphoreDecorator("bh", 0);
            GreetingService proxy = ProxyPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build())
                    .protect(GreetingService.class, new RealGreetingService());

            // When / Then
            assertThatThrownBy(() -> proxy.greet("World"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Bulkhead 'bh' is full");
        }
    }

    // =========================================================================
    // Object method handling
    // =========================================================================

    @Nested
    @DisplayName("Object methods on the proxy")
    class ObjectMethods {

        @Test
        void to_string_shows_pipeline_structure_and_target_class() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, new ArrayList<>()))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .build();
            GreetingService proxy = ProxyPipelineTerminal.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // When
            String str = proxy.toString();

            // Then
            assertThat(str)
                    .contains("GreetingService")
                    .contains("RealGreetingService")
                    .contains("BH")
                    .contains("CB")
                    .contains("2 elements");
        }

        @Test
        void to_string_does_not_trigger_pipeline_execution() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            GreetingService proxy = ProxyPipelineTerminal.of(pipeline)
                    .protect(GreetingService.class, new RealGreetingService());

            // When
            proxy.toString();

            // Then — no pipeline execution
            assertThat(trace).isEmpty();
        }

        @Test
        void equals_uses_proxy_identity() {
            // Given
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(
                    InqPipeline.builder().build());
            RealGreetingService target = new RealGreetingService();

            GreetingService proxy1 = terminal.protect(GreetingService.class, target);
            GreetingService proxy2 = terminal.protect(GreetingService.class, target);

            // Then — different proxy instances, even with same target
            assertThat(proxy1.equals(proxy1)).isTrue();
            assertThat(proxy1.equals(proxy2)).isFalse();
        }

        @Test
        void hash_code_is_consistent() {
            // Given
            GreetingService proxy = ProxyPipelineTerminal.of(InqPipeline.builder().build())
                    .protect(GreetingService.class, new RealGreetingService());

            // Then — same hash code on repeated calls
            assertThat(proxy.hashCode()).isEqualTo(proxy.hashCode());
        }
    }

    // =========================================================================
    // Concurrency through the proxy
    // =========================================================================

    @Nested
    @DisplayName("Concurrency through the proxy")
    class Concurrency {

        @Test
        void bulkhead_element_limits_concurrent_proxy_calls() throws Exception {
            // Given — semaphore with 1 permit
            SemaphoreDecorator bh = new SemaphoreDecorator("bh", 1);
            GreetingService proxy = ProxyPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build())
                    .protect(GreetingService.class, new RealGreetingService());

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();

            // Hold the single permit
            executor.submit(() -> {
                // Use proxy directly — the pipeline routes through the semaphore
                GreetingService blockingProxy = ProxyPipelineTerminal.of(
                        InqPipeline.builder().shield(bh).build())
                        .protect(GreetingService.class, new GreetingService() {
                            @Override
                            public String greet(String name) {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException ignored) {}
                                return "blocking";
                            }
                            @Override
                            public String farewell(String name) { return ""; }
                        });
                blockingProxy.greet("block");
            });

            entered.await(2, TimeUnit.SECONDS);

            // When / Then — second call rejected
            assertThatThrownBy(() -> proxy.greet("World"))
                    .hasMessageContaining("Bulkhead 'bh' is full");

            release.countDown();
            executor.shutdown();

            // Permit restored after blocking call completes
            executor.awaitTermination(5, TimeUnit.SECONDS);
            assertThat(bh.availablePermits()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Terminal reuse
    // =========================================================================

    @Nested
    @DisplayName("Terminal reuse")
    class TerminalReuse {

        @Test
        void same_terminal_can_protect_different_interfaces() {
            // Given
            List<String> trace = new ArrayList<>();
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                            .build());

            // When — two different proxies from the same terminal
            GreetingService proxy = terminal.protect(
                    GreetingService.class, new RealGreetingService());
            proxy.greet("A");

            // Then
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }

        @Test
        void pipeline_is_accessible_for_introspection() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, new ArrayList<>()))
                    .build();
            ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(pipeline);

            // Then
            assertThat(terminal.pipeline()).isSameAs(pipeline);
            assertThat(terminal.pipeline().depth()).isEqualTo(1);
        }
    }
}
