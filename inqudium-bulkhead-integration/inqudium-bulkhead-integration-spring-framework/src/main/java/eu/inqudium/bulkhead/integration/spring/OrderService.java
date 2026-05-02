package eu.inqudium.bulkhead.integration.spring;

import eu.inqudium.annotation.InqBulkhead;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static eu.inqudium.bulkhead.integration.spring.BulkheadConfig.BULKHEAD_NAME;

/**
 * Tiny webshop order service used as the example domain.
 *
 * <p>The class is plain Java decorated with {@link Service @Service} for component-scanning
 * compatibility. Resilience attaches at runtime through Spring AOP: each method that should
 * be protected carries the {@link InqBulkhead} annotation from {@code inqudium-annotation} —
 * the same annotation the AspectJ-native and Spring Boot examples use — and Spring's proxy
 * generator wraps the bean with a dispatch surface that routes annotated methods through
 * {@link eu.inqudium.spring.InqShieldAspect}.
 *
 * <p>The aspect is not woven into the bytecode and the call site does not build a proxy
 * manually: Spring builds the proxy when it instantiates the bean, provided the configuration
 * carries {@link org.springframework.context.annotation.EnableAspectJAutoProxy
 * @EnableAspectJAutoProxy} and an {@link eu.inqudium.spring.InqShieldAspect} bean is present.
 * Both conditions are met by {@link ResilienceConfig}.
 *
 * <p>Both call shapes appear here — synchronous methods that return a value directly, and
 * asynchronous methods that return a {@link CompletionStage}. {@link
 * eu.inqudium.spring.InqShieldAspect} reads each annotated method's return type once (and
 * caches the decision per {@link java.lang.reflect.Method}) and dispatches sync methods
 * through the {@code InqDecorator} chain and methods returning {@link CompletionStage}
 * through the {@code InqAsyncDecorator} chain — a single bulkhead instance therefore protects
 * both paths through one aspect invocation.
 *
 * <p>The bulkhead's name appears exactly once in this module's source — as the literal inside
 * {@link BulkheadConfig#BULKHEAD_NAME} — and is referenced from each annotation through the
 * static import below. No annotation in this file inlines the name as a string literal, so
 * renaming the bulkhead is a one-line change in {@link BulkheadConfig}.
 *
 * <h3>Self-invocation caveat</h3>
 * <p>Because the integration uses Spring AOP (proxy-based), calls between methods of this
 * class via {@code this.placeOrder(...)} bypass the proxy and the bulkhead. The example only
 * calls each method from outside the bean (from {@link Main} and from the tests), so the
 * caveat does not bite here; a production service that needs intra-bean calls would either
 * inject {@code @Lazy OrderService self} or split the protected methods into a separate bean.
 * The Javadoc on {@link eu.inqudium.spring.InqShieldAspect} discusses the workarounds.
 */
@Service
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
     * third call is rejected with {@link
     * eu.inqudium.core.element.bulkhead.InqBulkheadFullException}.
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
     * proxied method is invoked, and the bulkhead's {@code whenComplete} release callback
     * fires only when the stage produced here completes — which is when {@code release}
     * completes. Two concurrent invocations therefore consume both permits and a third async
     * call is rejected with {@link eu.inqudium.core.element.bulkhead.InqBulkheadFullException}
     * — the surface through which the rejection reaches the caller is documented in {@link
     * Main#demonstrateAsyncSaturation Main.demonstrateAsyncSaturation}.
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
