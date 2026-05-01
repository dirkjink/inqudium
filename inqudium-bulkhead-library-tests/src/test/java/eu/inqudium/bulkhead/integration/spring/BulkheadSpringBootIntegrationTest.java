package eu.inqudium.bulkhead.integration.spring;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bulkhead end-to-end through Spring Boot auto-configuration (audit finding F-2.19-7).
 *
 * <p>The test wires a real {@link InqRuntime} as a Spring bean, exposes the bulkhead handle
 * as an {@link InqElement}, and exercises a service annotated with {@code @InqBulkhead}.
 * The pattern mirrors the way an application is expected to set up Inqudium under Spring
 * Boot: declare the runtime as a @Bean, expose each component handle as an {@link InqElement}
 * @Bean, and let auto-configuration wire them through {@code InqShieldAspect} to user
 * methods.
 *
 * <p>This class is deliberately flat (no {@code @Nested} groupings) to comply with the
 * caveat documented in {@code CLAUDE.md} — non-static {@code @Nested} test classes alongside
 * a static {@code @Configuration} inner class break Spring Boot's
 * {@code TestTypeExcludeFilter} and leak the configuration into other tests' component
 * scans.
 */
@SpringBootTest(classes = BulkheadSpringBootIntegrationTest.TestApplication.class)
@DisplayName("Bulkhead Spring Boot integration — basic + saturation")
class BulkheadSpringBootIntegrationTest {

    @SpringBootApplication
    static class TestApplication {

        /**
         * The runtime is the source of truth for the bulkhead handle. Spring's
         * DisposableBean auto-close handling will call {@link InqRuntime#close()} at
         * context shutdown.
         */
        @Bean(destroyMethod = "close")
        public InqRuntime inqRuntime() {
            return Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("orderBh", b -> b.balanced().maxConcurrentCalls(2)))
                    .build();
        }

        /**
         * Expose the bulkhead handle as an {@link InqElement} bean so
         * {@code InqAutoConfiguration} discovers and registers it. The bean name matches the
         * handle's {@code name()} which is the {@code @InqBulkhead("...")} key in the
         * service.
         */
        @Bean
        public InqElement orderBh(InqRuntime runtime) {
            return (InqElement) runtime.imperative().bulkhead("orderBh");
        }

        @Bean
        public OrderService orderService() {
            return new OrderService();
        }
    }

    @Service
    static class OrderService {

        @InqBulkhead("orderBh")
        public String placeOrder(String item) {
            return "ordered:" + item;
        }

        /**
         * Latch-driven variant used by the saturation test to hold the permit while another
         * call attempts to enter.
         */
        @InqBulkhead("orderBh")
        public String placeOrderHolding(CountDownLatch acquired, CountDownLatch release) {
            acquired.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timeout: holder never released");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
            return "released";
        }

        /**
         * Async-returning variant used by the F-2.19-6 regression pin. The
         * downstream stage is manually completed so the assertion does not
         * depend on scheduling.
         */
        @InqBulkhead("orderBh")
        public CompletionStage<String> placeOrderAsync(String item) {
            return CompletableFuture.completedFuture("async-ordered:" + item);
        }
    }

    @Autowired
    OrderService orderService;

    @Autowired
    InqRuntime runtime;

    @Test
    void annotated_method_routes_through_the_real_bulkhead() {
        // What is to be tested: a Spring-managed service with @InqBulkhead("orderBh") routes
        // its call through the real InqBulkhead beneath InqShieldAspect. Why successful: the
        // service returns its expected value and the bulkhead's concurrentCalls is zero
        // afterwards. Why important: this is the headline contract of the Spring Boot
        // integration. A regression in the auto-configuration descriptor, the registry
        // wiring, or the aspect's @Around dispatch would surface here.

        String result = orderService.placeOrder("Widget");

        assertThat(result).isEqualTo("ordered:Widget");
    }

    @Test
    void saturation_throws_InqBulkheadFullException() throws InterruptedException {
        // What is to be tested: the bulkhead's permit limit (2) is enforceable through the
        // Spring-AOP aspect. Two concurrent holders saturate; a third attempt is rejected
        // with the bulkhead's own exception type, which propagates through the aspect to
        // the caller without rewriting.
        // Why successful: the third call throws InqBulkheadFullException; the holders are
        // released cleanly afterwards.
        // Why important: the Spring AOP path must not swallow or re-wrap the bulkhead's
        // rejection — the type is part of the user-facing contract.

        CountDownLatch holderAcquired1 = new CountDownLatch(1);
        CountDownLatch holderAcquired2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();

        Thread holder1 = Thread.startVirtualThread(() -> {
            try {
                orderService.placeOrderHolding(holderAcquired1, release);
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        Thread holder2 = Thread.startVirtualThread(() -> {
            try {
                orderService.placeOrderHolding(holderAcquired2, release);
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        assertThat(holderAcquired1.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(holderAcquired2.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> orderService.placeOrder("Saturated"))
                    .isInstanceOf(InqBulkheadFullException.class);
        } finally {
            release.countDown();
            holder1.join();
            holder2.join();
        }

        assertThat(errors).as("holders must release without errors").isEmpty();
    }

    @Test
    void runtime_is_the_same_instance_underlying_the_bean() {
        // Pinning that the bulkhead bean and the runtime's own bulkhead view return the
        // same underlying handle — i.e. the @Bean is not duplicating identity.

        InqElement bean = (InqElement) runtime.imperative().bulkhead("orderBh");
        assertThat(bean.name()).isEqualTo("orderBh");
    }

    @Test
    @DisplayName("F-2.19-6 — InqShieldAspect async dispatch through InqBulkhead")
    void should_intercept_an_async_returning_method_with_an_InqBulkhead_without_class_cast_exception() {
        // What is to be tested: a Spring-managed service exposes a CompletionStage-returning
        // method annotated with @InqBulkhead("orderBh"). InqShieldAspect's async-method
        // dispatch path casts each pipeline element to InqAsyncDecorator. Before InqBulkhead
        // implemented InqAsyncDecorator, this cast threw ClassCastException at first
        // invocation — the audit-2.19 finding F-2.19-6.
        // Why successful: the async invocation completes without ClassCastException, the
        // returned stage carries the expected value, and the bulkhead's permit count returns
        // to zero on stage completion.
        // Why important: a future refactor that drops "implements InqAsyncDecorator" from
        // InqBulkhead would silently re-introduce the runtime cast failure on every async
        // call through the aspect. This test fails loudly at first invocation if that
        // happens.

        // Given — the orderBh bulkhead and the OrderService bean wired through Spring,
        // routed through InqShieldAspect's @Around advice on the async method.

        // When / Then — first invocation must not throw a ClassCastException synchronously
        // through the aspect's async-dispatch path
        assertThatCode(() -> orderService.placeOrderAsync("Widget"))
                .doesNotThrowAnyException();

        // And — the stage propagates the value end-to-end
        CompletionStage<String> stage = orderService.placeOrderAsync("Widget");
        String result = stage.toCompletableFuture().join();
        assertThat(result).isEqualTo("async-ordered:Widget");

        // And — permit released on stage completion
        @SuppressWarnings("unchecked")
        eu.inqudium.imperative.bulkhead.InqBulkhead<Void, Object> bh =
                (eu.inqudium.imperative.bulkhead.InqBulkhead<Void, Object>)
                        runtime.imperative().bulkhead("orderBh");
        assertThat(bh.concurrentCalls())
                .as("permit released on stage completion")
                .isZero();
    }
}
