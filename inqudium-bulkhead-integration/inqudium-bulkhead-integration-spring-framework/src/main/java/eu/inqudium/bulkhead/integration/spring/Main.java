package eu.inqudium.bulkhead.integration.spring;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * End-to-end demonstration of the plain-Spring (no Boot) integration style.
 *
 * <p>The flow follows the natural structure of a non-Boot Spring application:
 * <ol>
 *   <li>build an {@link AnnotationConfigApplicationContext} from {@link ResilienceConfig} —
 *       no {@code SpringApplication.run(...)}, no auto-configuration, no
 *       {@code application.properties}; everything is wired by hand,</li>
 *   <li>obtain the proxied {@link OrderService} bean from the context — Spring's AOP machinery
 *       has already wrapped it on instantiation because {@link ResilienceConfig} carries
 *       {@link org.springframework.context.annotation.EnableAspectJAutoProxy} and exposes an
 *       {@link eu.inqudium.spring.InqShieldAspect} bean,</li>
 *   <li>call its methods directly — the proxy reads the {@code @InqBulkhead} annotation,
 *       resolves the bulkhead through the {@link
 *       eu.inqudium.core.element.InqElementRegistry}, and routes the invocation through the
 *       pipeline.</li>
 * </ol>
 *
 * <p>The decisive structural difference from the function-based example: here the bulkhead's
 * placement is invisible at every call site. The {@code service} reference held by the demo
 * code is an {@link OrderService}, indistinguishable from a plain implementation; callers do
 * not see {@code decorateFunction(...)} or {@code decorateAsyncFunction(...)} sprinkled
 * around the call site, and they do not even build a proxy. Spring built it when it
 * instantiated the bean.
 *
 * <p>The structural difference from the AspectJ-native example: there is no compile-time
 * weaving and no AspectJ singleton owning a runtime for the JVM's lifetime. The runtime is a
 * Spring bean owned by the application context; the {@code try-with-resources} on the
 * context closes the runtime via the bean's {@code destroyMethod = "close"}.
 *
 * <p>The structural difference from the Spring Boot example (5.F): no auto-configuration —
 * the {@link eu.inqudium.core.element.InqElementRegistry} is populated by hand from the
 * runtime's bulkhead handle inside {@link ResilienceConfig}, and the {@link
 * eu.inqudium.spring.InqShieldAspect} is registered explicitly as a bean. Pedagogically that
 * is the point: the example makes Spring Boot's auto-configuration value visible by showing
 * what would otherwise be manual.
 */
public final class Main {

    private Main() {
        // entry point only
    }

    public static void main(String[] args) {
        // The application context owns every Spring-managed bean — including the
        // InqRuntime — for the duration of the application. Try-with-resources guarantees a
        // clean shutdown: Spring iterates registered destroyMethods, which calls
        // runtime.close() and tears down the Inqudium components.
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ResilienceConfig.class)) {

            OrderService service = context.getBean(OrderService.class);
            InqRuntime runtime = context.getBean(InqRuntime.class);

            System.out.println("=== Sync demo ===");
            runSyncDemo(service);

            System.out.println("=== Async demo ===");
            runAsyncDemo(service, runtime);
        }
    }

    /**
     * Synchronous half of the demo: call the proxied service's sync methods directly. Spring's
     * AOP proxy routes each invocation through {@link eu.inqudium.spring.InqShieldAspect}'s
     * sync chain — the bulkhead acquires a permit, the original method body runs, the permit
     * is released in the chain's finally block.
     */
    private static void runSyncDemo(OrderService service) {
        // Plain method calls on a plain OrderService reference. Compare to the function-based
        // example, where every call site holds a separately-built Function<String, String> —
        // and to the proxy-based example, where the call site at least builds the proxy
        // itself. The Spring AOP proxy makes both wrappings invisible: the call site looks
        // like ordinary Java, and Spring built the proxy at bean-instantiation time.
        System.out.println(service.placeOrder("Widget"));
        System.out.println(service.placeOrder("Sprocket"));
        System.out.println(service.placeOrder("Gadget"));

        demonstrateSyncSaturation(service);
    }

    /**
     * Asynchronous half of the demo: call the proxied service's async methods directly. The
     * aspect reads the {@link CompletionStage} return type once and routes the invocation
     * through the async chain; the same bulkhead instance the sync demo just exercised serves
     * the async path through one cached pipeline.
     */
    private static void runAsyncDemo(OrderService service, InqRuntime runtime) {
        // Each invocation returns an already-complete stage (the original method body
        // synchronously produces its value). join() reads the stage's value and confirms the
        // permit has returned to the pool by the time we move on.
        System.out.println(service.placeOrderAsync("Apple").toCompletableFuture().join());
        System.out.println(service.placeOrderAsync("Banana").toCompletableFuture().join());
        System.out.println(service.placeOrderAsync("Cherry").toCompletableFuture().join());

        demonstrateAsyncSaturation(service, runtime);
    }

    /**
     * Saturate the bulkhead with two virtual-thread holders, attempt a third call from the
     * main thread, and observe the rejection. The pattern mirrors the function-based,
     * proxy-based, and AspectJ-native examples' saturation flows — the surface is the
     * proxied {@link OrderService} reference, but the rejection contract on the sync path is
     * identical: a synchronous throw of {@link InqBulkheadFullException} from the bulkhead's
     * start phase, propagated unwrapped through Spring AOP.
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
     * <p>Channel detail: {@link eu.inqudium.spring.InqShieldAspect}'s async dispatch wraps any
     * synchronous throw on the async path into a failed {@link CompletionStage} via {@link
     * CompletableFuture#failedFuture(Throwable)} rather than letting it propagate to the
     * caller. The behaviour matches the proxy-based and AspectJ-native examples'
     * uniform-error-channel policy; the rejection itself is identical, only the surface
     * through which it reaches the caller is different from the function-based decoration
     * path, which lets the throw propagate.
     *
     * <p>One structural detail that differs from the sync saturation:
     * <ul>
     *   <li>No "acquired" latch is needed. The async path acquires its permit synchronously
     *       on the calling thread, so by the time the proxied method returns its still-
     *       pending stage the permit is already held.</li>
     * </ul>
     *
     * <p>After the holders release, the runtime's bulkhead handle is read directly to confirm
     * both permits have returned to the pool — proving the aspect's release callback fired on
     * every settled stage.
     */
    private static void demonstrateAsyncSaturation(OrderService service, InqRuntime runtime) {
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

        // Reach the bulkhead through the runtime bean and confirm both permits have returned
        // to the pool before the demo exits. The runtime is the natural source for this
        // information in the Spring style — InqShieldAspect resolves elements from the
        // registry, which holds the same instance the runtime exposes.
        @SuppressWarnings("unchecked")
        InqBulkhead<Object, Object> bulkhead = (InqBulkhead<Object, Object>)
                runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
        System.out.println("permits after async saturation: " + bulkhead.availablePermits());
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
