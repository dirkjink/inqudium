package eu.inqudium.bulkhead.integration.proxy;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import eu.inqudium.imperative.core.pipeline.InqAsyncProxyFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * End-to-end demonstration of the proxy-based integration style.
 *
 * <p>The flow follows the natural structure of a proxy-based application:
 * <ol>
 *   <li>build an {@link InqRuntime} via the DSL,</li>
 *   <li>obtain the bulkhead handle from the runtime's imperative paradigm container,</li>
 *   <li>compose an {@link InqPipeline} containing the bulkhead and lift it through
 *       {@link InqAsyncProxyFactory#of(InqPipeline)},</li>
 *   <li>call {@code factory.protect(OrderService.class, new DefaultOrderService())} to
 *       obtain a proxy that implements {@link OrderService}; method calls on the proxy are
 *       routed transparently through the pipeline.</li>
 * </ol>
 *
 * <p>The decisive structural difference from the function-based example: here the bulkhead's
 * placement is invisible at every call site. The {@code service} reference held by the demo
 * code is an {@link OrderService}, indistinguishable from a plain implementation; callers do
 * not see {@code decorateFunction(...)} or {@code decorateAsyncFunction(...)} sprinkled
 * around the call site. The {@link InqAsyncProxyFactory#of(InqPipeline)} factory wires two
 * dispatch extensions behind one proxy — the async extension claims methods that return
 * {@link CompletionStage} via its {@code canHandle}, the sync catch-all extension claims
 * everything else — so a single bulkhead instance protects both shapes through one proxy.
 */
public final class Main {

    private Main() {
        // entry point only
    }

    public static void main(String[] args) {
        // The runtime owns every Inqudium component for the duration of the application.
        // Try-with-resources guarantees a clean shutdown — paradigm containers close,
        // strategies tear down, the runtime-scoped event publisher releases.
        try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
            InqBulkhead<Object, Object> bulkhead = orderBulkhead(runtime);

            // Headline shape of the proxy-based pattern: build a pipeline holding the
            // bulkhead, lift it through the hybrid factory, and protect the implementation
            // behind a JDK dynamic proxy. The variable below is typed as OrderService —
            // the proxy is transparent: it can be passed anywhere an OrderService is
            // expected, and the caller does not need to know the service is decorated.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(bulkhead)
                    .build();
            OrderService service = InqAsyncProxyFactory.of(pipeline)
                    .protect(OrderService.class, new DefaultOrderService());

            System.out.println("=== Sync demo ===");
            runSyncDemo(service);

            System.out.println("=== Async demo ===");
            runAsyncDemo(service);
        }
    }

    /**
     * Synchronous half of the demo: call the proxied service's sync methods directly. The
     * proxy routes each invocation through the sync pipeline chain — the bulkhead acquires
     * a permit, the implementation runs, the permit is released in the chain's finally
     * block.
     */
    private static void runSyncDemo(OrderService service) {
        // Plain method calls on a plain OrderService reference. Compare to the
        // function-based example, where every call site holds a separately-built
        // Function<String, String> — the proxy makes that wrapping invisible.
        System.out.println(service.placeOrder("Widget"));
        System.out.println(service.placeOrder("Sprocket"));
        System.out.println(service.placeOrder("Gadget"));

        demonstrateSyncSaturation(service);
    }

    /**
     * Asynchronous half of the demo: call the proxied service's async methods directly. The
     * proxy reads {@link OrderService#placeOrderAsync}'s {@link CompletionStage} return type
     * and routes the invocation through the async pipeline chain; the same bulkhead instance
     * the sync demo just exercised serves the async path through one terminal.
     */
    private static void runAsyncDemo(OrderService service) {
        // Each invocation returns an already-complete stage (the implementation's body
        // synchronously produces its value). join() reads the stage's value and confirms
        // the permit has returned to the pool by the time we move on.
        System.out.println(service.placeOrderAsync("Apple").toCompletableFuture().join());
        System.out.println(service.placeOrderAsync("Banana").toCompletableFuture().join());
        System.out.println(service.placeOrderAsync("Cherry").toCompletableFuture().join());

        demonstrateAsyncSaturation(service);
    }

    /**
     * Look up the example bulkhead by name and cast to the typed surface so it can be fed to
     * {@link InqPipeline.Builder#shield(eu.inqudium.core.element.InqElement)}. The cast is
     * safe because the runtime registry stores the same instance under both views.
     *
     * <p>The proxy style does not need a per-call-site type witness — the proxy dispatches
     * by method return type. A single {@code <Object, Object>} witness is enough to wire the
     * bulkhead into the pipeline.
     */
    @SuppressWarnings("unchecked")
    private static InqBulkhead<Object, Object> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<Object, Object>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    /**
     * Saturate the bulkhead with two virtual-thread holders, attempt a third call from the
     * main thread, and observe the rejection. The pattern mirrors the function-based
     * example's saturation flow — the surface is the proxied {@link OrderService} reference
     * rather than a hand-wrapped {@code Function}, but the rejection contract is identical.
     */
    private static void demonstrateSyncSaturation(OrderService service) {
        CountDownLatch holderAcquired1 = new CountDownLatch(1);
        CountDownLatch holderAcquired2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder1 = Thread.startVirtualThread(() ->
                service.placeOrderHolding(holderAcquired1, release));
        Thread holder2 = Thread.startVirtualThread(() ->
                service.placeOrderHolding(holderAcquired2, release));

        try {
            holderAcquired1.await();
            holderAcquired2.await();

            try {
                service.placeOrder("Saturated");
                System.out.println("unexpected: third sync call returned");
            } catch (InqBulkheadFullException rejected) {
                System.out.println("third sync call rejected: " + rejected.getRejectionReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
            joinQuietly(holder1);
            joinQuietly(holder2);
        }
    }

    /**
     * Async-path saturation. Two holders consume both permits by invoking the proxied
     * async-holding method with a release future that has not yet completed; a third
     * invocation is rejected with {@link InqBulkheadFullException}.
     *
     * <p>Channel difference vs. the function-based example: the hybrid proxy's async
     * dispatch path implements a documented <em>uniform error channel</em> — any exception
     * thrown synchronously by a pipeline element (including the bulkhead's
     * {@code InqBulkheadFullException}) is captured into a failed {@link CompletionStage}
     * rather than thrown to the caller. The rejection itself is identical; only the surface
     * through which it reaches the caller is different. The function-based decoration path
     * lets the throw propagate; the proxy normalizes it for callers that always expect a
     * stage on async-typed methods.
     *
     * <p>One structural detail that differs from the sync saturation:
     * <ul>
     *   <li>No "acquired" latch is needed. The async path acquires its permit synchronously
     *       on the calling thread, so by the time the proxied method returns its still-
     *       pending stage the permit is already held.</li>
     * </ul>
     */
    private static void demonstrateAsyncSaturation(OrderService service) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletionStage<String> holder1 = service.placeOrderHoldingAsync(release);
        CompletionStage<String> holder2 = service.placeOrderHoldingAsync(release);

        try {
            CompletionStage<String> third = service.placeOrderAsync("Saturated");
            try {
                third.toCompletableFuture().join();
                System.out.println("unexpected: third async call produced a value");
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof InqBulkheadFullException rejected) {
                    System.out.println("third async call rejected: "
                            + rejected.getRejectionReason() + " (failed stage)");
                } else {
                    System.out.println("unexpected cause: " + cause);
                }
            }
        } finally {
            release.complete(null);
            holder1.toCompletableFuture().join();
            holder2.toCompletableFuture().join();
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
