package eu.inqudium.bulkhead.integration.aspectj;

import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.aspectj.lang.Aspects;
import org.junit.jupiter.api.Disabled;
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
 * Tests for the AspectJ-native bulkhead example.
 *
 * <p>The tests exercise the example application — they construct a plain {@link OrderService}
 * and call its methods directly, just as {@link Main} does. The compile-time-woven
 * {@link OrderBulkheadAspect} routes every {@link BulkheadProtected}-annotated invocation
 * through the bulkhead transparently, so the test code does not build a runtime, a pipeline,
 * a terminal, or an aspect manually — that is the structural payoff of CTW: tests look like
 * application code.
 *
 * <p>To inspect the bulkhead's permit count, the tests reach the aspect singleton through
 * the AspectJ-generated {@link Aspects#aspectOf(Class)} accessor. That is the canonical way
 * for any code outside the woven join points to talk to a CTW aspect, and matches what
 * {@code Main} does in {@code demonstrateAsyncSaturation}.
 *
 * <h3>Singleton state across tests</h3>
 * <p>The aspect is an AspectJ singleton — one instance per classloader, owning one runtime
 * for the lifetime of the JVM. The runtime is therefore <em>shared</em> across every test
 * method in this class; each test asserts that {@code availablePermits()} returns to the
 * configured limit (two) before it ends, so that subsequent tests start from a clean
 * baseline. This mirrors the production singleton-aspect pattern: a regression that left a
 * permit dangling would surface as a deterministic failure on a follow-up test.
 *
 * <h3>Lifecycle group: intentionally disabled</h3>
 * <p>The function-based and proxy-based modules pin a "close one runtime, build a fresh one"
 * lifecycle property; that property does not translate idiomatically to the CTW pattern,
 * because the singleton aspect owns the runtime for the JVM's lifetime — there is no second
 * runtime to build inside one test class. The lifecycle group below documents that
 * difference with an explicit {@link Disabled} marker rather than failing tests; the
 * lifecycle property is pinned by the function and proxy modules, and the corresponding
 * runtime-mutation property at the aspect-pipeline level is pinned by
 * {@link eu.inqudium.bulkhead.integration.aspectj.lifecycle.BulkheadAspectLifecycleTest}.
 */
@DisplayName("AspectJ-native bulkhead example")
class OrderServiceAspectJExampleTest {

    private static InqBulkhead<Object, Object> bulkhead() {
        return Aspects.aspectOf(OrderBulkheadAspect.class).bulkhead();
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void place_order_succeeds_through_the_woven_aspect() {
            // Given: a plain OrderService whose @BulkheadProtected methods have been woven
            // by ajc to enter OrderBulkheadAspect first
            OrderService service = new OrderService();

            // When: a single order is placed through the woven service
            String result = service.placeOrder("Widget");

            // Then: the original method's reply propagates back unchanged through the aspect
            assertThat(result).isEqualTo("ordered:Widget");
        }

        @Test
        void place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the bulkhead releases the acquired permit at the end of
            // every woven sync call, so that sequential calls never deplete the permit pool.
            // How will the test be deemed successful and why: availablePermits() reads two
            // (the configured limit) before and after each call. If the aspect's sync chain
            // failed to release the permit on the synchronous return path, the count would
            // drop monotonically.
            // Why is it important: a leaked permit on the happy path is the most
            // user-impacting class of bulkhead defect — the protection mechanism turns into
            // a cliff for every subsequent caller. The aspect adds a layer of advice
            // dispatch on top of the bulkhead's own release contract; this test pins that
            // the additional layer does not perturb release.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            // Given: a fully-released bulkhead at the configured limit
            assertThat(bh.availablePermits()).isEqualTo(2);

            // When: the woven sync method is invoked multiple times sequentially
            for (int i = 0; i < 5; i++) {
                service.placeOrder("item-" + i);

                // Then: the permit count returns to two after every call
                assertThat(bh.availablePermits())
                        .as("after call %d", i)
                        .isEqualTo(2);
            }
        }

        @Test
        void async_place_order_succeeds_through_the_woven_aspect() {
            // Given: a plain OrderService whose @BulkheadProtected async methods have been
            // woven by ajc. The aspect reads the CompletionStage return type and routes the
            // invocation through the async pipeline chain.
            OrderService service = new OrderService();

            // When: a single async order is placed through the woven service
            String result = service.placeOrderAsync("Apple")
                    .toCompletableFuture().join();

            // Then: the original method's reply propagates back unchanged and the permit
            // has returned to the pool by the time the stage completes
            assertThat(result).isEqualTo("async-ordered:Apple");
            assertThat(bulkhead().availablePermits()).isEqualTo(2);
        }

        @Test
        void async_place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the aspect's async chain releases the acquired permit on
            // stage completion, so sequential async calls never deplete the permit pool. The
            // async release fires from the bulkhead's whenComplete callback rather than from
            // a finally clause, so it earns its own coverage even though the user-visible
            // property mirrors the sync case.
            // How will the test be deemed successful and why: availablePermits() reads two
            // before and after every joined async call. If the aspect's async dispatch
            // swallowed the whenComplete release callback, the count would drop monotonically.
            // Why is it important: a leaked permit on the async happy path is just as
            // user-impacting as on the sync path; it would silently throttle every caller
            // after the pool drains. ADR-020's release contract requires the callback fires
            // on both success and failure terminations, regardless of the dispatch mechanism.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            // Given: a fully-released bulkhead at the configured limit
            assertThat(bh.availablePermits()).isEqualTo(2);

            // When: the woven async method is invoked multiple times sequentially, joining
            // each stage before the next call
            for (int i = 0; i < 5; i++) {
                service.placeOrderAsync("item-" + i)
                        .toCompletableFuture().join();

                // Then: the permit count returns to two after every joined stage
                assertThat(bh.availablePermits())
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
            // What is to be tested: when both permits are held by concurrent in-flight woven
            // calls, a third synchronous call cannot acquire a permit and is rejected with
            // InqBulkheadFullException — the same contract the bulkhead enforces under direct
            // decoration also holds when the bulkhead sits behind the woven aspect.
            // How will the test be deemed successful and why: two virtual-thread holders enter
            // placeOrderHolding through the woven method and decrement their acquired latches;
            // a third woven call from the main thread is rejected synchronously; both holders
            // complete cleanly once the release latch fires.
            // Why is it important: saturation rejection is the bulkhead's reason to exist —
            // a regression here means either the aspect did not actually wire the bulkhead in
            // (no rejection at all) or the aspect re-wrapped the rejection type, breaking the
            // user-facing contract.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            CountDownLatch holderAcquired1 = new CountDownLatch(1);
            CountDownLatch holderAcquired2 = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: two virtual threads each holding a permit through the woven method
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
                // When / Then: a third sync call through the woven method is rejected
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
            assertThat(bh.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(2);
        }

        @Test
        void concurrent_async_calls_above_the_limit_are_rejected_through_a_failed_stage() {
            // What is to be tested: when both permits are held by in-flight async calls (the
            // permits were acquired synchronously on the calling thread when the woven async
            // method returned its still-pending stage), a third async call cannot acquire a
            // permit and is rejected with InqBulkheadFullException. Channel detail:
            // HybridAspectPipelineTerminal's documented uniform-error-channel policy captures
            // any synchronous throw on the async path into a failed CompletionStage rather
            // than letting it propagate to the caller. The exception is the same; the
            // surface differs from the function-based decoration path, where the throw
            // propagates synchronously, and matches the proxy-based example's behaviour.
            // How will the test be deemed successful and why: two stage holders each consume
            // a permit; the third woven async call returns a CompletionStage that, when
            // joined, throws CompletionException whose cause is InqBulkheadFullException.
            // After releasing the holders, both permits return to the pool.
            // Why is it important: this test pins both halves of the aspect's async-saturation
            // contract — that the bulkhead actually rejects, and that the rejection surfaces
            // through the failed-stage channel. A regression to either half (no rejection, or
            // a synchronous throw escaping the aspect's error normalization) would break the
            // example's documented contract.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            CompletableFuture<Void> release = new CompletableFuture<>();

            // Given: two in-flight async holders, each holding a permit while their stages
            // remain pending
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
    @DisplayName("Shared strategy")
    class SharedStrategy {

        @Test
        void sync_and_async_calls_share_the_same_bulkhead_strategy_through_the_woven_aspect() {
            // What is to be tested: the aspect's sync and async dispatch paths route through
            // the same bulkhead instance and therefore the same permit pool. A sync hold
            // consumes one permit; a concurrent async call (also routed through the woven
            // method) observes one available permit and acquires successfully (since
            // maxConcurrentCalls is two). Both paths read and update the same concurrentCalls
            // count.
            // How will the test be deemed successful and why: while a sync holder is in
            // flight (concurrentCalls == 1), an async call through the woven method is
            // admitted and returns its value; concurrentCalls reads two while both are
            // mid-flight, then drops back to zero after both release. If the aspect ever
            // wired sync and async dispatch to separate bulkheads, the async call would
            // observe two free permits regardless of the sync holder, and the count would
            // never read two simultaneously.
            // Why is it important: the function-based and proxy-based examples' SharedStrategy
            // tests pin the shared-strategy property at their respective surfaces; this test
            // pins the same property at the woven-method dispatch surface. The aspect is a
            // different surface that could regress independently — for example, by composing
            // sync and async chains over a stale lookup of separate bulkheads — even if the
            // underlying decorate APIs continued to share their strategy. ADR-033's
            // one-bulkhead-two-pipeline-shapes property is what HybridAspectPipelineTerminal
            // depends on; pinning it at the dispatch surface is what guarantees the woven
            // aspect honors that property end-to-end.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            CountDownLatch holderAcquired = new CountDownLatch(1);
            CountDownLatch syncRelease = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: one virtual-thread sync holder occupies one permit through the woven
            // method
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
                assertThat(bh.concurrentCalls())
                        .as("sync holder consumed one permit on the shared strategy")
                        .isEqualTo(1);

                // When: an async holding call enters in parallel through the same woven
                // method
                CompletableFuture<Void> asyncRelease = new CompletableFuture<>();
                CompletionStage<String> asyncHolder =
                        service.placeOrderHoldingAsync(asyncRelease);

                // Then: the async permit was acquired against the same pool — both paths
                // mid-flight pushes the count to two
                assertThat(bh.concurrentCalls())
                        .as("sync and async holders share one strategy through the aspect")
                        .isEqualTo(2);
                assertThat(bh.availablePermits()).isZero();

                // When: both paths release
                asyncRelease.complete(null);
                String asyncResult = asyncHolder.toCompletableFuture().join();
                syncRelease.countDown();
                holder.join();

                // Then: the shared pool drains back to the configured limit
                assertThat(asyncResult).isEqualTo("async-released");
                assertThat(holderErrors)
                        .as("sync holder must release without errors").isEmpty();
                assertThat(bh.concurrentCalls()).isZero();
                assertThat(bh.availablePermits()).isEqualTo(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                syncRelease.countDown();
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @Disabled("""
                The aspect owns its runtime as a CTW singleton — there is no second runtime to \
                build inside one test class, so a close-and-rebuild lifecycle test does not \
                translate idiomatically to this pattern. The lifecycle property at the \
                runtime level is pinned by the function and proxy example modules, and the \
                runtime-mutation property at the aspect-pipeline level is pinned by \
                BulkheadAspectLifecycleTest in this module.""")
        void the_runtime_can_be_closed_and_a_fresh_one_built_in_the_same_test_class() {
            // Intentionally empty — see @Disabled reason above. The placeholder method exists
            // so the lifecycle group is documented in the test report instead of silently
            // missing; a reader scanning the suite sees the explicit decision rather than a
            // gap.
        }
    }
}
