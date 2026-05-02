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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the function-based bulkhead example.
 *
 * <p>The tests exercise the example application — they use the same {@link OrderService},
 * the same {@link BulkheadConfig#newRuntime()} entry point, and the same
 * {@code decorateFunction}/{@code decorateSupplier} wrapping pattern that {@link Main}
 * demonstrates. The tests do not reach into bulkhead internals: assertions read
 * {@link InqBulkhead#availablePermits()}, the public handle accessor an application could
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
            // Given: a runtime with the example's bulkhead and a wrapped service method
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);
                Function<String, String> protectedPlaceOrder =
                        bulkhead.decorateFunction(service::placeOrder);

                // When: a single order is placed through the wrapped function
                String result = protectedPlaceOrder.apply("Widget");

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
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);
                Function<String, String> protectedPlaceOrder =
                        bulkhead.decorateFunction(service::placeOrder);

                // Given: a fully-released bulkhead at the configured limit
                assertThat(bulkhead.availablePermits()).isEqualTo(2);

                // When: the same wrapped function is called multiple times sequentially
                for (int i = 0; i < 5; i++) {
                    protectedPlaceOrder.apply("item-" + i);

                    // Then: the permit count returns to two after every call
                    assertThat(bulkhead.availablePermits())
                            .as("after call %d", i)
                            .isEqualTo(2);
                }
            }
        }

        @Test
        void async_place_order_succeeds_through_the_bulkhead() {
            // Given: a runtime with the example's bulkhead and the async order method wrapped
            // through decorateAsyncFunction — the async sibling of decorateFunction
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);
                Function<String, CompletionStage<String>> protectedPlaceOrderAsync =
                        bulkhead.decorateAsyncFunction(service::placeOrderAsync);

                // When: a single async order is placed through the wrapped function
                String result = protectedPlaceOrderAsync.apply("Apple")
                        .toCompletableFuture().join();

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
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);
                Function<String, CompletionStage<String>> protectedPlaceOrderAsync =
                        bulkhead.decorateAsyncFunction(service::placeOrderAsync);

                // Given: a fully-released bulkhead at the configured limit
                assertThat(bulkhead.availablePermits()).isEqualTo(2);

                // When: the same wrapped async function is invoked multiple times
                // sequentially, joining each stage before the next call
                for (int i = 0; i < 5; i++) {
                    protectedPlaceOrderAsync.apply("item-" + i)
                            .toCompletableFuture().join();

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
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                CountDownLatch holderAcquired1 = new CountDownLatch(1);
                CountDownLatch holderAcquired2 = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                List<Throwable> holderErrors = new ArrayList<>();

                // Given: two virtual threads each holding a permit
                Thread holder1 = Thread.startVirtualThread(() -> {
                    try {
                        bulkhead.decorateSupplier(
                                () -> service.placeOrderHolding(holderAcquired1, release)).get();
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });
                Thread holder2 = Thread.startVirtualThread(() -> {
                    try {
                        bulkhead.decorateSupplier(
                                () -> service.placeOrderHolding(holderAcquired2, release)).get();
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });

                assertThat(holderAcquired1.await(5, TimeUnit.SECONDS))
                        .as("holder 1 must enter the body").isTrue();
                assertThat(holderAcquired2.await(5, TimeUnit.SECONDS))
                        .as("holder 2 must enter the body").isTrue();

                try {
                    // When: a third call attempts to acquire a permit
                    Function<String, String> protectedPlaceOrder =
                            bulkhead.decorateFunction(service::placeOrder);

                    // Then: it is rejected synchronously with the bulkhead's own exception
                    assertThatThrownBy(() -> protectedPlaceOrder.apply("Saturated"))
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
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);
                InqBulkhead<CompletableFuture<Void>, String> holdingBulkhead =
                        orderBulkhead(runtime);

                Function<CompletableFuture<Void>, CompletionStage<String>> protectedHolding =
                        holdingBulkhead.decorateAsyncFunction(service::placeOrderHoldingAsync);
                Function<String, CompletionStage<String>> protectedPlaceOrderAsync =
                        bulkhead.decorateAsyncFunction(service::placeOrderAsync);

                CompletableFuture<Void> release = new CompletableFuture<>();

                // Given: two in-flight async holders, each holding a permit while their
                // stages remain pending
                CompletionStage<String> holder1 = protectedHolding.apply(release);
                CompletionStage<String> holder2 = protectedHolding.apply(release);

                assertThat(bulkhead.concurrentCalls())
                        .as("both async holders must hold a permit synchronously")
                        .isEqualTo(2);
                assertThat(bulkhead.availablePermits()).isZero();

                try {
                    // When / Then: a third async call is rejected synchronously with the
                    // bulkhead's own exception — the throw happens during apply(...), no
                    // stage is returned to the caller
                    assertThatThrownBy(() -> protectedPlaceOrderAsync.apply("Saturated"))
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
            // wrapping APIs; this test additionally invokes decorateAsyncFunction on each
            // runtime and joins a returned stage, so any regression that broke the async
            // pipeline construction or release callback specifically — without breaking the
            // sync surface — would surface here.
            // How will the test be deemed successful and why: each of two consecutively
            // built runtimes hosts the bulkhead, accepts an async wrapping, returns the
            // expected stage value, and shows the permit returned to the pool after stage
            // completion.
            // Why is it important: an async-only construction or teardown defect would slip
            // past the sync lifecycle test entirely.
            try (InqRuntime first = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(first);
                Function<String, CompletionStage<String>> wrappedAsync =
                        bulkhead.decorateAsyncFunction(service::placeOrderAsync);

                String firstResult = wrappedAsync.apply("First").toCompletableFuture().join();
                assertThat(firstResult).isEqualTo("async-ordered:First");
                assertThat(bulkhead.availablePermits()).isEqualTo(2);
            }

            try (InqRuntime second = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(second);
                Function<String, CompletionStage<String>> wrappedAsync =
                        bulkhead.decorateAsyncFunction(service::placeOrderAsync);

                String secondResult = wrappedAsync.apply("Second").toCompletableFuture().join();
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
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                CountDownLatch holderAcquired = new CountDownLatch(1);
                CountDownLatch syncRelease = new CountDownLatch(1);
                List<Throwable> holderErrors = new ArrayList<>();

                // Given: one virtual-thread sync holder occupies one permit
                Thread holder = Thread.startVirtualThread(() -> {
                    try {
                        bulkhead.decorateSupplier(
                                () -> service.placeOrderHolding(holderAcquired, syncRelease))
                                .get();
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
                    InqBulkhead<CompletableFuture<Void>, String> holdingBulkhead =
                            orderBulkhead(runtime);
                    Function<CompletableFuture<Void>, CompletionStage<String>> protectedHolding =
                            holdingBulkhead.decorateAsyncFunction(service::placeOrderHoldingAsync);
                    CompletableFuture<Void> asyncRelease = new CompletableFuture<>();
                    CompletionStage<String> asyncHolder = protectedHolding.apply(asyncRelease);

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
}
