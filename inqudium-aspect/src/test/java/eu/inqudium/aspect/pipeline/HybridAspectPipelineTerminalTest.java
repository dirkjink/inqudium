package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HybridAspectPipelineTerminal")
class HybridAspectPipelineTerminalTest {

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Dual decorator: implements both sync and async interfaces.
     * Records separate traces for each path to verify correct dispatch.
     */
    static class DualDecorator implements InqDecorator<Void, Object>, InqAsyncDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;
        private final List<String> trace;

        DualDecorator(String name, InqElementType type, List<String> trace) {
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
            trace.add(name + ":sync-enter");
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                trace.add(name + ":sync-exit");
            }
        }

        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Void arg,
                                                     InternalAsyncExecutor<Void, Object> next) {
            trace.add(name + ":async-enter");
            return next.executeAsync(chainId, callId, arg)
                    .whenComplete((r, e) -> trace.add(name + ":async-exit"));
        }
    }

    // -------------------------------------------------------------------------
    // ProceedingJoinPoint stubs (no Mockito)
    // -------------------------------------------------------------------------

    /**
     * Stub service with sync and async methods for Method resolution.
     */
    interface StubService {
        String syncMethod();
        CompletionStage<String> asyncMethod();
    }

    /**
     * Creates a PJP stub for a sync method (returns String).
     */
    private static ProceedingJoinPoint syncPjp(Object returnValue) throws NoSuchMethodException {
        Method method = StubService.class.getMethod("syncMethod");
        return new StubPjp(method, () -> returnValue);
    }

    /**
     * Creates a PJP stub for an async method (returns CompletionStage).
     */
    private static ProceedingJoinPoint asyncPjp(CompletionStage<?> stage) throws NoSuchMethodException {
        Method method = StubService.class.getMethod("asyncMethod");
        return new StubPjp(method, () -> stage);
    }

    /**
     * Creates a PJP stub for a sync method that throws.
     */
    private static ProceedingJoinPoint syncPjpThrowing(Throwable exception) throws NoSuchMethodException {
        Method method = StubService.class.getMethod("syncMethod");
        return new StubPjp(method, () -> { throw exception; });
    }

    /**
     * Minimal ProceedingJoinPoint stub with MethodSignature support.
     */
    static class StubPjp implements ProceedingJoinPoint {

        private final Method method;
        private final ThrowingSupplier action;

        StubPjp(Method method, ThrowingSupplier action) {
            this.method = method;
            this.action = action;
        }

        @Override
        public Object proceed() throws Throwable {
            return action.get();
        }

        @Override
        public org.aspectj.lang.Signature getSignature() {
            // Return a MethodSignature that provides the Method
            return new StubMethodSignature(method);
        }

        @Override public Object proceed(Object[] args) throws Throwable { return proceed(); }
        @Override public void set$AroundClosure(org.aspectj.runtime.internal.AroundClosure arc) {}
        @Override public String toShortString() { return "stub-pjp"; }
        @Override public String toLongString() { return "stub-pjp"; }
        @Override public Object getThis() { return null; }
        @Override public Object getTarget() { return null; }
        @Override public Object[] getArgs() { return new Object[0]; }
        @Override public org.aspectj.lang.reflect.SourceLocation getSourceLocation() { return null; }
        @Override public String getKind() { return "method-execution"; }
        @Override public StaticPart getStaticPart() { return null; }
    }

    /**
     * Minimal MethodSignature stub that provides the Method reference.
     */
    static class StubMethodSignature implements MethodSignature {
        private final Method method;
        StubMethodSignature(Method method) { this.method = method; }

        @Override public Method getMethod() { return method; }
        @Override public Class getReturnType() { return method.getReturnType(); }

        // Remaining MethodSignature methods — not used
        @Override public Class[] getParameterTypes() { return method.getParameterTypes(); }
        @Override public String[] getParameterNames() { return new String[0]; }
        @Override public Class[] getExceptionTypes() { return new Class[0]; }
        @Override public String getName() { return method.getName(); }
        @Override public int getModifiers() { return method.getModifiers(); }
        @Override public Class getDeclaringType() { return method.getDeclaringClass(); }
        @Override public String getDeclaringTypeName() { return method.getDeclaringClass().getName(); }
        @Override public String toShortString() { return method.getName(); }
        @Override public String toLongString() { return method.toString(); }
    }

    @FunctionalInterface
    interface ThrowingSupplier {
        Object get() throws Throwable;
    }

    // =========================================================================
    // Sync vs async dispatch
    // =========================================================================

    @Nested
    @DisplayName("executeAround dispatch")
    class ExecuteAroundDispatch {

        @Test
        void sync_method_goes_through_sync_chain() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                            .build());

            // When
            Object result = terminal.executeAround(syncPjp("sync-result"));

            // Then
            assertThat(result).isEqualTo("sync-result");
            assertThat(trace).containsExactly("BH:sync-enter", "BH:sync-exit");
        }

        @Test
        void async_method_goes_through_async_chain() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                            .build());

            // When
            Object result = terminal.executeAround(
                    asyncPjp(CompletableFuture.completedFuture("async-result")));

            // Then — result is a CompletionStage
            assertThat(result).isInstanceOf(CompletionStage.class);
            @SuppressWarnings("unchecked")
            String value = ((CompletionStage<String>) result).toCompletableFuture().join();
            assertThat(value).isEqualTo("async-result");
            assertThat(trace).containsExactly("BH:async-enter", "BH:async-exit");
        }

        @Test
        void elements_execute_in_standard_order_for_both_paths() throws Throwable {
            // Given — shuffled elements
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("RT", InqElementType.RETRY, trace))
                            .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                            .shield(new DualDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                            .build());

            // When — sync
            terminal.executeAround(syncPjp("ok"));
            List<String> syncTrace = List.copyOf(trace);
            trace.clear();

            // When — async
            terminal.executeAround(asyncPjp(CompletableFuture.completedFuture("ok")));

            // Then — same element order: BH(400) → CB(500) → RT(600)
            assertThat(syncTrace).containsExactly(
                    "BH:sync-enter", "CB:sync-enter", "RT:sync-enter",
                    "RT:sync-exit", "CB:sync-exit", "BH:sync-exit");
            assertThat(trace).containsExactly(
                    "BH:async-enter", "CB:async-enter", "RT:async-enter",
                    "RT:async-exit", "CB:async-exit", "BH:async-exit");
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void sync_exception_propagates_directly() throws Throwable {
            // Given
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder().build());

            // When / Then
            assertThatThrownBy(() -> terminal.executeAround(
                    syncPjpThrowing(new IllegalStateException("sync-boom"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("sync-boom");
        }

        @Test
        void async_failure_delivered_via_stage_not_thrown() throws Throwable {
            // Given
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder().build());

            // When — async method returns failed future
            Object result = terminal.executeAround(
                    asyncPjp(CompletableFuture.failedFuture(
                            new RuntimeException("async-boom"))));

            // Then — result is a stage with the error, no exception thrown
            assertThat(result).isInstanceOf(CompletionStage.class);
            @SuppressWarnings("unchecked")
            CompletionStage<Object> stage = (CompletionStage<Object>) result;
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("async-boom");
        }
    }

    // =========================================================================
    // Generic execute methods (for unit tests without AspectJ)
    // =========================================================================

    @Nested
    @DisplayName("Generic execute methods")
    class GenericExecute {

        @Test
        void execute_routes_through_sync_chain() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                            .build());

            // When — no PJP, just a lambda
            Object result = terminal.execute(() -> "lambda-result");

            // Then
            assertThat(result).isEqualTo("lambda-result");
            assertThat(trace).containsExactly("CB:sync-enter", "CB:sync-exit");
        }

        @Test
        void execute_async_routes_through_async_chain() {
            // Given
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                            .build());

            // When
            CompletionStage<String> stage = terminal.executeAsync(
                    () -> CompletableFuture.completedFuture("async-lambda"));
            String result = stage.toCompletableFuture().join();

            // Then
            assertThat(result).isEqualTo("async-lambda");
            assertThat(trace).containsExactly("CB:async-enter", "CB:async-exit");
        }
    }

    // =========================================================================
    // Per-Method caching
    // =========================================================================

    @Nested
    @DisplayName("Per-Method caching")
    class PerMethodCaching {

        @Test
        void repeated_sync_calls_to_the_same_method_reuse_the_cached_chain() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                            .build());

            // When — three calls to the same sync method
            terminal.executeAround(syncPjp("A"));
            terminal.executeAround(syncPjp("B"));
            terminal.executeAround(syncPjp("C"));

            // Then — identical trace pattern each time (factory reused)
            assertThat(trace).containsExactly(
                    "BH:sync-enter", "BH:sync-exit",
                    "BH:sync-enter", "BH:sync-exit",
                    "BH:sync-enter", "BH:sync-exit"
            );
        }

        @Test
        void repeated_async_calls_to_the_same_method_reuse_the_cached_chain() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                            .build());

            // When — three calls to the same async method
            for (int i = 0; i < 3; i++) {
                Object result = terminal.executeAround(
                        asyncPjp(CompletableFuture.completedFuture("R" + i)));
                ((CompletionStage<?>) result).toCompletableFuture().join();
            }

            // Then
            assertThat(trace).containsExactly(
                    "BH:async-enter", "BH:async-exit",
                    "BH:async-enter", "BH:async-exit",
                    "BH:async-enter", "BH:async-exit"
            );
        }

        @Test
        void interleaved_sync_and_async_calls_each_use_the_correct_cached_chain()
                throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                            .build());

            // When — interleaved: sync, async, sync, async
            terminal.executeAround(syncPjp("S1"));
            Object a1 = terminal.executeAround(asyncPjp(CompletableFuture.completedFuture("A1")));
            ((CompletionStage<?>) a1).toCompletableFuture().join();
            terminal.executeAround(syncPjp("S2"));
            Object a2 = terminal.executeAround(asyncPjp(CompletableFuture.completedFuture("A2")));
            ((CompletionStage<?>) a2).toCompletableFuture().join();

            // Then — each dispatched to the correct cached chain
            assertThat(trace).containsExactly(
                    "BH:sync-enter", "BH:sync-exit",
                    "BH:async-enter", "BH:async-exit",
                    "BH:sync-enter", "BH:sync-exit",
                    "BH:async-enter", "BH:async-exit"
            );
        }

        @Test
        void cached_chain_returns_correct_results_on_repeated_calls() throws Throwable {
            // Given
            HybridAspectPipelineTerminal terminal = HybridAspectPipelineTerminal.of(
                    InqPipeline.builder()
                            .shield(new DualDecorator("CB", InqElementType.CIRCUIT_BREAKER, new ArrayList<>()))
                            .build());

            // When / Then — first call builds cache, second reuses it
            assertThat(terminal.executeAround(syncPjp("first"))).isEqualTo("first");
            assertThat(terminal.executeAround(syncPjp("second"))).isEqualTo("second");
        }
    }
}
