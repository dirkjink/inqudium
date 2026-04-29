package eu.inqudium.config.live;

import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.patch.ComponentPatch;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("LiveContainer")
class LiveContainerTest {

    /**
     * Build a BulkheadSnapshot used as a stand-in for a generic ComponentSnapshot. We exercise
     * the container's CAS loop and dispatch through {@code maxConcurrentCalls}; nothing in
     * LiveContainer cares about the field's bulkhead semantics.
     */
    private static BulkheadSnapshot snapshot(int maxConcurrent) {
        return new BulkheadSnapshot(
                "c", maxConcurrent, Duration.ZERO, Set.of(), null,
                BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
    }

    /**
     * Patch that replaces {@code maxConcurrentCalls} with the given value. Implemented as an
     * anonymous class because {@link ComponentPatch} is no longer a single-abstract-method
     * interface — it also exposes {@code touchedFields()} and {@code proposedValues()} for the
     * dispatcher. The container's CAS loop only consults {@link ComponentPatch#applyTo}, so the
     * other accessors are stubbed.
     */
    private static ComponentPatch<BulkheadSnapshot> setValue(int newValue) {
        return new ComponentPatch<>() {
            @Override
            public BulkheadSnapshot applyTo(BulkheadSnapshot base) {
                return new BulkheadSnapshot(
                        base.name(), newValue, base.maxWaitDuration(),
                        base.tags(), base.derivedFromPreset(), base.events(), base.strategy());
            }

            @Override
            public Set<? extends ComponentField> touchedFields() {
                return Set.of();
            }

            @Override
            public Map<ComponentField, Object> proposedValues() {
                return Map.of();
            }
        };
    }

    /**
     * Patch that increments {@code maxConcurrentCalls} by the delta. Used to expose CAS retry
     * under contention.
     */
    private static ComponentPatch<BulkheadSnapshot> incrementBy(int delta) {
        return new ComponentPatch<>() {
            @Override
            public BulkheadSnapshot applyTo(BulkheadSnapshot base) {
                return new BulkheadSnapshot(
                        base.name(), base.maxConcurrentCalls() + delta, base.maxWaitDuration(),
                        base.tags(), base.derivedFromPreset(), base.events(), base.strategy());
            }

            @Override
            public Set<? extends ComponentField> touchedFields() {
                return Set.of();
            }

            @Override
            public Map<ComponentField, Object> proposedValues() {
                return Map.of();
            }
        };
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void should_reject_a_null_initial_snapshot() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new LiveContainer<BulkheadSnapshot>(null))
                    .withMessageContaining("initial snapshot");
        }

        @Test
        void should_expose_the_initial_snapshot_via_snapshot() {
            // Given
            BulkheadSnapshot initial = snapshot(7);

            // When
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(initial);

            // Then
            assertThat(container.snapshot()).isSameAs(initial);
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        void should_replace_the_snapshot_atomically_in_the_uncontended_case() {
            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));

            // When
            BulkheadSnapshot result = container.apply(setValue(42));

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(42);
            assertThat(container.snapshot().maxConcurrentCalls()).isEqualTo(42);
        }

        @Test
        void should_reject_a_null_patch() {
            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> container.apply(null))
                    .withMessageContaining("patch");
        }

        @Test
        void should_notify_every_subscriber_exactly_once_per_successful_apply() {
            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));
            List<BulkheadSnapshot> received = new ArrayList<>();
            container.subscribe(received::add);

            // When
            container.apply(setValue(1));
            container.apply(setValue(2));
            container.apply(setValue(3));

            // Then
            assertThat(received).extracting(BulkheadSnapshot::maxConcurrentCalls).containsExactly(1, 2, 3);
        }

        @Test
        void should_dispatch_to_subscribers_in_registration_order() {
            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));
            List<String> order = new ArrayList<>();
            container.subscribe(s -> order.add("first"));
            container.subscribe(s -> order.add("second"));
            container.subscribe(s -> order.add("third"));

            // When
            container.apply(setValue(1));

            // Then
            assertThat(order).containsExactly("first", "second", "third");
        }

        @Test
        void should_converge_under_concurrent_apply_via_cas_retry() throws InterruptedException {
            // What is to be tested: that LiveContainer.apply correctly retries the patch against
            // the freshly observed snapshot when its CAS loses to another thread, so that no
            // increment is lost.
            // How will the test case be deemed successful and why: after N threads each apply
            // an "increment by 1" patch M times, the final value must equal initial + N*M. If the
            // CAS retry were missing, lost updates would produce a value strictly less than that.
            // Why is it important to test this test case: lock-free atomic update is the load-
            // bearing property the LiveContainer promises; a regression here corrupts every live
            // configuration update under contention.

            // Given
            int threads = 8;
            int incrementsPerThread = 500;
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            // When
            for (int i = 0; i < threads; i++) {
                Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < incrementsPerThread; j++) {
                            container.apply(incrementBy(1));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            // Then
            int initialValue = 1;
            assertThat(container.snapshot().maxConcurrentCalls())
                    .isEqualTo(initialValue + threads * incrementsPerThread);
        }
    }

    @Nested
    @DisplayName("subscribe")
    class Subscribe {

        @Test
        void should_reject_a_null_listener() {
            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> container.subscribe(null))
                    .withMessageContaining("listener");
        }

        @Test
        void should_stop_notifying_a_listener_after_its_handle_is_closed() throws Exception {
            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));
            AtomicInteger observed = new AtomicInteger();
            AutoCloseable handle = container.subscribe(s -> observed.incrementAndGet());

            // When
            container.apply(setValue(1));
            handle.close();
            container.apply(setValue(2));
            container.apply(setValue(3));

            // Then
            assertThat(observed.get()).isEqualTo(1);
        }

        @Test
        void should_keep_other_subscribers_active_when_one_unsubscribes() throws Exception {
            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            AutoCloseable firstHandle = container.subscribe(s -> first.incrementAndGet());
            container.subscribe(s -> second.incrementAndGet());

            // When
            container.apply(setValue(1));
            firstHandle.close();
            container.apply(setValue(2));

            // Then
            assertThat(first.get()).isEqualTo(1);
            assertThat(second.get()).isEqualTo(2);
        }

        @Test
        void should_isolate_subscriber_failures_within_apply() {
            // What is to be tested: documenting the current behaviour when a subscriber throws.
            // The container does not catch listener exceptions; the failure propagates out of
            // apply to the caller, and subsequent listeners are not invoked for that update.
            // Why this matters: a misbehaving listener should not silently corrupt the runtime,
            // but the framework also does not currently impose isolation. This test pins the
            // contract so any future change is deliberate.

            // Given
            LiveContainer<BulkheadSnapshot> container = new LiveContainer<>(snapshot(1));
            List<String> hits = new CopyOnWriteArrayList<>();
            container.subscribe(s -> hits.add("first"));
            container.subscribe(s -> {
                throw new RuntimeException("boom");
            });
            container.subscribe(s -> hits.add("third"));

            // When / Then
            assertThat(hits).isEmpty();
            try {
                container.apply(setValue(1));
            } catch (RuntimeException expected) {
                // The first listener was called; the second threw and aborted dispatch; the
                // third never saw this update.
            }
            assertThat(hits).containsExactly("first");
            // The snapshot still committed before dispatch began.
            assertThat(container.snapshot().maxConcurrentCalls()).isEqualTo(1);
        }
    }
}
