package eu.inqudium.bulkhead.integration.function;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tiny webshop order service used as the example domain.
 *
 * <p>The class is plain Java — no Inqudium type, no annotation, no framework hook. Resilience
 * is layered on at the call site by wrapping the methods through an {@code InqBulkhead}. That
 * is the function-based pattern's defining shape: the service does not know it is protected.
 *
 * <p>The service exposes both call shapes — synchronous methods that return a value directly,
 * and asynchronous methods that return a {@link CompletionStage}. The same bulkhead handle
 * protects both paths: {@link eu.inqudium.imperative.bulkhead.InqBulkhead} implements both the
 * synchronous {@code InqDecorator} contract and the asynchronous {@code InqAsyncDecorator}
 * contract, sharing one strategy and one permit pool across both shapes.
 */
public class OrderService {

    /**
     * Places an order for the given item. Synchronous happy-path call.
     *
     * @param item the item identifier; never {@code null}.
     * @return a confirmation string of the form {@code "ordered:<item>"}.
     */
    public String placeOrder(String item) {
        return "ordered:" + item;
    }

    /**
     * Held-permit variant used to demonstrate saturation. The method counts down the
     * {@code acquired} latch as soon as the body begins (so the caller knows the permit is
     * held) and then waits on {@code release} before returning. Combined with a small bulkhead
     * limit, two concurrent invocations of this method exhaust the available permits and a
     * third call is rejected with {@link eu.inqudium.core.element.bulkhead.InqBulkheadFullException}.
     *
     * @param acquired counted down once the permit-holding body has entered.
     * @param release  awaited before the body returns; the test (or {@code Main}) signals this
     *                 latch when the permits should be freed.
     * @return the literal string {@code "released"} once the release latch fires.
     * @throws IllegalStateException if the release latch does not fire within five seconds —
     *                               an internal sanity bound that prevents a stuck test from
     *                               hanging the JVM forever.
     */
    public String placeOrderHolding(CountDownLatch acquired, CountDownLatch release) {
        acquired.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timeout: holder never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return "released";
    }

    /**
     * Places an order asynchronously. Async happy-path call — the returned stage is already
     * complete by the time the caller observes it, modelling a service whose async work has
     * settled by the moment its method returns.
     *
     * @param item the item identifier; never {@code null}.
     * @return a stage that completes with {@code "async-ordered:<item>"}.
     */
    public CompletionStage<String> placeOrderAsync(String item) {
        return CompletableFuture.completedFuture("async-ordered:" + item);
    }

    /**
     * Held-permit async variant used to demonstrate saturation on the async path.
     *
     * <p>The returned stage completes only when {@code release} completes. The bulkhead's
     * permit is held for the entire wait: the async path acquires synchronously when the
     * decorated function is invoked, and the bulkhead's {@code whenComplete} release callback
     * fires only when the stage produced here completes — which is when {@code release}
     * completes. Two concurrent invocations therefore consume both permits and a third call
     * is rejected with {@link eu.inqudium.core.element.bulkhead.InqBulkheadFullException},
     * thrown synchronously during the wrapped function's invocation.
     *
     * <p>The signature takes a {@link CompletableFuture} rather than the two-latch dance the
     * synchronous variant uses because the test (or {@code Main}) controls release via
     * {@link CompletableFuture#complete(Object) release.complete(null)}. Callers do not need
     * a separate "acquired" signal: under {@link CompletionStage} semantics the bulkhead's
     * sync acquire happens on the calling thread, so by the time the decorated function call
     * returns the permit is already held.
     *
     * @param release a future signalled by the caller when the held permit should be freed.
     * @return a stage that completes with the literal string {@code "async-released"} once
     *         {@code release} completes.
     */
    public CompletionStage<String> placeOrderHoldingAsync(CompletableFuture<Void> release) {
        return release.thenApply(ignored -> "async-released");
    }
}
