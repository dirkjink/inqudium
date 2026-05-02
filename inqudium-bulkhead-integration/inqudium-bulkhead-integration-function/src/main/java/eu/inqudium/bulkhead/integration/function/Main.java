package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.event.ComponentBecameHotEvent;
import eu.inqudium.config.event.RuntimeComponentAddedEvent;
import eu.inqudium.config.event.RuntimeComponentPatchedEvent;
import eu.inqudium.config.event.RuntimeComponentRemovedEvent;
import eu.inqudium.config.event.RuntimeComponentVetoedEvent;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * End-to-end demonstration of the function-based integration style with practice-grade logging
 * and a runtime-configuration-change demo.
 *
 * <p>The flow follows the natural structure of a function-based application that takes
 * operability seriously:
 * <ol>
 *   <li>build an {@link InqRuntime} via {@link BulkheadConfig#newRuntime()},</li>
 *   <li>install a bootstrap-side lifecycle-event subscriber on the runtime's event publisher
 *       so topology changes (added / patched / removed / vetoed / became-hot) appear in the
 *       log,</li>
 *   <li>build {@link OrderService} — its own constructor pulls the bulkhead, wraps its
 *       methods, logs the topology, and subscribes to per-component bulkhead events,</li>
 *   <li>build {@link AdminService} — the operator surface for the runtime patch demo,</li>
 *   <li>run the three-phase demo: normal operation → sell promotion (patched) → after
 *       promotion (patched back).</li>
 * </ol>
 *
 * <p>The class is verbose by design: it doubles as a tutorial. The headers printed to stdout
 * complement the SLF4J log output — together they make the three-phase shape unambiguous.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
        // entry point only
    }

    public static void main(String[] args) {
        try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
            subscribeLifecycleEvents(runtime.general().eventPublisher());

            OrderService service = new OrderService(runtime);
            AdminService admin = new AdminService(runtime);

            phase1NormalOperation(runtime, service);
            phase2SellPromotion(runtime, service, admin);
            phase3AfterPromotion(runtime, service, admin);
        }
    }

    /**
     * Phase 1: balanced/2 permits. Two normal calls succeed; saturation with two holders +
     * one extra rejects the third call synchronously with
     * {@link InqBulkheadFullException}.
     */
    private static void phase1NormalOperation(InqRuntime runtime, OrderService service) {
        System.out.println();
        System.out.println("=== Phase 1: Normal operation (balanced/2) ===");

        System.out.println(service.placeOrder("Widget"));
        System.out.println(service.placeOrder("Sprocket"));
        demonstrateSaturation(runtime, service, 2);
    }

    /**
     * Phase 2: patch to permissive/50, then demonstrate that 5 concurrent holders all succeed
     * — none rejected. Releases all holders cleanly before returning.
     */
    private static void phase2SellPromotion(InqRuntime runtime, OrderService service,
                                            AdminService admin) {
        System.out.println();
        System.out.println("=== Phase 2: Sell promotion (permissive/50) ===");

        admin.startSellPromotion();
        runFiveConcurrentHolders(service);
    }

    /**
     * Phase 3: patch back to balanced/2 and re-run the saturation pattern from phase 1. The
     * third call is rejected again — proof the patch reversed cleanly.
     */
    private static void phase3AfterPromotion(InqRuntime runtime, OrderService service,
                                             AdminService admin) {
        System.out.println();
        System.out.println("=== Phase 3: After promotion (balanced/2) ===");

        admin.endSellPromotion();
        demonstrateSaturation(runtime, service, 2);
    }

    /**
     * Subscribe handlers for the five runtime-lifecycle event types. Levels follow sub-step
     * 6.C decision&nbsp;5: INFO for the four "normal" lifecycle events, WARN for vetoes
     * (a policy rejection is louder than a routine topology change).
     */
    private static void subscribeLifecycleEvents(InqEventPublisher publisher) {
        publisher.onEvent(ComponentBecameHotEvent.class, e ->
                LOG.info("Component became hot: '{}' ({})",
                        e.getElementName(), e.getElementType()));
        publisher.onEvent(RuntimeComponentAddedEvent.class, e ->
                LOG.info("Runtime component added: '{}' ({})",
                        e.getElementName(), e.getElementType()));
        publisher.onEvent(RuntimeComponentPatchedEvent.class, e ->
                LOG.info("Runtime component patched: '{}' ({}) — touched {}",
                        e.getElementName(), e.getElementType(), e.touchedFields()));
        publisher.onEvent(RuntimeComponentRemovedEvent.class, e ->
                LOG.info("Runtime component removed: '{}' ({})",
                        e.getElementName(), e.getElementType()));
        publisher.onEvent(RuntimeComponentVetoedEvent.class, e ->
                LOG.warn("Runtime component vetoed: '{}' ({}) — finding {}",
                        e.getElementName(), e.getElementType(), e.vetoFinding()));
    }

    /**
     * Saturate the bulkhead with {@code limit} virtual-thread holders, attempt one extra call
     * from the main thread, and observe synchronous rejection. Used by phases 1 and 3.
     */
    private static void demonstrateSaturation(InqRuntime runtime, OrderService service,
                                              int limit) {
        InqBulkhead<?, ?> bulkhead = bulkheadOf(runtime);

        CountDownLatch[] acquired = new CountDownLatch[limit];
        CountDownLatch release = new CountDownLatch(1);
        Thread[] holders = new Thread[limit];
        for (int i = 0; i < limit; i++) {
            acquired[i] = new CountDownLatch(1);
            CountDownLatch acq = acquired[i];
            holders[i] = Thread.startVirtualThread(() -> service.placeOrderHolding(acq, release));
        }

        try {
            for (CountDownLatch a : acquired) {
                a.await();
            }

            System.out.println("available permits while saturated: " + bulkhead.availablePermits());
            try {
                service.placeOrder("Saturated");
                System.out.println("unexpected: extra call returned");
            } catch (InqBulkheadFullException rejected) {
                System.out.println("extra call rejected: " + rejected.getRejectionReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
            for (Thread t : holders) {
                joinQuietly(t);
            }
        }
    }

    /**
     * Five concurrent async holders — exercises the post-patch capacity (50 permits). All five
     * succeed; no rejection. The async holding shape needs no "acquired" latch because the
     * bulkhead acquires synchronously on the calling thread.
     */
    private static void runFiveConcurrentHolders(OrderService service) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletionStage<String>[] holders = new CompletionStage[5];
        for (int i = 0; i < 5; i++) {
            holders[i] = service.placeOrderHoldingAsync(release);
        }

        System.out.println("five concurrent async holders accepted under permissive/50");

        release.complete(null);
        for (CompletionStage<String> h : holders) {
            System.out.println(h.toCompletableFuture().join());
        }
    }

    private static InqBulkhead<?, ?> bulkheadOf(InqRuntime runtime) {
        return (InqBulkhead<?, ?>) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
