package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HybridProxyPipelineTerminal")
class HybridProxyPipelineTerminalTest {

    // =========================================================================
    // Service interface with sync and async methods
    // =========================================================================

    interface OrderService {
        String placeOrder(String item);
        CompletionStage<String> placeOrderAsync(String item);
    }

    static class RealOrderService implements OrderService {
        @Override
        public String placeOrder(String item) {
            return "ordered:" + item;
        }

        @Override
        public CompletionStage<String> placeOrderAsync(String item) {
            return CompletableFuture.completedFuture("async-ordered:" + item);
        }
    }

    // =========================================================================
    // Dual decorator: implements both InqDecorator AND InqAsyncDecorator
    // =========================================================================

    /**
     * Stub element implementing both sync and async decorator interfaces.
     * Records separate traces for sync and async paths to verify correct dispatch.
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

        // Sync path
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

        // Async path
        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Void arg,
                                                     InternalAsyncExecutor<Void, Object> next) {
            trace.add(name + ":async-enter");
            return next.executeAsync(chainId, callId, arg)
                    .whenComplete((r, e) -> trace.add(name + ":async-exit"));
        }
    }

    /**
     * Dual decorator with a semaphore — verifies correct permit lifecycle.
     * Sync: release in finally block.
     * Async: release on stage completion.
     */
    static class SemaphoreDualDecorator implements InqDecorator<Void, Object>, InqAsyncDecorator<Void, Object> {

        private final String name;
        private final Semaphore semaphore;
        private final List<String> trace;

        SemaphoreDualDecorator(String name, int permits, List<String> trace) {
            this.name = name;
            this.semaphore = new Semaphore(permits);
            this.trace = trace;
        }

        int availablePermits() { return semaphore.availablePermits(); }

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return InqElementType.BULKHEAD; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        // Sync: acquire → call → release (in finally)
        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            if (!semaphore.tryAcquire()) throw new RuntimeException("full");
            trace.add(name + ":sync-acquire");
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                semaphore.release();
                trace.add(name + ":sync-release");
            }
        }

        // Async: acquire → call → release on stage completion
        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Void arg,
                                                     InternalAsyncExecutor<Void, Object> next) {
            if (!semaphore.tryAcquire()) throw new RuntimeException("full");
            trace.add(name + ":async-acquire");
            return next.executeAsync(chainId, callId, arg)
                    .whenComplete((r, e) -> {
                        semaphore.release();
                        trace.add(name + ":async-release");
                    });
        }
    }

    // =========================================================================
    // Dispatch routing
    // =========================================================================

    @Nested
    @DisplayName("Sync vs async dispatch")
    class DispatchRouting {

        @Test
        void sync_method_goes_through_sync_chain() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                    .build();
            OrderService proxy = HybridProxyPipelineTerminal.of(pipeline)
                    .protect(OrderService.class, new RealOrderService());

            // When
            String result = proxy.placeOrder("Widget");

            // Then — sync path used
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(trace).containsExactly("BH:sync-enter", "BH:sync-exit");
        }

        @Test
        void async_method_goes_through_async_chain() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                    .build();
            OrderService proxy = HybridProxyPipelineTerminal.of(pipeline)
                    .protect(OrderService.class, new RealOrderService());

            // When
            CompletionStage<String> stage = proxy.placeOrderAsync("Widget");
            String result = stage.toCompletableFuture().join();

            // Then — async path used
            assertThat(result).isEqualTo("async-ordered:Widget");
            assertThat(trace).containsExactly("BH:async-enter", "BH:async-exit");
        }

        @Test
        void same_proxy_routes_sync_and_async_calls_correctly() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                    .build();
            OrderService proxy = HybridProxyPipelineTerminal.of(pipeline)
                    .protect(OrderService.class, new RealOrderService());

            // When — both on the same proxy
            proxy.placeOrder("sync");
            proxy.placeOrderAsync("async").toCompletableFuture().join();

            // Then — each routed through the correct chain
            assertThat(trace).containsExactly(
                    "BH:sync-enter", "BH:sync-exit",
                    "BH:async-enter", "BH:async-exit"
            );
        }
    }

    // =========================================================================
    // Permit lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Permit lifecycle (sync vs async release)")
    class PermitLifecycle {

        @Test
        void async_permit_is_released_on_stage_completion_not_on_method_return() {
            // Given — 1 permit
            List<String> trace = new ArrayList<>();
            SemaphoreDualDecorator bh = new SemaphoreDualDecorator("BH", 1, trace);
            OrderService proxy = HybridProxyPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build())
                    .protect(OrderService.class, new RealOrderService());

            // When — async call
            CompletionStage<String> stage = proxy.placeOrderAsync("Widget");

            // Then — after join, permit is released
            stage.toCompletableFuture().join();
            assertThat(bh.availablePermits()).isEqualTo(1);
            assertThat(trace).containsExactly("BH:async-acquire", "BH:async-release");
        }

        @Test
        void sync_permit_is_released_in_finally_block() {
            // Given — 1 permit
            List<String> trace = new ArrayList<>();
            SemaphoreDualDecorator bh = new SemaphoreDualDecorator("BH", 1, trace);
            OrderService proxy = HybridProxyPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build())
                    .protect(OrderService.class, new RealOrderService());

            // When
            proxy.placeOrder("Widget");

            // Then
            assertThat(bh.availablePermits()).isEqualTo(1);
            assertThat(trace).containsExactly("BH:sync-acquire", "BH:sync-release");
        }

        @Test
        void async_permit_is_released_even_on_failed_stage() {
            // Given
            List<String> trace = new ArrayList<>();
            SemaphoreDualDecorator bh = new SemaphoreDualDecorator("BH", 1, trace);

            OrderService failing = new OrderService() {
                @Override public String placeOrder(String item) { return ""; }
                @Override public CompletionStage<String> placeOrderAsync(String item) {
                    return CompletableFuture.failedFuture(new RuntimeException("async-fail"));
                }
            };

            OrderService proxy = HybridProxyPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build())
                    .protect(OrderService.class, failing);

            // When
            try {
                proxy.placeOrderAsync("fail").toCompletableFuture().join();
            } catch (CompletionException ignored) {}

            // Then — permit released despite failure
            assertThat(bh.availablePermits()).isEqualTo(1);
            assertThat(trace).containsExactly("BH:async-acquire", "BH:async-release");
        }
    }

    // =========================================================================
    // Pipeline ordering
    // =========================================================================

    @Nested
    @DisplayName("Pipeline ordering for both paths")
    class PipelineOrdering {

        @Test
        void standard_ordering_applied_for_sync_and_async() {
            // Given — shuffled
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new DualDecorator("RT", InqElementType.RETRY, trace))
                    .shield(new DualDecorator("BH", InqElementType.BULKHEAD, trace))
                    .shield(new DualDecorator("CB", InqElementType.CIRCUIT_BREAKER, trace))
                    .build();
            OrderService proxy = HybridProxyPipelineTerminal.of(pipeline)
                    .protect(OrderService.class, new RealOrderService());

            // When — sync
            proxy.placeOrder("sync");
            List<String> syncTrace = List.copyOf(trace);
            trace.clear();

            // When — async
            proxy.placeOrderAsync("async").toCompletableFuture().join();
            List<String> asyncTrace = List.copyOf(trace);

            // Then — same element order: BH(400) → CB(500) → RT(600)
            assertThat(syncTrace).containsExactly(
                    "BH:sync-enter", "CB:sync-enter", "RT:sync-enter",
                    "RT:sync-exit", "CB:sync-exit", "BH:sync-exit");
            assertThat(asyncTrace).containsExactly(
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
        void sync_exception_propagates_directly() {
            // Given
            OrderService failing = new OrderService() {
                @Override public String placeOrder(String item) {
                    throw new IllegalStateException("sync-error");
                }
                @Override public CompletionStage<String> placeOrderAsync(String item) {
                    return CompletableFuture.completedFuture("");
                }
            };
            OrderService proxy = HybridProxyPipelineTerminal.of(InqPipeline.builder().build())
                    .protect(OrderService.class, failing);

            // When / Then
            assertThatThrownBy(() -> proxy.placeOrder("fail"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("sync-error");
        }

        @Test
        void async_exception_delivered_via_stage() {
            // Given
            OrderService failing = new OrderService() {
                @Override public String placeOrder(String item) { return ""; }
                @Override public CompletionStage<String> placeOrderAsync(String item) {
                    return CompletableFuture.failedFuture(new RuntimeException("async-error"));
                }
            };
            OrderService proxy = HybridProxyPipelineTerminal.of(InqPipeline.builder().build())
                    .protect(OrderService.class, failing);

            // When / Then — error in the stage, not thrown
            CompletionStage<String> stage = proxy.placeOrderAsync("fail");
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("async-error");
        }

        @Test
        void to_string_shows_hybrid_proxy_info() {
            // Given
            OrderService proxy = HybridProxyPipelineTerminal.of(InqPipeline.builder().build())
                    .protect(OrderService.class, new RealOrderService());

            // Then
            assertThat(proxy.toString()).contains("HybridPipelineProxy");
        }
    }
}
