package eu.inqudium.bulkhead.integration.proxy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of {@link OrderService}. The implementation is plain Java with no
 * Inqudium type, no annotation, and no framework hook — exactly as in the function-based
 * example. Resilience is layered on by the
 * {@code InqAsyncProxyFactory.of(InqPipeline)} factory that wraps an instance of this class
 * behind a JDK dynamic proxy; this class itself does not know it is protected.
 */
public class DefaultOrderService implements OrderService {

    @Override
    public String placeOrder(String item) {
        return "ordered:" + item;
    }

    @Override
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

    @Override
    public CompletionStage<String> placeOrderAsync(String item) {
        return CompletableFuture.completedFuture("async-ordered:" + item);
    }

    @Override
    public CompletionStage<String> placeOrderHoldingAsync(CompletableFuture<Void> release) {
        return release.thenApply(ignored -> "async-released");
    }
}
