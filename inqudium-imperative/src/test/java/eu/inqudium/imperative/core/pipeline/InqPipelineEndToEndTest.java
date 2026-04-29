package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerException;
import eu.inqudium.core.element.circuitbreaker.CircuitState;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.metrics.SlidingWindowMetrics;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.PipelineOrdering;
import eu.inqudium.core.pipeline.SyncPipelineTerminal;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.bulkhead.Bulkhead;
import eu.inqudium.imperative.circuitbreaker.ImperativeCircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder.bulkhead;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * End-to-end integration test verifying the full pipeline chain with
 * <strong>real</strong> {@link Bulkhead} and {@link ImperativeCircuitBreaker}
 * implementations composed via {@link InqPipeline} and executed through
 * {@link SyncPipelineTerminal}.
 *
 * <h3>What this test proves</h3>
 * <ul>
 *   <li>Real elements can be passed to {@code InqPipeline.builder().shield()}
 *       and are sorted correctly by {@link PipelineOrdering}</li>
 *   <li>Bulkhead permit management (acquire/release) works through the
 *       pipeline chain — permits are released on success and failure</li>
 *   <li>CircuitBreaker state machine (CLOSED → OPEN → HALF_OPEN → CLOSED)
 *       works through the pipeline chain</li>
 *   <li>Real exception types ({@link InqBulkheadFullException},
 *       {@link CircuitBreakerException}) propagate through all layers</li>
 *   <li>Elements interact correctly: outer element rejection prevents
 *       inner element from recording a failure</li>
 * </ul>
 *
 * <h3>Deterministic time</h3>
 * <p>Uses a deterministic {@link InqNanoTimeSource} backed by an
 * {@link AtomicLong} for the CircuitBreaker's wait duration, ensuring
 * tests run without {@code Thread.sleep()} (ADR-016).</p>
 */
@DisplayName("InqPipeline end-to-end with real elements")
class InqPipelineEndToEndTest {

    // =========================================================================
    // Shared infrastructure
    // =========================================================================

    private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    // Deterministic time source for CircuitBreaker
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final InqNanoTimeSource timeSource = clock::get;

    private final GeneralConfig generalConfig = new GeneralConfig(
            Instant::now,
            timeSource,
            null,
            LoggerFactory.NO_OP_LOGGER_FACTORY,
            true,
            Map.of()
    );

    // =========================================================================
    // Element factories
    // =========================================================================

    private Bulkhead<Void, Object> createBulkhead(String name, int maxConcurrent) {
        var config = InqConfig.configure()
                .general()
                .with(bulkhead(), c -> c
                        .name(name)
                        .maxConcurrentCalls(maxConcurrent)
                ).build();
        return Bulkhead.of(config);
    }

    private ImperativeCircuitBreaker<Void, Object> createCircuitBreaker(
            String name, int failureThreshold, int windowSize, int minimumCalls,
            int successThreshold, int permittedInHalfOpen) {
        var config = new InqCircuitBreakerConfig(
                generalConfig,
                new InqElementCommonConfig(name, InqElementType.CIRCUIT_BREAKER, null),
                WAIT_DURATION.toNanos(),
                successThreshold,
                permittedInHalfOpen,
                WAIT_DURATION,
                t -> true,
                nowNanos -> SlidingWindowMetrics.initial(
                        failureThreshold, windowSize, minimumCalls)
        );
        return new ImperativeCircuitBreaker<>(
                config, config.metricsFactory(), config.recordFailurePredicate());
    }

    private ImperativeCircuitBreaker<Void, Object> createDefaultCircuitBreaker(String name) {
        return createCircuitBreaker(name, 3, 10, 3, 2, 3);
    }

    private void advancePastWaitDuration() {
        clock.addAndGet(WAIT_DURATION.toNanos() + NANOS_PER_SECOND);
    }

    // =========================================================================
    // Pipeline composition and ordering
    // =========================================================================

    @Nested
    @DisplayName("Pipeline composition and ordering")
    class CompositionAndOrdering {

        @Test
        void standard_ordering_places_bulkhead_outside_circuit_breaker() throws Throwable {
            // Given — standard order: BH(400) → CB(500)
            var bh = createBulkhead("bh", 5);
            var cb = createDefaultCircuitBreaker("cb");

            InqPipeline pipeline = InqPipeline.builder()
                    .shield(cb)
                    .shield(bh)
                    .build();

            // When
            Object result = SyncPipelineTerminal.of(pipeline)
                    .execute(() -> "success");

            // Then
            assertThat(result).isEqualTo("success");
            assertThat(pipeline.elements())
                    .extracting(InqElement::name)
                    .containsExactly("bh", "cb");
        }

        @Test
        void resilience4j_ordering_places_circuit_breaker_outside_bulkhead() throws Throwable {
            // Given — R4J order: CB(200) → BH(600)
            var bh = createBulkhead("bh", 5);
            var cb = createDefaultCircuitBreaker("cb");

            InqPipeline pipeline = InqPipeline.builder()
                    .shield(cb)
                    .shield(bh)
                    .order(PipelineOrdering.resilience4j())
                    .build();

            // When
            Object result = SyncPipelineTerminal.of(pipeline)
                    .execute(() -> "success");

            // Then — CB before BH
            assertThat(result).isEqualTo("success");
            assertThat(pipeline.elements())
                    .extracting(InqElement::name)
                    .containsExactly("cb", "bh");
        }

        @Test
        void elements_report_correct_element_types() {
            // Given
            var bh = createBulkhead("bh", 5);
            var cb = createDefaultCircuitBreaker("cb");

            InqPipeline pipeline = InqPipeline.builder()
                    .shield(bh).shield(cb).build();

            // Then
            assertThat(pipeline.elements())
                    .extracting(InqElement::elementType)
                    .containsExactly(InqElementType.BULKHEAD, InqElementType.CIRCUIT_BREAKER);
        }
    }

    // =========================================================================
    // Bulkhead behavior through the pipeline
    // =========================================================================

    @Nested
    @DisplayName("Bulkhead behavior through the pipeline")
    class BulkheadBehavior {

        @Test
        void permits_are_released_after_successful_pipeline_call() throws Throwable {
            // Given — 1 permit
            var bh = createBulkhead("bh", 1);
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build());

            // When
            terminal.execute(() -> "first");

            // Then — permit released, second call succeeds
            assertThat(bh.getAvailablePermits()).isEqualTo(1);
            Object second = terminal.execute(() -> "second");
            assertThat(second).isEqualTo("second");
        }

        @Test
        void permits_are_released_after_failed_pipeline_call() {
            // Given — 1 permit
            var bh = createBulkhead("bh", 1);
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build());

            // When — call fails
            try {
                terminal.execute(() -> {
                    throw new RuntimeException("boom");
                });
            } catch (Throwable ignored) {
            }

            // Then — permit still released
            assertThat(bh.getAvailablePermits()).isEqualTo(1);
        }

        @Test
        void bulkhead_rejects_with_inq_bulkhead_full_exception_when_all_permits_are_held()
                throws Exception {
            // Given — 1 permit, held by a blocking call
            var bh = createBulkhead("bh", 1);
            var terminal = SyncPipelineTerminal.of(
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
                } catch (Throwable ignored) {
                }
            });

            entered.await(2, TimeUnit.SECONDS);

            // When / Then — real InqBulkheadFullException with error code
            var exception = catchThrowableOfType(
                    () -> terminal.execute(() -> "rejected"),
                    InqBulkheadFullException.class);

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo("INQ-BH-001");

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

        @Test
        void circuit_breaker_permits_calls_in_closed_state() throws Throwable {
            // Given
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(cb).build());

            // When
            Object result = terminal.execute(() -> "hello");

            // Then
            assertThat(result).isEqualTo("hello");
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void circuit_breaker_opens_after_failure_threshold_through_pipeline() {
            // Given — threshold=3, min=3
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(cb).build());

            // When — 3 failures through the pipeline
            for (int i = 0; i < 3; i++) {
                try {
                    terminal.execute(() -> {
                        throw new IOException("fail");
                    });
                } catch (Throwable ignored) {
                }
            }

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void open_circuit_breaker_rejects_with_circuit_breaker_exception() {
            // Given — trip the circuit
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(cb).build());

            for (int i = 0; i < 3; i++) {
                try {
                    terminal.execute(() -> {
                        throw new IOException("fail");
                    });
                } catch (Throwable ignored) {
                }
            }
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

            // When / Then — real CircuitBreakerException
            var exception = catchThrowableOfType(
                    () -> terminal.execute(() -> "blocked"),
                    CircuitBreakerException.class);

            assertThat(exception).isNotNull();
            assertThat(exception.getCircuitBreakerName()).isEqualTo("cb");
            assertThat(exception.getState()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void circuit_breaker_recovers_through_half_open_to_closed() throws Throwable {
            // Given — trip the circuit
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(cb).build());

            for (int i = 0; i < 3; i++) {
                try {
                    terminal.execute(() -> {
                        throw new IOException("fail");
                    });
                } catch (Throwable ignored) {
                }
            }
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

            // When — advance past wait duration, then 2 successful probes
            advancePastWaitDuration();
            terminal.execute(() -> "probe-1");
            terminal.execute(() -> "probe-2");

            // Then — circuit closed again
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

            // And — normal calls succeed
            Object result = terminal.execute(() -> "back-to-normal");
            assertThat(result).isEqualTo("back-to-normal");
        }
    }

    // =========================================================================
    // Multi-element interaction
    // =========================================================================

    @Nested
    @DisplayName("Multi-element interaction")
    class MultiElementInteraction {

        @Test
        void successful_call_through_bulkhead_and_circuit_breaker() throws Throwable {
            // Given — BH(400) → CB(500) → core
            var bh = createBulkhead("bh", 5);
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).shield(cb).build());

            // When
            Object result = terminal.execute(() -> "pipeline-result");

            // Then
            assertThat(result).isEqualTo("pipeline-result");
            assertThat(bh.getAvailablePermits()).isEqualTo(5);
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void bulkhead_rejection_prevents_circuit_breaker_from_recording_a_failure()
                throws Exception {
            // Given — BH with 1 permit (outer), CB with threshold 3 (inner)
            var bh = createBulkhead("bh", 1);
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
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
                } catch (Throwable ignored) {
                }
            });

            entered.await(2, TimeUnit.SECONDS);

            // When — BH rejects before CB sees the call
            assertThatThrownBy(() -> terminal.execute(() -> "rejected"))
                    .isInstanceOf(InqBulkheadFullException.class);

            // Then — CB never saw the rejection, stays CLOSED
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

            release.countDown();
            executor.shutdown();
        }

        @Test
        void circuit_breaker_opens_while_bulkhead_still_has_permits() throws Throwable {
            // Given — BH with 5 permits (outer), CB with threshold 3 (inner)
            var bh = createBulkhead("bh", 5);
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).shield(cb).build());

            // When — 3 failures through the full pipeline
            for (int i = 0; i < 3; i++) {
                try {
                    terminal.execute(() -> {
                        throw new IOException("fail");
                    });
                } catch (Throwable ignored) {
                }
            }

            // Then — CB is OPEN, BH still has all 5 permits
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);
            assertThat(bh.getAvailablePermits()).isEqualTo(5);

            // And — next call: BH acquires permit, CB rejects inside, BH releases permit
            assertThatThrownBy(() -> terminal.execute(() -> "blocked"))
                    .isInstanceOf(CircuitBreakerException.class);
            assertThat(bh.getAvailablePermits()).isEqualTo(5);
        }

        @Test
        void full_lifecycle_through_pipeline_open_half_open_close() throws Throwable {
            // Given — BH(400) → CB(500) → core
            var bh = createBulkhead("bh", 5);
            var cb = createDefaultCircuitBreaker("cb");
            var terminal = SyncPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).shield(cb).build());

            // Phase 1: Normal operation
            assertThat(terminal.execute(() -> "normal")).isEqualTo("normal");
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

            // Phase 2: Trip the circuit
            for (int i = 0; i < 3; i++) {
                try {
                    terminal.execute(() -> {
                        throw new IOException("fail");
                    });
                } catch (Throwable ignored) {
                }
            }
            assertThat(cb.getState()).isEqualTo(CircuitState.OPEN);

            // Phase 3: Rejection phase — CB rejects, BH still works
            assertThatThrownBy(() -> terminal.execute(() -> "blocked"))
                    .isInstanceOf(CircuitBreakerException.class);
            assertThat(bh.getAvailablePermits()).isEqualTo(5);

            // Phase 4: Recovery — advance time, successful probes
            advancePastWaitDuration();
            terminal.execute(() -> "probe-1");
            terminal.execute(() -> "probe-2");
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);

            // Phase 5: Back to normal
            Object recovered = terminal.execute(() -> "recovered");
            assertThat(recovered).isEqualTo("recovered");
            assertThat(bh.getAvailablePermits()).isEqualTo(5);
            assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED);
        }
    }

    // =========================================================================
    // Pipeline diagnostics with real elements
    // =========================================================================

    @Nested
    @DisplayName("Pipeline diagnostics")
    class PipelineDiagnostics {

        @Test
        void pipeline_to_string_shows_real_element_names_and_types() {
            // Given
            var bh = createBulkhead("paymentBh", 10);
            var cb = createDefaultCircuitBreaker("paymentCb");

            InqPipeline pipeline = InqPipeline.builder()
                    .shield(cb).shield(bh).build();

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

            // BH (order=400) listed before CB (order=500)
            assertThat(summary.indexOf("BULKHEAD"))
                    .isLessThan(summary.indexOf("CIRCUIT_BREAKER"));
        }

        @Test
        void chain_fold_produces_correct_nesting_representation() {
            // Given
            var bh = createBulkhead("bh", 5);
            var cb = createDefaultCircuitBreaker("cb");

            InqPipeline pipeline = InqPipeline.builder()
                    .shield(bh).shield(cb).build();

            // When
            String nesting = pipeline.chain("core",
                    (acc, element) -> element.name() + "(" + acc + ")");

            // Then — outermost wraps all: bh(cb(core))
            assertThat(nesting).isEqualTo("bh(cb(core))");
        }
    }
}
