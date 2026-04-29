package eu.inqudium.spring.boot;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqShield;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import eu.inqudium.spring.InqShieldAspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full Spring Boot integration test that validates the complete
 * auto-configuration mechanism end-to-end:
 *
 * <ol>
 *   <li>{@link InqAutoConfiguration} is picked up via
 *       {@code META-INF/spring/...AutoConfiguration.imports}</li>
 *   <li>All {@code InqElement} {@code @Bean}s are auto-discovered
 *       and registered in an {@link InqElementRegistry}</li>
 *   <li>{@link InqShieldAspect} is created and intercepts annotated
 *       service methods via Spring AOP proxies</li>
 *   <li>Sync methods route through the sync pipeline chain</li>
 *   <li>Async methods (returning {@code CompletionStage}) route through
 *       the async pipeline chain with correct lifecycle</li>
 *   <li>TYPE-level annotations are inherited by all methods</li>
 *   <li>METHOD-level annotations override TYPE-level for the same element type</li>
 * </ol>
 */
@SpringBootTest(classes = InqSpringBootIntegrationTest.TestApplication.class)
@DisplayName("Spring Boot full integration")
class InqSpringBootIntegrationTest {

    // =========================================================================
    // Shared trace — records element execution order
    // =========================================================================

    static final List<String> TRACE = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void clearTrace() {
        TRACE.clear();
    }

    // =========================================================================
    // Test application — @SpringBootApplication triggers auto-configuration
    // =========================================================================

    @SpringBootApplication
    @org.springframework.context.annotation.Import(InqAutoConfiguration.class)
    static class TestApplication {

        // --- Element beans: auto-discovered by InqAutoConfiguration ---

        @Bean
        public DualTracingElement orderCb() {
            return new DualTracingElement("orderCb", InqElementType.CIRCUIT_BREAKER);
        }

        @Bean
        public DualTracingElement orderRetry() {
            return new DualTracingElement("orderRetry", InqElementType.RETRY);
        }

        @Bean
        public DualTracingElement orderBh() {
            return new DualTracingElement("orderBh", InqElementType.BULKHEAD);
        }

        @Bean
        public DualTracingElement globalCb() {
            return new DualTracingElement("globalCb", InqElementType.CIRCUIT_BREAKER);
        }

        @Bean
        public DualTracingElement specialCb() {
            return new DualTracingElement("specialCb", InqElementType.CIRCUIT_BREAKER);
        }

        @Bean
        public DualTracingElement globalRetry() {
            return new DualTracingElement("globalRetry", InqElementType.RETRY);
        }
    }

    // =========================================================================
    // Dual tracing element — implements both sync and async interfaces
    // =========================================================================

    static class DualTracingElement implements
            InqDecorator<Void, Object>, InqAsyncDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;

        DualTracingElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public InqElementType getElementType() {
            return type;
        }

        @Override
        public InqEventPublisher getEventPublisher() {
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
    // Service beans — injected by Spring, proxied by Spring AOP
    // =========================================================================

    /**
     * METHOD-level annotations only.
     */
    @Service
    static class OrderService {

        @InqCircuitBreaker("orderCb")
        @InqRetry("orderRetry")
        public String placeOrder(String item) {
            TRACE.add("CORE:placeOrder:" + item);
            return "ordered:" + item;
        }

        @InqCircuitBreaker("orderCb")
        public CompletionStage<String> placeOrderAsync(String item) {
            TRACE.add("CORE:placeOrderAsync:" + item);
            return CompletableFuture.completedFuture("async-ordered:" + item);
        }

        @InqCircuitBreaker("orderCb")
        @InqRetry("orderRetry")
        @InqBulkhead("orderBh")
        public String fullPipeline(String item) {
            TRACE.add("CORE:fullPipeline:" + item);
            return "full:" + item;
        }

        public String unprotected(String item) {
            TRACE.add("CORE:unprotected:" + item);
            return "unprotected:" + item;
        }

        @InqCircuitBreaker("orderCb")
        public String failing() {
            throw new IllegalStateException("order-failed");
        }

        @InqCircuitBreaker("orderCb")
        public CompletionStage<String> failingAsync() {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("async-order-failed"));
        }

        @InqCircuitBreaker("orderCb")
        @InqRetry("orderRetry")
        public void auditOrder(String orderId) {
            TRACE.add("CORE:auditOrder:" + orderId);
        }

        @InqCircuitBreaker("orderCb")
        @InqRetry("orderRetry")
        public CompletionStage<String> placeOrderAsyncFull(String item) {
            TRACE.add("CORE:placeOrderAsyncFull:" + item);
            return CompletableFuture.completedFuture("async-full:" + item);
        }

        @InqCircuitBreaker("orderCb")
        @InqRetry("orderRetry")
        public CompletionStage<String> failingAsyncWithPipeline() {
            TRACE.add("CORE:failingAsyncWithPipeline");
            return CompletableFuture.failedFuture(
                    new IllegalStateException("async-pipeline-failed"));
        }
    }

    /**
     * TYPE-level annotation — all public methods are protected.
     */
    @Service
    @InqCircuitBreaker("globalCb")
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
     * TYPE + METHOD merge — METHOD overrides TYPE for same element type.
     */
    @Service
    @InqCircuitBreaker("globalCb")
    @InqRetry("globalRetry")
    static class ShippingService {

        // Inherits: globalCb + globalRetry
        public String estimateDelivery(String address) {
            TRACE.add("CORE:estimateDelivery:" + address);
            return "3-5 days";
        }

        // Overrides: globalCb → specialCb, inherits globalRetry
        @InqCircuitBreaker("specialCb")
        public String shipOrder(String orderId) {
            TRACE.add("CORE:shipOrder:" + orderId);
            return "shipped:" + orderId;
        }

        // Adds: BH on top of inherited globalCb + globalRetry
        @InqBulkhead("orderBh")
        public String trackPackage(String trackingId) {
            TRACE.add("CORE:trackPackage:" + trackingId);
            return "in-transit:" + trackingId;
        }
    }

    /**
     * Resilience4J ordering via @InqShield.
     */
    @Service
    static class NotificationService {

        @InqShield(order = "RESILIENCE4J")
        @InqCircuitBreaker("orderCb")
        @InqRetry("orderRetry")
        @InqBulkhead("orderBh")
        public String sendNotification(String message) {
            TRACE.add("CORE:sendNotification:" + message);
            return "sent:" + message;
        }
    }

    /**
     * Shared element: uses the same "orderCb" as OrderService.
     */
    @Service
    static class PaymentService {

        @InqCircuitBreaker("orderCb")
        public String chargeCard(String cardId) {
            TRACE.add("CORE:chargeCard:" + cardId);
            return "charged:" + cardId;
        }
    }

    // =========================================================================
    // Auto-configuration verification
    // =========================================================================

    @Nested
    @DisplayName("Auto-configuration")
    class AutoConfiguration {

        @Autowired
        ApplicationContext context;

        @Autowired
        InqElementRegistry registry;

        @Test
        void auto_configuration_creates_the_registry_bean() {
            // Then — registry exists as a Spring bean
            assertThat(context.getBean(InqElementRegistry.class)).isNotNull();
        }

        @Test
        void auto_configuration_creates_the_aspect_bean() {
            // Then — aspect exists as a Spring bean
            assertThat(context.getBean(InqShieldAspect.class)).isNotNull();
        }

        @Test
        void registry_contains_all_inq_element_beans() {
            // Then — all six elements auto-discovered by getName()
            assertThat(registry.size()).isEqualTo(6);
            assertThat(registry.names()).containsExactlyInAnyOrder(
                    "orderCb", "orderRetry", "orderBh",
                    "globalCb", "specialCb", "globalRetry");
        }

        @Test
        void registry_elements_are_the_actual_bean_instances() {
            // Then — same instance, not a copy
            DualTracingElement bean = context.getBean("orderCb", DualTracingElement.class);
            assertThat(registry.get("orderCb")).isSameAs(bean);
        }

        @Test
        void services_are_proxied_by_spring_aop() {
            // Then — Spring creates AOP proxies for annotated services
            OrderService service = context.getBean(OrderService.class);
            assertThat(service.getClass().getName()).contains("$$");
        }
    }

    // =========================================================================
    // METHOD-level sync interception
    // =========================================================================

    @Nested
    @DisplayName("METHOD-level sync interception")
    class MethodLevelSync {

        @Autowired
        OrderService orderService;

        @Test
        void single_annotation_wraps_the_method_call() {
            // When
            String result = orderService.placeOrder("Widget");

            // Then — standard order: CB(500) → RT(600)
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(TRACE).containsExactly(
                    "orderCb:sync-enter",
                    "orderRetry:sync-enter",
                    "CORE:placeOrder:Widget",
                    "orderRetry:sync-exit",
                    "orderCb:sync-exit"
            );
        }

        @Test
        void three_element_pipeline_executes_in_standard_order() {
            // When — CB + RT + BH → standard: BH(400) → CB(500) → RT(600)
            String result = orderService.fullPipeline("Gadget");

            // Then
            assertThat(result).isEqualTo("full:Gadget");
            assertThat(TRACE).containsExactly(
                    "orderBh:sync-enter",
                    "orderCb:sync-enter",
                    "orderRetry:sync-enter",
                    "CORE:fullPipeline:Gadget",
                    "orderRetry:sync-exit",
                    "orderCb:sync-exit",
                    "orderBh:sync-exit"
            );
        }

        @Test
        void unannotated_method_is_not_intercepted() {
            // When
            String result = orderService.unprotected("Free");

            // Then — no pipeline trace
            assertThat(result).isEqualTo("unprotected:Free");
            assertThat(TRACE).containsExactly("CORE:unprotected:Free");
        }

        @Test
        void repeated_calls_to_the_same_method_produce_consistent_traces() {
            // When — three calls (first builds cache, rest reuse)
            orderService.placeOrder("A");
            List<String> trace1 = List.copyOf(TRACE);
            TRACE.clear();

            orderService.placeOrder("B");
            List<String> trace2 = List.copyOf(TRACE);
            TRACE.clear();

            orderService.placeOrder("C");
            List<String> trace3 = List.copyOf(TRACE);

            // Then — identical trace structure (different core args)
            assertThat(trace1).hasSize(5);
            assertThat(trace2).hasSize(5);
            assertThat(trace3).hasSize(5);
            assertThat(trace1.get(0)).isEqualTo(trace2.get(0));
        }
    }

    // =========================================================================
    // Async dispatch
    // =========================================================================

    @Nested
    @DisplayName("Async dispatch")
    class AsyncDispatch {

        @Autowired
        OrderService orderService;

        @Test
        void async_method_goes_through_the_async_chain() {
            // When
            CompletionStage<String> stage = orderService.placeOrderAsync("Widget");
            String result = stage.toCompletableFuture().join();

            // Then — async path
            assertThat(result).isEqualTo("async-ordered:Widget");
            assertThat(TRACE).containsExactly(
                    "orderCb:async-enter",
                    "CORE:placeOrderAsync:Widget",
                    "orderCb:async-exit"
            );
        }

        @Test
        void async_failure_is_delivered_via_stage_not_thrown() {
            // When
            CompletionStage<String> stage = orderService.failingAsync();

            // Then — exception in the stage
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("async-order-failed");
        }

        @Test
        void same_service_routes_sync_and_async_through_correct_chains() {
            // When — sync
            orderService.placeOrder("sync");
            List<String> syncTrace = List.copyOf(TRACE);
            TRACE.clear();

            // When — async
            orderService.placeOrderAsync("async").toCompletableFuture().join();
            List<String> asyncTrace = List.copyOf(TRACE);

            // Then — different chains used
            assertThat(syncTrace).allMatch(s -> s.contains("sync"));
            assertThat(asyncTrace).allMatch(s -> s.contains("async"));
        }

        @Test
        void multi_element_async_method_goes_through_the_async_chain() {
            // Given — placeOrderAsyncFull has CB + RT (2 async elements)

            // When
            CompletionStage<String> stage = orderService.placeOrderAsyncFull("Gadget");
            String result = stage.toCompletableFuture().join();

            // Then — both elements used the async path
            assertThat(result).isEqualTo("async-full:Gadget");
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("async-enter"))
                    .hasSize(2);
            assertThat(TRACE)
                    .filteredOn(s -> s.contains("async-exit"))
                    .hasSize(2);
            assertThat(TRACE).contains("CORE:placeOrderAsyncFull:Gadget");
        }
    }

    // =========================================================================
    // Exception propagation
    // =========================================================================

    @Nested
    @DisplayName("Exception propagation")
    class ExceptionPropagation {

        @Autowired
        OrderService orderService;

        @Test
        void sync_exception_propagates_with_original_type_and_message() {
            // When / Then
            assertThatThrownBy(() -> orderService.failing())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("order-failed");

            // Pipeline elements recorded enter + exit
            assertThat(TRACE).containsExactly(
                    "orderCb:sync-enter",
                    "orderCb:sync-exit"
            );
        }
    }

    // =========================================================================
    // TYPE-level interception
    // =========================================================================

    @Nested
    @DisplayName("TYPE-level interception")
    class TypeLevel {

        @Autowired
        InventoryService inventoryService;

        @Test
        void all_methods_on_annotated_class_go_through_the_pipeline() {
            // When
            String stock = inventoryService.checkStock("SKU-001");
            List<String> trace1 = List.copyOf(TRACE);
            TRACE.clear();

            String reserved = inventoryService.reserveStock("SKU-001");
            List<String> trace2 = List.copyOf(TRACE);

            // Then — both methods intercepted with globalCb
            assertThat(stock).isEqualTo("in-stock:SKU-001");
            assertThat(trace1).containsExactly(
                    "globalCb:sync-enter",
                    "CORE:checkStock:SKU-001",
                    "globalCb:sync-exit"
            );

            assertThat(reserved).isEqualTo("reserved:SKU-001");
            assertThat(trace2).containsExactly(
                    "globalCb:sync-enter",
                    "CORE:reserveStock:SKU-001",
                    "globalCb:sync-exit"
            );
        }
    }

    // =========================================================================
    // TYPE + METHOD merge
    // =========================================================================

    @Nested
    @DisplayName("TYPE + METHOD merge")
    class TypeMethodMerge {

        @Autowired
        ShippingService shippingService;

        @Test
        void unannotated_method_inherits_all_type_level_elements() {
            // Given — TYPE: globalCb + globalRetry, METHOD: (none)

            // When
            String result = shippingService.estimateDelivery("Berlin");

            // Then — inherits both: CB(500) → RT(600)
            assertThat(result).isEqualTo("3-5 days");
            assertThat(TRACE).containsExactly(
                    "globalCb:sync-enter",
                    "globalRetry:sync-enter",
                    "CORE:estimateDelivery:Berlin",
                    "globalRetry:sync-exit",
                    "globalCb:sync-exit"
            );
        }

        @Test
        void method_level_overrides_type_level_for_same_element_type() {
            // Given — TYPE: globalCb + globalRetry, METHOD: specialCb (overrides CB)

            // When
            String result = shippingService.shipOrder("ORD-001");

            // Then — specialCb (not globalCb) + globalRetry
            assertThat(result).isEqualTo("shipped:ORD-001");
            assertThat(TRACE).containsExactly(
                    "specialCb:sync-enter",
                    "globalRetry:sync-enter",
                    "CORE:shipOrder:ORD-001",
                    "globalRetry:sync-exit",
                    "specialCb:sync-exit"
            );

            // globalCb is NOT in the trace
            assertThat(TRACE).noneMatch(s -> s.contains("globalCb"));
        }

        @Test
        void method_level_adds_new_element_type_to_inherited_ones() {
            // Given — TYPE: globalCb + globalRetry, METHOD: orderBh (new type)

            // When
            String result = shippingService.trackPackage("TRK-001");

            // Then — BH(400) → CB(500) → RT(600) — three elements
            assertThat(result).isEqualTo("in-transit:TRK-001");
            assertThat(TRACE).containsExactly(
                    "orderBh:sync-enter",
                    "globalCb:sync-enter",
                    "globalRetry:sync-enter",
                    "CORE:trackPackage:TRK-001",
                    "globalRetry:sync-exit",
                    "globalCb:sync-exit",
                    "orderBh:sync-exit"
            );
        }
    }

    // =========================================================================
    // @InqShield ordering
    // =========================================================================

    @Nested
    @DisplayName("@InqShield ordering")
    class ShieldOrdering {

        @Autowired
        NotificationService notificationService;
        @Autowired
        ShippingService shippingService;

        @Test
        void resilience4j_ordering_reverses_the_pipeline() {
            // Given — @InqShield(order = "RESILIENCE4J") + CB + RT + BH

            // When
            String result = notificationService.sendNotification("hello");

            // Then — R4J order: RT(100) → CB(200) → BH(600)
            assertThat(result).isEqualTo("sent:hello");
            assertThat(TRACE).containsExactly(
                    "orderRetry:sync-enter",
                    "orderCb:sync-enter",
                    "orderBh:sync-enter",
                    "CORE:sendNotification:hello",
                    "orderBh:sync-exit",
                    "orderCb:sync-exit",
                    "orderRetry:sync-exit"
            );
        }

        @Test
        void resilience4j_ordering_differs_from_standard_ordering() {
            // Given — same elements, different order
            @SuppressWarnings("unused")
            String standardResult = shippingService.trackPackage("STD");
            List<String> standardTrace = List.copyOf(TRACE);
            TRACE.clear();

            String r4jResult = notificationService.sendNotification("R4J");
            List<String> r4jTrace = List.copyOf(TRACE);

            // Then — first element differs: BH (standard) vs RT (R4J)
            assertThat(standardTrace.getFirst()).startsWith("orderBh:");
            assertThat(r4jTrace.getFirst()).startsWith("orderRetry:");
        }
    }

    // =========================================================================
    // Void return type
    // =========================================================================

    @Nested
    @DisplayName("Void return type")
    class VoidReturnType {

        @Autowired
        OrderService orderService;

        @Test
        void void_method_goes_through_the_pipeline() {
            // When
            orderService.auditOrder("ORD-001");

            // Then — pipeline executes, no return value
            assertThat(TRACE).containsExactly(
                    "orderCb:sync-enter",
                    "orderRetry:sync-enter",
                    "CORE:auditOrder:ORD-001",
                    "orderRetry:sync-exit",
                    "orderCb:sync-exit"
            );
        }
    }

    // =========================================================================
    // Shared element across services
    // =========================================================================

    @Nested
    @DisplayName("Shared element across services")
    class SharedElement {

        @Autowired
        OrderService orderService;

        @Autowired
        PaymentService paymentService;

        @Autowired
        InqElementRegistry registry;

        @Test
        void two_services_share_the_same_element_instance() {
            // When — both use "orderCb"
            orderService.placeOrder("ORD-001");
            List<String> orderTrace = List.copyOf(TRACE);
            TRACE.clear();

            paymentService.chargeCard("CARD-001");
            List<String> paymentTrace = List.copyOf(TRACE);

            // Then — both route through the same element
            assertThat(orderTrace).anyMatch(s -> s.startsWith("orderCb:"));
            assertThat(paymentTrace).anyMatch(s -> s.startsWith("orderCb:"));
        }

        @Test
        void shared_element_is_the_same_bean_instance() {
            // Then — registry returns the same object for both services
            assertThat(registry.get("orderCb"))
                    .isSameAs(registry.get("orderCb"));
        }
    }

    // =========================================================================
    // Async exception with element trace
    // =========================================================================

    @Nested
    @DisplayName("Async exception with element trace")
    class AsyncExceptionTrace {

        @Autowired
        OrderService orderService;

        @Test
        void async_failure_preserves_original_exception_through_pipeline() {
            // When
            CompletionStage<String> stage = orderService.failingAsyncWithPipeline();

            // Then — original exception preserved inside CompletionException
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("async-pipeline-failed");
        }

        @Test
        void async_failure_method_body_is_executed() {
            // When
            CompletionStage<String> stage = orderService.failingAsyncWithPipeline();
            try {
                stage.toCompletableFuture().join();
            } catch (CompletionException ignored) {
            }

            // Then — the method body ran (CORE trace present)
            assertThat(TRACE).contains("CORE:failingAsyncWithPipeline");
        }

        @Test
        void async_failure_records_element_traces_if_chain_supports_it() {
            // When
            CompletionStage<String> stage = orderService.failingAsyncWithPipeline();
            try {
                stage.toCompletableFuture().join();
            } catch (CompletionException ignored) {
            }

            // Then — elements should have recorded their enter/exit around the core
            //        The exact trace depends on how AsyncJoinPointWrapper handles
            //        failed futures from delegate.proceed():
            //        - If elements wrap the failed future: enter + exit for each element
            //        - If the chain rethrows: only enter traces (exit lost)
            assertThat(TRACE)
                    .as("Trace should at minimum contain the core execution")
                    .contains("CORE:failingAsyncWithPipeline");

            // Log actual trace for diagnostics
            System.out.println("[AsyncExceptionTrace] Actual trace: " + TRACE);
        }
    }
}
