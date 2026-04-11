package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.LayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

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
            @Override public String layerName() { return name; }
            @Override public int order() { return order; }
            @Override public LayerAction<Void, Object> layerAction() { return action; }
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
    @DisplayName("executeThrough")
    class ExecuteThrough {

        @Test
        void executes_the_core_and_returns_its_result_with_no_layers() throws Throwable {
            // Given
            AbstractPipelineAspect aspect = aspectWith(Collections.emptyList());

            // When
            Object result = aspect.executeThrough(() -> "hello");

            // Then
            assertThat(result).isEqualTo("hello");
        }

        @Test
        void executes_all_layers_in_order_around_the_core() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("AUTH", 10, recordingAction("AUTH", trace)),
                    provider("TIMING", 20, recordingAction("TIMING", trace))
            ));

            // When
            Object result = aspect.executeThrough(() -> {
                trace.add("core");
                return "done";
            });

            // Then
            assertThat(result).isEqualTo("done");
            assertThat(trace).containsExactly(
                    "AUTH:before",
                    "TIMING:before",
                    "core",
                    "TIMING:after",
                    "AUTH:after"
            );
        }

        @Test
        void sorts_providers_by_order_regardless_of_list_position() throws Throwable {
            // Given — providers added in reverse order
            List<String> trace = new ArrayList<>();
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("INNER", 30, recordingAction("INNER", trace)),
                    provider("OUTER", 10, recordingAction("OUTER", trace)),
                    provider("MIDDLE", 20, recordingAction("MIDDLE", trace))
            ));

            // When
            aspect.executeThrough(() -> {
                trace.add("core");
                return null;
            });

            // Then — sorted by order: OUTER(10) → MIDDLE(20) → INNER(30)
            assertThat(trace).containsExactly(
                    "OUTER:before",
                    "MIDDLE:before",
                    "INNER:before",
                    "core",
                    "INNER:after",
                    "MIDDLE:after",
                    "OUTER:after"
            );
        }

        @Test
        void propagates_checked_exceptions_from_the_core_unwrapped() {
            // Given
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("LAYER", 10, LayerAction.passThrough())
            ));

            // When / Then
            assertThatThrownBy(() -> aspect.executeThrough(() -> {
                throw new java.io.IOException("disk full");
            }))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("disk full");
        }

        @Test
        void propagates_runtime_exceptions_from_the_core() {
            // Given
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("LAYER", 10, LayerAction.passThrough())
            ));

            // When / Then
            assertThatThrownBy(() -> aspect.executeThrough(() -> {
                throw new IllegalArgumentException("bad input");
            }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("bad input");
        }

        @Test
        void each_invocation_builds_a_fresh_chain() throws Throwable {
            // Given
            AtomicInteger buildCount = new AtomicInteger();
            AspectLayerProvider<Object> countingProvider = new AspectLayerProvider<>() {
                @Override public String layerName() { return "COUNTER"; }
                @Override public int order() { return 10; }
                @Override public LayerAction<Void, Object> layerAction() {
                    buildCount.incrementAndGet();
                    return LayerAction.passThrough();
                }
            };
            AbstractPipelineAspect aspect = aspectWith(List.of(countingProvider));

            // When
            aspect.executeThrough(() -> "first");
            aspect.executeThrough(() -> "second");

            // Then — layerAction() was called twice, once per chain build
            assertThat(buildCount).hasValue(2);
        }

        @Test
        void null_return_from_core_is_propagated() throws Throwable {
            // Given
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("LAYER", 10, LayerAction.passThrough())
            ));

            // When
            Object result = aspect.executeThrough(() -> null);

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("buildPipeline")
    class BuildPipeline {

        @Test
        void returns_a_chain_without_executing_it() {
            // Given
            AtomicInteger coreCallCount = new AtomicInteger();
            AbstractPipelineAspect aspect = aspectWith(List.of(
                    provider("A", 10, LayerAction.passThrough())
            ));

            // When
            JoinPointWrapper<Object> pipeline = aspect.buildPipeline(() -> {
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
            JoinPointWrapper<Object> pipeline = aspect.buildPipeline(() -> null);
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
            JoinPointWrapper<Object> pipeline = aspect.buildPipeline(() -> "deferred");

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
            Object result = aspect.executeThrough(() -> {
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
            Object result = aspect.executeThrough(() -> {
                coreCallCount.incrementAndGet();
                return "from-db";
            });

            // Then
            assertThat(result).isEqualTo("from-cache");
            assertThat(coreCallCount).hasValue(0);
        }
    }
}
