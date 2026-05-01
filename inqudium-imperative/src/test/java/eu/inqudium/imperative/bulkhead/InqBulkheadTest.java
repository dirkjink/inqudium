package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqPublisherConfig;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        return new BulkheadSnapshot(
                name, maxConcurrent, maxWait, Set.of(), null, BulkheadEventConfig.disabled(),
                new SemaphoreStrategyConfig());
    }

    private static <A> InternalExecutor<A, A> identityExecutor() {
        return (chainId, callId, argument) -> argument;
    }

    private InqBulkhead<String, String> newBulkhead(LiveContainer<BulkheadSnapshot> live) {
        GeneralSnapshot general = new GeneralSnapshot(
                InqClock.system(),
                InqNanoTimeSource.system(),
                publisher,
                InqEventPublisher::create,
                LoggerFactory.NO_OP_LOGGER_FACTORY,
                true);
        return new InqBulkhead<>(live, general);
    }

    @Nested
    @DisplayName("per-component publisher")
    class PerComponentPublisher {

        @Test
        void should_provision_a_publisher_via_the_general_snapshot_factory() {
            // What is to be tested: that InqBulkhead constructs its per-component publisher
            // through GeneralSnapshot.componentPublisherFactory at instantiation time, with
            // the bulkhead's name and InqElementType.BULKHEAD as identity.
            // Why successful: the publisher returned by bulkhead.eventPublisher() is exactly
            // the one our custom factory minted for ("inventory", BULKHEAD).
            // Why important: this is the central ADR-030 contract — per-component publishers
            // are sourced from the GeneralSnapshot factory, not shared with the runtime
            // publisher.

            // Given — a custom factory that returns a sentinel publisher and records the args
            java.util.concurrent.atomic.AtomicReference<String> capturedName =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<InqElementType> capturedType =
                    new java.util.concurrent.atomic.AtomicReference<>();
            InqEventPublisher componentPub = InqEventPublisher.create(
                    "inventory",
                    InqElementType.BULKHEAD,
                    new InqEventExporterRegistry(),
                    InqPublisherConfig.defaultConfig());
            try {
                eu.inqudium.config.snapshot.ComponentEventPublisherFactory factory =
                        (name, type) -> {
                            capturedName.set(name);
                            capturedType.set(type);
                            return componentPub;
                        };
                GeneralSnapshot general = new GeneralSnapshot(
                        InqClock.system(), InqNanoTimeSource.system(),
                        publisher, factory, LoggerFactory.NO_OP_LOGGER_FACTORY,
                        true);
                LiveContainer<BulkheadSnapshot> live =
                        new LiveContainer<>(snapshot("inventory", 5, Duration.ZERO));

                // When — the publisher-factory contract does not depend on the call's <A, R>,
                // so the variable is wildcard-typed and diamond inference settles the
                // constructor's binding from the target.
                InqBulkhead<?, ?> bulkhead = new InqBulkhead<>(live, general);

                // Then
                assertThat(capturedName.get()).isEqualTo("inventory");
                assertThat(capturedType.get()).isEqualTo(InqElementType.BULKHEAD);
                assertThat(bulkhead.eventPublisher()).isSameAs(componentPub);
            } finally {
                try {
                    componentPub.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }

        @Test
        void runtime_publisher_should_remain_separate_from_component_publisher() {
            // The two publishers serve distinct purposes (ADR-030): the runtime publisher
            // carries lifecycle topology events (ComponentBecameHotEvent), the component
            // publisher carries per-call traces. They must not be the same instance.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inventory", 5, Duration.ZERO));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

            // When / Then
            assertThat(bulkhead.eventPublisher())
                    .as("component publisher is the one minted by the factory, not the runtime "
                            + "publisher passed to the lifecycle base")
                    .isNotSameAs(publisher);
        }
    }

    @Nested
    @DisplayName("cold state")
    class ColdState {

        @Test
        void should_be_cold_before_first_execute() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inventory", 10, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

            // When / Then
            assertThat(bulkhead.availablePermits()).isEqualTo(25);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }

        @Test
        void should_carry_the_name_from_the_snapshot() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("payments", 5, Duration.ZERO));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
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
    @DisplayName("async execution")
    class AsyncExecution {

        @Test
        void executeAsync_acquires_synchronously_and_releases_on_stage_completion() throws Exception {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 3, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

            AtomicReference<Integer> midFlightPermits = new AtomicReference<>();
            CompletableFuture<String> downstream = new CompletableFuture<>();
            InternalAsyncExecutor<String, String> next = (chainId, callId, arg) -> {
                midFlightPermits.set(bulkhead.availablePermits());
                return downstream;
            };

            // When — call returns a pending stage and the permit was acquired synchronously
            CompletionStage<String> result = bulkhead.executeAsync(1L, 1L, "hello", next);

            // Then — sync acquire fired on the calling thread; permit is held while stage pending
            assertThat(midFlightPermits.get())
                    .as("one permit consumed during async start phase")
                    .isEqualTo(2);
            assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
            assertThat(result.toCompletableFuture().isDone()).isFalse();

            // When — downstream completes
            downstream.complete("done");

            // Then — release callback ran, permit returned, stage carries the value
            String value = result.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(value).isEqualTo("done");
            assertThat(bulkhead.availablePermits()).isEqualTo(3);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }

        @Test
        void executeAsync_throws_synchronously_when_bulkhead_is_full() {
            // What is to be tested: the async path must fail fast when no permit is available —
            // a saturated bulkhead reports back-pressure to the caller as a synchronous throw,
            // not as a failed CompletionStage. This is the contract that lets callers measure
            // load and shed traffic before scheduling new async work.
            // Why successful: the second executeAsync throws InqBulkheadFullException directly
            // and concurrentCalls stays at the held count, not incremented for the rejected
            // attempt.
            // Why important: a failed-stage shape would force every caller to thread error
            // handling through both the synchronous throw path AND the async failure path —
            // unfaithful to ADR-020's back-pressure contract.

            // Given — single-permit fail-fast bulkhead, an outstanding async call holds it
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 1, Duration.ZERO));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
            CompletableFuture<String> hold = new CompletableFuture<>();
            CompletionStage<String> first = bulkhead.executeAsync(
                    1L, 1L, "first", (chainId, callId, arg) -> hold);
            assertThat(bulkhead.concurrentCalls()).isEqualTo(1);

            InternalAsyncExecutor<String, String> never = (chainId, callId, arg) -> {
                throw new AssertionError("downstream must not run on a full bulkhead");
            };

            // When / Then — second call throws synchronously, NOT via failed stage
            try {
                assertThatThrownBy(() -> bulkhead.executeAsync(1L, 2L, "second", never))
                        .isInstanceOf(InqBulkheadFullException.class)
                        .hasMessageContaining("Bulkhead 'inv'");
                assertThat(bulkhead.concurrentCalls())
                        .as("rejected attempt does not consume a permit")
                        .isEqualTo(1);
            } finally {
                hold.complete("ok");
                first.toCompletableFuture().join();
            }
        }

        @Test
        void executeAsync_releases_immediately_when_downstream_throws_during_stage_construction() {
            // What is to be tested: a downstream layer that throws synchronously during stage
            // construction (before returning a CompletionStage) must not leak the permit. The
            // bulkhead releases on the catch-and-rethrow path before any whenComplete callback
            // could have been attached.
            // Why successful: after the throw, concurrentCalls() returns to zero and the
            // available-permit pool is restored.
            // Why important: a layer's contract permits a synchronous failure during stage
            // construction; without the catch around next.executeAsync, every such failure
            // would leak one permit per occurrence.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 2, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

            InternalAsyncExecutor<String, String> throwingNext = (chainId, callId, arg) -> {
                throw new IllegalStateException("downstream construction failure");
            };

            // When / Then — throw propagates synchronously
            assertThatThrownBy(() -> bulkhead.executeAsync(1L, 1L, "x", throwingNext))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("downstream construction failure");

            // Permit released, no callback could have fired (no stage was returned)
            assertThat(bulkhead.concurrentCalls())
                    .as("permit released after sync downstream failure")
                    .isZero();
            assertThat(bulkhead.availablePermits()).isEqualTo(2);
        }

        @Test
        void executeAsync_releases_on_failed_stage() throws Exception {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 2, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

            RuntimeException downstreamFailure = new RuntimeException("downstream boom");
            InternalAsyncExecutor<String, String> failingNext = (chainId, callId, arg) ->
                    CompletableFuture.failedFuture(downstreamFailure);

            // When
            CompletionStage<String> result = bulkhead.executeAsync(1L, 1L, "x", failingNext);

            // Then — stage carries the failure, permit released
            assertThatThrownBy(() -> result.toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("downstream boom");
            assertThat(bulkhead.availablePermits()).isEqualTo(2);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }

        @Test
        void executeAsync_releases_on_cancelled_stage() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 2, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
            CompletableFuture<String> downstream = new CompletableFuture<>();
            InternalAsyncExecutor<String, String> next = (chainId, callId, arg) -> downstream;

            // When — call returns a stage; permit held
            CompletionStage<String> result = bulkhead.executeAsync(1L, 1L, "x", next);
            assertThat(bulkhead.concurrentCalls()).isEqualTo(1);

            // When — cancel the underlying; whenComplete sees CancellationException, releases
            boolean cancelled = downstream.cancel(true);

            // Then
            assertThat(cancelled).isTrue();
            assertThat(bulkhead.availablePermits()).isEqualTo(2);
            assertThat(bulkhead.concurrentCalls()).isZero();
            assertThat(result.toCompletableFuture().isCompletedExceptionally()).isTrue();
        }

        @Test
        void executeAsync_fast_path_returns_original_stage_when_already_completed() {
            // What is to be tested: when the downstream stage is an already-completed
            // CompletableFuture (sync-wrapped-as-async, caching, validation failure), the
            // bulkhead must return that exact stage without allocating a whenComplete wrapper.
            // The release runs inline before the method returns.
            // Why successful: the returned reference is identity-equal to the downstream stage,
            // and concurrentCalls() returns to zero before the call returns.
            // Why important: the fast path eliminates one stage allocation and one callback
            // entry on the cache-hit and validation-failure code paths — a recurring perf win
            // for callers that pass synchronously-resolved stages.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 2, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
            CompletableFuture<String> alreadyDone = CompletableFuture.completedFuture("x");
            AtomicBoolean nextWasInvoked = new AtomicBoolean(false);
            InternalAsyncExecutor<String, String> next = (chainId, callId, arg) -> {
                nextWasInvoked.set(true);
                return alreadyDone;
            };

            // When
            CompletionStage<String> returned = bulkhead.executeAsync(1L, 1L, "x", next);

            // Then — same identity (no whenComplete copy)
            assertThat(returned).isSameAs(alreadyDone);
            assertThat(nextWasInvoked.get()).isTrue();
            // Release ran inline — no async hand-off needed
            assertThat(bulkhead.concurrentCalls()).isZero();
            assertThat(bulkhead.availablePermits()).isEqualTo(2);
        }

        @Test
        void concurrent_calls_count_matches_in_flight_async_acquires_minus_completions()
                throws Exception {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 5, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

            CompletableFuture<String> a = new CompletableFuture<>();
            CompletableFuture<String> b = new CompletableFuture<>();
            CompletableFuture<String> c = new CompletableFuture<>();

            CompletionStage<String> sa =
                    bulkhead.executeAsync(1L, 1L, "a", (cid, cd, arg) -> a);
            CompletionStage<String> sb =
                    bulkhead.executeAsync(1L, 2L, "b", (cid, cd, arg) -> b);
            CompletionStage<String> sc =
                    bulkhead.executeAsync(1L, 3L, "c", (cid, cd, arg) -> c);

            // Then — three permits in flight
            assertThat(bulkhead.concurrentCalls()).isEqualTo(3);
            assertThat(bulkhead.availablePermits()).isEqualTo(2);

            // When — complete one
            a.complete("done");
            sa.toCompletableFuture().get(5, TimeUnit.SECONDS);

            // Then — count drops by one
            assertThat(bulkhead.concurrentCalls()).isEqualTo(2);
            assertThat(bulkhead.availablePermits()).isEqualTo(3);

            // When — complete the rest
            b.complete("done");
            c.complete("done");
            sb.toCompletableFuture().get(5, TimeUnit.SECONDS);
            sc.toCompletableFuture().get(5, TimeUnit.SECONDS);

            // Then — count zero
            assertThat(bulkhead.concurrentCalls()).isZero();
            assertThat(bulkhead.availablePermits()).isEqualTo(5);
        }

        @Test
        void same_bulkhead_serves_both_sync_and_async_paths_through_one_strategy()
                throws Exception {
            // What is to be tested: a single InqBulkhead instance routes both sync (execute)
            // and async (executeAsync) calls through the SAME strategy, so a sync call holding
            // a permit makes that count visible to a concurrent async acquire on the same
            // bulkhead.
            // Why successful: with maxConcurrentCalls=2, a sync call mid-flight plus an async
            // call mid-flight produces concurrentCalls() == 2; both releases drain to zero.
            // Why important: this pins decision (1) of the async-bulkhead refactor — one
            // class, one strategy, two dispatch paths sharing the same accounting. A
            // regression that built a separate strategy per path would let either path acquire
            // beyond the shared limit.

            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("inv", 2, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);

            CountDownLatch syncMidFlight = new CountDownLatch(1);
            CountDownLatch syncRelease = new CountDownLatch(1);
            InternalExecutor<String, String> syncBlocking = (cid, callId, arg) -> {
                syncMidFlight.countDown();
                try {
                    syncRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return arg;
            };

            // When — start a sync call on a virtual thread; wait until it holds the permit
            Thread holder = Thread.startVirtualThread(
                    () -> bulkhead.execute(1L, 1L, "sync", syncBlocking));
            assertThat(syncMidFlight.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(bulkhead.concurrentCalls())
                    .as("sync call holds one permit on the shared strategy")
                    .isEqualTo(1);

            // When — issue an async call on top; the second permit is free, async acquires
            CompletableFuture<String> asyncDownstream = new CompletableFuture<>();
            CompletionStage<String> asyncResult = bulkhead.executeAsync(
                    2L, 1L, "async", (cid, callId, arg) -> asyncDownstream);

            // Then — both permits accounted to the same strategy
            assertThat(bulkhead.concurrentCalls()).isEqualTo(2);
            assertThat(bulkhead.availablePermits()).isZero();

            // When — complete async, then release sync
            asyncDownstream.complete("a");
            asyncResult.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(bulkhead.concurrentCalls()).isEqualTo(1);

            syncRelease.countDown();
            holder.join();

            // Then — both paths drained the shared strategy back to zero
            assertThat(bulkhead.concurrentCalls()).isZero();
            assertThat(bulkhead.availablePermits()).isEqualTo(2);
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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
            // Transition to hot first, otherwise the strategy doesn't exist yet.
            bulkhead.execute(1L, 1L, "warm", identityExecutor());
            assertThat(bulkhead.availablePermits()).isEqualTo(10);

            // When
            BulkheadPatch raiseLimit = new BulkheadPatch();
            raiseLimit.touchMaxConcurrentCalls(25);
            live.apply(raiseLimit);

            // Then
            assertThat(bulkhead.availablePermits()).isEqualTo(25);
        }

        @Test
        void should_propagate_a_limit_decrease_to_the_strategy() {
            // Given
            LiveContainer<BulkheadSnapshot> live =
                    new LiveContainer<>(snapshot("x", 10, Duration.ofMillis(100)));
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
            bulkhead.execute(1L, 1L, "warm", identityExecutor());

            // When
            BulkheadPatch lowerLimit = new BulkheadPatch();
            lowerLimit.touchMaxConcurrentCalls(3);
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
            InqBulkhead<String, String> bulkhead = newBulkhead(live);
            assertThat(bulkhead.lifecycleState()).isEqualTo(LifecycleState.COLD);

            // When — update before any execute
            BulkheadPatch raiseLimit = new BulkheadPatch();
            raiseLimit.touchMaxConcurrentCalls(25);
            live.apply(raiseLimit);

            // Then — bulkhead is still cold; available permits read the new snapshot directly
            assertThat(bulkhead.lifecycleState()).isEqualTo(LifecycleState.COLD);
            assertThat(bulkhead.availablePermits()).isEqualTo(25);
        }
    }
}
