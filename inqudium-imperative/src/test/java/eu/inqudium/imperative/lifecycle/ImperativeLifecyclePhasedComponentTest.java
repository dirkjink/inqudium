package eu.inqudium.imperative.lifecycle;

import eu.inqudium.config.event.ComponentBecameHotEvent;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.lifecycle.PostCommitInitializable;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqPublisherConfig;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.imperative.lifecycle.spi.HotPhaseMarker;
import eu.inqudium.imperative.lifecycle.spi.ImperativePhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("ImperativeLifecyclePhasedComponent")
class ImperativeLifecyclePhasedComponentTest {

    /**
     * Test snapshot — a minimum implementation of ComponentSnapshot.
     */
    private record TestSnapshot(String name) implements ComponentSnapshot {
    }

    /**
     * Test component with optional contention coordination. The hot phase optionally implements
     * PostCommitInitializable, and the construction can wait on a barrier so the test forces every
     * thread to finish constructing its hot candidate before any thread reaches the CAS.
     */
    private static final class TestComponent
            extends ImperativeLifecyclePhasedComponent<TestSnapshot> {

        final AtomicInteger hotPhaseConstructions = new AtomicInteger();
        final AtomicInteger afterCommitInvocations = new AtomicInteger();
        final AtomicReference<LiveContainer<?>> afterCommitLiveArg = new AtomicReference<>();
        final boolean hotPhaseImplementsPostCommit;
        private final CyclicBarrier constructionBarrier;

        TestComponent(InqEventPublisher publisher,
                      boolean hotPhaseImplementsPostCommit,
                      CyclicBarrier constructionBarrier) {
            super(
                    "test",
                    InqElementType.BULKHEAD,
                    new LiveContainer<>(new TestSnapshot("test")),
                    publisher,
                    InqClock.system());
            this.hotPhaseImplementsPostCommit = hotPhaseImplementsPostCommit;
            this.constructionBarrier = constructionBarrier;
        }

        @Override
        protected ImperativePhase createHotPhase() {
            hotPhaseConstructions.incrementAndGet();
            ImperativePhase phase = hotPhaseImplementsPostCommit
                    ? new TestHotPhaseWithPostCommit()
                    : new TestHotPhase();
            // Force every concurrent constructor to converge here before any thread is allowed to
            // reach the enclosing CAS. The barrier guarantees that with N threads, exactly N hot
            // candidates have been constructed before any CAS attempt — so the test can assert
            // that afterCommit and the event still fire only once on the winner.
            if (constructionBarrier != null) {
                try {
                    constructionBarrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } catch (BrokenBarrierException | TimeoutException e) {
                    throw new RuntimeException(e);
                }
            }
            return phase;
        }

        class TestHotPhase implements ImperativePhase, HotPhaseMarker {
            @Override
            public <A, R> R execute(
                    long chainId, long callId, A argument, InternalExecutor<A, R> next) {
                return next.execute(chainId, callId, argument);
            }
        }

        final class TestHotPhaseWithPostCommit
                extends TestHotPhase implements PostCommitInitializable {
            @Override
            public void afterCommit(LiveContainer<?> live) {
                afterCommitInvocations.incrementAndGet();
                afterCommitLiveArg.set(live);
            }
        }
    }

    private static <A> InternalExecutor<A, A> identityExecutor() {
        return (chainId, callId, argument) -> argument;
    }

    private static InqEventPublisher isolatedPublisher() {
        return InqEventPublisher.create(
                "test",
                InqElementType.BULKHEAD,
                new InqEventExporterRegistry(),
                InqPublisherConfig.defaultConfig());
    }

    private InqEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = isolatedPublisher();
    }

    @AfterEach
    void tearDown() throws Exception {
        publisher.close();
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void should_reject_a_null_name() {
            // Given / When / Then
            assertThatNullPointerException().isThrownBy(() -> new ImperativeLifecyclePhasedComponent<TestSnapshot>(
                    null,
                    InqElementType.BULKHEAD,
                    new LiveContainer<>(new TestSnapshot("x")),
                    publisher,
                    InqClock.system()) {
                @Override
                protected ImperativePhase createHotPhase() {
                    return null;
                }
            }).withMessageContaining("name");
        }

        @Test
        void should_reject_a_null_element_type() {
            // Given / When / Then
            assertThatNullPointerException().isThrownBy(() -> new ImperativeLifecyclePhasedComponent<TestSnapshot>(
                    "n",
                    null,
                    new LiveContainer<>(new TestSnapshot("x")),
                    publisher,
                    InqClock.system()) {
                @Override
                protected ImperativePhase createHotPhase() {
                    return null;
                }
            }).withMessageContaining("elementType");
        }

        @Test
        void should_reject_a_null_live_container() {
            // Given / When / Then
            assertThatNullPointerException().isThrownBy(() -> new ImperativeLifecyclePhasedComponent<TestSnapshot>(
                    "n",
                    InqElementType.BULKHEAD,
                    null,
                    publisher,
                    InqClock.system()) {
                @Override
                protected ImperativePhase createHotPhase() {
                    return null;
                }
            }).withMessageContaining("live");
        }

        @Test
        void should_reject_a_null_event_publisher() {
            // Given / When / Then
            assertThatNullPointerException().isThrownBy(() -> new ImperativeLifecyclePhasedComponent<TestSnapshot>(
                    "n",
                    InqElementType.BULKHEAD,
                    new LiveContainer<>(new TestSnapshot("x")),
                    null,
                    InqClock.system()) {
                @Override
                protected ImperativePhase createHotPhase() {
                    return null;
                }
            }).withMessageContaining("eventPublisher");
        }

        @Test
        void should_reject_a_null_clock() {
            // Given / When / Then
            assertThatNullPointerException().isThrownBy(() -> new ImperativeLifecyclePhasedComponent<TestSnapshot>(
                    "n",
                    InqElementType.BULKHEAD,
                    new LiveContainer<>(new TestSnapshot("x")),
                    publisher,
                    null) {
                @Override
                protected ImperativePhase createHotPhase() {
                    return null;
                }
            }).withMessageContaining("clock");
        }
    }

    @Nested
    @DisplayName("lifecycleState")
    class LifecycleStateTest {

        @Test
        void should_be_cold_before_the_first_execute_call() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);

            // When / Then
            assertThat(component.lifecycleState()).isEqualTo(LifecycleState.COLD);
        }

        @Test
        void should_be_hot_after_the_first_execute_call() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);

            // When
            String result = component.execute(1L, 2L, "x", identityExecutor());

            // Then
            assertThat(result).isEqualTo("x");
            assertThat(component.lifecycleState()).isEqualTo(LifecycleState.HOT);
        }

        @Test
        void should_remain_hot_on_subsequent_execute_calls() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);

            // When
            component.execute(1L, 1L, "a", identityExecutor());
            component.execute(1L, 2L, "b", identityExecutor());
            component.execute(1L, 3L, "c", identityExecutor());

            // Then
            assertThat(component.lifecycleState()).isEqualTo(LifecycleState.HOT);
        }
    }

    @Nested
    @DisplayName("cold-to-hot transition")
    class ColdToHotTransition {

        @Test
        void should_construct_the_hot_phase_at_most_once_under_uncontended_execute() {
            // What is to be tested: that under no contention, the hot phase is constructed
            // exactly once. Why successful: createHotPhase is the costly allocation; if it
            // ran on every execute the hot path would slow to a crawl.
            // Why important: this is the central performance promise of the lifecycle
            // pattern — the hot path is free of lifecycle overhead after transition.

            // Given
            TestComponent component = new TestComponent(publisher, false, null);

            // When
            component.execute(1L, 1L, "a", identityExecutor());
            component.execute(1L, 2L, "b", identityExecutor());
            component.execute(1L, 3L, "c", identityExecutor());

            // Then
            assertThat(component.hotPhaseConstructions.get()).isEqualTo(1);
        }

        @Test
        void should_publish_component_became_hot_event_exactly_once_uncontended() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);
            AtomicInteger eventCount = new AtomicInteger();
            publisher.onEvent(ComponentBecameHotEvent.class, e -> eventCount.incrementAndGet());

            // When
            component.execute(1L, 1L, "a", identityExecutor());
            component.execute(1L, 2L, "b", identityExecutor());
            component.execute(1L, 3L, "c", identityExecutor());

            // Then
            assertThat(eventCount.get()).isEqualTo(1);
        }

        @Test
        void should_publish_component_became_hot_event_exactly_once_under_cas_contention()
                throws InterruptedException {
            // What is to be tested: that under multi-thread contention on the cold-to-hot CAS,
            // ComponentBecameHotEvent fires exactly once for the winning thread, even though
            // every losing thread also went through the cold phase code path. The barrier
            // inside createHotPhase forces all N threads to construct a hot candidate before
            // any thread reaches the CAS, guaranteeing genuine contention.
            // Why successful: assertion eventCount == 1 with N candidate constructions proves
            // exactly one CAS won and exactly one event fired.
            // Why important: ADR-028 mandates "fired exactly once per component lifetime" —
            // a regression here corrupts every operational dashboard built on top of the event.

            // Given
            int threads = 8;
            CyclicBarrier constructionBarrier = new CyclicBarrier(threads);
            TestComponent component = new TestComponent(publisher, false, constructionBarrier);
            AtomicInteger eventCount = new AtomicInteger();
            publisher.onEvent(ComponentBecameHotEvent.class, e -> eventCount.incrementAndGet());

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            // When
            for (int i = 0; i < threads; i++) {
                int callId = i;
                Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        component.execute(1L, callId, "a", identityExecutor());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();

            // Then
            assertThat(component.hotPhaseConstructions.get())
                    .as("every racing thread constructs its own hot-phase candidate")
                    .isEqualTo(threads);
            assertThat(eventCount.get())
                    .as("exactly one CAS wins, exactly one event fires")
                    .isEqualTo(1);
            assertThat(component.lifecycleState()).isEqualTo(LifecycleState.HOT);
        }
    }

    @Nested
    @DisplayName("post-commit hook")
    class PostCommitHook {

        @Test
        void should_invoke_after_commit_on_uncontended_transition() {
            // Given
            TestComponent component = new TestComponent(publisher, true, null);

            // When
            component.execute(1L, 1L, "a", identityExecutor());

            // Then
            assertThat(component.afterCommitInvocations.get()).isEqualTo(1);
            assertThat(component.afterCommitLiveArg.get()).isNotNull();
        }

        @Test
        void should_not_invoke_after_commit_again_on_subsequent_executes() {
            // Given
            TestComponent component = new TestComponent(publisher, true, null);

            // When
            component.execute(1L, 1L, "a", identityExecutor());
            component.execute(1L, 2L, "b", identityExecutor());
            component.execute(1L, 3L, "c", identityExecutor());

            // Then
            assertThat(component.afterCommitInvocations.get()).isEqualTo(1);
        }

        @Test
        void should_invoke_after_commit_only_on_the_winning_candidate_under_contention()
                throws InterruptedException {
            // What is to be tested: that PostCommitInitializable.afterCommit fires only once,
            // on the candidate whose CAS won. The N-thread barrier ensures every thread
            // builds its own hot candidate; we then verify N constructions but exactly one
            // afterCommit invocation.
            // Why successful: the asymmetry (constructions == N but afterCommits == 1) proves
            // that N-1 candidates were discarded without their post-commit hook running. If a
            // discarded candidate's afterCommit ran, we would see > 1 afterCommit invocations.
            // Why important: ADR-029 explicitly requires post-commit work "fires after the
            // successful CAS, not on discarded candidates". A regression here would re-register
            // subscribers and double-schedule tasks for every losing thread.

            // Given
            int threads = 8;
            CyclicBarrier constructionBarrier = new CyclicBarrier(threads);
            TestComponent component = new TestComponent(publisher, true, constructionBarrier);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            // When
            for (int i = 0; i < threads; i++) {
                int callId = i;
                Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        component.execute(1L, callId, "a", identityExecutor());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();

            // Then
            assertThat(component.hotPhaseConstructions.get())
                    .as("every racing thread constructs its own candidate")
                    .isEqualTo(threads);
            assertThat(component.afterCommitInvocations.get())
                    .as("only the winning candidate's afterCommit fires")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("change request listeners")
    class ChangeRequestListeners {

        @Test
        void should_reject_a_null_listener() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> component.onChangeRequest(null))
                    .withMessageContaining("listener");
        }

        @Test
        void should_register_a_listener_in_registration_order() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);
            ChangeRequestListener<TestSnapshot> first = req -> null;
            ChangeRequestListener<TestSnapshot> second = req -> null;
            ChangeRequestListener<TestSnapshot> third = req -> null;

            // When
            component.onChangeRequest(first);
            component.onChangeRequest(second);
            component.onChangeRequest(third);

            // Then
            assertThat(component.listeners()).containsExactly(first, second, third);
        }

        @Test
        void should_remove_a_listener_via_the_returned_auto_closeable() throws Exception {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);
            ChangeRequestListener<TestSnapshot> listener = req -> null;
            AutoCloseable handle = component.onChangeRequest(listener);
            assertThat(component.listeners()).containsExactly(listener);

            // When
            handle.close();

            // Then
            assertThat(component.listeners()).isEmpty();
        }

        @Test
        void should_keep_other_listeners_active_when_one_is_unregistered() throws Exception {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);
            ChangeRequestListener<TestSnapshot> first = req -> null;
            ChangeRequestListener<TestSnapshot> second = req -> null;
            AutoCloseable firstHandle = component.onChangeRequest(first);
            component.onChangeRequest(second);

            // When
            firstHandle.close();

            // Then
            assertThat(component.listeners()).containsExactly(second);
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void should_pass_the_argument_through_to_next_executor() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);
            AtomicReference<String> seenArgument = new AtomicReference<>();
            InternalExecutor<String, String> next = (chainId, callId, arg) -> {
                seenArgument.set(arg);
                return arg.toUpperCase();
            };

            // When
            String result = component.execute(7L, 11L, "hello", next);

            // Then
            assertThat(seenArgument.get()).isEqualTo("hello");
            assertThat(result).isEqualTo("HELLO");
        }

        @Test
        void should_pass_chain_and_call_ids_to_the_next_executor() {
            // Given
            TestComponent component = new TestComponent(publisher, false, null);
            AtomicReference<long[]> seenIds = new AtomicReference<>();
            InternalExecutor<String, String> next = (chainId, callId, arg) -> {
                seenIds.set(new long[]{chainId, callId});
                return arg;
            };

            // When
            component.execute(42L, 99L, "x", next);

            // Then
            assertThat(seenIds.get()).containsExactly(42L, 99L);
        }
    }

    @Nested
    @DisplayName("event publication identity")
    class EventPublicationIdentity {

        @Test
        void should_carry_the_components_name_element_type_and_triggering_call_ids_in_the_event() {
            // Given
            AtomicReference<Instant> fixedTime = new AtomicReference<>(
                    Instant.parse("2026-04-26T12:00:00Z"));
            InqClock fixedClock = fixedTime::get;
            ClockOverriddenComponent component = new ClockOverriddenComponent(publisher, fixedClock);
            AtomicReference<ComponentBecameHotEvent> captured = new AtomicReference<>();
            publisher.onEvent(ComponentBecameHotEvent.class, captured::set);

            // When
            component.execute(7L, 11L, "x", identityExecutor());

            // Then
            ComponentBecameHotEvent event = captured.get();
            assertThat(event).isNotNull();
            assertThat(event.getElementName()).isEqualTo("test");
            assertThat(event.getElementType()).isEqualTo(InqElementType.BULKHEAD);
            assertThat(event.getChainId()).isEqualTo(7L);
            assertThat(event.getCallId()).isEqualTo(11L);
            assertThat(event.getTimestamp()).isEqualTo(Instant.parse("2026-04-26T12:00:00Z"));
        }
    }

    /**
     * Variant of TestComponent that takes an externally injected clock. Used by the event-identity
     * test to assert that the event's timestamp comes from the configured clock rather than a
     * direct {@code Instant.now()} call.
     */
    private static final class ClockOverriddenComponent
            extends ImperativeLifecyclePhasedComponent<TestSnapshot> {

        ClockOverriddenComponent(InqEventPublisher publisher, InqClock clock) {
            super(
                    "test",
                    InqElementType.BULKHEAD,
                    new LiveContainer<>(new TestSnapshot("test")),
                    publisher,
                    clock);
        }

        @Override
        protected ImperativePhase createHotPhase() {
            class Hot implements ImperativePhase, HotPhaseMarker {
                @Override
                public <A, R> R execute(
                        long chainId, long callId, A argument, InternalExecutor<A, R> next) {
                    return next.execute(chainId, callId, argument);
                }
            }
            return new Hot();
        }
    }
}
