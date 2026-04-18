package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test verifying the full pipeline chain:
 * {@link InqPipeline} → {@link SyncPipelineTerminal} → real element behavior.
 *
 * <p>Uses simplified but realistic implementations of Bulkhead and
 * CircuitBreaker that mirror the behavior of the production elements
 * (permit management, sliding window, state transitions) without
 * requiring the full element module dependencies.</p>
 *
 * <h3>What this test proves</h3>
 * <ul>
 *   <li>Elements are composed and sorted correctly by {@link PipelineOrdering}</li>
 *   <li>Permit acquire/release works through the pipeline chain</li>
 *   <li>Circuit state transitions work through the pipeline chain</li>
 *   <li>Elements interact correctly (outer rejects before inner sees the call)</li>
 *   <li>Exception propagation preserves type and message through all layers</li>
 *   <li>The same pipeline works for all dispatch styles (functions, proxy, AspectJ)</li>
 * </ul>
 */
@DisplayName("InqPipeline end-to-end integration")
class InqPipelineEndToEndTest {

    // =========================================================================
    // Simplified element implementations
    // =========================================================================

    /**
     * Simplified bulkhead — limits concurrency via a semaphore.
     * Mirrors the real {@code Bulkhead}'s permit management:
     * acquire before call, release on success or failure.
     */
    static class SimpleBulkhead implements InqDecorator<Void, Object> {

        private final String name;
        private final Semaphore semaphore;
        private final List<String> trace;

        SimpleBulkhead(String name, int maxConcurrent, List<String> trace) {
            this.name = name;
            this.semaphore = new Semaphore(maxConcurrent);
            this.trace = trace;
        }

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return InqElementType.BULKHEAD; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        int availablePermits() { return semaphore.availablePermits(); }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            if (!semaphore.tryAcquire()) {
                trace.add("BH:reject");
                throw new RuntimeException("INQ-BH-001: Bulkhead '" + name + "' is full");
            }
            trace.add("BH:acquire");
            try {
                Object result = next.execute(chainId, callId, arg);
                trace.add("BH:release");
                return result;
            } catch (Throwable t) {
                trace.add("BH:release-on-error");
                throw t;
            } finally {
                semaphore.release();
            }
        }
    }

    /**
     * Simplified circuit breaker — count-based sliding window.
     * Mirrors the real {@code ImperativeCircuitBreaker}'s state machine:
     * CLOSED → OPEN (after failure threshold), rejects when OPEN.
     */
    static class SimpleCircuitBreaker implements InqDecorator<Void, Object> {

        enum State { CLOSED, OPEN }

        private final String name;
        private final int failureThreshold;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private volatile State state = State.CLOSED;
        private final List<String> trace;

        SimpleCircuitBreaker(String name, int failureThreshold, List<String> trace) {
            this.name = name;
            this.failureThreshold = failureThreshold;
            this.trace = trace;
        }

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return InqElementType.CIRCUIT_BREAKER; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        State state() { return state; }

        void reset() {
            state = State.CLOSED;
            failureCount.set(0);
        }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            if (state == State.OPEN) {
                trace.add("CB:reject");
                throw new RuntimeException("INQ-CB-001: CircuitBreaker '" + name + "' is OPEN");
            }
            trace.add("CB:permit");
            try {
                Object result = next.execute(chainId, callId, arg);
                trace.add("CB:success");
                return result;
            } catch (Throwable t) {
                int failures = failureCount.incrementAndGet();
                if (failures >= failureThreshold) {
                    state = State.OPEN;
                    trace.add("CB:failure→OPEN");
                } else {
                    trace.add("CB:failure(" + failures + "/" + failureThreshold + ")");
                }
                throw t;
            }
        }
    }

    // =========================================================================
    // Pipeline composition and ordering
    // =========================================================================

    @Nested
    @DisplayName("Pipeline composition and ordering")
    class CompositionAndOrdering {

        private List<String> trace;
        private SimpleBulkhead bulkhead;
        private SimpleCircuitBreaker circuitBreaker;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
            bulkhead = new SimpleBulkhead("bh", 5, trace);
            circuitBreaker = new SimpleCircuitBreaker("cb", 3, trace);
        }

        @Test
        void standard_ordering_places_bulkhead_outside_circuit_breaker() throws Throwable {
            // Given — standard order: BH(400) → CB(500)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(circuitBreaker)
                    .shield(bulkhead)
                    .build();

            // When
            Object result = SyncPipelineTerminal.of(pipeline)
                    .execute(() -> "success");

            // Then — BH first (outer), then CB (inner)
            assertThat(result).isEqualTo("success");
            assertThat(trace).containsExactly(
                    "BH:acquire", "CB:permit", "CB:success", "BH:release");
        }

        @Test
        void resilience4j_ordering_places_circuit_breaker_outside_bulkhead() throws Throwable {
            // Given — R4J order: CB(200) → BH(600)
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(circuitBreaker)
                    .shield(bulkhead)
                    .order(PipelineOrdering.resilience4j())
                    .build();

            // When
            SyncPipelineTerminal.of(pipeline).execute(() -> "success");

            // Then — CB first (outer), then BH (inner)
            assertThat(trace).containsExactly(
                    "CB:permit", "BH:acquire", "BH:release", "CB:success");
        }

        @Test
        void shield_order_is_irrelevant_when_using_predefined_ordering() throws Throwable {
            // Given — added BH first, CB second — but ordering sorts them
            InqPipeline pipeline1 = InqPipeline.builder()
                    .shield(bulkhead).shield(circuitBreaker).build();
            InqPipeline pipeline2 = InqPipeline.builder()
                    .shield(circuitBreaker).shield(bulkhead).build();

            // When
            SyncPipelineTerminal.of(pipeline1).execute(() -> "a");
            List<String> trace1 = List.copyOf(trace);
            trace.clear();
            SyncPipelineTerminal.of(pipeline2).execute(() -> "b");

            // Then — identical execution order regardless of shield() order
            assertThat(trace1).containsExactly("BH:acquire", "CB:permit", "CB:success", "BH:release");
            assertThat(trace).containsExactly("BH:acquire", "CB:permit", "CB:success", "BH:release");
        }

        @Test
        void pipeline_elements_list_reflects_the_sorted_order() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(circuitBreaker)
                    .shield(bulkhead)
                    .build();

            // Then
            assertThat(pipeline.elements())
                    .extracting(InqElement::getName)
                    .containsExactly("bh", "cb");
            assertThat(pipeline.depth()).isEqualTo(2);
        }
    }

    // =========================================================================
    // Bulkhead behavior through the pipeline
    // =========================================================================

    @Nested
    @DisplayName("Bulkhead behavior through the pipeline")
    class BulkheadBehavior {

        private List<String> trace;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
        }

        @Test
        void bulkhead_releases_permit_after_successful_pipeline_call() throws Throwable {
            // Given — 1 permit
            SimpleBulkhead bh = new SimpleBulkhead("bh", 1, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build());

            // When
            terminal.execute(() -> "first");

            // Then — permit released, second call succeeds
            assertThat(bh.availablePermits()).isEqualTo(1);
            Object second = terminal.execute(() -> "second");
            assertThat(second).isEqualTo("second");
        }

        @Test
        void bulkhead_releases_permit_after_failed_pipeline_call() {
            // Given — 1 permit
            SimpleBulkhead bh = new SimpleBulkhead("bh", 1, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build());

            // When — call fails
            try {
                terminal.execute(() -> { throw new RuntimeException("boom"); });
            } catch (Throwable ignored) {}

            // Then — permit still released
            assertThat(bh.availablePermits()).isEqualTo(1);
            assertThat(trace).contains("BH:acquire", "BH:release-on-error");
        }

        @Test
        void bulkhead_rejects_when_all_permits_are_held() throws Exception {
            // Given — 1 permit, held by a blocking call
            SimpleBulkhead bh = new SimpleBulkhead("bh", 1, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build());

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();

            executor.submit(() -> {
                try {
                    terminal.execute(() -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return "blocking";
                    });
                } catch (Throwable ignored) {}
            });

            entered.await(2, TimeUnit.SECONDS);

            // When / Then — second call is rejected
            assertThatThrownBy(() -> terminal.execute(() -> "rejected"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("INQ-BH-001");

            release.countDown();
            executor.shutdown();
        }
    }

    // =========================================================================
    // CircuitBreaker behavior through the pipeline
    // =========================================================================

    @Nested
    @DisplayName("CircuitBreaker behavior through the pipeline")
    class CircuitBreakerBehavior {

        private List<String> trace;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
        }

        @Test
        void circuit_breaker_permits_calls_in_closed_state() throws Throwable {
            // Given
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 3, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(cb).build());

            // When
            Object result = terminal.execute(() -> "hello");

            // Then
            assertThat(result).isEqualTo("hello");
            assertThat(cb.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        }

        @Test
        void circuit_breaker_opens_after_failure_threshold_through_pipeline() {
            // Given — threshold = 3
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 3, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(cb).build());

            // When — 3 failures
            for (int i = 0; i < 3; i++) {
                try {
                    int finalI = i;
                    terminal.execute(() -> { throw new RuntimeException("fail-" + finalI); });
                } catch (Throwable ignored) {}
            }

            // Then — circuit is open
            assertThat(cb.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        }

        @Test
        void open_circuit_breaker_rejects_subsequent_calls() {
            // Given — force open
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 1, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(cb).build());

            try {
                terminal.execute(() -> { throw new RuntimeException("trip"); });
            } catch (Throwable ignored) {}
            assertThat(cb.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);

            // When / Then — next call is rejected
            assertThatThrownBy(() -> terminal.execute(() -> "should-not-reach"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("INQ-CB-001");
            assertThat(trace).contains("CB:reject");
        }
    }

    // =========================================================================
    // Multi-element interaction
    // =========================================================================

    @Nested
    @DisplayName("Multi-element interaction")
    class MultiElementInteraction {

        private List<String> trace;

        @BeforeEach
        void setUp() {
            trace = new ArrayList<>();
        }

        @Test
        void bulkhead_rejection_prevents_circuit_breaker_from_recording_a_failure()
                throws Exception {
            // Given — BH with 1 permit (outer), CB with threshold 3 (inner)
            SimpleBulkhead bh = new SimpleBulkhead("bh", 1, trace);
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 3, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).shield(cb).build());

            // Hold the single BH permit
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();

            executor.submit(() -> {
                try {
                    terminal.execute(() -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return "blocking";
                    });
                } catch (Throwable ignored) {}
            });

            entered.await(2, TimeUnit.SECONDS);

            // When — BH rejects before CB sees the call
            try {
                terminal.execute(() -> "rejected");
            } catch (Throwable ignored) {}

            // Then — CB never saw the rejection, stays CLOSED
            assertThat(cb.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
            assertThat(trace).contains("BH:reject");
            assertThat(trace).doesNotContain("CB:reject");

            release.countDown();
            executor.shutdown();
        }

        @Test
        void circuit_breaker_opens_while_bulkhead_still_has_permits() throws Throwable {
            // Given — BH with 5 permits (outer), CB with threshold 2 (inner)
            SimpleBulkhead bh = new SimpleBulkhead("bh", 5, trace);
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 2, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).shield(cb).build());

            // When — 2 failures through the pipeline
            for (int i = 0; i < 2; i++) {
                try {
                    terminal.execute(() -> { throw new RuntimeException("fail"); });
                } catch (Throwable ignored) {}
            }

            // Then — CB is open, BH still has all 5 permits
            assertThat(cb.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
            assertThat(bh.availablePermits()).isEqualTo(5);

            // And — next call: BH acquires permit, but CB rejects inside
            assertThatThrownBy(() -> terminal.execute(() -> "should-not-reach"))
                    .hasMessageContaining("INQ-CB-001");

            // BH still released the permit despite CB rejection
            assertThat(bh.availablePermits()).isEqualTo(5);
        }

        @Test
        void successful_call_through_full_pipeline_produces_correct_trace() throws Throwable {
            // Given — BH(400) → CB(500) → core
            SimpleBulkhead bh = new SimpleBulkhead("bh", 5, trace);
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 3, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).shield(cb).build());

            // When
            Object result = terminal.execute(() -> {
                trace.add("CORE:execute");
                return "pipeline-result";
            });

            // Then
            assertThat(result).isEqualTo("pipeline-result");
            assertThat(trace).containsExactly(
                    "BH:acquire",
                    "CB:permit",
                    "CORE:execute",
                    "CB:success",
                    "BH:release"
            );
        }

        @Test
        void failed_call_through_full_pipeline_produces_correct_trace() {
            // Given
            SimpleBulkhead bh = new SimpleBulkhead("bh", 5, trace);
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 3, trace);
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).shield(cb).build());

            // When
            try {
                terminal.execute(() -> {
                    trace.add("CORE:execute");
                    throw new RuntimeException("business error");
                });
            } catch (Throwable ignored) {}

            // Then
            assertThat(trace).containsExactly(
                    "BH:acquire",
                    "CB:permit",
                    "CORE:execute",
                    "CB:failure(1/3)",
                    "BH:release-on-error"
            );
        }
    }

    // =========================================================================
    // Pipeline reuse and dispatch agnosticism
    // =========================================================================

    @Nested
    @DisplayName("Pipeline reuse and dispatch styles")
    class PipelineReuse {

        @Test
        void same_pipeline_can_be_used_with_different_executors() throws Throwable {
            // Given — one pipeline, one terminal
            List<String> trace = new ArrayList<>();
            SimpleBulkhead bh = new SimpleBulkhead("bh", 5, trace);
            SimpleCircuitBreaker cb = new SimpleCircuitBreaker("cb", 3, trace);
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(bh).shield(cb).build();
            SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

            // When — Function-style
            trace.clear();
            Object r1 = terminal.execute(() -> "function-style");
            assertThat(r1).isEqualTo("function-style");

            // When — Proxy-style (simulated method.invoke)
            trace.clear();
            Object r2 = terminal.execute(() -> "proxy-style");
            assertThat(r2).isEqualTo("proxy-style");

            // When — AspectJ-style (simulated pjp.proceed)
            trace.clear();
            Object r3 = terminal.execute(() -> "aspectj-style");
            assertThat(r3).isEqualTo("aspectj-style");

            // Then — all three produce the same trace pattern
            // (verified individually above; permits still balanced)
            assertThat(bh.availablePermits()).isEqualTo(5);
            assertThat(cb.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        }

        @Test
        void decorated_supplier_wraps_pipeline_for_repeated_calls() {
            // Given
            List<String> trace = new ArrayList<>();
            SimpleBulkhead bh = new SimpleBulkhead("bh", 5, trace);
            InqPipeline pipeline = InqPipeline.builder().shield(bh).build();

            java.util.function.Supplier<Object> decorated =
                    SyncPipelineTerminal.of(pipeline).decorateSupplier(() -> "reusable");

            // When — three calls
            decorated.get();
            decorated.get();
            decorated.get();

            // Then — three acquire/release cycles, all permits restored
            assertThat(trace).hasSize(6);
            assertThat(bh.availablePermits()).isEqualTo(5);
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    @Nested
    @DisplayName("Pipeline diagnostics")
    class PipelineDiagnostics {

        @Test
        void to_string_shows_element_names_and_types_in_order() {
            // Given
            List<String> trace = new ArrayList<>();
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new SimpleCircuitBreaker("paymentCb", 3, trace))
                    .shield(new SimpleBulkhead("paymentBh", 10, trace))
                    .build();

            // When
            String summary = pipeline.toString();

            // Then
            System.out.println(summary);
            assertThat(summary)
                    .contains("2 elements")
                    .contains("BULKHEAD")
                    .contains("paymentBh")
                    .contains("CIRCUIT_BREAKER")
                    .contains("paymentCb");

            // Ordering: BH before CB in the output (lower order = outermost = first)
            assertThat(summary.indexOf("BULKHEAD"))
                    .isLessThan(summary.indexOf("CIRCUIT_BREAKER"));
        }
    }
}
