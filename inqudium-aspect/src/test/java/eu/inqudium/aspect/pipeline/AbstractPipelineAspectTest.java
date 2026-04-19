package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.LayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AbstractPipelineAspect")
class AbstractPipelineAspectTest {

    // -------------------------------------------------------------------------
    // Helper: creates a concrete subclass with the given providers
    // -------------------------------------------------------------------------
    private static AbstractPipelineAspect aspectWith(List<AspectLayerProvider<Object>> providers) {
        return new AbstractPipelineAspect() {
            @Override
            protected List<AspectLayerProvider<Object>> layerProviders() {
                return providers;
            }
        };
    }

    private static AspectLayerProvider<Object> provider(String name, int order, LayerAction<Void, Object> action) {
        return new AspectLayerProvider<>() {
            @Override
            public String layerName() {
                return name;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public LayerAction<Void, Object> layerAction() {
                return action;
            }
        };
    }

    private static LayerAction<Void, Object> recordingAction(String marker, List<String> trace) {
        return (chainId, callId, arg, next) -> {
            trace.add(marker + ":before");
            Object result = next.execute(chainId, callId, arg);
            trace.add(marker + ":after");
            return result;
        };
    }

    @Nested
    @DisplayName("execute(JoinPointExecutor) — unfiltered path")
    class Execute {

        @Test
        void the_unfiltered_pipeline_is_resolved_once_and_reused_across_invocations()
                throws Throwable {
            // given
            AtomicInteger resolutionCount = new AtomicInteger();
            AtomicInteger invocationCount = new AtomicInteger();

            AspectLayerProvider<Object> countingProvider = new AspectLayerProvider<>() {
                @Override
                public String layerName() {
                    return "COUNTING";
                }

                @Override
                public int order() {
                    return 100;
                }

                @Override
                public LayerAction<Void, Object> layerAction() {
                    // Called during pipeline resolution (once for the unfiltered
                    // path), NOT per invocation
                    resolutionCount.incrementAndGet();
                    return (chainId, callId, arg, next) -> {
                        invocationCount.incrementAndGet();
                        return next.execute(chainId, callId, arg);
                    };
                }
            };

            AbstractPipelineAspect aspect =
                    new AbstractPipelineAspect(List.of(countingProvider)) {
                    };

            // when
            aspect.execute(() -> "first");
            aspect.execute(() -> "second");

            // then — resolution happened exactly once, chain executed twice
            assertThat(resolutionCount).hasValue(1);
            assertThat(invocationCount).hasValue(2);
        }

        @Test
        void concurrent_first_access_still_triggers_only_a_single_pipeline_resolution()
                throws InterruptedException {
            // given
            AtomicInteger resolutionCount = new AtomicInteger();
            AspectLayerProvider<Object> provider = new AspectLayerProvider<>() {
                @Override
                public String layerName() {
                    return "COUNTING";
                }

                @Override
                public int order() {
                    return 100;
                }

                @Override
                public LayerAction<Void, Object> layerAction() {
                    resolutionCount.incrementAndGet();
                    return (c, ca, a, next) -> next.execute(c, ca, a);
                }
            };
            AbstractPipelineAspect aspect =
                    new AbstractPipelineAspect(List.of(provider)) {
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
                        aspect.execute(() -> "v");
                    } catch (Throwable ignored) {
                        // not asserting on execution outcome here
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            doneGate.await();
            executor.shutdownNow();

            // then
            assertThat(resolutionCount).hasValue(1);
        }

        @Test
        void the_result_of_the_core_executor_is_returned_directly() throws Throwable {
            // given — empty pipeline passes through
            AbstractPipelineAspect aspect = new AbstractPipelineAspect(List.of()) {
            };

            // when
            Object result = aspect.execute(() -> "expected");

            // then
            assertThat(result).isEqualTo("expected");
        }

        @Test
        void a_checked_exception_from_the_core_executor_propagates_with_its_original_type() {
            // given — empty pipeline, core executor throws a checked exception
            AbstractPipelineAspect aspect = new AbstractPipelineAspect(List.of()) {
            };
            IOException cause = new IOException("boom");

            // when / then — the original throwable surfaces, unwrapped from CompletionException
            assertThatThrownBy(() -> aspect.execute(() -> {
                throw cause;
            }))
                    .isSameAs(cause);
        }

        @Test
        void a_runtime_exception_from_the_core_executor_propagates_unchanged() {
            // given
            AbstractPipelineAspect aspect = new AbstractPipelineAspect(List.of()) {
            };
            IllegalStateException cause = new IllegalStateException("boom");

            // when / then
            assertThatThrownBy(() -> aspect.execute(() -> {
                throw cause;
            }))
                    .isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("inspectPipeline")
    class BuildPipeline {

        @Test
        void returns_a_chain_without_executing_it() {
            // Given
            AtomicInteger coreCallCount = new AtomicInteger();
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("A", 10, LayerAction.passThrough())
            ));

            // When
            JoinPointWrapper<Object> pipeline = aspect.inspectPipeline(() -> {
                coreCallCount.incrementAndGet();
                return "result";
            });

            // Then — chain is built but core was never called
            assertThat(pipeline).isNotNull();
            assertThat(pipeline.layerDescription()).isEqualTo("A");
            assertThat(coreCallCount).hasValue(0);
        }

        @Test
        void hierarchy_string_contains_all_registered_layers() {
            // Given
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("LOGGING", 10, LayerAction.passThrough()),
                    provider("BULKHEAD", 20, LayerAction.passThrough()),
                    provider("RETRY", 30, LayerAction.passThrough())
            ));

            // When
            JoinPointWrapper<Object> pipeline = aspect.inspectPipeline(() -> null);
            String hierarchy = pipeline.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                    .contains("LOGGING")
                    .contains("BULKHEAD")
                    .contains("RETRY");
        }

        @Test
        void built_pipeline_can_be_executed_afterwards() throws Throwable {
            // Given
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("LAYER", 10, LayerAction.passThrough())
            ));
            JoinPointWrapper<Object> pipeline = aspect.inspectPipeline(() -> "deferred");

            // When
            Object result = pipeline.proceed();

            // Then
            assertThat(result).isEqualTo("deferred");
        }
    }

    @Nested
    @DisplayName("Integration: realistic multi-layer aspect")
    class Integration {

        @Test
        void simulates_a_full_resilience_aspect_with_logging_timing_and_retry() throws Throwable {
            // Given
            List<String> events = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger();

            // Logging layer — outermost
            AspectLayerProvider<Object> logging = provider("LOGGING", 10,
                    (chainId, callId, arg, next) -> {
                        events.add("log:enter");
                        try {
                            Object r = next.execute(chainId, callId, arg);
                            events.add("log:success");
                            return r;
                        } catch (Exception e) {
                            events.add("log:failure:" + e.getMessage());
                            throw e;
                        }
                    });

            // Timing layer — middle
            AspectLayerProvider<Object> timing = provider("TIMING", 20,
                    (chainId, callId, arg, next) -> {
                        events.add("timer:start");
                        try {
                            return next.execute(chainId, callId, arg);
                        } finally {
                            events.add("timer:stop");
                        }
                    });

            // Retry layer — innermost, retries up to 2 times
            AspectLayerProvider<Object> retry = provider("RETRY", 30,
                    (chainId, callId, arg, next) -> {
                        Exception lastException = null;
                        for (int i = 0; i < 3; i++) {
                            try {
                                events.add("retry:attempt-" + (i + 1));
                                return next.execute(chainId, callId, arg);
                            } catch (RuntimeException e) {
                                lastException = e;
                            }
                        }
                        throw new IllegalStateException(lastException);
                    });

            AbstractPipelineAspect aspect = aspectWith(List.of(logging, timing, retry));

            // When — core fails twice, succeeds on third attempt
            Object result = aspect.execute(() -> {
                int attempt = attempts.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("transient-" + attempt);
                }
                return "recovered";
            });

            // Then
            assertThat(result).isEqualTo("recovered");
            assertThat(attempts).hasValue(3);
            assertThat(events).containsExactly(
                    "log:enter",
                    "timer:start",
                    "retry:attempt-1",
                    "retry:attempt-2",
                    "retry:attempt-3",
                    "timer:stop",
                    "log:success"
            );
        }

        @Test
        void simulates_a_caching_layer_that_prevents_core_execution() throws Throwable {
            // Given
            AtomicInteger coreCallCount = new AtomicInteger();
            AspectLayerProvider<Object> cache = provider("CACHE", 10,
                    (chainId, callId, arg, next) -> "from-cache");

            AbstractPipelineAspect aspect = aspectWith(List.of(cache));

            // When
            Object result = aspect.execute(() -> {
                coreCallCount.incrementAndGet();
                return "from-db";
            });

            // Then
            assertThat(result).isEqualTo("from-cache");
            assertThat(coreCallCount).hasValue(0);
        }
    }
}
