package eu.inqudium.aspect.pipeline;

import eu.inqudium.imperative.core.pipeline.AsyncJoinPointWrapper;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            @Override
            public String layerName() {
                return name;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public AsyncLayerAction<Void, Object> asyncLayerAction() {
                return action;
            }
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
    @DisplayName("executeThroughAsync(JoinPointExecutor) — unfiltered path")
    class ExecuteThroughAsync {

        @Test
        void the_unfiltered_pipeline_is_resolved_once_and_reused_across_invocations() {
            // given — a provider that counts how often its action reference is
            //         requested, plus a separate counter for actual chain invocations
            AtomicInteger resolutionCount = new AtomicInteger();
            AtomicInteger invocationCount = new AtomicInteger();

            AsyncAspectLayerProvider<Object> countingProvider = new AsyncAspectLayerProvider<>() {
                @Override
                public String layerName() {
                    return "COUNTING";
                }

                @Override
                public int order() {
                    return 100;
                }

                @Override
                public AsyncLayerAction<Void, Object> asyncLayerAction() {
                    // Called during pipeline resolution (once for the unfiltered
                    // path), NOT per invocation
                    resolutionCount.incrementAndGet();
                    return (chainId, callId, arg, next) -> {
                        invocationCount.incrementAndGet();
                        return next.executeAsync(chainId, callId, arg);
                    };
                }
            };

            AbstractAsyncPipelineAspect aspect = new AbstractAsyncPipelineAspect() {
                @Override
                protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
                    return List.of(countingProvider);
                }
            };

            // when — execute the unfiltered path twice
            aspect.executeThroughAsync(() -> CompletableFuture.completedFuture("first"))
                    .toCompletableFuture().join();
            aspect.executeThroughAsync(() -> CompletableFuture.completedFuture("second"))
                    .toCompletableFuture().join();

            // then — resolution happened exactly once, chain executed twice
            assertThat(resolutionCount).hasValue(1);
            assertThat(invocationCount).hasValue(2);
        }

        @Test
        void concurrent_first_access_still_triggers_only_a_single_pipeline_resolution()
                throws InterruptedException {
            // given — counts resolutions; 16 threads race on the same aspect instance
            AtomicInteger resolutionCount = new AtomicInteger();
            AsyncAspectLayerProvider<Object> provider = new AsyncAspectLayerProvider<>() {
                @Override
                public String layerName() {
                    return "COUNTING";
                }

                @Override
                public int order() {
                    return 100;
                }

                @Override
                public AsyncLayerAction<Void, Object> asyncLayerAction() {
                    resolutionCount.incrementAndGet();
                    return (c, ca, a, next) -> next.executeAsync(c, ca, a);
                }
            };
            AbstractAsyncPipelineAspect aspect = new AbstractAsyncPipelineAspect() {
                @Override
                protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
                    return List.of(provider);
                }
            };

            int threadCount = 16;
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when — release all threads simultaneously on the first-ever call
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        aspect.executeThroughAsync(
                                        () -> CompletableFuture.completedFuture("v"))
                                .toCompletableFuture().join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            doneGate.await();
            executor.shutdownNow();

            // then — double-checked locking guarantees a single resolution
            assertThat(resolutionCount).hasValue(1);
        }

        @Test
        void the_result_of_the_core_executor_is_propagated_through_the_completion_stage() {
            // given — an aspect with no layers
            AbstractAsyncPipelineAspect aspect = new AbstractAsyncPipelineAspect() {
                @Override
                protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
                    return List.of();
                }
            };

            // when
            Object result = aspect.executeThroughAsync(
                            () -> CompletableFuture.completedFuture("expected"))
                    .toCompletableFuture().join();

            // then
            assertThat(result).isEqualTo("expected");
        }

        @Test
        void a_synchronous_failure_in_the_core_executor_is_delivered_as_a_failed_future() {
            // given — core executor throws synchronously
            AbstractAsyncPipelineAspect aspect = new AbstractAsyncPipelineAspect() {
                @Override
                protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
                    return List.of();
                }
            };
            IllegalStateException cause = new IllegalStateException("boom");

            // when
            CompletionStage<Object> stage = aspect.executeThroughAsync(() -> {
                throw cause;
            });

            // then — uniform error channel: never thrown, always delivered via stage
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(cause);
        }

        @Test
        void an_asynchronous_failure_from_the_core_executor_surfaces_through_the_returned_stage() {
            // given
            AbstractAsyncPipelineAspect aspect = new AbstractAsyncPipelineAspect() {
                @Override
                protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
                    return List.of();
                }
            };
            IllegalStateException cause = new IllegalStateException("async boom");

            // when
            CompletionStage<Object> stage = aspect.executeThroughAsync(
                    () -> CompletableFuture.failedFuture(cause));

            // then
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(cause);
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
