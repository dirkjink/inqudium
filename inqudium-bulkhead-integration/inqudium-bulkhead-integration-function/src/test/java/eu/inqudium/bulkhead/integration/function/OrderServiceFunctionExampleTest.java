package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.runtime.BulkheadHandle;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the function-based bulkhead example.
 *
 * <p>The tests exercise the example application — they call {@link OrderService}'s public
 * methods directly. Self-wrapping is the production responsibility (see
 * {@link OrderService}'s constructor): the tests verify the shape that wrapping produces
 * rather than re-implement it. Assertions read {@link InqBulkhead#availablePermits()} and
 * {@link InqBulkhead#concurrentCalls()}, the public handle accessors an application could
 * also consult.
 */
@DisplayName("Function-based bulkhead example")
class OrderServiceFunctionExampleTest {

    @SuppressWarnings("unchecked")
    private static <A, R> InqBulkhead<A, R> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<A, R>) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void place_order_succeeds_through_the_bulkhead() {
            // Given: a runtime with the example's bulkhead and a self-wrapping order service
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);

                // When: a single order is placed through the service's public method
                String result = service.placeOrder("Widget");

                // Then: the service's reply propagates back unchanged
                assertThat(result).isEqualTo("ordered:Widget");
            }
        }

        @Test
        void place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the bulkhead releases the acquired permit at the end of
            // every wrapped call, so that sequential calls never deplete the permit pool.
            // How will the test be deemed successful and why: availablePermits() reads two
            // (the configured limit) before and after each call. If the bulkhead leaked a
            // permit on the synchronous return path, the count would drop monotonically.
            // Why is it important: a leaked permit on the happy path is the most
            // user-impacting class of bulkhead defect — the protection mechanism turns into
            // a cliff for every subsequent caller.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                // Given: a fully-released bulkhead at the configured limit
                assertThat(bulkhead.availablePermits()).isEqualTo(2);

                // When: the same service method is called multiple times sequentially
                for (int i = 0; i < 5; i++) {
                    service.placeOrder("item-" + i);

                    // Then: the permit count returns to two after every call
                    assertThat(bulkhead.availablePermits())
                            .as("after call %d", i)
                            .isEqualTo(2);
                }
            }
        }

        @Test
        void async_place_order_succeeds_through_the_bulkhead() {
            // Given: a runtime with the example's bulkhead and a self-wrapping order service
            // — the async path goes through the same bulkhead instance, wrapped via
            // decorateAsyncFunction inside the service's constructor
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                // When: a single async order is placed through the service's public method
                String result = service.placeOrderAsync("Apple").toCompletableFuture().join();

                // Then: the service's reply propagates back unchanged and the permit has
                // returned to the pool by the time the stage completes
                assertThat(result).isEqualTo("async-ordered:Apple");
                assertThat(bulkhead.availablePermits()).isEqualTo(2);
            }
        }

        @Test
        void async_place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the async path releases the acquired permit on stage
            // completion, so sequential async calls never deplete the permit pool. The async
            // release is structurally different from the sync release — it fires from the
            // bulkhead's whenComplete callback rather than from a finally clause — so it
            // earns its own coverage even though the user-visible property mirrors the sync
            // case.
            // How will the test be deemed successful and why: availablePermits() reads two
            // before and after every joined async call. If the whenComplete release callback
            // were skipped on the success path, the count would drop monotonically.
            // Why is it important: a leaked permit on the async happy path is just as
            // user-impacting as on the sync path; it would silently throttle every caller
            // after the pool drains. ADR-020's release contract requires the callback fires
            // on both success and failure terminations.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                // Given: a fully-released bulkhead at the configured limit
                assertThat(bulkhead.availablePermits()).isEqualTo(2);

                // When: the same service async method is invoked multiple times sequentially,
                // joining each stage before the next call
                for (int i = 0; i < 5; i++) {
                    service.placeOrderAsync("item-" + i).toCompletableFuture().join();

                    // Then: the permit count returns to two after every joined stage
                    assertThat(bulkhead.availablePermits())
                            .as("after async call %d", i)
                            .isEqualTo(2);
                }
            }
        }
    }

    @Nested
    @DisplayName("Saturation")
    class Saturation {

        @Test
        void concurrent_calls_above_the_limit_are_rejected_with_InqBulkheadFullException() throws InterruptedException {
            // What is to be tested: when both permits are held by concurrent in-flight
            // calls, a third synchronous call cannot acquire a permit and is rejected with
            // InqBulkheadFullException. How will the test be deemed successful and why: two
            // virtual-thread holders enter placeOrderHolding and decrement their acquired
            // latches; a third call from the main thread is rejected synchronously; both
            // holders complete cleanly once the release latch fires. Why is it important:
            // saturation rejection is the bulkhead's reason to exist — a regression here
            // means the protection mechanism has been silently disabled or the rejection
            // type has been re-wrapped, breaking the user-facing contract.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                CountDownLatch holderAcquired1 = new CountDownLatch(1);
                CountDownLatch holderAcquired2 = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                List<Throwable> holderErrors = new ArrayList<>();

                // Given: two virtual threads each holding a permit
                Thread holder1 = Thread.startVirtualThread(() -> {
                    try {
                        service.placeOrderHolding(holderAcquired1, release);
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });
                Thread holder2 = Thread.startVirtualThread(() -> {
                    try {
                        service.placeOrderHolding(holderAcquired2, release);
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });

                assertThat(holderAcquired1.await(5, TimeUnit.SECONDS))
                        .as("holder 1 must enter the body").isTrue();
                assertThat(holderAcquired2.await(5, TimeUnit.SECONDS))
                        .as("holder 2 must enter the body").isTrue();

                try {
                    // When / Then: a third call attempts to acquire a permit and is rejected
                    // synchronously with the bulkhead's own exception
                    assertThatThrownBy(() -> service.placeOrder("Saturated"))
                            .isInstanceOf(InqBulkheadFullException.class);
                } finally {
                    release.countDown();
                    holder1.join();
                    holder2.join();
                }

                assertThat(holderErrors)
                        .as("holders must release without errors").isEmpty();
                assertThat(bulkhead.availablePermits())
                        .as("permits return to the configured limit after holders release")
                        .isEqualTo(2);
            }
        }

        @Test
        void concurrent_async_calls_above_the_limit_are_rejected_with_InqBulkheadFullException() {
            // What is to be tested: when both permits are held by in-flight async calls (the
            // permits were acquired synchronously on the calling thread when the wrapped
            // async function returned its still-pending stage), a third async call cannot
            // acquire a permit and is rejected with a synchronous throw — not a failed
            // CompletionStage.
            // How will the test be deemed successful and why: two stage holders each consume
            // a permit; the third call to the wrapped async function throws
            // InqBulkheadFullException directly during the apply(...) invocation, before any
            // stage is constructed. After releasing the holders, both permits return to the
            // pool.
            // Why is it important: ADR-020's back-pressure contract says the async path
            // reports rejection synchronously so callers can detect overload before
            // scheduling new async work. A regression that re-wrapped the rejection inside a
            // failed stage would force every caller to thread error handling through both
            // paths — and would silently disable ergonomic upstream load-shedding.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                CompletableFuture<Void> release = new CompletableFuture<>();

                // Given: two in-flight async holders, each holding a permit while their
                // stages remain pending
                CompletionStage<String> holder1 = service.placeOrderHoldingAsync(release);
                CompletionStage<String> holder2 = service.placeOrderHoldingAsync(release);

                assertThat(bulkhead.concurrentCalls())
                        .as("both async holders must hold a permit synchronously")
                        .isEqualTo(2);
                assertThat(bulkhead.availablePermits()).isZero();

                try {
                    // When / Then: a third async call is rejected synchronously with the
                    // bulkhead's own exception — the throw happens during apply(...), no
                    // stage is returned to the caller
                    assertThatThrownBy(() -> service.placeOrderAsync("Saturated"))
                            .isInstanceOf(InqBulkheadFullException.class);
                } finally {
                    release.complete(null);
                    holder1.toCompletableFuture().join();
                    holder2.toCompletableFuture().join();
                }

                assertThat(bulkhead.availablePermits())
                        .as("permits return to the configured limit after holders release")
                        .isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        void the_runtime_can_be_closed_and_a_fresh_one_built_in_the_same_test_class() {
            // Sanity check: BulkheadConfig.newRuntime() produces independent runtimes that
            // do not interfere with one another, so two consecutive tests in this class
            // each build and close their own without observing artefacts from the other.
            // The check is structural — if the example's runtime construction were
            // accidentally tied to a process-level singleton, this would surface.
            try (InqRuntime first = BulkheadConfig.newRuntime()) {
                BulkheadHandle<ImperativeTag> firstHandle =
                        first.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
                assertThat(firstHandle.name()).isEqualTo(BulkheadConfig.BULKHEAD_NAME);
            }

            try (InqRuntime second = BulkheadConfig.newRuntime()) {
                BulkheadHandle<ImperativeTag> secondHandle =
                        second.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
                assertThat(secondHandle.name()).isEqualTo(BulkheadConfig.BULKHEAD_NAME);
                assertThat(secondHandle.availablePermits()).isEqualTo(2);
            }
        }

        @Test
        void the_runtime_can_be_closed_and_a_fresh_one_built_in_the_same_test_class_for_async() {
            // What is to be tested: the close-and-rebuild lifecycle works when the example's
            // bulkhead is exercised through the *async* wrapping surface. The sync sibling
            // test pins handle name and permit count after rebuild but never invokes the
            // wrapping APIs; this test additionally invokes the service's async method on
            // each runtime and joins a returned stage, so any regression that broke the async
            // pipeline construction or release callback specifically — without breaking the
            // sync surface — would surface here.
            // How will the test be deemed successful and why: each of two consecutively
            // built runtimes hosts the bulkhead, accepts an async wrapping, returns the
            // expected stage value, and shows the permit returned to the pool after stage
            // completion.
            // Why is it important: an async-only construction or teardown defect would slip
            // past the sync lifecycle test entirely.
            try (InqRuntime first = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(first);
                InqBulkhead<String, String> bulkhead = orderBulkhead(first);

                String firstResult = service.placeOrderAsync("First").toCompletableFuture().join();
                assertThat(firstResult).isEqualTo("async-ordered:First");
                assertThat(bulkhead.availablePermits()).isEqualTo(2);
            }

            try (InqRuntime second = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(second);
                InqBulkhead<String, String> bulkhead = orderBulkhead(second);

                String secondResult = service.placeOrderAsync("Second").toCompletableFuture().join();
                assertThat(secondResult).isEqualTo("async-ordered:Second");
                assertThat(bulkhead.availablePermits()).isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Shared strategy")
    class SharedStrategy {

        @Test
        void sync_and_async_calls_share_the_same_bulkhead_strategy() {
            // What is to be tested: the example's single bulkhead instance protects both the
            // sync and the async path through the *same* permit pool. A sync hold consumes
            // one permit; a concurrent async call observes one available permit and acquires
            // successfully (since maxConcurrentCalls is two). Both paths read and update the
            // same concurrentCalls count.
            // How will the test be deemed successful and why: while a sync holder is in
            // flight (concurrentCalls == 1), an async call is admitted and returns its
            // value; concurrentCalls reads two while both are mid-flight, then drops back to
            // zero after both release. If sync and async were ever wired to separate
            // strategies, the async call would observe two free permits regardless of the
            // sync holder, and the count would never read two simultaneously.
            // Why is it important: REFACTORING_ASYNC_BULKHEAD.md decision 1 makes the shared
            // strategy a load-bearing structural property — one bulkhead, one pool, two
            // pipeline shapes. The example application's wrapper APIs are the surface a
            // developer touches; pinning the property here ensures it holds at that level,
            // not just at the library-internal `executeAsync(...)` level.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                CountDownLatch holderAcquired = new CountDownLatch(1);
                CountDownLatch syncRelease = new CountDownLatch(1);
                List<Throwable> holderErrors = new ArrayList<>();

                // Given: one virtual-thread sync holder occupies one permit
                Thread holder = Thread.startVirtualThread(() -> {
                    try {
                        service.placeOrderHolding(holderAcquired, syncRelease);
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });

                try {
                    assertThat(holderAcquired.await(5, TimeUnit.SECONDS))
                            .as("sync holder must enter the body").isTrue();
                    assertThat(bulkhead.concurrentCalls())
                            .as("sync holder consumed one permit on the shared strategy")
                            .isEqualTo(1);

                    // When: an async holding call enters in parallel
                    CompletableFuture<Void> asyncRelease = new CompletableFuture<>();
                    CompletionStage<String> asyncHolder = service.placeOrderHoldingAsync(asyncRelease);

                    // Then: the async permit was acquired against the same pool — both paths
                    // mid-flight pushes the count to two
                    assertThat(bulkhead.concurrentCalls())
                            .as("sync and async holders share one strategy")
                            .isEqualTo(2);
                    assertThat(bulkhead.availablePermits()).isZero();

                    // When: both paths release
                    asyncRelease.complete(null);
                    String asyncResult = asyncHolder.toCompletableFuture().join();
                    syncRelease.countDown();
                    holder.join();

                    // Then: the shared pool drains back to the configured limit
                    assertThat(asyncResult).isEqualTo("async-released");
                    assertThat(holderErrors)
                            .as("sync holder must release without errors").isEmpty();
                    assertThat(bulkhead.concurrentCalls()).isZero();
                    assertThat(bulkhead.availablePermits()).isEqualTo(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    syncRelease.countDown();
                }
            }
        }
    }

    @Nested
    @DisplayName("RuntimeConfigChange")
    class RuntimeConfigChange {

        @Test
        void a_full_promotion_cycle_changes_saturation_behavior_live() throws InterruptedException {
            // What is to be tested: the AdminService's promotion cycle observably changes the
            // bulkhead's saturation behaviour live, without rebuilding the runtime, the
            // service, or the wrapped functions. Three sequential phases:
            //   1. balanced/2 — two holders saturate, third call rejected.
            //   2. permissive/50 (after startSellPromotion) — five concurrent holders all
            //      succeed without rejection.
            //   3. balanced/2 (after endSellPromotion) — saturation restored, third call
            //      rejected again.
            // How will the test be deemed successful and why: the third call in phase 1 and
            // phase 3 throws InqBulkheadFullException; the five holders in phase 2 all
            // produce their result without any exception. Each phase uses the same
            // OrderService instance — a single set of cached wrapped functions — to prove
            // the patch works through them, not around them.
            // Why is it important: this is the operational headline of sub-step 6.C — that a
            // runtime patch flows through to the bulkhead's live behaviour without the
            // application having to rewire anything. A regression here would mean that
            // operators who patch a bulkhead through runtime.update(...) silently get no
            // effect at the call sites that matter.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService(runtime);
                AdminService admin = new AdminService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                // === Phase 1: balanced/2 — third call rejected ===
                runSaturationCycle(service, bulkhead, 2, /*expectRejection*/ true);

                // === Phase 2: permissive/50 — five holders all succeed ===
                admin.startSellPromotion();
                runFiveAsyncHoldersSuccessfully(service, bulkhead);

                // === Phase 3: balanced/2 again — third call rejected again ===
                admin.endSellPromotion();
                runSaturationCycle(service, bulkhead, 2, /*expectRejection*/ true);
            }
        }

        @Test
        void available_permits_jump_immediately_when_promotion_starts_and_ends() {
            // What is to be tested: availablePermits() on the bulkhead handle reflects a
            // runtime patch synchronously and without lag. The three reads (initial, after
            // start, after end) capture the bulkhead's permit ceiling at each phase.
            // How will the test be deemed successful and why: the read after construction
            // returns 2 (the balanced default); the read after startSellPromotion returns
            // 50 (the permissive patch); the read after endSellPromotion returns 2 again.
            // Why is it important: an operator's observability contract for a runtime patch
            // is "what I see right after the patch is what's true now". If the live
            // strategy were re-tuned lazily, or only on the next acquire, the permits read
            // would lag the patch and operators would not be able to confirm a successful
            // change from a dashboard or admin endpoint.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                // Build the service so the bulkhead becomes hot at construction time —
                // the available-permits accessor returns the live strategy's value once hot
                OrderService service = new OrderService(runtime);
                AdminService admin = new AdminService(runtime);
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);
                forceHotPhase(service);

                // Given: a freshly built runtime under the balanced/2 default
                assertThat(bulkhead.availablePermits())
                        .as("initial permit ceiling under balanced/2")
                        .isEqualTo(2);

                // When: the promotion patch is applied
                admin.startSellPromotion();

                // Then: the new permit ceiling is observable immediately
                assertThat(bulkhead.availablePermits())
                        .as("permit ceiling after startSellPromotion (permissive/50)")
                        .isEqualTo(50);

                // When: the promotion patch is reversed
                admin.endSellPromotion();

                // Then: the original permit ceiling is restored immediately
                assertThat(bulkhead.availablePermits())
                        .as("permit ceiling after endSellPromotion (balanced/2)")
                        .isEqualTo(2);
            }
        }

        /**
         * Drive {@code holderCount} virtual-thread holders into the bulkhead, attempt one
         * extra synchronous call, and assert the rejection (or success) of that extra call.
         */
        private void runSaturationCycle(OrderService service, InqBulkhead<?, ?> bulkhead,
                                        int holderCount, boolean expectRejection)
                throws InterruptedException {
            CountDownLatch[] acquired = new CountDownLatch[holderCount];
            CountDownLatch release = new CountDownLatch(1);
            Thread[] holders = new Thread[holderCount];
            List<Throwable> holderErrors = new ArrayList<>();
            for (int i = 0; i < holderCount; i++) {
                acquired[i] = new CountDownLatch(1);
                CountDownLatch acq = acquired[i];
                holders[i] = Thread.startVirtualThread(() -> {
                    try {
                        service.placeOrderHolding(acq, release);
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });
            }

            try {
                for (CountDownLatch a : acquired) {
                    assertThat(a.await(5, TimeUnit.SECONDS))
                            .as("each holder must enter the body").isTrue();
                }

                if (expectRejection) {
                    assertThatThrownBy(() -> service.placeOrder("Saturated"))
                            .isInstanceOf(InqBulkheadFullException.class);
                } else {
                    assertThat(service.placeOrder("Saturated")).isEqualTo("ordered:Saturated");
                }
            } finally {
                release.countDown();
                for (Thread t : holders) {
                    t.join();
                }
            }

            assertThat(holderErrors).as("holders must release without errors").isEmpty();
            assertThat(bulkhead.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(holderCount);
        }

        /**
         * Run five concurrent async holders against the bulkhead and confirm none is
         * rejected. Five is well below permissive/50, so the success is structural — none
         * of the calls runs out of permits.
         */
        private void runFiveAsyncHoldersSuccessfully(OrderService service,
                                                     InqBulkhead<String, String> bulkhead) {
            CompletableFuture<Void> release = new CompletableFuture<>();
            List<CompletionStage<String>> holders = new ArrayList<>();
            AtomicReference<Throwable> firstError = new AtomicReference<>();
            for (int i = 0; i < 5; i++) {
                try {
                    holders.add(service.placeOrderHoldingAsync(release));
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            }

            assertThat(firstError.get())
                    .as("no holder should be rejected under permissive/50")
                    .isNull();
            assertThat(holders).hasSize(5);
            assertThat(bulkhead.concurrentCalls())
                    .as("five async holders all hold permits at once")
                    .isEqualTo(5);

            release.complete(null);
            for (CompletionStage<String> h : holders) {
                assertThat(h.toCompletableFuture().join()).isEqualTo("async-released");
            }
            assertThat(bulkhead.concurrentCalls()).isZero();
        }

        /**
         * Force the bulkhead into its hot phase by issuing one no-op order. The handle's
         * {@code availablePermits()} returns the live strategy's value once hot; before that
         * it returns the cold-state limit from the snapshot. Either reading would suffice for
         * the assertions in this test, but driving through the service makes the path the
         * same as production traffic and removes any cold/hot ambiguity from the assertions.
         */
        private void forceHotPhase(OrderService service) {
            service.placeOrder("warm-up");
        }
    }
}
