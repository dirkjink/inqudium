package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.patch.ComponentPatch;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqPublisherConfig;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqBulkhead")
class InqBulkheadTest {

    private InqEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = InqEventPublisher.create(
                "inventory",
                InqElementType.BULKHEAD,
                new InqEventExporterRegistry(),
                InqPublisherConfig.defaultConfig());
    }

    @AfterEach
    void tearDown() throws Exception {
        publisher.close();
    }

    private static BulkheadSnapshot snapshot(String name, int maxConcurrent, Duration maxWait) {
        return new BulkheadSnapshot(name, maxConcurrent, maxWait, Set.of(), null);
    }

    private static <A> InternalExecutor<A, A> identityExecutor() {
        return (chainId, callId, argument) -> argument;
    }

    private InqBulkhead newBulkhead(LiveContainer<BulkheadSnapshot> live) {
        return new InqBulkhead(live, publisher, InqClock.system());
    }

    @Nested
    @DisplayName("cold state")
    class ColdState {

        @Test
        void should_be_cold_before_first_execute() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inventory", 10, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);

            // When / Then
            assertThat(bulkhead.lifecycleState()).isEqualTo(LifecycleState.COLD);
        }

        @Test
        void should_report_available_permits_from_the_snapshot_when_cold() {
            // What is to be tested: that availablePermits() in the cold state reads from the
            // snapshot rather than from a not-yet-constructed strategy. Why: the cold phase
            // has no strategy; querying it would NPE without the phase-aware accessor.
            // Why important: monitoring code reads availablePermits even on freshly-built
            // bulkheads that have not yet served traffic.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inventory", 25, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);

            // When / Then
            assertThat(bulkhead.availablePermits()).isEqualTo(25);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }

        @Test
        void should_carry_the_name_from_the_snapshot() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("payments", 5, Duration.ZERO));
            InqBulkhead bulkhead = newBulkhead(live);

            // When / Then
            assertThat(bulkhead.name()).isEqualTo("payments");
        }
    }

    @Nested
    @DisplayName("cold-to-hot transition")
    class ColdToHotTransition {

        @Test
        void should_transition_to_hot_on_first_execute() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inventory", 10, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);

            // When
            String result = bulkhead.execute(1L, 1L, "input", identityExecutor());

            // Then
            assertThat(result).isEqualTo("input");
            assertThat(bulkhead.lifecycleState()).isEqualTo(LifecycleState.HOT);
        }

        @Test
        void should_route_through_the_strategy_after_transition() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inventory", 5, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);

            // When — first execute transitions; second reads from the now-hot strategy
            bulkhead.execute(1L, 1L, "x", identityExecutor());

            // Then
            assertThat(bulkhead.availablePermits()).isEqualTo(5);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("happy path execution")
    class HappyPath {

        @Test
        void should_acquire_permit_run_chain_and_release() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("x", 3, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);
            AtomicReference<Integer> midFlightPermits = new AtomicReference<>();
            InternalExecutor<String, String> next = (chainId, callId, arg) -> {
                midFlightPermits.set(bulkhead.availablePermits());
                return arg.toUpperCase();
            };

            // When
            String result = bulkhead.execute(1L, 1L, "hello", next);

            // Then
            assertThat(result).isEqualTo("HELLO");
            assertThat(midFlightPermits.get())
                    .as("one permit consumed during chain execution")
                    .isEqualTo(2);
            assertThat(bulkhead.availablePermits())
                    .as("permit released after chain returns")
                    .isEqualTo(3);
        }

        @Test
        void should_release_the_permit_even_when_the_chain_throws() {
            // What is to be tested: that the bulkhead releases its permit even if the
            // downstream chain throws. The release runs in a finally block; without it, an
            // exception would leak a permit per call and the bulkhead would eventually starve.
            // Why important: the strategy is shared across calls; a leaked permit is
            // permanent until the strategy is rebuilt.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("x", 2, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);
            InternalExecutor<String, String> next = (chainId, callId, arg) -> {
                throw new RuntimeException("downstream failure");
            };

            // When
            assertThatThrownBy(() -> bulkhead.execute(1L, 1L, "x", next))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("downstream failure");

            // Then
            assertThat(bulkhead.availablePermits())
                    .as("permit released despite the exception")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("rejection path")
    class RejectionPath {

        @Test
        void should_throw_bulkhead_full_when_no_permits_available() throws InterruptedException {
            // Given — single-permit fail-fast bulkhead
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("x", 1, Duration.ZERO));
            InqBulkhead bulkhead = newBulkhead(live);
            CountDownLatch holdPermit = new CountDownLatch(1);
            CountDownLatch firstAcquired = new CountDownLatch(1);
            InternalExecutor<String, String> blocking = (chainId, callId, arg) -> {
                firstAcquired.countDown();
                try {
                    holdPermit.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return arg;
            };

            // When — first thread holds the only permit
            Thread holder = Thread.startVirtualThread(
                    () -> bulkhead.execute(1L, 1L, "first", blocking));
            assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            // Then — second thread is rejected immediately (Duration.ZERO, fail-fast)
            try {
                assertThatThrownBy(() -> bulkhead.execute(1L, 2L, "second", identityExecutor()))
                        .isInstanceOf(InqBulkheadFullException.class)
                        .hasMessageContaining("Bulkhead 'x' rejected");
            } finally {
                holdPermit.countDown();
                holder.join();
            }

            // After the holder releases, permits return
            assertThat(bulkhead.availablePermits()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("in-place limit adjustment")
    class InPlaceLimitAdjustment {

        @Test
        void should_propagate_a_limit_increase_to_the_strategy() {
            // What is to be tested: that an update to the live snapshot's maxConcurrentCalls
            // propagates to the running strategy without requiring a strategy hot-swap. This is
            // the in-place adjustment Phase 1 supports.
            // Why successful: after the patch, availablePermits reflects the new limit.
            // Why important: operational tooling expects to be able to raise/lower concurrency
            // limits during traffic spikes; this is the central Phase-1 promise.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("x", 10, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);
            // Transition to hot first, otherwise the strategy doesn't exist yet.
            bulkhead.execute(1L, 1L, "warm", identityExecutor());
            assertThat(bulkhead.availablePermits()).isEqualTo(10);

            // When
            ComponentPatch<BulkheadSnapshot> raiseLimit = base ->
                    new BulkheadSnapshot(
                            base.name(),
                            25,
                            base.maxWaitDuration(),
                            base.tags(),
                            base.derivedFromPreset());
            live.apply(raiseLimit);

            // Then
            assertThat(bulkhead.availablePermits()).isEqualTo(25);
        }

        @Test
        void should_propagate_a_limit_decrease_to_the_strategy() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("x", 10, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);
            bulkhead.execute(1L, 1L, "warm", identityExecutor());

            // When
            ComponentPatch<BulkheadSnapshot> lowerLimit = base ->
                    new BulkheadSnapshot(
                            base.name(),
                            3,
                            base.maxWaitDuration(),
                            base.tags(),
                            base.derivedFromPreset());
            live.apply(lowerLimit);

            // Then — held permits are not revoked, but available pool shrinks
            assertThat(bulkhead.availablePermits()).isEqualTo(3);
        }

        @Test
        void should_not_subscribe_in_the_cold_state() {
            // What is to be tested: that no listener is registered until the cold-to-hot
            // transition fires. ADR-029 forbids side effects in hot-phase constructors;
            // subscribing eagerly would mean a snapshot update on a never-used bulkhead would
            // try to adjust a strategy that does not yet exist.
            // Why successful: a snapshot update before any execute leaves the bulkhead's
            // permit count tracking the snapshot via the cold-state accessor (which reads from
            // the snapshot directly, not from a strategy).
            // Why important: subscribers are lifecycle-tied; eager subscriptions in cold
            // components are a memory and correctness risk if components are configured but
            // never used.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("x", 10, Duration.ofMillis(100)));
            InqBulkhead bulkhead = newBulkhead(live);
            assertThat(bulkhead.lifecycleState()).isEqualTo(LifecycleState.COLD);

            // When — update before any execute
            ComponentPatch<BulkheadSnapshot> raiseLimit = base ->
                    new BulkheadSnapshot(
                            base.name(),
                            25,
                            base.maxWaitDuration(),
                            base.tags(),
                            base.derivedFromPreset());
            live.apply(raiseLimit);

            // Then — bulkhead is still cold; available permits read the new snapshot directly
            assertThat(bulkhead.lifecycleState()).isEqualTo(LifecycleState.COLD);
            assertThat(bulkhead.availablePermits()).isEqualTo(25);
        }
    }
}
