package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.PipelineOrdering;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AspectPipelineTerminal")
class AspectPipelineTerminalTest {

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * InqDecorator stub that records enter/exit events in a trace list.
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
     * Minimal ProceedingJoinPoint stub — only implements {@code proceed()}.
     * No Mockito dependency required.
     */
    static class StubProceedingJoinPoint implements ProceedingJoinPoint {

        private final ThrowingSupplier<Object> action;

        StubProceedingJoinPoint(ThrowingSupplier<Object> action) {
            this.action = action;
        }

        @Override
        public Object proceed() throws Throwable {
            return action.get();
        }

        @Override public Object proceed(Object[] args) throws Throwable { return proceed(); }
        @Override public void set$AroundClosure(org.aspectj.runtime.internal.AroundClosure arc) {}
        @Override public String toShortString() { return "stub-pjp"; }
        @Override public String toLongString() { return "stub-pjp"; }
        @Override public Object getThis() { return null; }
        @Override public Object getTarget() { return null; }
        @Override public Object[] getArgs() { return new Object[0]; }
        @Override public Signature getSignature() { return null; }
        @Override public org.aspectj.lang.reflect.SourceLocation getSourceLocation() { return null; }
        @Override public String getKind() { return "method-execution"; }
        @Override public StaticPart getStaticPart() { return null; }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * Creates a PJP stub that returns the given value on proceed().
     */
    private static ProceedingJoinPoint pjpReturning(Object value) {
        return new StubProceedingJoinPoint(() -> value);
    }

    /**
     * Creates a PJP stub that executes the given action on proceed().
     */
    private static ProceedingJoinPoint pjpAction(ThrowingSupplier<Object> action) {
        return new StubProceedingJoinPoint(action);
    }

    /**
     * Creates a PJP stub that throws the given exception on proceed().
     */
    private static ProceedingJoinPoint pjpThrowing(Throwable exception) {
        return new StubProceedingJoinPoint(() -> { throw exception; });
    }

    // =========================================================================
    // executeAround with ProceedingJoinPoint
    // =========================================================================

    @Nested
    @DisplayName("executeAround (ProceedingJoinPoint)")
    class ExecuteAround {

        private List<String> trace;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
        }

        @Test
        void empty_pipeline_passes_through_to_pjp_proceed() throws Throwable {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);
            ProceedingJoinPoint pjp = pjpReturning("direct-result");

            // When
            Object result = terminal.executeAround(pjp);

            // Then
            assertThat(result).isEqualTo("direct-result");
        }

        @Test
        void single_element_wraps_pjp_proceed() throws Throwable {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);
            ProceedingJoinPoint pjp = pjpReturning("wrapped");

            // When
            Object result = terminal.executeAround(pjp);

            // Then
            assertThat(result).isEqualTo("wrapped");
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }

        @Test
        void elements_execute_in_pipeline_order_around_pjp() throws Throwable {
            // Given — added in arbitrary order, sorted by PipelineOrdering
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

            ProceedingJoinPoint pjp = pjpAction(() -> {
                trace.add("PJP:proceed");
                return "pjp-result";
            });

            // When
            Object result = terminal.executeAround(pjp);

            // Then — standard order: BH(400) → CB(500) → RT(600) → PJP
            assertThat(result).isEqualTo("pjp-result");
            assertThat(trace).containsExactly(
                    "BH:enter",
                    "CB:enter",
                    "RT:enter",
                    "PJP:proceed",
                    "RT:exit",
                    "CB:exit",
                    "BH:exit"
            );
        }

        @Test
        void exception_from_pjp_propagates_through_all_layers() throws Throwable {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);
            ProceedingJoinPoint pjp = pjpThrowing(
                    new IllegalStateException("business error"));

            // When / Then
            assertThatThrownBy(() -> terminal.executeAround(pjp))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("business error");

            // Both layers recorded enter and exit
            assertThat(trace).containsExactly(
                    "BH:enter", "CB:enter", "CB:exit", "BH:exit");
        }

        @Test
        void checked_exception_from_pjp_propagates_unwrapped() throws Throwable {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);
            ProceedingJoinPoint pjp = pjpThrowing(new Exception("checked"));

            // When / Then — checked exception preserved, not wrapped
            assertThatThrownBy(() -> terminal.executeAround(pjp))
                    .isInstanceOf(Exception.class)
                    .hasMessage("checked")
                    .isNotInstanceOf(RuntimeException.class);
        }
    }

    // =========================================================================
    // execute (JoinPointExecutor) — for unit tests without weaving
    // =========================================================================

    @Nested
    @DisplayName("execute (JoinPointExecutor)")
    class ExecuteJoinPoint {

        private List<String> trace;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
        }

        @Test
        void executes_through_the_pipeline_with_a_plain_lambda() throws Throwable {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

            // When — no ProceedingJoinPoint, just a lambda
            Object result = terminal.execute(() -> {
                trace.add("CORE");
                return "lambda-result";
            });

            // Then
            assertThat(result).isEqualTo("lambda-result");
            assertThat(trace).containsExactly(
                    "BH:enter", "CB:enter", "CORE", "CB:exit", "BH:exit");
        }

        @Test
        void execute_and_execute_around_produce_identical_traces() throws Throwable {
            // Given — same pipeline, two terminals (or same terminal)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

            // When — via execute (lambda)
            terminal.execute(() -> "value");
            List<String> lambdaTrace = List.copyOf(trace);

            // When — via executeAround (stubbed PJP)
            trace.clear();
            terminal.executeAround(pjpReturning("value"));
            List<String> pjpTrace = List.copyOf(trace);

            // Then — identical execution order
            assertThat(lambdaTrace).isEqualTo(pjpTrace);
        }
    }

    // =========================================================================
    // Ordering
    // =========================================================================

    @Nested
    @DisplayName("Pipeline ordering")
    class Ordering {

        private List<String> trace;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
        }

        @Test
        void standard_ordering_is_applied_by_default() throws Throwable {
            // Given — all six element types, shuffled
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("TL", InqElementType.TIME_LIMITER, trace))
                    .shield(new TracingDecorator("RL", InqElementType.RATE_LIMITER, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(new TracingDecorator("TS", InqElementType.TRAFFIC_SHAPER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

            // When
            terminal.execute(() -> {
                trace.add("CORE");
                return "ok";
            });

            // Then — ADR-017 canonical: TL → TS → RL → BH → CB → RT → CORE
            assertThat(trace).startsWith(
                    "TL:enter", "TS:enter", "RL:enter",
                    "BH:enter", "CB:enter", "RT:enter",
                    "CORE");
        }

        @Test
        void resilience4j_ordering_reverses_the_chain() throws Throwable {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .order(PipelineOrdering.resilience4j())
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

            // When
            terminal.execute(() -> {
                trace.add("CORE");
                return "ok";
            });

            // Then — R4J: RT(100) → CB(200) → BH(600) → CORE
            assertThat(trace).startsWith(
                    "RT:enter", "CB:enter", "BH:enter", "CORE");
        }
    }

    // =========================================================================
    // decorateJoinPoint
    // =========================================================================

    @Nested
    @DisplayName("decorateJoinPoint")
    class DecorateJoinPoint {

        @Test
        void decorated_executor_can_be_proceeded_later() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

            // When — build chain now, execute later
            var chain = terminal.decorateJoinPoint(() -> "deferred");
            assertThat(trace).isEmpty();

            Object result = chain.proceed();

            // Then
            assertThat(result).isEqualTo("deferred");
            assertThat(trace).containsExactly("CB:enter", "CB:exit");
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    @Nested
    @DisplayName("Diagnostics")
    class Diagnostics {

        @Test
        void pipeline_is_accessible_for_introspection() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TracingDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new TracingDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

            // Then
            assertThat(terminal.pipeline()).isSameAs(pipeline);
            assertThat(terminal.pipeline().elements())
                    .extracting(InqElement::getName)
                    .containsExactly("BH", "CB");
            assertThat(terminal.pipeline().depth()).isEqualTo(2);
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void null_pipeline_is_rejected() {
            assertThatThrownBy(() -> AspectPipelineTerminal.of(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Comparison: old way vs new way (documentation test)
    // =========================================================================

    @Nested
    @DisplayName("Old way vs new way comparison")
    class OldVsNewComparison {

        @Test
        void new_composition_based_approach_produces_same_result_as_inheritance() throws Throwable {
            // ===== OLD WAY: extends AbstractPipelineAspect =====
            // Requires: AspectLayerProvider with manual order() values
            // Requires: extending AbstractPipelineAspect
            // Requires: overriding layerProviders() or passing to super()
            var trace1 = new ArrayList<String>();
            AbstractPipelineAspect oldAspect = new AbstractPipelineAspect(List.of(
                    provider("BH", InqElementType.BULKHEAD, 400, trace1),
                    provider("CB", InqElementType.CIRCUIT_BREAKER, 500, trace1)
            )) {};
            Object oldResult = oldAspect.execute(() -> {
                trace1.add("CORE");
                return "old-way";
            });

            // ===== NEW WAY: InqPipeline + AspectPipelineTerminal =====
            // No inheritance, no manual order values, PipelineOrdering handles sorting
            var trace2 = new ArrayList<String>();
            AspectPipelineTerminal terminal = AspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(decorator("CB", InqElementType.CIRCUIT_BREAKER, trace2))
                            .shield(decorator("BH", InqElementType.BULKHEAD, trace2))
                            .build());
            Object newResult = terminal.execute(() -> {
                trace2.add("CORE");
                return "new-way";
            });

            // THEN — identical execution order, identical trace pattern
            assertThat(oldResult).isEqualTo("old-way");
            assertThat(newResult).isEqualTo("new-way");
            assertThat(trace1).isEqualTo(trace2);
        }

        /**
         * Creates an AspectLayerProvider for the old-way test.
         */
        private AspectLayerProvider<Object> provider(
                String name, InqElementType type, int order, List<String> trace) {
            return new AspectLayerProvider<>() {
                @Override public String layerName() { return name; }
                @Override public int order() { return order; }
                @Override
                public eu.inqudium.core.pipeline.LayerAction<Void, Object> layerAction() {
                    return (chainId, callId, arg, next) -> {
                        trace.add(name + ":enter");
                        try {
                            return next.execute(chainId, callId, arg);
                        } finally {
                            trace.add(name + ":exit");
                        }
                    };
                }
            };
        }

        /**
         * Creates an InqDecorator for the new-way test.
         */
        private InqDecorator<Void, Object> decorator(
                String name, InqElementType type, List<String> trace) {
            return new TracingDecorator(name, type, trace);
        }
    }
}
