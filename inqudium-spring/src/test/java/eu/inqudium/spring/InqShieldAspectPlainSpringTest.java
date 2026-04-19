package eu.inqudium.spring;

import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain Spring integration test (no Spring Boot) verifying that
 * {@link InqShieldAspect} works with manual configuration via
 * {@code @Configuration} and {@code @EnableAspectJAutoProxy}.
 *
 * <p>This proves that {@code inqudium-spring} works standalone without
 * any Spring Boot dependency.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = InqShieldAspectPlainSpringTest.TestConfig.class)
@DisplayName("InqShieldAspect (plain Spring, no Boot)")
class InqShieldAspectPlainSpringTest {

    static final List<String> TRACE = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void clearTrace() {
        TRACE.clear();
    }

    // =========================================================================
    // Dual tracing element
    // =========================================================================

    static class TracingElement implements InqDecorator<Void, Object>,
            InqAsyncDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;

        TracingElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return type; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            TRACE.add(name + ":sync-enter");
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                TRACE.add(name + ":sync-exit");
            }
        }

        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Void arg,
                                                     InternalAsyncExecutor<Void, Object> next) {
            TRACE.add(name + ":async-enter");
            return next.executeAsync(chainId, callId, arg)
                    .whenComplete((r, e) -> TRACE.add(name + ":async-exit"));
        }
    }

    // =========================================================================
    // Service
    // =========================================================================

    static class OrderService {

        @InqCircuitBreaker("testCb")
        @InqRetry("testRt")
        public String placeOrder(String item) {
            TRACE.add("CORE:" + item);
            return "ordered:" + item;
        }

        @InqCircuitBreaker("testCb")
        public CompletionStage<String> placeOrderAsync(String item) {
            TRACE.add("CORE:async:" + item);
            return CompletableFuture.completedFuture("async:" + item);
        }

        public String unprotected(String item) {
            TRACE.add("CORE:" + item);
            return "free:" + item;
        }
    }

    // =========================================================================
    // Plain Spring configuration (no Boot, no auto-config)
    // =========================================================================

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public InqElementRegistry inqElementRegistry() {
            return InqElementRegistry.builder()
                    .register("testCb", new TracingElement("testCb", InqElementType.CIRCUIT_BREAKER))
                    .register("testRt", new TracingElement("testRt", InqElementType.RETRY))
                    .build();
        }

        @Bean
        public InqShieldAspect inqShieldAspect(InqElementRegistry registry) {
            return new InqShieldAspect(registry);
        }

        @Bean
        public OrderService orderService() {
            return new OrderService();
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Autowired
    OrderService orderService;

    @Nested
    @DisplayName("Sync interception")
    class SyncInterception {

        @Test
        void annotated_method_goes_through_the_pipeline() {
            // When
            String result = orderService.placeOrder("Widget");

            // Then
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(TRACE).contains("testCb:sync-enter", "CORE:Widget", "testCb:sync-exit");
        }

        @Test
        void unannotated_method_is_not_intercepted() {
            // When
            String result = orderService.unprotected("Free");

            // Then
            assertThat(result).isEqualTo("free:Free");
            assertThat(TRACE).containsExactly("CORE:Free");
        }
    }

    @Nested
    @DisplayName("Async interception")
    class AsyncInterception {

        @Test
        void async_method_goes_through_async_chain() {
            // When
            CompletionStage<String> stage = orderService.placeOrderAsync("Widget");
            String result = stage.toCompletableFuture().join();

            // Then
            assertThat(result).isEqualTo("async:Widget");
            assertThat(TRACE).contains("testCb:async-enter", "CORE:async:Widget", "testCb:async-exit");
        }
    }
}
