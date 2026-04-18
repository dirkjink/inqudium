package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AsyncPipelineTerminal")
class AsyncPipelineTerminalTest {

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Creates a tracing InqAsyncDecorator that records enter/exit events.
     * The exit event is recorded when the downstream CompletionStage completes.
     */
    private static TracingAsyncDecorator tracing(
            String name, InqElementType type, List<String> trace) {
        return new TracingAsyncDecorator(name, type, trace);
    }

    /**
     * InqAsyncDecorator that records "name:enter" on the sync start phase
     * and "name:exit" when the downstream stage completes.
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

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return type; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        @Override
        public CompletionStage<Object> executeAsync(
                long chainId, long callId, Void arg,
                InternalAsyncExecutor<Void, Object> next) {
            trace.add(name + ":enter");
            return next.executeAsync(chainId, callId, arg)
                    .whenComplete((result, error) -> trace.add(name + ":exit"));
        }
    }

    /**
     * InqAsyncDecorator that short-circuits with a fixed completed future.
     */
    private static InqAsyncDecorator<Void, Object> shortCircuiting(
            String name, InqElementType type, Object fixedResult) {
        return new InqAsyncDecorator<>() {
            @Override public String getName() { return name; }
            @Override public InqElementType getElementType() { return type; }
            @Override public InqEventPublisher getEventPublisher() { return null; }

            @Override
            public CompletionStage<Object> executeAsync(
                    long chainId, long callId, Void arg,
                    InternalAsyncExecutor<Void, Object> next) {
                return CompletableFuture.completedFuture(fixedResult);
            }
        };
    }

    /**
     * InqAsyncDecorator that throws synchronously (before creating a stage).
     */
    private static InqAsyncDecorator<Void, Object> syncThrowing(
            String name, InqElementType type, RuntimeException exception) {
        return new InqAsyncDecorator<>() {
            @Override public String getName() { return name; }
            @Override public InqElementType getElementType() { return type; }
            @Override public InqEventPublisher getEventPublisher() { return null; }

            @Override
            public CompletionStage<Object> executeAsync(
                    long chainId, long callId, Void arg,
                    InternalAsyncExecutor<Void, Object> next) {
                throw exception;
            }
        };
    }

    /**
     * A plain InqElement that does NOT implement InqAsyncDecorator.
     */
    static class NonAsyncElement implements InqElement {
        @Override public String getName() { return "plain"; }
        @Override public InqElementType getElementType() { return InqElementType.BULKHEAD; }
        @Override public InqEventPublisher getEventPublisher() { return null; }
    }

    /**
     * Convenience: creates an async executor that completes immediately.
     */
    private static <R> JoinPointExecutor<CompletionStage<R>> asyncExecutor(R value) {
        return () -> CompletableFuture.completedFuture(value);
    }

    // =========================================================================
    // Execute
    // =========================================================================

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void empty_pipeline_passes_through_to_the_async_core_executor() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When
            CompletionStage<Object> stage = terminal.execute(asyncExecutor("direct"));

            // Then
            assertThat(stage.toCompletableFuture().join()).isEqualTo("direct");
        }

        @Test
        void single_element_wraps_the_async_core_executor() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When
            CompletionStage<Object> stage = terminal.execute(() -> {
                trace.add("core");
                return CompletableFuture.completedFuture("result");
            });

            // Then
            assertThat(stage.toCompletableFuture().join()).isEqualTo("result");
            assertThat(trace).containsExactly("cb:enter", "core", "cb:exit");
        }

        @Test
        void elements_execute_in_pipeline_order_outermost_first() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("rt", InqElementType.RETRY, trace))
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(tracing("tl", InqElementType.TIME_LIMITER, trace))
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When
            terminal.execute(() -> {
                trace.add("core");
                return CompletableFuture.completedFuture("ok");
            }).toCompletableFuture().join();

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
        void short_circuiting_element_prevents_core_execution() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(shortCircuiting("cb", InqElementType.CIRCUIT_BREAKER, "fallback"))
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When
            CompletionStage<Object> stage = terminal.execute(() -> {
                throw new AssertionError("Core should not be reached");
            });

            // Then
            assertThat(stage.toCompletableFuture().join()).isEqualTo("fallback");
        }
    }

    // =========================================================================
    // Uniform error channel
    // =========================================================================

    @Nested
    @DisplayName("Uniform error channel")
    class UniformErrorChannel {

        @Test
        void execute_never_throws_even_on_synchronous_failure() {
            // Given — element throws synchronously before creating a stage
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(syncThrowing("cb", InqElementType.CIRCUIT_BREAKER,
                            new IllegalStateException("sync boom")))
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When — execute does NOT throw; the error is in the stage
            CompletionStage<Object> stage = terminal.execute(asyncExecutor("unreachable"));

            // Then — exception delivered via CompletionStage
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("sync boom");
        }

        @Test
        void async_failure_in_the_core_surfaces_through_the_stage() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When — core returns a failed future
            CompletionStage<Object> stage = terminal.execute(
                    () -> CompletableFuture.failedFuture(
                            new RuntimeException("async fail")));

            // Then
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("async fail");

            // The element still recorded enter and exit
            assertThat(trace).containsExactly("cb:enter", "cb:exit");
        }

        @Test
        void core_executor_throwing_synchronously_is_delivered_via_the_stage() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When — core throws before creating a CompletionStage
            CompletionStage<Object> stage = terminal.execute(() -> {
                throw new IllegalArgumentException("bad input");
            });

            // Then — uniform channel: error in the stage, not thrown
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("bad input");
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
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When
            Supplier<CompletionStage<Object>> decorated = terminal.decorateSupplier(
                    () -> CompletableFuture.completedFuture("value"));
            Object result = decorated.get().toCompletableFuture().join();

            // Then
            assertThat(result).isEqualTo("value");
            assertThat(trace).containsExactly("cb:enter", "cb:exit");
        }

        @Test
        void decorated_supplier_never_throws_on_synchronous_failure() {
            // Given — element throws synchronously
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(syncThrowing("cb", InqElementType.CIRCUIT_BREAKER,
                            new IllegalStateException("sync")))
                    .build();

            Supplier<CompletionStage<Object>> decorated =
                    AsyncPipelineTerminal.of(pipeline).decorateSupplier(
                            () -> CompletableFuture.completedFuture("unreachable"));

            // When — supplier.get() does NOT throw
            CompletionStage<Object> stage = decorated.get();

            // Then — error in the stage
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void decorated_supplier_is_reusable_across_multiple_calls() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            Supplier<CompletionStage<Object>> decorated =
                    AsyncPipelineTerminal.of(pipeline).decorateSupplier(
                            () -> CompletableFuture.completedFuture("reusable"));

            // When — called three times
            decorated.get().toCompletableFuture().join();
            decorated.get().toCompletableFuture().join();
            decorated.get().toCompletableFuture().join();

            // Then
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
        void decorated_async_join_point_can_be_proceeded_later() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(tracing("cb", InqElementType.CIRCUIT_BREAKER, trace))
                    .shield(tracing("rt", InqElementType.RETRY, trace))
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When — build chain first, execute later
            JoinPointExecutor<CompletionStage<Object>> chain =
                    terminal.decorateJoinPoint(() -> {
                        trace.add("core");
                        return CompletableFuture.completedFuture("deferred");
                    });
            Object result = chain.proceed().toCompletableFuture().join();

            // Then
            assertThat(result).isEqualTo("deferred");
            assertThat(trace).containsExactly(
                    "cb:enter", "rt:enter", "core", "rt:exit", "cb:exit");
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void non_async_decorator_element_produces_descriptive_class_cast_exception() {
            // Given — an InqElement that does NOT implement InqAsyncDecorator
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new NonAsyncElement())
                    .build();
            AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);

            // When / Then — execute catches the CCE and wraps it in the stage
            CompletionStage<Object> stage = terminal.execute(asyncExecutor("fail"));
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .hasCauseInstanceOf(ClassCastException.class)
                    .hasRootCauseMessage(
                            new NonAsyncElement().getClass().getName()
                                    + " ('plain', type=BULKHEAD) does not implement "
                                    + "InqAsyncDecorator. AsyncPipelineTerminal requires all "
                                    + "pipeline elements to implement InqAsyncDecorator<A, R>. "
                                    + "For sync elements, use SyncPipelineTerminal instead.");
        }

        @Test
        void null_pipeline_is_rejected() {
            // When / Then
            assertThatThrownBy(() -> AsyncPipelineTerminal.of(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
