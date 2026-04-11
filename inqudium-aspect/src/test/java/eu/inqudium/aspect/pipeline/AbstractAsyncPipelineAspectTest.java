package eu.inqudium.aspect.pipeline;

import eu.inqudium.imperative.core.pipeline.AsyncJoinPointWrapper;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AbstractAsyncPipelineAspect")
class AbstractAsyncPipelineAspectTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AbstractAsyncPipelineAspect asyncAspectWith(
            List<AsyncAspectLayerProvider<Object>> providers) {
        return new AbstractAsyncPipelineAspect() {
            @Override
            protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
                return providers;
            }
        };
    }

    private static AsyncAspectLayerProvider<Object> asyncProvider(
            String name, int order, AsyncLayerAction<Void, Object> action) {
        return new AsyncAspectLayerProvider<>() {
            @Override public String layerName() { return name; }
            @Override public int order() { return order; }
            @Override public AsyncLayerAction<Void, Object> asyncLayerAction() { return action; }
        };
    }

    private static AsyncLayerAction<Void, Object> recordingAsyncAction(
            String marker, List<String> trace) {
        return (chainId, callId, arg, next) -> {
            trace.add(marker + ":start");
            CompletionStage<Object> stage = next.executeAsync(chainId, callId, arg);
            return stage.whenComplete((r, e) -> trace.add(marker + ":end"));
        };
    }

    @Nested
    @DisplayName("executeThroughAsync")
    class ExecuteThroughAsync {

        @Test
        void executes_the_core_and_returns_its_async_result_with_no_layers() throws Throwable {
            // Given
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(Collections.emptyList());

            // When — simulate pjp::proceed returning a CompletionStage
            CompletionStage<Object> result = aspect.executeThroughAsync(
                    () -> CompletableFuture.completedFuture("async-hello"));

            // Then
            assertThat(result.toCompletableFuture().join()).isEqualTo("async-hello");
        }

        @Test
        void executes_all_async_layers_in_order_around_the_core() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("BULKHEAD", 10, recordingAsyncAction("BULKHEAD", trace)),
                    asyncProvider("TIMING", 20, recordingAsyncAction("TIMING", trace))
            ));

            // When
            CompletionStage<Object> result = aspect.executeThroughAsync(() -> {
                trace.add("core");
                return CompletableFuture.completedFuture("done");
            });
            result.toCompletableFuture().join();

            // Then
            assertThat(trace).containsExactly(
                    "BULKHEAD:start",
                    "TIMING:start",
                    "core",
                    "TIMING:end",
                    "BULKHEAD:end"
            );
        }

        @Test
        void sorts_providers_by_order_regardless_of_list_position() throws Throwable {
            // Given — providers in reverse order
            List<String> trace = new ArrayList<>();
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("INNER", 30, recordingAsyncAction("INNER", trace)),
                    asyncProvider("OUTER", 10, recordingAsyncAction("OUTER", trace)),
                    asyncProvider("MIDDLE", 20, recordingAsyncAction("MIDDLE", trace))
            ));

            // When
            aspect.executeThroughAsync(() -> {
                trace.add("core");
                return CompletableFuture.completedFuture(null);
            }).toCompletableFuture().join();

            // Then
            assertThat(trace).containsExactly(
                    "OUTER:start",
                    "MIDDLE:start",
                    "INNER:start",
                    "core",
                    "INNER:end",
                    "MIDDLE:end",
                    "OUTER:end"
            );
        }

        @Test
        void propagates_synchronous_runtime_exception_from_the_core() {
            // Given
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("LAYER", 10, AsyncLayerAction.passThrough())
            ));

            // When / Then — core throws synchronously before creating a stage
            assertThatThrownBy(() -> aspect.executeThroughAsync(() -> {
                throw new IllegalStateException("sync failure");
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("sync failure");
        }

        @Test
        void propagates_checked_exception_from_the_core_unwrapped() {
            // Given
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("LAYER", 10, AsyncLayerAction.passThrough())
            ));

            // When / Then
            assertThatThrownBy(() -> aspect.executeThroughAsync(() -> {
                throw new Exception("checked failure");
            }))
                    .isInstanceOf(Exception.class)
                    .hasMessage("checked failure")
                    .isNotInstanceOf(RuntimeException.class);
        }

        @Test
        void async_failure_in_the_completion_stage_surfaces_through_the_returned_stage()
                throws Throwable {
            // Given
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("LAYER", 10, AsyncLayerAction.passThrough())
            ));

            // When
            CompletionStage<Object> stage = aspect.executeThroughAsync(
                    () -> CompletableFuture.failedFuture(new RuntimeException("async fail")));

            // Then
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("async fail");
        }

        @Test
        void each_invocation_builds_a_fresh_chain() throws Throwable {
            // Given
            AtomicInteger buildCount = new AtomicInteger();
            AsyncAspectLayerProvider<Object> countingProvider = new AsyncAspectLayerProvider<>() {
                @Override public String layerName() { return "COUNTER"; }
                @Override public int order() { return 10; }
                @Override public AsyncLayerAction<Void, Object> asyncLayerAction() {
                    buildCount.incrementAndGet();
                    return AsyncLayerAction.passThrough();
                }
            };
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(countingProvider));

            // When
            aspect.executeThroughAsync(
                    () -> CompletableFuture.completedFuture("first"))
                    .toCompletableFuture().join();
            aspect.executeThroughAsync(
                    () -> CompletableFuture.completedFuture("second"))
                    .toCompletableFuture().join();

            // Then
            assertThat(buildCount).hasValue(2);
        }

        @Test
        void null_return_value_in_the_stage_is_propagated() throws Throwable {
            // Given
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("LAYER", 10, AsyncLayerAction.passThrough())
            ));

            // When
            CompletionStage<Object> stage = aspect.executeThroughAsync(
                    () -> CompletableFuture.completedFuture(null));

            // Then
            assertThat(stage.toCompletableFuture().join()).isNull();
        }
    }

    @Nested
    @DisplayName("buildAsyncPipeline")
    class BuildAsyncPipeline {

        @Test
        void returns_a_chain_without_executing_it() {
            // Given
            AtomicInteger coreCallCount = new AtomicInteger();
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("A", 10, AsyncLayerAction.passThrough())
            ));

            // When
            AsyncJoinPointWrapper<Object> pipeline = aspect.buildAsyncPipeline(() -> {
                coreCallCount.incrementAndGet();
                return CompletableFuture.completedFuture("result");
            });

            // Then
            assertThat(pipeline).isNotNull();
            assertThat(pipeline.layerDescription()).isEqualTo("A");
            assertThat(coreCallCount).hasValue(0);
        }

        @Test
        void hierarchy_string_contains_all_registered_layers() {
            // Given
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("BULKHEAD", 10, AsyncLayerAction.passThrough()),
                    asyncProvider("TIMING", 20, AsyncLayerAction.passThrough()),
                    asyncProvider("LOGGING", 30, AsyncLayerAction.passThrough())
            ));

            // When
            String hierarchy = aspect.buildAsyncPipeline(
                    () -> CompletableFuture.completedFuture(null))
                    .toStringHierarchy();

            // Then
            assertThat(hierarchy)
                    .contains("BULKHEAD")
                    .contains("TIMING")
                    .contains("LOGGING");
        }

        @Test
        void built_pipeline_can_be_executed_afterwards() throws Throwable {
            // Given
            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(
                    asyncProvider("LAYER", 10, AsyncLayerAction.passThrough())
            ));
            AsyncJoinPointWrapper<Object> pipeline = aspect.buildAsyncPipeline(
                    () -> CompletableFuture.completedFuture("deferred"));

            // When
            Object result = pipeline.proceed().toCompletableFuture().join();

            // Then
            assertThat(result).isEqualTo("deferred");
        }
    }

    @Nested
    @DisplayName("Integration: realistic async multi-layer aspect")
    class Integration {

        @Test
        void simulates_a_full_async_resilience_aspect_with_bulkhead_and_timing() throws Throwable {
            // Given
            List<String> events = new ArrayList<>();

            // Bulkhead layer — outermost: acquire sync, release async
            AsyncAspectLayerProvider<Object> bulkhead = asyncProvider("BULKHEAD", 10,
                    (chainId, callId, arg, next) -> {
                        events.add("bulkhead:acquire");
                        CompletionStage<Object> stage;
                        try {
                            stage = next.executeAsync(chainId, callId, arg);
                        } catch (Throwable t) {
                            events.add("bulkhead:release-on-failure");
                            throw t;
                        }
                        return stage.whenComplete((r, e) -> events.add("bulkhead:release"));
                    });

            // Timing layer — innermost: start sync, record async
            AsyncAspectLayerProvider<Object> timing = asyncProvider("TIMING", 20,
                    (chainId, callId, arg, next) -> {
                        events.add("timer:start");
                        CompletionStage<Object> stage = next.executeAsync(chainId, callId, arg);
                        return stage.whenComplete((r, e) -> events.add("timer:stop"));
                    });

            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(bulkhead, timing));

            // When
            CompletionStage<Object> result = aspect.executeThroughAsync(() -> {
                events.add("core");
                return CompletableFuture.completedFuture("success");
            });
            result.toCompletableFuture().join();

            // Then
            assertThat(events).containsExactly(
                    "bulkhead:acquire",
                    "timer:start",
                    "core",
                    "timer:stop",
                    "bulkhead:release"
            );
        }

        @Test
        void async_caching_layer_prevents_core_execution() throws Throwable {
            // Given
            AtomicInteger coreCallCount = new AtomicInteger();
            AsyncAspectLayerProvider<Object> cache = asyncProvider("CACHE", 10,
                    (chainId, callId, arg, next) ->
                            CompletableFuture.completedFuture("cached-value"));

            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(cache));

            // When
            CompletionStage<Object> result = aspect.executeThroughAsync(() -> {
                coreCallCount.incrementAndGet();
                return CompletableFuture.completedFuture("db-value");
            });

            // Then
            assertThat(result.toCompletableFuture().join()).isEqualTo("cached-value");
            assertThat(coreCallCount).hasValue(0);
        }

        @Test
        void bulkhead_releases_permit_even_when_core_future_fails() throws Throwable {
            // Given
            List<String> events = new ArrayList<>();
            AsyncAspectLayerProvider<Object> bulkhead = asyncProvider("BULKHEAD", 10,
                    (chainId, callId, arg, next) -> {
                        events.add("acquire");
                        CompletionStage<Object> stage = next.executeAsync(chainId, callId, arg);
                        return stage.whenComplete((r, e) -> events.add("release"));
                    });

            AbstractAsyncPipelineAspect aspect = asyncAspectWith(List.of(bulkhead));

            // When
            CompletionStage<Object> result = aspect.executeThroughAsync(
                    () -> CompletableFuture.failedFuture(new RuntimeException("boom")));

            // Then — release still fires despite async failure
            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .hasCauseInstanceOf(RuntimeException.class);
            assertThat(events).containsExactly("acquire", "release");
        }
    }
}
