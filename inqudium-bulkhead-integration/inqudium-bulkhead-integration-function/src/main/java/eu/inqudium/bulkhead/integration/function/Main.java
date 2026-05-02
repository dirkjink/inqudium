package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * End-to-end demonstration of the function-based integration style.
 *
 * <p>The flow follows the natural structure of a function-based application:
 * <ol>
 *   <li>build an {@link InqRuntime} via the DSL,</li>
 *   <li>obtain the bulkhead handle from the runtime's imperative paradigm container,</li>
 *   <li>wrap the service's methods through {@link InqBulkhead#decorateFunction
 *       decorateFunction} (or its sibling {@code decorateSupplier}),</li>
 *   <li>call the wrapped function — the bulkhead acquires and releases its permit
 *       around the delegate transparently.</li>
 * </ol>
 *
 * <p>The class is verbose by design: it doubles as a tutorial. Comments describe what each
 * step demonstrates so a reader can map the code onto the function-based pattern's shape.
 *
 * <p>Two demos run in sequence against the <em>same</em> bulkhead instance: the synchronous
 * demo wraps the service's sync methods through {@code decorateFunction}/{@code
 * decorateSupplier}; the asynchronous demo wraps the service's async methods through
 * {@link InqBulkhead#decorateAsyncFunction decorateAsyncFunction}. One bulkhead, two paths
 * — that is the structural property the example pins.
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
            OrderService service = new OrderService();

            System.out.println("=== Sync demo ===");
            runSyncDemo(runtime, service);

            System.out.println("=== Async demo ===");
            runAsyncDemo(runtime, service);
        }
    }

    /**
     * Synchronous half of the demo: wrap {@link OrderService#placeOrder} through
     * {@link InqBulkhead#decorateFunction}, run three sample calls, then saturate the bulkhead
     * to observe rejection.
     */
    private static void runSyncDemo(InqRuntime runtime, OrderService service) {
        InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

        // The headline shape of the function-based pattern: the bulkhead wraps an
        // ordinary method reference into a Function. The wrapped function carries the
        // resilience behaviour; the service stays unaware of it.
        Function<String, String> protectedPlaceOrder =
                bulkhead.decorateFunction(service::placeOrder);

        System.out.println(protectedPlaceOrder.apply("Widget"));
        System.out.println(protectedPlaceOrder.apply("Sprocket"));
        System.out.println(protectedPlaceOrder.apply("Gadget"));

        demonstrateSyncSaturation(bulkhead, service);
    }

    /**
     * Asynchronous half of the demo: wrap {@link OrderService#placeOrderAsync} through
     * {@link InqBulkhead#decorateAsyncFunction}, run three sample calls, then saturate the
     * bulkhead with two stage holders to observe the synchronous rejection of a third call.
     *
     * <p>The async demo runs against the very same bulkhead instance the sync demo just
     * exercised. By the time control reaches this method, the sync demo's holders have
     * released and both permits are available again.
     */
    private static void runAsyncDemo(InqRuntime runtime, OrderService service) {
        InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

        // Async sibling of decorateFunction. The wrapped function returns a CompletionStage
        // on each call; the bulkhead's permit is acquired synchronously on the calling
        // thread and released asynchronously on stage completion.
        Function<String, CompletionStage<String>> protectedPlaceOrderAsync =
                bulkhead.decorateAsyncFunction(service::placeOrderAsync);

        // Each invocation returns an already-complete stage (the service's body synchronously
        // produces its value). join() reads the stage's value and confirms the permit has
        // returned to the pool by the time we move on.
        System.out.println(protectedPlaceOrderAsync.apply("Apple").toCompletableFuture().join());
        System.out.println(protectedPlaceOrderAsync.apply("Banana").toCompletableFuture().join());
        System.out.println(protectedPlaceOrderAsync.apply("Cherry").toCompletableFuture().join());

        demonstrateAsyncSaturation(bulkhead, service);
    }

    /**
     * Look up the example bulkhead by name and cast to the typed surface so that the
     * fully-type-safe {@link InqBulkhead#decorateFunction} factory becomes available. The
     * cast is safe because the runtime registry stores the same instance under both views.
     *
     * <p>The method is generic: callers can ask for the same instance under any
     * {@code <A, R>} witness — for example {@code <String, String>} for the sync demo or
     * {@code <CompletableFuture<Void>, String>} for the async-holding helper. Type erasure
     * means the underlying object is identical regardless of the witness.
     */
    @SuppressWarnings("unchecked")
    private static <A, R> InqBulkhead<A, R> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<A, R>) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    /**
     * Saturate the bulkhead with two virtual-thread holders, attempt a third call from the
     * main thread, and observe the rejection. The pattern mirrors the saturation test
     * fixture — the example shows the same shape an application developer would write to
     * verify rejection behaviour by hand.
     */
    private static void demonstrateSyncSaturation(InqBulkhead<String, String> bulkhead,
                                                  OrderService service) {
        CountDownLatch holderAcquired1 = new CountDownLatch(1);
        CountDownLatch holderAcquired2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder1 = Thread.startVirtualThread(() ->
                bulkhead.decorateSupplier(
                        () -> service.placeOrderHolding(holderAcquired1, release)).get());
        Thread holder2 = Thread.startVirtualThread(() ->
                bulkhead.decorateSupplier(
                        () -> service.placeOrderHolding(holderAcquired2, release)).get());

        try {
            holderAcquired1.await();
            holderAcquired2.await();

            try {
                bulkhead.decorateFunction(service::placeOrder).apply("Saturated");
                System.out.println("unexpected: third call returned");
            } catch (InqBulkheadFullException rejected) {
                System.out.println("third call rejected: " + rejected.getRejectionReason());
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
     * Async-path saturation. Two holders consume both permits by invoking the decorated
     * async-holding function with a release future that has not yet completed; a third
     * invocation attempts to acquire and is rejected synchronously — the
     * {@link InqBulkheadFullException} is a thrown exception, not a failed
     * {@link CompletionStage}, exactly as on the sync path. Releasing the future drains
     * both holder stages cleanly.
     *
     * <p>Two structural details that differ from the sync saturation:
     * <ul>
     *   <li>No "acquired" latch is needed. The async path acquires its permit synchronously
     *       on the calling thread, so by the time {@code decorateAsyncFunction(...).apply(...)}
     *       returns the permit is already held.</li>
     *   <li>The rejection point is the {@code apply(...)} call itself. The exception
     *       propagates from the layer's start-phase, before any stage has been constructed
     *       to wrap it in.</li>
     * </ul>
     */
    private static void demonstrateAsyncSaturation(InqBulkhead<String, String> bulkhead,
                                                   OrderService service) {
        // The holding async function takes a CompletableFuture<Void> and returns a
        // CompletionStage<String>. The bulkhead instance is identical to the one used for
        // the sync demo; only the type witness differs. See orderBulkhead's contract for
        // why the cast is safe under erasure.
        InqBulkhead<CompletableFuture<Void>, String> holdingBulkhead =
                orderBulkheadHolding(bulkhead);
        Function<CompletableFuture<Void>, CompletionStage<String>> protectedHolding =
                holdingBulkhead.decorateAsyncFunction(service::placeOrderHoldingAsync);

        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletionStage<String> holder1 = protectedHolding.apply(release);
        CompletionStage<String> holder2 = protectedHolding.apply(release);

        try {
            // When — both permits are now held by stages waiting on `release`. A third
            // async call attempts to acquire and fails fast with a synchronous throw.
            Function<String, CompletionStage<String>> protectedPlaceOrderAsync =
                    bulkhead.decorateAsyncFunction(service::placeOrderAsync);
            try {
                protectedPlaceOrderAsync.apply("Saturated");
                System.out.println("unexpected: third async call returned a stage");
            } catch (InqBulkheadFullException rejected) {
                System.out.println("third async call rejected: "
                        + rejected.getRejectionReason() + " (synchronously)");
            }
        } finally {
            release.complete(null);
            holder1.toCompletableFuture().join();
            holder2.toCompletableFuture().join();
        }
    }

    /**
     * Cast helper that exposes the same bulkhead instance under the holding-async type
     * witness {@code <CompletableFuture<Void>, String>}. Two views of one component, made
     * possible by erasure.
     */
    @SuppressWarnings("unchecked")
    private static InqBulkhead<CompletableFuture<Void>, String> orderBulkheadHolding(
            InqBulkhead<String, String> sameInstance) {
        return (InqBulkhead<CompletableFuture<Void>, String>) (InqBulkhead<?, ?>) sameInstance;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
