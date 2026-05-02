package eu.inqudium.bulkhead.integration.proxy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * Interface of the tiny webshop order service used as the example domain.
 *
 * <p>Whereas the function-based example exposes the service as a plain class, the proxy-based
 * example splits the service into an interface plus an implementation. JDK dynamic proxies
 * substitute for an interface type, so the structural separation is what enables the proxy
 * style: callers depend on the interface, the proxy implements the interface, and the bulkhead
 * sits between caller and implementation transparently.
 *
 * <p>Both call shapes appear on the same interface — synchronous methods that return a value
 * directly, and asynchronous methods that return a {@link CompletionStage}. The proxy routes
 * each invocation to the appropriate pipeline based on the method's return type, so a single
 * bulkhead instance protects both paths through one terminal: ADR-033's contract that
 * {@code InqBulkhead} implements both {@code InqDecorator} and {@code InqAsyncDecorator}
 * surfaces here as a single proxy that handles both shapes.
 */
public interface OrderService {

    /**
     * Places an order for the given item. Synchronous happy-path call.
     *
     * @param item the item identifier; never {@code null}.
     * @return a confirmation string of the form {@code "ordered:<item>"}.
     */
    String placeOrder(String item);

    /**
     * Held-permit variant used to demonstrate saturation. The method counts down the
     * {@code acquired} latch as soon as the body begins (so the caller knows the permit is
     * held) and then waits on {@code release} before returning.
     *
     * @param acquired counted down once the permit-holding body has entered.
     * @param release  awaited before the body returns; the test (or {@code Main}) signals this
     *                 latch when the permits should be freed.
     * @return the literal string {@code "released"} once the release latch fires.
     */
    String placeOrderHolding(CountDownLatch acquired, CountDownLatch release);

    /**
     * Places an order asynchronously. Async happy-path call — the returned stage is already
     * complete by the time the caller observes it.
     *
     * @param item the item identifier; never {@code null}.
     * @return a stage that completes with {@code "async-ordered:<item>"}.
     */
    CompletionStage<String> placeOrderAsync(String item);

    /**
     * Held-permit async variant used to demonstrate saturation on the async path. The returned
     * stage completes only when {@code release} completes; the bulkhead's permit is held for
     * the entire wait.
     *
     * @param release a future signalled by the caller when the held permit should be freed.
     * @return a stage that completes with the literal string {@code "async-released"} once
     *         {@code release} completes.
     */
    CompletionStage<String> placeOrderHoldingAsync(CompletableFuture<Void> release);
}
