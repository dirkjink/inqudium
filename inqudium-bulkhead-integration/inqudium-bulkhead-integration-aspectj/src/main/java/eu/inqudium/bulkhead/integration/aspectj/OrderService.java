package eu.inqudium.bulkhead.integration.aspectj;

import eu.inqudium.annotation.InqBulkhead;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static eu.inqudium.bulkhead.integration.aspectj.BulkheadConfig.BULKHEAD_NAME;

/**
 * Tiny webshop order service used as the example domain.
 *
 * <p>The class is plain Java — no Inqudium type, no framework hook. Resilience attaches at
 * build time: each method that should be protected carries the {@link InqBulkhead}
 * annotation from {@code inqudium-annotation} — the same annotation the Spring Boot
 * integration uses — and the AspectJ compile-time weaver rewrites the bytecode so calls to
 * those methods route through {@link OrderBulkheadAspect}. The annotation's
 * {@link InqBulkhead#value() value()} carries the registered bulkhead's name; the aspect
 * binds the annotation in its pointcut, reads the {@code value()}, and resolves the matching
 * bulkhead from the runtime's registry on each invocation. The service itself does not know
 * it is protected, just like the function-based example's service; the difference is that
 * the wrapping is invisible at every call site, exactly as a 2026 application written in the
 * AspectJ idiom would expect.
 *
 * <p>Both call shapes appear here — synchronous methods that return a value directly, and
 * asynchronous methods that return a {@link CompletionStage}. {@link OrderBulkheadAspect}
 * routes the annotated methods through a {@link
 * eu.inqudium.aspect.pipeline.HybridAspectPipelineTerminal} that dispatches by return type,
 * so a single bulkhead instance protects both paths through one terminal: ADR-033's
 * contract that {@code InqBulkhead} implements both {@code InqDecorator} and
 * {@code InqAsyncDecorator} surfaces here as a single aspect that handles both shapes.
 *
 * <p>The bulkhead's name appears exactly once in this module's source — as the literal
 * inside {@link BulkheadConfig#BULKHEAD_NAME} — and is referenced from each annotation
 * through the static import below. No annotation in this file inlines the name as a string
 * literal, so renaming the bulkhead is a one-line change in {@link BulkheadConfig}.
 */
public class OrderService {

    /**
     * Places an order for the given item. Synchronous happy-path call.
     *
     * @param item the item identifier; never {@code null}.
     * @return a confirmation string of the form {@code "ordered:<item>"}.
     */
    @InqBulkhead(BULKHEAD_NAME)
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
    @InqBulkhead(BULKHEAD_NAME)
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
    @InqBulkhead(BULKHEAD_NAME)
    public CompletionStage<String> placeOrderAsync(String item) {
        return CompletableFuture.completedFuture("async-ordered:" + item);
    }

    /**
     * Held-permit async variant used to demonstrate saturation on the async path.
     *
     * <p>The returned stage completes only when {@code release} completes. The bulkhead's
     * permit is held for the entire wait: the async path acquires synchronously when the
     * woven advice runs, and the bulkhead's {@code whenComplete} release callback fires only
     * when the stage produced here completes — which is when {@code release} completes. Two
     * concurrent invocations therefore consume both permits and a third async call is
     * rejected with {@link eu.inqudium.core.element.bulkhead.InqBulkheadFullException} — the
     * surface through which the rejection reaches the caller is documented in
     * {@link Main#demonstrateAsyncSaturation Main.demonstrateAsyncSaturation}.
     *
     * @param release a future signalled by the caller when the held permit should be freed.
     * @return a stage that completes with the literal string {@code "async-released"} once
     *         {@code release} completes.
     */
    @InqBulkhead(BULKHEAD_NAME)
    public CompletionStage<String> placeOrderHoldingAsync(CompletableFuture<Void> release) {
        return release.thenApply(ignored -> "async-released");
    }
}
