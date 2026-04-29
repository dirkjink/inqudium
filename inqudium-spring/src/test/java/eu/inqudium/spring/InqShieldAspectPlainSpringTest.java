package eu.inqudium.spring;

import eu.inqudium.annotation.InqBulkhead;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired
    OrderService orderService;

    // =========================================================================
    // Dual tracing element
    // =========================================================================
    @Autowired
    InventoryService inventoryService;

    // =========================================================================
    // Services
    // =========================================================================
    @Autowired
    ShippingService shippingService;

    @BeforeEach
    void clearTrace() {
        TRACE.clear();
    }

    static class TracingElement implements InqDecorator<Void, Object>,
            InqAsyncDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;

        TracingElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return type;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

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
    // Plain Spring configuration
    // =========================================================================

    /**
     * METHOD-level annotations.
     */
    static class OrderService {

        @InqCircuitBreaker("testCb")
        @InqRetry("testRt")
        public String placeOrder(String item) {
            TRACE.add("CORE:placeOrder:" + item);
            return "ordered:" + item;
        }

        @InqCircuitBreaker("testCb")
        @InqRetry("testRt")
        @InqBulkhead("testBh")
        public String fullPipeline(String item) {
            TRACE.add("CORE:fullPipeline:" + item);
            return "full:" + item;
        }

        @InqCircuitBreaker("testCb")
        public CompletionStage<String> placeOrderAsync(String item) {
            TRACE.add("CORE:async:" + item);
            return CompletableFuture.completedFuture("async:" + item);
        }

        public String unprotected(String item) {
            TRACE.add("CORE:unprotected:" + item);
            return "free:" + item;
        }

        @InqCircuitBreaker("testCb")
        public String failing() {
            throw new IllegalStateException("sync-boom");
        }

        @InqCircuitBreaker("testCb")
        public CompletionStage<String> failingAsync() {
            return CompletableFuture.failedFuture(new IllegalStateException("async-boom"));
        }

        @InqCircuitBreaker("testCb")
        @InqRetry("testRt")
        public void auditOrder(String orderId) {
            TRACE.add("CORE:audit:" + orderId);
        }
    }

    // =========================================================================
    // Injected services
    // =========================================================================

    /**
     * TYPE-level annotation — all methods protected.
     */
    @InqCircuitBreaker("testCb")
    static class InventoryService {

        public String checkStock(String sku) {
            TRACE.add("CORE:checkStock:" + sku);
            return "in-stock:" + sku;
        }

        public String reserveStock(String sku) {
            TRACE.add("CORE:reserveStock:" + sku);
            return "reserved:" + sku;
        }
    }

    /**
     * TYPE + METHOD merge.
     */
    @InqCircuitBreaker("testCb")
    @InqRetry("testRt")
    static class ShippingService {

        // Inherits: testCb + testRt
        public String estimateDelivery(String address) {
            TRACE.add("CORE:estimate:" + address);
            return "3-5 days";
        }

        // Overrides: testCb → testCb2, inherits testRt
        @InqCircuitBreaker("testCb2")
        public String shipOrder(String orderId) {
            TRACE.add("CORE:ship:" + orderId);
            return "shipped:" + orderId;
        }

        // Adds: testBh on top of inherited testCb + testRt
        @InqBulkhead("testBh")
        public String trackPackage(String trackingId) {
            TRACE.add("CORE:track:" + trackingId);
            return "in-transit:" + trackingId;
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public InqElementRegistry inqElementRegistry() {
            return InqElementRegistry.builder()
                    .register("testCb", new TracingElement("testCb", InqElementType.CIRCUIT_BREAKER))
                    .register("testCb2", new TracingElement("testCb2", InqElementType.CIRCUIT_BREAKER))
                    .register("testRt", new TracingElement("testRt", InqElementType.RETRY))
                    .register("testBh", new TracingElement("testBh", InqElementType.BULKHEAD))
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

        @Bean
        public InventoryService inventoryService() {
            return new InventoryService();
        }

        @Bean
        public ShippingService shippingService() {
            return new ShippingService();
        }
    }

    // =========================================================================
    // 1. Sync interception (METHOD-level)
    // =========================================================================

    @Nested
    @DisplayName("Sync interception (METHOD-level)")
    class SyncInterception {

        @Test
        void annotated_method_goes_through_the_pipeline() {
            // When
            String result = orderService.placeOrder("Widget");

            // Then
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(TRACE)
                    .contains("CORE:placeOrder:Widget")
                    .anyMatch(s -> s.contains("sync-enter"))
                    .anyMatch(s -> s.contains("sync-exit"));
        }

        @Test
        void unannotated_method_is_not_intercepted() {
            // When
            String result = orderService.unprotected("Free");

            // Then
            assertThat(result).isEqualTo("free:Free");
            assertThat(TRACE).containsExactly("CORE:unprotected:Free");
        }
    }

    // =========================================================================
    // 2. Async interception
    // =========================================================================

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
            assertThat(TRACE)
                    .contains("CORE:async:Widget")
                    .anyMatch(s -> s.contains("async-enter"))
                    .anyMatch(s -> s.contains("async-exit"));
        }
    }

    // =========================================================================
    // 3. Multi-element ordering
    // =========================================================================

    @Nested
    @DisplayName("Multi-element ordering")
    class MultiElementOrdering {

        @Test
        void three_elements_execute_in_standard_pipeline_order() {
            // Given — CB + RT + BH → standard: BH(400) → CB(500) → RT(600)

            // When
            String result = orderService.fullPipeline("Gadget");

            // Then — all three elements present
            assertThat(result).isEqualTo("full:Gadget");
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("sync-enter"))
                    .hasSize(3);
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("sync-exit"))
                    .hasSize(3);
            assertThat(TRACE).contains("CORE:fullPipeline:Gadget");
        }
    }

    // =========================================================================
    // 4. Exception transport
    // =========================================================================

    @Nested
    @DisplayName("Exception transport")
    class ExceptionTransport {

        @Test
        void sync_exception_preserves_original_type_and_message() {
            // When / Then
            assertThatThrownBy(() -> orderService.failing())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("sync-boom");
        }

        @Test
        void sync_exception_does_not_get_wrapped_by_spring_aop() {
            // When / Then — no UndeclaredThrowableException or AopInvocationException
            assertThatThrownBy(() -> orderService.failing())
                    .isExactlyInstanceOf(IllegalStateException.class);
        }

        @Test
        void async_exception_is_delivered_via_stage_not_thrown() {
            // When — should not throw, returns a stage with the error
            CompletionStage<String> stage = orderService.failingAsync();

            // Then
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("async-boom");
        }
    }

    // =========================================================================
    // 5. Void return type
    // =========================================================================

    @Nested
    @DisplayName("Void return type")
    class VoidReturnType {

        @Test
        void void_method_goes_through_the_pipeline() {
            // When
            orderService.auditOrder("ORD-001");

            // Then — pipeline executed, core reached
            assertThat(TRACE).contains("CORE:audit:ORD-001");
            assertThat(TRACE)
                    .anyMatch(s -> s.contains("sync-enter"))
                    .anyMatch(s -> s.contains("sync-exit"));
        }

        @Test
        void void_method_has_correct_element_count_in_trace() {
            // Given — auditOrder has CB + RT = 2 elements

            // When
            orderService.auditOrder("ORD-002");

            // Then
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("sync-enter"))
                    .hasSize(2);
        }
    }

    // =========================================================================
    // 6. TYPE-level interception (@within)
    // =========================================================================

    @Nested
    @DisplayName("TYPE-level interception (@within)")
    class TypeLevelInterception {

        @Test
        void all_methods_on_annotated_class_go_through_the_pipeline() {
            // When
            inventoryService.checkStock("SKU-001");
            List<String> trace1 = List.copyOf(TRACE);
            TRACE.clear();

            inventoryService.reserveStock("SKU-001");
            List<String> trace2 = List.copyOf(TRACE);

            // Then — both intercepted
            assertThat(trace1)
                    .contains("CORE:checkStock:SKU-001")
                    .anyMatch(s -> s.contains("testCb:sync-enter"));
            assertThat(trace2)
                    .contains("CORE:reserveStock:SKU-001")
                    .anyMatch(s -> s.contains("testCb:sync-enter"));
        }

        @Test
        void type_level_annotation_produces_correct_element_count() {
            // Given — InventoryService has only @InqCircuitBreaker("testCb") on TYPE

            // When
            inventoryService.checkStock("SKU-002");

            // Then — exactly 1 element (CB)
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("sync-enter"))
                    .hasSize(1);
        }
    }

    // =========================================================================
    // 7. TYPE + METHOD merge
    // =========================================================================

    @Nested
    @DisplayName("TYPE + METHOD merge")
    class TypeMethodMerge {

        @Test
        void unannotated_method_inherits_all_type_level_elements() {
            // Given — TYPE: testCb + testRt, METHOD: none

            // When
            String result = shippingService.estimateDelivery("Berlin");

            // Then — inherits both (2 elements)
            assertThat(result).isEqualTo("3-5 days");
            assertThat(TRACE).contains("CORE:estimate:Berlin");
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("sync-enter"))
                    .hasSize(2);
        }

        @Test
        void method_level_overrides_type_level_for_same_element_type() {
            // Given — TYPE: testCb + testRt, METHOD: testCb2 (overrides CB)

            // When
            String result = shippingService.shipOrder("ORD-001");

            // Then — testCb2 (not testCb) + testRt
            assertThat(result).isEqualTo("shipped:ORD-001");
            assertThat(TRACE)
                    .anyMatch(s -> s.startsWith("testCb2:"))
                    .noneMatch(s -> s.startsWith("testCb:sync"));
            assertThat(TRACE)
                    .anyMatch(s -> s.startsWith("testRt:"));
        }

        @Test
        void method_level_adds_new_element_type_to_inherited_ones() {
            // Given — TYPE: testCb + testRt, METHOD: testBh (new)

            // When
            String result = shippingService.trackPackage("TRK-001");

            // Then — 3 elements: testCb + testRt + testBh
            assertThat(result).isEqualTo("in-transit:TRK-001");
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("sync-enter"))
                    .hasSize(3);
            assertThat(TRACE)
                    .anyMatch(s -> s.startsWith("testBh:"))
                    .anyMatch(s -> s.startsWith("testCb:"))
                    .anyMatch(s -> s.startsWith("testRt:"));
        }
    }
}
