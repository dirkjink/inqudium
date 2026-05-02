package eu.inqudium.bulkhead.integration.springboot;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * End-to-end demonstration of the Spring Boot integration style.
 *
 * <p>The class is the entire wiring of the example: {@link SpringBootApplication
 * @SpringBootApplication} on a single class that holds the {@link Bean @Bean} methods and the
 * {@link #main(String[]) main} method. This is the most common 2026 Spring Boot shape — a
 * reader sees the application's whole topology on one screen.
 *
 * <h3>What auto-configuration buys</h3>
 * <p>Three things happen automatically that the plain-Spring example performs by hand:
 * <ol>
 *   <li>{@link eu.inqudium.spring.boot.InqAutoConfiguration} discovers every {@link InqElement}
 *       bean in the application context and indexes it by {@link InqElement#name()} into an
 *       {@link eu.inqudium.core.element.InqElementRegistry InqElementRegistry} bean. The
 *       plain-Spring example builds the registry by hand inside its {@code ResilienceConfig}
 *       and registers each element by name explicitly.</li>
 *   <li>{@link eu.inqudium.spring.boot.InqAutoConfiguration} also registers an
 *       {@link eu.inqudium.spring.InqShieldAspect InqShieldAspect} bean fed the auto-built
 *       registry. The plain-Spring example registers the aspect explicitly.</li>
 *   <li>{@code spring-boot-starter-aspectj} on the classpath makes Boot add
 *       {@code @EnableAspectJAutoProxy} implicitly, so the aspect's {@code @Around} advice
 *       fires without any opt-in on this class. The plain-Spring example carries
 *       {@code @EnableAspectJAutoProxy} on its configuration class.</li>
 * </ol>
 *
 * <p>The application's contribution shrinks correspondingly: declare the {@link InqRuntime}
 * bean (the source of truth for the bulkhead handle), and expose the bulkhead handle as an
 * {@link InqElement} bean so auto-configuration sees it. Both are below.
 *
 * <h3>Lifecycle</h3>
 * <p>The {@link InqRuntime} bean carries {@code destroyMethod = "close"}, so Spring's
 * {@link org.springframework.beans.factory.DisposableBean DisposableBean} handling closes the
 * runtime when the application context shuts down — paradigm containers tear down, strategies
 * release their locks, and the runtime-scoped event publisher releases. The {@link
 * #main(String[]) main} method calls {@link ConfigurableApplicationContext#close()
 * context.close()} explicitly so the demo terminates cleanly.
 *
 * <p>Compared with the AspectJ-native example: there is no compile-time weaving and no AspectJ
 * singleton owning a runtime for the JVM's lifetime. The runtime is a Spring bean owned by the
 * application context.
 */
@SpringBootApplication
public class OrderServiceApplication {

    /**
     * The Inqudium runtime, owned by Spring Boot. The {@code destroyMethod = "close"}
     * attribute ensures the runtime is closed when the application context shuts down.
     */
    @Bean(destroyMethod = "close")
    public InqRuntime inqRuntime() {
        return BulkheadConfig.newRuntime();
    }

    /**
     * Expose the bulkhead handle as an {@link InqElement} bean. Spring Boot's
     * {@link eu.inqudium.spring.boot.InqAutoConfiguration} discovers all {@link InqElement}
     * beans in the application context, populates the
     * {@link eu.inqudium.core.element.InqElementRegistry InqElementRegistry} with them, and
     * registers an {@link eu.inqudium.spring.InqShieldAspect InqShieldAspect} that resolves
     * names from that registry — all without any {@code @EnableAspectJAutoProxy} or manual
     * registry setup on this class. The plain-Spring example performs every step of that
     * setup by hand on its configuration class; pedagogically, the contrast is the point.
     *
     * <p>The bean's name ({@code orderBh}, taken from {@link BulkheadConfig#BULKHEAD_NAME})
     * matches the handle's {@link InqElement#name()} which is the {@code @InqBulkhead("...")}
     * key on every method of {@link OrderService} — so the registry lookup and the annotation
     * resolution agree on a single source of truth.
     */
    @Bean
    public InqElement orderBh(InqRuntime runtime) {
        return (InqElement) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    public static void main(String[] args) {
        // SpringApplication.run starts the auto-configured context. From this point on every
        // bean — the runtime, the bulkhead handle, the auto-discovered registry, the
        // auto-registered aspect, and the component-scanned OrderService — is live and the
        // OrderService reference held below is already a Spring AOP proxy.
        try (ConfigurableApplicationContext context =
                     SpringApplication.run(OrderServiceApplication.class, args)) {

            OrderService service = context.getBean(OrderService.class);
            InqRuntime runtime = context.getBean(InqRuntime.class);

            System.out.println("=== Sync demo ===");
            runSyncDemo(service);

            System.out.println("=== Async demo ===");
            runAsyncDemo(service, runtime);
        }
        // try-with-resources closes the context, which triggers the runtime bean's
        // destroyMethod = "close" — paradigm containers tear down cleanly.
    }

    /**
     * Synchronous half of the demo: call the proxied service's sync methods directly.
     * Spring's AOP proxy routes each invocation through {@link
     * eu.inqudium.spring.InqShieldAspect}'s sync chain — the bulkhead acquires a permit, the
     * original method body runs, the permit is released in the chain's finally block.
     */
    private static void runSyncDemo(OrderService service) {
        // Plain method calls on a plain OrderService reference. The proxy and the aspect are
        // invisible at the call site: this looks like ordinary Java, exactly as it does in
        // the plain-Spring example, and Spring built the proxy at bean-instantiation time.
        System.out.println(service.placeOrder("Widget"));
        System.out.println(service.placeOrder("Sprocket"));
        System.out.println(service.placeOrder("Gadget"));

        demonstrateSyncSaturation(service);
    }

    /**
     * Asynchronous half of the demo: call the proxied service's async methods directly.
     * {@link eu.inqudium.spring.InqShieldAspect} reads the {@link CompletionStage} return type
     * once and routes the invocation through the async chain; the same bulkhead instance the
     * sync demo just exercised serves the async path through one cached pipeline.
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
     * proxy-based, AspectJ-native, and plain-Spring examples' saturation flows — the surface
     * is the proxied {@link OrderService} reference, but the rejection contract on the sync
     * path is identical: a synchronous throw of {@link InqBulkheadFullException} from the
     * bulkhead's start phase, propagated unwrapped through Spring AOP.
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
     * Async-path saturation. Two holders consume both permits by invoking the proxied async-
     * holding method with a release future that has not yet completed; a third invocation is
     * rejected with {@link InqBulkheadFullException}.
     *
     * <p>Channel detail: {@link eu.inqudium.spring.InqShieldAspect}'s async dispatch wraps any
     * synchronous throw on the async path into a failed {@link CompletionStage} via {@link
     * CompletableFuture#failedFuture(Throwable)} rather than letting it propagate to the
     * caller. The behaviour matches the proxy-based, AspectJ-native, and plain-Spring
     * examples' uniform-error-channel policy; the rejection itself is identical, only the
     * surface through which it reaches the caller is different from the function-based
     * decoration path, which lets the throw propagate.
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
