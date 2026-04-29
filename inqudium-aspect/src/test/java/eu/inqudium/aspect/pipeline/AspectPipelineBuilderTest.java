package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.LayerAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AspectPipelineBuilder")
class AspectPipelineBuilderTest {

    private AspectPipelineBuilder<Object> builder;

    // -------------------------------------------------------------------------
    // Helper: creates a layer action that records its invocation in the given list
    // -------------------------------------------------------------------------
    private static LayerAction<Void, Object> recordingAction(String marker, List<String> trace) {
        return (chainId, callId, arg, next) -> {
            trace.add(marker + ":before");
            Object result = next.execute(chainId, callId, arg);
            trace.add(marker + ":after");
            return result;
        };
    }

    // -------------------------------------------------------------------------
    // Helper: creates a simple AspectLayerProvider
    // -------------------------------------------------------------------------
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

    private static AspectLayerProvider<Object> provider(String name, int order, List<String> trace) {
        return provider(name, order, recordingAction(name, trace));
    }

    @BeforeEach
    void setUp() {
        builder = new AspectPipelineBuilder<>();
    }

    @Nested
    @DisplayName("Adding layers via addLayer")
    class AddLayer {

        @Test
        void a_single_layer_is_registered_in_the_layer_list() {
            // Given
            LayerAction<Void, Object> action = LayerAction.passThrough();

            // When
            builder.addLayer("MY_LAYER", action);

            // Then
            assertThat(builder.layers()).hasSize(1);
            assertThat(builder.layers().getFirst().name()).isEqualTo("MY_LAYER");
            assertThat(builder.layers().getFirst().action()).isSameAs(action);
        }

        @Test
        void multiple_layers_preserve_insertion_order() {
            // Given
            LayerAction<Void, Object> action = LayerAction.passThrough();

            // When
            builder.addLayer("FIRST", action);
            builder.addLayer("SECOND", action);
            builder.addLayer("THIRD", action);

            // Then
            assertThat(builder.layers())
                    .extracting(AspectPipelineBuilder.NamedLayer::name)
                    .containsExactly("FIRST", "SECOND", "THIRD");
        }

        @Test
        void null_name_throws_illegal_argument_exception() {
            // Given
            LayerAction<Void, Object> action = LayerAction.passThrough();

            // When / Then
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.addLayer(null, action))
                    .withMessageContaining("name");
        }

        @Test
        void null_action_throws_illegal_argument_exception() {
            // When / Then
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.addLayer("VALID", null))
                    .withMessageContaining("action");
        }

        @Test
        void add_layer_returns_builder_for_fluent_chaining() {
            // Given
            LayerAction<Void, Object> action = LayerAction.passThrough();

            // When
            AspectPipelineBuilder<Object> returned = builder.addLayer("LAYER", action);

            // Then
            assertThat(returned).isSameAs(builder);
        }
    }

    @Nested
    @DisplayName("Adding layers via addProvider")
    class AddProvider {

        @Test
        void provider_is_extracted_into_a_named_layer() {
            // Given
            LayerAction<Void, Object> action = LayerAction.passThrough();
            AspectLayerProvider<Object> prov = provider("FROM_PROVIDER", 10, action);

            // When
            builder.addProvider(prov);

            // Then
            assertThat(builder.layers()).hasSize(1);
            assertThat(builder.layers().getFirst().name()).isEqualTo("FROM_PROVIDER");
            assertThat(builder.layers().getFirst().action()).isSameAs(action);
        }

        @Test
        void null_provider_throws_illegal_argument_exception() {
            // When / Then
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.addProvider(null))
                    .withMessageContaining("Provider");
        }
    }

    @Nested
    @DisplayName("Adding layers via addProviders")
    class AddProviders {

        @Test
        void providers_are_sorted_by_order_value() {
            // Given
            List<String> trace = new ArrayList<>();
            AspectLayerProvider<Object> high = provider("HIGH", 30, trace);
            AspectLayerProvider<Object> low = provider("LOW", 10, trace);
            AspectLayerProvider<Object> mid = provider("MID", 20, trace);

            // When — deliberate non-sorted insertion order
            builder.addProviders(List.of(high, low, mid));

            // Then — layers are reordered by priority
            assertThat(builder.layers())
                    .extracting(AspectPipelineBuilder.NamedLayer::name)
                    .containsExactly("LOW", "MID", "HIGH");
        }

        @Test
        void providers_with_equal_order_retain_relative_position() {
            // Given
            List<String> trace = new ArrayList<>();
            AspectLayerProvider<Object> alpha = provider("ALPHA", 10, trace);
            AspectLayerProvider<Object> beta = provider("BETA", 10, trace);
            AspectLayerProvider<Object> gamma = provider("GAMMA", 10, trace);

            // When
            builder.addProviders(List.of(alpha, beta, gamma));

            // Then — stable sort preserves input order
            assertThat(builder.layers())
                    .extracting(AspectPipelineBuilder.NamedLayer::name)
                    .containsExactly("ALPHA", "BETA", "GAMMA");
        }

        @Test
        void null_providers_list_throws_illegal_argument_exception() {
            // When / Then
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.addProviders(null))
                    .withMessageContaining("Providers list");
        }

        @Test
        void empty_providers_list_adds_no_layers() {
            // When
            builder.addProviders(Collections.emptyList());

            // Then
            assertThat(builder.layers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Building the wrapper chain")
    class BuildChain {

        @Test
        void empty_builder_produces_a_passthrough_wrapper() throws Throwable {
            // Given
            JoinPointExecutor<Object> core = () -> "core-result";

            // When
            JoinPointWrapper<Object> chain = builder.buildChain(core);

            // Then
            assertThat(chain.layerDescription()).isEqualTo("passthrough");
            assertThat(chain.proceed()).isEqualTo("core-result");
        }

        @Test
        void single_layer_wraps_the_core_executor() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            builder.addLayer("ONLY", recordingAction("ONLY", trace));
            JoinPointExecutor<Object> core = () -> {
                trace.add("core");
                return "done";
            };

            // When
            JoinPointWrapper<Object> chain = builder.buildChain(core);
            Object result = chain.proceed();

            // Then
            assertThat(result).isEqualTo("done");
            assertThat(trace).containsExactly("ONLY:before", "core", "ONLY:after");
        }

        @Test
        void multiple_layers_are_nested_outermost_first() throws Throwable {
            // Given
            List<String> trace = new ArrayList<>();
            builder.addLayer("OUTER", recordingAction("OUTER", trace));
            builder.addLayer("MIDDLE", recordingAction("MIDDLE", trace));
            builder.addLayer("INNER", recordingAction("INNER", trace));
            JoinPointExecutor<Object> core = () -> {
                trace.add("core");
                return "result";
            };

            // When
            JoinPointWrapper<Object> chain = builder.buildChain(core);
            chain.proceed();

            // Then — outermost layer executes first, innermost last before core
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
        void all_layers_share_the_same_chain_id() throws Throwable {
            // Given
            builder.addLayer("A", LayerAction.passThrough());
            builder.addLayer("B", LayerAction.passThrough());
            builder.addLayer("C", LayerAction.passThrough());
            JoinPointExecutor<Object> core = () -> "ok";

            // When
            JoinPointWrapper<Object> chain = builder.buildChain(core);

            // Then — walk the chain and verify all layers share the same chain ID
            long expectedChainId = chain.chainId();
            JoinPointWrapper<Object> current = chain;
            while (current != null) {
                assertThat(current.chainId()).isEqualTo(expectedChainId);
                current = current.inner();
            }
        }

        @Test
        void null_core_executor_throws_illegal_argument_exception() {
            // When / Then
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.buildChain(null))
                    .withMessageContaining("Core executor");
        }

        @Test
        void layer_description_matches_registered_name() throws Throwable {
            // Given
            builder.addLayer("AUTH", LayerAction.passThrough());
            builder.addLayer("RATE_LIMIT", LayerAction.passThrough());
            JoinPointExecutor<Object> core = () -> null;

            // When
            JoinPointWrapper<Object> chain = builder.buildChain(core);

            // Then
            assertThat(chain.layerDescription()).isEqualTo("AUTH");
            assertThat(chain.inner().layerDescription()).isEqualTo("RATE_LIMIT");
        }

        @Test
        void hierarchy_string_reflects_all_layers() throws Throwable {
            // Given
            builder.addLayer("LOGGING", LayerAction.passThrough());
            builder.addLayer("TIMING", LayerAction.passThrough());
            builder.addLayer("RETRY", LayerAction.passThrough());
            JoinPointExecutor<Object> core = () -> null;

            // When
            JoinPointWrapper<Object> chain = builder.buildChain(core);
            String hierarchy = chain.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                    .contains("LOGGING")
                    .contains("TIMING")
                    .contains("RETRY");
        }
    }

    @Nested
    @DisplayName("Exception handling through the chain")
    class ExceptionHandling {

        @Test
        void runtime_exception_from_core_propagates_directly() {
            // Given
            builder.addLayer("LAYER", LayerAction.passThrough());
            JoinPointExecutor<Object> core = () -> {
                throw new IllegalStateException("boom");
            };
            JoinPointWrapper<Object> chain = builder.buildChain(core);

            // When / Then
            assertThatThrownBy(chain::proceed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
        }

        @Test
        void checked_exception_from_core_is_unwrapped_and_rethrown() {
            // Given
            builder.addLayer("LAYER", LayerAction.passThrough());
            JoinPointExecutor<Object> core = () -> {
                throw new Exception("checked");
            };
            JoinPointWrapper<Object> chain = builder.buildChain(core);

            // When / Then — JoinPointWrapper unwraps CompletionException
            assertThatThrownBy(chain::proceed)
                    .isInstanceOf(Exception.class)
                    .hasMessage("checked")
                    .isNotInstanceOf(RuntimeException.class);
        }

        @Test
        void error_from_core_propagates_without_wrapping() {
            // Given
            builder.addLayer("LAYER", LayerAction.passThrough());
            JoinPointExecutor<Object> core = () -> {
                throw new OutOfMemoryError("oom");
            };
            JoinPointWrapper<Object> chain = builder.buildChain(core);

            // When / Then
            assertThatThrownBy(chain::proceed)
                    .isInstanceOf(OutOfMemoryError.class)
                    .hasMessage("oom");
        }

        @Test
        void exception_handling_layer_can_catch_and_return_fallback() throws Throwable {
            // Given — a layer that catches exceptions and returns a fallback
            LayerAction<Void, Object> fallbackAction = (chainId, callId, arg, next) -> {
                try {
                    return next.execute(chainId, callId, arg);
                } catch (Exception e) {
                    return "fallback";
                }
            };
            builder.addLayer("FALLBACK", fallbackAction);
            JoinPointExecutor<Object> core = () -> {
                throw new RuntimeException("fail");
            };

            // When
            JoinPointWrapper<Object> chain = builder.buildChain(core);
            Object result = chain.proceed();

            // Then
            assertThat(result).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("Layer actions with custom behavior")
    class LayerBehavior {

        @Test
        void layer_can_modify_the_return_value() throws Throwable {
            // Given
            LayerAction<Void, Object> uppercaseAction = (chainId, callId, arg, next) -> {
                Object result = next.execute(chainId, callId, arg);
                return result.toString().toUpperCase();
            };
            builder.addLayer("UPPER", uppercaseAction);
            JoinPointExecutor<Object> core = () -> "hello";

            // When
            Object result = builder.buildChain(core).proceed();

            // Then
            assertThat(result).isEqualTo("HELLO");
        }

        @Test
        void layer_can_short_circuit_without_calling_next() throws Throwable {
            // Given
            AtomicInteger coreCallCount = new AtomicInteger();
            LayerAction<Void, Object> cacheAction = (chainId, callId, arg, next) -> "cached";
            builder.addLayer("CACHE", cacheAction);
            JoinPointExecutor<Object> core = () -> {
                coreCallCount.incrementAndGet();
                return "not-cached";
            };

            // When
            Object result = builder.buildChain(core).proceed();

            // Then
            assertThat(result).isEqualTo("cached");
            assertThat(coreCallCount).hasValue(0);
        }

        @Test
        void layer_can_retry_by_calling_next_multiple_times() throws Throwable {
            // Given
            AtomicInteger attempts = new AtomicInteger();
            LayerAction<Void, Object> retryAction = (chainId, callId, arg, next) -> {
                for (int i = 0; i < 3; i++) {
                    try {
                        return next.execute(chainId, callId, arg);
                    } catch (RuntimeException e) {
                        if (i == 2) throw e;
                    }
                }
                throw new AssertionError("unreachable");
            };
            builder.addLayer("RETRY", retryAction);
            JoinPointExecutor<Object> core = () -> {
                int attempt = attempts.incrementAndGet();
                if (attempt < 3) throw new RuntimeException("transient");
                return "success-on-" + attempt;
            };

            // When
            Object result = builder.buildChain(core).proceed();

            // Then
            assertThat(result).isEqualTo("success-on-3");
            assertThat(attempts).hasValue(3);
        }

        @Test
        void chain_id_and_call_id_are_passed_through_all_layers() throws Throwable {
            // Given
            List<Long> seenChainIds = new ArrayList<>();
            List<Long> seenCallIds = new ArrayList<>();

            LayerAction<Void, Object> captureAction = (chainId, callId, arg, next) -> {
                seenChainIds.add(chainId);
                seenCallIds.add(callId);
                return next.execute(chainId, callId, arg);
            };

            builder.addLayer("A", captureAction);
            builder.addLayer("B", captureAction);
            JoinPointExecutor<Object> core = () -> "ok";

            // When
            builder.buildChain(core).proceed();

            // Then — both layers received the same chain ID and call ID
            assertThat(seenChainIds).hasSize(2);
            assertThat(seenChainIds.get(0)).isEqualTo(seenChainIds.get(1));
            assertThat(seenCallIds).hasSize(2);
            assertThat(seenCallIds.get(0)).isEqualTo(seenCallIds.get(1));
        }
    }

    @Nested
    @DisplayName("Layers view is unmodifiable")
    class LayersView {

        @Test
        void layers_returns_an_unmodifiable_list() {
            // Given
            builder.addLayer("A", LayerAction.passThrough());

            // When
            List<AspectPipelineBuilder.NamedLayer<Object>> view = builder.layers();

            // Then
            assertThatThrownBy(() -> view.add(new AspectPipelineBuilder.NamedLayer<>("X", LayerAction.passThrough())))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("NamedLayer record validation")
    class NamedLayerValidation {

        @Test
        void named_layer_rejects_null_name() {
            // When / Then
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AspectPipelineBuilder.NamedLayer<>(null, LayerAction.passThrough()));
        }

        @Test
        void named_layer_rejects_null_action() {
            // When / Then
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AspectPipelineBuilder.NamedLayer<>("VALID", null));
        }
    }
}
