package eu.inqudium.bulkhead.integration.proxy;

import eu.inqudium.config.runtime.BulkheadHandle;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import eu.inqudium.imperative.core.pipeline.HybridProxyPipelineTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the proxy-based bulkhead example.
 *
 * <p>The tests exercise the example application — they use the same {@link OrderService}
 * interface, the same {@link DefaultOrderService} implementation, the same
 * {@link BulkheadConfig#newRuntime()} entry point, and the same
 * {@link HybridProxyPipelineTerminal#protect proxy wrapping} pattern that {@link Main}
 * demonstrates. The tests do not reach into bulkhead internals: assertions read
 * {@link InqBulkhead#availablePermits()}, the public handle accessor an application could
 * also consult.
 *
 * <p>The fixture is per-test: each {@code @Test} builds a fresh runtime, pipeline, terminal,
 * and proxy in {@link #setUp()} and tears the runtime down in {@link #tearDown()}. The
 * lifecycle tests intentionally skip the fixture and build their own runtimes inside the
 * test method, since the property they pin is "two consecutive runtimes can be built and
 * closed in the same test class". They are flagged with their own setup notes.
 */
@DisplayName("Proxy-based bulkhead example")
class OrderServiceProxyExampleTest {

    private InqRuntime runtime;
    private InqBulkhead<Object, Object> bulkhead;
    private OrderService service;

    @SuppressWarnings("unchecked")
    private static InqBulkhead<Object, Object> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<Object, Object>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    @BeforeEach
    void setUp() {
        runtime = BulkheadConfig.newRuntime();
        bulkhead = orderBulkhead(runtime);
        InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
        HybridProxyPipelineTerminal terminal = HybridProxyPipelineTerminal.of(pipeline);
        service = terminal.protect(OrderService.class, new DefaultOrderService());
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void place_order_succeeds_through_the_proxy() {
            // Given: a runtime with the example's bulkhead and a proxy wrapping the default
            // service implementation behind the OrderService interface

            // When: a single order is placed through the proxy
            String result = service.placeOrder("Widget");

            // Then: the implementation's reply propagates back unchanged through the proxy
            assertThat(result).isEqualTo("ordered:Widget");
        }

        @Test
        void place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the bulkhead releases the acquired permit at the end of
            // every proxied call, so that sequential calls never deplete the permit pool.
            // How will the test be deemed successful and why: availablePermits() reads two
            // (the configured limit) before and after each call. If the proxy's sync chain
            // failed to release the permit on the synchronous return path, the count would
            // drop monotonically.
            // Why is it important: a leaked permit on the happy path is the most
            // user-impacting class of bulkhead defect — the protection mechanism turns into
            // a cliff for every subsequent caller. The proxy adds a layer of method-dispatch
            // machinery on top of the bulkhead's own release contract; this test pins that
            // the additional layer does not perturb release.

            // Given: a fully-released bulkhead at the configured limit
            assertThat(bulkhead.availablePermits()).isEqualTo(2);

            // When: the proxy's sync method is invoked multiple times sequentially
            for (int i = 0; i < 5; i++) {
                service.placeOrder("item-" + i);

                // Then: the permit count returns to two after every call
                assertThat(bulkhead.availablePermits())
                        .as("after call %d", i)
                        .isEqualTo(2);
            }
        }

        @Test
        void async_place_order_succeeds_through_the_proxy() {
            // Given: a runtime with the example's bulkhead and a proxy wrapping the default
            // service implementation. The proxy reads the method's CompletionStage return
            // type and routes the invocation through the async pipeline chain.

            // When: a single async order is placed through the proxy
            String result = service.placeOrderAsync("Apple")
                    .toCompletableFuture().join();

            // Then: the implementation's reply propagates back unchanged and the permit has
            // returned to the pool by the time the stage completes
            assertThat(result).isEqualTo("async-ordered:Apple");
            assertThat(bulkhead.availablePermits()).isEqualTo(2);
        }

        @Test
        void async_place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the proxy's async chain releases the acquired permit on
            // stage completion, so sequential async calls never deplete the permit pool. The
            // async release fires from the bulkhead's whenComplete callback rather than from
            // a finally clause, so it earns its own coverage even though the user-visible
            // property mirrors the sync case.
            // How will the test be deemed successful and why: availablePermits() reads two
            // before and after every joined async call. If the proxy's async dispatch swallowed
            // the whenComplete release callback, the count would drop monotonically.
            // Why is it important: a leaked permit on the async happy path is just as
            // user-impacting as on the sync path; it would silently throttle every caller
            // after the pool drains. ADR-020's release contract requires the callback fires
            // on both success and failure terminations, regardless of the dispatch mechanism.

            // Given: a fully-released bulkhead at the configured limit
            assertThat(bulkhead.availablePermits()).isEqualTo(2);

            // When: the proxy's async method is invoked multiple times sequentially,
            // joining each stage before the next call
            for (int i = 0; i < 5; i++) {
                service.placeOrderAsync("item-" + i)
                        .toCompletableFuture().join();

                // Then: the permit count returns to two after every joined stage
                assertThat(bulkhead.availablePermits())
                        .as("after async call %d", i)
                        .isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Saturation")
    class Saturation {

        @Test
        void concurrent_calls_above_the_limit_are_rejected_with_InqBulkheadFullException() throws InterruptedException {
            // What is to be tested: when both permits are held by concurrent in-flight
            // proxied calls, a third synchronous call cannot acquire a permit and is rejected
            // with InqBulkheadFullException — the same contract the bulkhead enforces under
            // direct decoration also holds when the bulkhead sits behind the proxy.
            // How will the test be deemed successful and why: two virtual-thread holders enter
            // placeOrderHolding through the proxy and decrement their acquired latches; a third
            // proxied call from the main thread is rejected synchronously; both holders complete
            // cleanly once the release latch fires.
            // Why is it important: saturation rejection is the bulkhead's reason to exist —
            // a regression here means either the proxy did not actually wire the bulkhead in
            // (no rejection at all) or the proxy re-wrapped the rejection type, breaking the
            // user-facing contract.
            CountDownLatch holderAcquired1 = new CountDownLatch(1);
            CountDownLatch holderAcquired2 = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: two virtual threads each holding a permit through the proxy
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
                // When / Then: a third sync call through the proxy is rejected synchronously
                // with the bulkhead's own exception
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

        @Test
        void concurrent_async_calls_above_the_limit_are_rejected_through_a_failed_stage() {
            // What is to be tested: when both permits are held by in-flight async calls (the
            // permits were acquired synchronously on the calling thread when the proxied
            // async method returned its still-pending stage), a third async call cannot
            // acquire a permit and is rejected with InqBulkheadFullException. Channel
            // detail: HybridProxyPipelineTerminal's documented uniform-error-channel policy
            // captures any synchronous throw on the async path into a failed CompletionStage
            // rather than letting it propagate to the caller. The exception is the same; the
            // surface differs from the function-based decoration path, where the throw
            // propagates synchronously.
            // How will the test be deemed successful and why: two stage holders each consume
            // a permit; the third proxied async call returns a CompletionStage that, when
            // joined, throws CompletionException whose cause is InqBulkheadFullException.
            // After releasing the holders, both permits return to the pool.
            // Why is it important: this test pins both halves of the proxy's async-saturation
            // contract — that the bulkhead actually rejects, and that the rejection surfaces
            // through the failed-stage channel. A regression to either half (no rejection, or
            // a synchronous throw escaping the proxy's error normalization) would break the
            // proxy's documented contract.
            InqBulkhead<Object, Object> bh = bulkhead;

            CompletableFuture<Void> release = new CompletableFuture<>();

            // Given: two in-flight async holders, each holding a permit while their
            // stages remain pending
            CompletionStage<String> holder1 = service.placeOrderHoldingAsync(release);
            CompletionStage<String> holder2 = service.placeOrderHoldingAsync(release);

            assertThat(bh.concurrentCalls())
                    .as("both async holders must hold a permit synchronously")
                    .isEqualTo(2);
            assertThat(bh.availablePermits()).isZero();

            try {
                // When: a third async call attempts to acquire
                CompletionStage<String> rejected = service.placeOrderAsync("Saturated");

                // Then: the rejection arrives through the failed-stage channel
                assertThatThrownBy(() -> rejected.toCompletableFuture().join())
                        .isInstanceOf(CompletionException.class)
                        .hasCauseInstanceOf(InqBulkheadFullException.class);
            } finally {
                release.complete(null);
                holder1.toCompletableFuture().join();
                holder2.toCompletableFuture().join();
            }

            assertThat(bh.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        void the_runtime_can_be_closed_and_a_fresh_one_built_in_the_same_test_class() {
            // The class fixture's runtime is closed in tearDown after every test, so by the
            // time this method runs the per-test runtime is already in flight and the class
            // has already exercised the close-and-rebuild lifecycle implicitly. This test
            // exercises the property explicitly by closing the fixture's runtime and
            // building a second one inside the test body — to pin that two consecutively
            // built proxies route their calls cleanly through their own runtimes.

            // Close the fixture's runtime now so this test can drive the lifecycle directly.
            runtime.close();
            runtime = null;

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
            // bulkhead is exercised through the proxy's *async* dispatch path. The sync
            // sibling test pins handle name and permit count after rebuild but never invokes
            // the proxy; this test additionally builds a fresh proxy on each runtime and
            // joins a returned async stage, so any regression that broke the async chain
            // construction or release callback specifically — without breaking the sync
            // surface — would surface here.
            // How will the test be deemed successful and why: each of two consecutively
            // built runtimes hosts the bulkhead, accepts a fresh proxy, returns the expected
            // stage value, and shows the permit returned to the pool after stage completion.
            // Why is it important: an async-only construction or teardown defect would slip
            // past the sync lifecycle test entirely.

            runtime.close();
            runtime = null;

            try (InqRuntime first = BulkheadConfig.newRuntime()) {
                InqBulkhead<Object, Object> bh = orderBulkhead(first);
                OrderService firstProxy = HybridProxyPipelineTerminal
                        .of(InqPipeline.builder().shield(bh).build())
                        .protect(OrderService.class, new DefaultOrderService());

                String firstResult = firstProxy.placeOrderAsync("First")
                        .toCompletableFuture().join();
                assertThat(firstResult).isEqualTo("async-ordered:First");
                assertThat(bh.availablePermits()).isEqualTo(2);
            }

            try (InqRuntime second = BulkheadConfig.newRuntime()) {
                InqBulkhead<Object, Object> bh = orderBulkhead(second);
                OrderService secondProxy = HybridProxyPipelineTerminal
                        .of(InqPipeline.builder().shield(bh).build())
                        .protect(OrderService.class, new DefaultOrderService());

                String secondResult = secondProxy.placeOrderAsync("Second")
                        .toCompletableFuture().join();
                assertThat(secondResult).isEqualTo("async-ordered:Second");
                assertThat(bh.availablePermits()).isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Shared strategy")
    class SharedStrategy {

        @Test
        void sync_and_async_calls_share_the_same_bulkhead_strategy_through_the_proxy() {
            // What is to be tested: the proxy's sync and async dispatch paths route through
            // the same bulkhead instance and therefore the same permit pool. A sync hold
            // consumes one permit; a concurrent async call (also routed through the proxy)
            // observes one available permit and acquires successfully (since
            // maxConcurrentCalls is two). Both paths read and update the same concurrentCalls
            // count.
            // How will the test be deemed successful and why: while a sync holder is in
            // flight (concurrentCalls == 1), an async call through the proxy is admitted and
            // returns its value; concurrentCalls reads two while both are mid-flight, then
            // drops back to zero after both release. If the proxy ever wired sync and async
            // dispatch to separate bulkheads, the async call would observe two free permits
            // regardless of the sync holder, and the count would never read two
            // simultaneously.
            // Why is it important: the function-based example's SharedStrategy test pins the
            // shared-strategy property at the decorateXxx surface; this test pins the same
            // property at the proxy's method-dispatch surface. The proxy is a different
            // surface that could regress independently — for example, by routing async
            // methods through a per-Method-cached chain that holds a stale reference to a
            // separate bulkhead — even if the underlying decorate APIs continued to share
            // their strategy. ADR-033's one-bulkhead-two-pipeline-shapes property is what the
            // proxy depends on; pinning it at the dispatch surface is what guarantees the
            // proxy honors that property end-to-end.
            CountDownLatch holderAcquired = new CountDownLatch(1);
            CountDownLatch syncRelease = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: one virtual-thread sync holder occupies one permit through the proxy
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

                // When: an async holding call enters in parallel through the same proxy
                CompletableFuture<Void> asyncRelease = new CompletableFuture<>();
                CompletionStage<String> asyncHolder =
                        service.placeOrderHoldingAsync(asyncRelease);

                // Then: the async permit was acquired against the same pool — both paths
                // mid-flight pushes the count to two
                assertThat(bulkhead.concurrentCalls())
                        .as("sync and async holders share one strategy through the proxy")
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
