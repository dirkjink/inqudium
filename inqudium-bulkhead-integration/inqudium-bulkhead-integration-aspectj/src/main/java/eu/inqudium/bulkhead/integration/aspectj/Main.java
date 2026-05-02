package eu.inqudium.bulkhead.integration.aspectj;

import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import org.aspectj.lang.Aspects;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * End-to-end demonstration of the AspectJ-native integration style.
 *
 * <p>The flow follows the natural structure of an AspectJ-native application:
 * <ol>
 *   <li>build a plain {@link OrderService} — no Inqudium type, no decoration, no proxy
 *       wrapper at the call site. Methods on {@code OrderService} are annotated with
 *       {@link eu.inqudium.annotation.InqBulkhead @InqBulkhead("orderBh")} — the same
 *       annotation the Spring Boot integration uses,</li>
 *   <li>call its methods directly — the compile-time-woven {@link OrderBulkheadAspect}
 *       binds the {@code @InqBulkhead} annotation, reads its {@code value()}, resolves the
 *       named bulkhead from the runtime, and routes the join point through that bulkhead
 *       transparently,</li>
 *   <li>let AspectJ construct the aspect singleton on first use; the aspect's constructor
 *       builds the {@link eu.inqudium.config.runtime.InqRuntime}, and the per-name terminal
 *       cache populates lazily on the first invocation for each bulkhead name.</li>
 * </ol>
 *
 * <p>The decisive structural difference from the function-based example: here the bulkhead's
 * placement is invisible at every call site. The {@code service} reference in the demo code
 * is an {@link OrderService}, indistinguishable from a plain implementation; callers do not
 * see {@code decorateFunction(...)} or {@code decorateAsyncFunction(...)} sprinkled around
 * the call site, and they do not even build a proxy. The bytecode of every annotated method
 * has been rewritten at build time to enter the aspect first.
 *
 * <p>The aspect's runtime is not closed explicitly — the AspectJ singleton lives for the
 * JVM's lifetime, so its runtime does too. For an example/demo this is acceptable; in a real
 * application the natural pattern is to manage the runtime through a DI container that owns
 * the close call (Spring's {@code @Bean(destroyMethod = "close")} is a typical fit) — see the
 * Spring Framework and Spring Boot example modules.
 */
public final class Main {

    private Main() {
        // entry point only
    }

    public static void main(String[] args) {
        // The aspect is woven into OrderService at build time. By the time we reach the first
        // call below, ajc has already rewritten the bytecode of every @InqBulkhead-annotated
        // method to enter OrderBulkheadAspect.aroundInqBulkhead first; that advice reads the
        // annotation's value() ("orderBh") and dispatches through the matching bulkhead.
        OrderService service = new OrderService();

        System.out.println("=== Sync demo ===");
        runSyncDemo(service);

        System.out.println("=== Async demo ===");
        runAsyncDemo(service);
    }

    /**
     * Synchronous half of the demo: call the woven service's sync methods directly. The
     * aspect routes each invocation through the sync chain — the bulkhead acquires a permit,
     * the original method body runs, the permit is released in the chain's finally block.
     */
    private static void runSyncDemo(OrderService service) {
        // Plain method calls on a plain OrderService reference. Compare to the function-based
        // example, where every call site holds a separately-built Function<String, String> —
        // and to the proxy-based example, where the call site at least builds a proxy. The
        // aspect makes both wrappings invisible: the call site looks like ordinary Java.
        System.out.println(service.placeOrder("Widget"));
        System.out.println(service.placeOrder("Sprocket"));
        System.out.println(service.placeOrder("Gadget"));

        demonstrateSyncSaturation(service);
    }

    /**
     * Asynchronous half of the demo: call the woven service's async methods directly. The
     * aspect reads the {@link CompletionStage} return type and routes the invocation through
     * the async chain; the same bulkhead instance the sync demo just exercised serves the
     * async path through one terminal.
     */
    private static void runAsyncDemo(OrderService service) {
        // Each invocation returns an already-complete stage (the original method body
        // synchronously produces its value). join() reads the stage's value and confirms the
        // permit has returned to the pool by the time we move on.
        System.out.println(service.placeOrderAsync("Apple").toCompletableFuture().join());
        System.out.println(service.placeOrderAsync("Banana").toCompletableFuture().join());
        System.out.println(service.placeOrderAsync("Cherry").toCompletableFuture().join());

        demonstrateAsyncSaturation(service);
    }

    /**
     * Saturate the bulkhead with two virtual-thread holders, attempt a third call from the
     * main thread, and observe the rejection. The pattern mirrors the function-based and
     * proxy-based examples' saturation flows — the surface is the woven {@link OrderService}
     * reference, but the rejection contract on the sync path is identical: a synchronous
     * throw of {@link InqBulkheadFullException} from the bulkhead's start phase.
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
     * Async-path saturation. Two holders consume both permits by invoking the woven
     * async-holding method with a release future that has not yet completed; a third
     * invocation is rejected with {@link InqBulkheadFullException}.
     *
     * <p>Channel difference vs. the function-based example: {@link
     * eu.inqudium.aspect.pipeline.HybridAspectPipelineTerminal} implements a documented
     * <em>uniform error channel</em> on the async path — any exception thrown synchronously
     * by a pipeline element (including the bulkhead's synchronous {@code
     * InqBulkheadFullException}) is captured into a failed {@link CompletionStage} rather
     * than thrown to the caller. The behaviour matches what the proxy-based example
     * demonstrates through {@code InqAsyncProxyFactory.of(InqPipeline)}; the rejection
     * itself is identical, only the surface through which it reaches the caller is
     * different. The function-based decoration path lets the throw propagate; the aspect
     * normalizes it for callers that always expect a stage on async-typed methods.
     *
     * <p>One structural detail that differs from the sync saturation:
     * <ul>
     *   <li>No "acquired" latch is needed. The async path acquires its permit synchronously
     *       on the calling thread, so by the time the woven method returns its still-pending
     *       stage the permit is already held.</li>
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

        // Reach the aspect singleton via the AspectJ-generated aspectOf() accessor and
        // confirm the bulkhead has released both permits before the demo exits. The accessor
        // is what every CTW user uses to talk to a woven aspect from outside the join points.
        OrderBulkheadAspect aspect = Aspects.aspectOf(OrderBulkheadAspect.class);
        System.out.println("permits after async saturation: "
                + aspect.bulkhead().availablePermits());
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
