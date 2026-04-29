package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatNoException;

@DisplayName("SyncPipelineTerminal")
class SyncPipelineTerminalTest {

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Creates a tracing InqDecorator that records enter/exit events.
     */
    private static TracingDecorator tracing(String name, InqElementType type, List<String> trace) {
        return new TracingDecorator(name, type, trace);
    }

    /**
     * Creates a short-circuiting decorator that returns a fixed value
     * without calling next.
     */
    private static InqDecorator<Void, Object> shortCircuiting(
            String name, InqElementType type, Object fixedResult) {
        return new InqDecorator<>() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public InqElementType getElementType() {
                return type;
            }

            @Override
            public InqEventPublisher getEventPublisher() {
                return null;
            }

            @Override
            public Object execute(long chainId, long callId, Void arg,
                                  InternalExecutor<Void, Object> next) {
                return fixedResult;
            }
        };
    }

    /**
     * InqDecorator that records "name:enter" and "name:exit" in a trace list.
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
        public String getName() {
            return name;
        }

        @Override
        public InqElementType getElementType() {
            return type;
        }

        @Override
        public InqEventPublisher getEventPublisher() {
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

    /**
     * A plain InqElement that does NOT implement InqDecorator.
     */
    static class NonDecoratorElement implements InqElement {
        @Override
        public String getName() {
            return "plain";
        }

        @Override
        public InqElementType getElementType() {
            return InqElementType.BULKHEAD;
        }

        @Override
        public InqEventPublisher getEventPublisher() {
            return null;
        }
    }

    // =========================================================================
    // Execute
    // =========================================================================

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void empty_pipeline_passes_through_to_the_core_executor() throws Throwable {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When
            Object result = terminal.execute(() -> "direct");

            // Then
            assertThat(result).isEqualTo("direct");
        }

        @Test
        void single_element_wraps_the_core_executor() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When
            Object result = terminal.execute(() -> {
                trace.add("core");
                return "result";
            });

            // Then
            assertThat(result).isEqualTo("result");
            assertThat(trace).containsExactly("cb:enter", "core", "cb:exit");
        }

        @Test
        void elements_execute_in_pipeline_order_outermost_first() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("rt", InqElementType.RETRY, trace))
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(tracing("tl", InqElementType.TIME_LIMITER, trace))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When
            terminal.execute(() -> {
                trace.add("core");
                return "ok";
            });

            // Then — standard order: TL(100) → CB(500) → RT(600) → core
            assertThat(trace).containsExactly(
                    "tl:enter",
                    "cb:enter",
                    "rt:enter",
                    "core",
                    "rt:exit",
                    "cb:exit",
                    "tl:exit"
            );
        }

        @Test
        void short_circuiting_element_prevents_core_execution() throws Throwable {
            // Given — circuit breaker that short-circuits
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(shortCircuiting("cb", InqElementType.CIRCUIT_BREAKER, "fallback"))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When
            Object result = terminal.execute(() -> {
                throw new AssertionError("Core should not be reached");
            });

            // Then
            assertThat(result).isEqualTo("fallback");
        }

        @Test
        void runtime_exception_from_core_propagates_through_the_chain() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When / Then
            assertThatThrownBy(() -> terminal.execute(() -> {
                throw new IllegalStateException("boom");
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");

            // The element still recorded enter and exit
            assertThat(trace).containsExactly("cb:enter", "cb:exit");
        }

        @Test
        void checked_exception_from_core_propagates_through_the_chain() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When / Then
            assertThatThrownBy(() -> terminal.execute(() -> {
                throw new Exception("checked");
            }))
                    .isInstanceOf(Exception.class)
                    .hasMessage("checked")
                    .isNotInstanceOf(RuntimeException.class);
        }
    }

    // =========================================================================
    // Decorate supplier
    // =========================================================================

    @Nested
    @DisplayName("decorateSupplier")
    class DecorateSupplier {

        @Test
        void decorated_supplier_executes_through_the_pipeline_on_each_get() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When
            Supplier<Object> decorated = terminal.decorateSupplier(() -> "value");
            Object result = decorated.get();

            // Then
            assertThat(result).isEqualTo("value");
            assertThat(trace).containsExactly("cb:enter", "cb:exit");
        }

        @Test
        void decorated_supplier_wraps_checked_exceptions_in_runtime_exception() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            Supplier<Object> decorated = terminal.decorateSupplier(() -> {
                throw new RuntimeException(new Exception("checked from supplier"));
            });

            // When / Then
            assertThatThrownBy(decorated::get)
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void decorated_supplier_is_reusable_across_multiple_calls() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            Supplier<Object> decorated = SyncPipelineTerminal.of(pipeline)
                    .decorateSupplier(() -> "reusable");

            // When — called three times
            decorated.get();
            decorated.get();
            decorated.get();

            // Then — three enter/exit pairs
            assertThat(trace).hasSize(6);
            assertThat(trace).containsExactly(
                    "cb:enter", "cb:exit",
                    "cb:enter", "cb:exit",
                    "cb:enter", "cb:exit"
            );
        }
    }

    // =========================================================================
    // Decorate JoinPointExecutor
    // =========================================================================

    @Nested
    @DisplayName("decorateJoinPoint")
    class DecorateJoinPoint {

        @Test
        void decorated_join_point_can_be_proceeded_later() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(tracing("rt", InqElementType.RETRY, trace))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When — build the chain first, execute later
            JoinPointExecutor<Object> chain = terminal.decorateJoinPoint(() -> {
                trace.add("core");
                return "deferred";
            });
            Object result = chain.proceed();

            // Then
            assertThat(result).isEqualTo("deferred");
            assertThat(trace).containsExactly(
                    "cb:enter", "rt:enter", "core", "rt:exit", "cb:exit");
        }

        @Test
        void decorated_join_point_works_with_proxy_style_executor() throws Throwable {
            // Given — simulates method.invoke(target, args)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When — proxy-style: the executor wraps a reflective call
            JoinPointExecutor<Object> proxyExecutor = () -> "proxy-result";
            JoinPointExecutor<Object> chain = terminal.decorateJoinPoint(proxyExecutor);

            // Then
            assertThat(chain.proceed()).isEqualTo("proxy-result");
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

// =========================================================================
    // Error handling
    // =========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void non_decorator_element_produces_descriptive_class_cast_exception_at_construction() {
            // Given — an InqElement that does NOT implement InqDecorator.
            //         Layer actions are pre-extracted in SyncPipelineTerminal's
            //         constructor, so validation happens eagerly at of(...) —
            //         not lazily on the first execute(...) call.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new NonDecoratorElement())
                    .build();

            // When / Then — of(...) fails fast with a descriptive message
            assertThatThrownBy(() -> SyncPipelineTerminal.of(pipeline))
                    .isInstanceOf(ClassCastException.class)
                    .hasMessageContaining("does not implement InqDecorator")
                    .hasMessageContaining("plain")
                    .hasMessageContaining("BULKHEAD")
                    .hasMessageContaining("SyncPipelineTerminal");
        }

        @Test
        void construction_with_only_decorator_elements_succeeds_without_throwing() {
            // Given — a pipeline with exclusively InqDecorator elements.
            //         Documents the happy-path counterpart to the fail-fast
            //         contract above.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .shield(tracing("rt", InqElementType.RETRY, new ArrayList<>()))
                    .build();

            // When / Then — construction completes, no exception thrown
            assertThatNoException().isThrownBy(() -> SyncPipelineTerminal.of(pipeline));
        }

        @Test
        void null_pipeline_is_rejected() {
            // Given — no setup needed

            // When / Then
            assertThatThrownBy(() -> SyncPipelineTerminal.of(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Dispatch-agnostic usage
    // =========================================================================

    @Nested
    @DisplayName("Dispatch-agnostic usage")
    class DispatchAgnostic {

        @Test
        void same_pipeline_same_terminal_three_dispatch_styles() throws Throwable {
            // Given — one pipeline, one terminal
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When — Function-style (programmatic)
            trace.clear();
            terminal.execute(() -> "function");
            assertThat(trace).containsExactly("cb:enter", "cb:exit");

            // When — Proxy-style (dynamic proxy)
            trace.clear();
            terminal.execute(() -> "proxy");
            assertThat(trace).containsExactly("cb:enter", "cb:exit");

            // When — AspectJ-style (pjp::proceed)
            trace.clear();
            terminal.execute(() -> "aspectj");
            assertThat(trace).containsExactly("cb:enter", "cb:exit");

            // Then — all three produce identical trace patterns
        }
    }
}
