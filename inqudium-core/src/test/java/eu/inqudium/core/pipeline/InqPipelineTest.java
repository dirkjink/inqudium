package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqPipeline")
class InqPipelineTest {

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Creates a stub InqDecorator that records its name when the chain
     * passes through it.
     */
    private static StubDecorator decorator(String name, InqElementType type) {
        return new StubDecorator(name, type);
    }

    /**
     * Minimal InqDecorator stub for testing. Records execution order
     * in a shared trace list when the chain is executed.
     */
    static class StubDecorator implements InqDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;

        StubDecorator(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return type;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            return next.execute(chainId, callId, arg);
        }
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
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return type;
        }

        @Override
        public InqEventPublisher eventPublisher() {
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

    // =========================================================================
    // Builder
    // =========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        void empty_pipeline_has_no_elements() {
            // When
            InqPipeline pipeline = InqPipeline.builder().build();

            // Then
            assertThat(pipeline.elements()).isEmpty();
            assertThat(pipeline.depth()).isZero();
            assertThat(pipeline.isEmpty()).isTrue();
        }

        @Test
        void single_element_pipeline() {
            // Given
            StubDecorator cb = decorator("cb", InqElementType.CIRCUIT_BREAKER);

            // When
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(cb)
                    .build();

            // Then
            assertThat(pipeline.elements()).containsExactly(cb);
            assertThat(pipeline.depth()).isEqualTo(1);
            assertThat(pipeline.isEmpty()).isFalse();
        }

        @Test
        void shield_rejects_null_element() {
            // When / Then
            assertThatThrownBy(() -> InqPipeline.builder().shield(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("null");
        }

        @Test
        void shield_all_varargs_adds_multiple_elements() {
            // Given
            StubDecorator cb = decorator("cb", InqElementType.CIRCUIT_BREAKER);
            StubDecorator rt = decorator("rt", InqElementType.RETRY);

            // When
            InqPipeline pipeline = InqPipeline.builder()
                    .shieldAll(cb, rt)
                    .build();

            // Then
            assertThat(pipeline.depth()).isEqualTo(2);
        }

        @Test
        void shield_all_iterable_adds_multiple_elements() {
            // Given
            List<InqElement> elements = List.of(
                    decorator("cb", InqElementType.CIRCUIT_BREAKER),
                    decorator("rt", InqElementType.RETRY));

            // When
            InqPipeline pipeline = InqPipeline.builder()
                    .shieldAll(elements)
                    .build();

            // Then
            assertThat(pipeline.depth()).isEqualTo(2);
        }

        @Test
        void built_pipeline_is_immutable() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(decorator("cb", InqElementType.CIRCUIT_BREAKER))
                    .build();

            // When / Then
            assertThatThrownBy(() -> pipeline.elements().add(
                    decorator("rt", InqElementType.RETRY)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // Ordering
    // =========================================================================

    @Nested
    @DisplayName("Standard ordering (ADR-017)")
    class StandardOrdering {

        @Test
        void elements_are_sorted_outermost_first_regardless_of_shield_order() {
            // Given — added in arbitrary order
            StubDecorator retry = decorator("rt", InqElementType.RETRY);
            StubDecorator cb = decorator("cb", InqElementType.CIRCUIT_BREAKER);
            StubDecorator tl = decorator("tl", InqElementType.TIME_LIMITER);
            StubDecorator rl = decorator("rl", InqElementType.RATE_LIMITER);

            // When — no explicit order → standard ordering
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(retry)
                    .shield(cb)
                    .shield(tl)
                    .shield(rl)
                    .build();

            // Then — sorted by standard order: TL(100) → RL(300) → CB(500) → RT(600)
            assertThat(pipeline.elements())
                    .extracting(InqElement::name)
                    .containsExactly("tl", "rl", "cb", "rt");
        }

        @Test
        void full_canonical_order_matches_adr_017() {
            // Given — all six element types
            StubDecorator tl = decorator("tl", InqElementType.TIME_LIMITER);
            StubDecorator ts = decorator("ts", InqElementType.TRAFFIC_SHAPER);
            StubDecorator rl = decorator("rl", InqElementType.RATE_LIMITER);
            StubDecorator bh = decorator("bh", InqElementType.BULKHEAD);
            StubDecorator cb = decorator("cb", InqElementType.CIRCUIT_BREAKER);
            StubDecorator rt = decorator("rt", InqElementType.RETRY);

            // When — shuffled input
            InqPipeline pipeline = InqPipeline.builder()
                    .shieldAll(rt, bh, tl, rl, cb, ts)
                    .build();

            // Then — ADR-017 canonical: TL → TS → RL → BH → CB → RT
            assertThat(pipeline.elements())
                    .extracting(InqElement::name)
                    .containsExactly("tl", "ts", "rl", "bh", "cb", "rt");
        }

        @Test
        void equal_order_values_retain_shield_registration_order() {
            // Given — two circuit breakers (same type → same order)
            StubDecorator cb1 = decorator("cb-primary", InqElementType.CIRCUIT_BREAKER);
            StubDecorator cb2 = decorator("cb-secondary", InqElementType.CIRCUIT_BREAKER);

            // When
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(cb1)
                    .shield(cb2)
                    .build();

            // Then — stable sort preserves registration order
            assertThat(pipeline.elements())
                    .extracting(InqElement::name)
                    .containsExactly("cb-primary", "cb-secondary");
        }
    }

    @Nested
    @DisplayName("Resilience4J ordering")
    class Resilience4JOrdering {

        @Test
        void elements_are_sorted_in_resilience4j_order() {
            // Given
            StubDecorator retry = decorator("rt", InqElementType.RETRY);
            StubDecorator cb = decorator("cb", InqElementType.CIRCUIT_BREAKER);
            StubDecorator bh = decorator("bh", InqElementType.BULKHEAD);
            StubDecorator tl = decorator("tl", InqElementType.TIME_LIMITER);

            // When — Resilience4J order
            InqPipeline pipeline = InqPipeline.builder()
                    .shieldAll(bh, tl, cb, retry)
                    .order(PipelineOrdering.resilience4j())
                    .build();

            // Then — R4J: RT(100) → CB(200) → TL(500) → BH(600)
            assertThat(pipeline.elements())
                    .extracting(InqElement::name)
                    .containsExactly("rt", "cb", "tl", "bh");
        }
    }

    @Nested
    @DisplayName("Custom ordering")
    class CustomOrdering {

        @Test
        void custom_ordering_via_lambda() {
            // Given — custom: retry outermost, then bulkhead
            PipelineOrdering custom = type -> switch (type) {
                case RETRY -> 10;
                case BULKHEAD -> 20;
                default -> type.defaultPipelineOrder();
            };

            StubDecorator rt = decorator("rt", InqElementType.RETRY);
            StubDecorator bh = decorator("bh", InqElementType.BULKHEAD);
            StubDecorator cb = decorator("cb", InqElementType.CIRCUIT_BREAKER);

            // When
            InqPipeline pipeline = InqPipeline.builder()
                    .shieldAll(cb, bh, rt)
                    .order(custom)
                    .build();

            // Then — RT(10) → BH(20) → CB(500, default)
            assertThat(pipeline.elements())
                    .extracting(InqElement::name)
                    .containsExactly("rt", "bh", "cb");
        }
    }

    // =========================================================================
    // Chain fold
    // =========================================================================

    @Nested
    @DisplayName("Chain fold")
    class ChainFold {

        @Test
        void chain_folds_elements_innermost_first() {
            // Given — three elements in standard order
            List<String> foldOrder = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(decorator("tl", InqElementType.TIME_LIMITER))
                    .shield(decorator("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(decorator("rt", InqElementType.RETRY))
                    .build();

            // When — fold, recording the order
            pipeline.chain("seed", (acc, element) -> {
                foldOrder.add(element.name());
                return acc + "→" + element.name();
            });

            // Then — innermost first: RT → CB → TL
            assertThat(foldOrder).containsExactly("rt", "cb", "tl");
        }

        @Test
        void chain_produces_correct_nesting_string() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(decorator("tl", InqElementType.TIME_LIMITER))
                    .shield(decorator("cb", InqElementType.CIRCUIT_BREAKER))
                    .shield(decorator("rt", InqElementType.RETRY))
                    .build();

            // When — fold elements into a nesting representation
            String chain = pipeline.chain("core", (acc, element) ->
                    element.name() + "(" + acc + ")");

            // Then — outermost wraps all: tl(cb(rt(core)))
            assertThat(chain).isEqualTo("tl(cb(rt(core)))");
        }

        @Test
        void chain_with_empty_pipeline_returns_seed_unchanged() {
            // Given
            InqPipeline pipeline = InqPipeline.builder().build();

            // When
            String result = pipeline.chain("passthrough", (acc, element) ->
                    element.name() + "(" + acc + ")");

            // Then
            assertThat(result).isEqualTo("passthrough");
        }

        @Test
        void chain_with_single_element_wraps_seed_once() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(decorator("cb", InqElementType.CIRCUIT_BREAKER))
                    .build();

            // When
            String result = pipeline.chain("core", (acc, element) ->
                    element.name() + "(" + acc + ")");

            // Then
            assertThat(result).isEqualTo("cb(core)");
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    @Nested
    @DisplayName("Diagnostics")
    class Diagnostics {

        @Test
        void to_string_shows_pipeline_summary() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(decorator("paymentCb", InqElementType.CIRCUIT_BREAKER))
                    .shield(decorator("paymentRetry", InqElementType.RETRY))
                    .build();

            // When
            String summary = pipeline.toString();

            // Then
            assertThat(summary)
                    .contains("2 elements")
                    .contains("CIRCUIT_BREAKER")
                    .contains("paymentCb")
                    .contains("RETRY")
                    .contains("paymentRetry");
        }

        @Test
        void ordering_is_accessible() {
            // Given
            PipelineOrdering r4j = PipelineOrdering.resilience4j();

            // When
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(decorator("cb", InqElementType.CIRCUIT_BREAKER))
                    .order(r4j)
                    .build();

            // Then
            assertThat(pipeline.ordering()).isSameAs(r4j);
        }
    }
}
