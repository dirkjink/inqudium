package eu.inqudium.spring.boot;

import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqShieldAspect (Spring AOP integration)")
class InqShieldAspectTest {

    // =========================================================================
    // Shared trace list for observing element execution
    // =========================================================================

    static final List<String> TRACE = Collections.synchronizedList(new ArrayList<>());

    // =========================================================================
    // Dual stub element — implements both sync and async interfaces
    // =========================================================================

    static class TracingElement implements InqDecorator<Void, Object>,
            InqAsyncDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;

        TracingElement(String name, InqElementType type) {
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
    // Test services
    // =========================================================================

    /**
     * Service with METHOD-level annotations.
     */
    @Service
    static class PaymentService {

        @InqCircuitBreaker("testCb")
        @InqRetry("testRt")
        public String processPayment(String orderId) {
            TRACE.add("CORE:" + orderId);
            return "paid:" + orderId;
        }

        public String noAnnotation(String orderId) {
            TRACE.add("CORE:" + orderId);
            return "unprotected:" + orderId;
        }

        @InqCircuitBreaker("testCb")
        public CompletionStage<String> processPaymentAsync(String orderId) {
            TRACE.add("CORE:async:" + orderId);
            return CompletableFuture.completedFuture("async-paid:" + orderId);
        }

        @InqCircuitBreaker("testCb")
        public String failingMethod() {
            throw new IllegalStateException("payment-failed");
        }
    }

    /**
     * Service with TYPE-level annotations.
     */
    @Service
    @InqCircuitBreaker("testCb")
    static class FullyProtectedService {

        public String methodA() {
            TRACE.add("CORE:A");
            return "result-A";
        }

        public String methodB() {
            TRACE.add("CORE:B");
            return "result-B";
        }
    }

    /**
     * Service with TYPE + METHOD merge.
     */
    @Service
    @InqCircuitBreaker("testCb")
    static class MergeService {

        @InqRetry("testRt")
        public String withRetry() {
            TRACE.add("CORE:merge");
            return "merged";
        }
    }

    // =========================================================================
    // Spring configuration
    // =========================================================================

    @Configuration
    @EnableAspectJAutoProxy
    @Import({InqAutoConfiguration.class, AopAutoConfiguration.class})
    static class TestConfig {

        @Bean
        public TracingElement testCb() {
            return new TracingElement("testCb", InqElementType.CIRCUIT_BREAKER);
        }

        @Bean
        public TracingElement testRt() {
            return new TracingElement("testRt", InqElementType.RETRY);
        }

        @Bean
        public PaymentService paymentService() {
            return new PaymentService();
        }

        @Bean
        public FullyProtectedService fullyProtectedService() {
            return new FullyProtectedService();
        }

        @Bean
        public MergeService mergeService() {
            return new MergeService();
        }
    }

    // =========================================================================
    // Auto-configuration
    // =========================================================================

    @Nested
    @DisplayName("Auto-configuration")
    @SpringBootTest(classes = TestConfig.class)
    class AutoConfig {

        @Autowired
        InqElementRegistry registry;

        @Test
        void auto_discovers_inq_element_beans_and_populates_registry() {
            // Then — both elements registered by getName()
            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.contains("testCb")).isTrue();
            assertThat(registry.contains("testRt")).isTrue();
        }

        @Test
        void registry_elements_are_the_actual_bean_instances() {
            // Then
            assertThat(registry.get("testCb")).isInstanceOf(TracingElement.class);
            assertThat(registry.get("testRt")).isInstanceOf(TracingElement.class);
        }
    }

    // =========================================================================
    // METHOD-level interception
    // =========================================================================

    @Nested
    @DisplayName("METHOD-level interception")
    @SpringBootTest(classes = TestConfig.class)
    class MethodLevel {

        @Autowired
        PaymentService paymentService;

        @Test
        void annotated_method_goes_through_the_pipeline() {
            // Given
            TRACE.clear();

            // When
            String result = paymentService.processPayment("ORD-001");

            // Then — standard order: CB(500) → RT(600) → CORE
            assertThat(result).isEqualTo("paid:ORD-001");
            assertThat(TRACE).containsExactly(
                    "testCb:sync-enter",
                    "testRt:sync-enter",
                    "CORE:ORD-001",
                    "testRt:sync-exit",
                    "testCb:sync-exit"
            );
        }

        @Test
        void unannotated_method_is_not_intercepted() {
            // Given
            TRACE.clear();

            // When
            String result = paymentService.noAnnotation("ORD-002");

            // Then — no pipeline execution
            assertThat(result).isEqualTo("unprotected:ORD-002");
            assertThat(TRACE).containsExactly("CORE:ORD-002");
        }

        @Test
        void exception_propagates_through_the_pipeline() {
            // Given
            TRACE.clear();

            // When / Then
            assertThatThrownBy(() -> paymentService.failingMethod())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("payment-failed");

            // Pipeline elements still recorded enter + exit
            assertThat(TRACE).containsExactly(
                    "testCb:sync-enter",
                    "testCb:sync-exit"
            );
        }
    }

    // =========================================================================
    // Async dispatch
    // =========================================================================

    @Nested
    @DisplayName("Async dispatch")
    @SpringBootTest(classes = TestConfig.class)
    class AsyncDispatch {

        @Autowired
        PaymentService paymentService;

        @Test
        void async_method_goes_through_the_async_chain() {
            // Given
            TRACE.clear();

            // When
            CompletionStage<String> stage = paymentService.processPaymentAsync("ORD-003");
            String result = stage.toCompletableFuture().join();

            // Then — async path
            assertThat(result).isEqualTo("async-paid:ORD-003");
            assertThat(TRACE).containsExactly(
                    "testCb:async-enter",
                    "CORE:async:ORD-003",
                    "testCb:async-exit"
            );
        }
    }

    // =========================================================================
    // TYPE-level interception
    // =========================================================================

    @Nested
    @DisplayName("TYPE-level interception")
    @SpringBootTest(classes = TestConfig.class)
    class TypeLevel {

        @Autowired
        FullyProtectedService fullyProtectedService;

        @Test
        void all_methods_on_annotated_class_go_through_the_pipeline() {
            // Given
            TRACE.clear();

            // When
            fullyProtectedService.methodA();
            List<String> traceA = List.copyOf(TRACE);
            TRACE.clear();

            fullyProtectedService.methodB();
            List<String> traceB = List.copyOf(TRACE);

            // Then — both methods intercepted
            assertThat(traceA).containsExactly(
                    "testCb:sync-enter", "CORE:A", "testCb:sync-exit");
            assertThat(traceB).containsExactly(
                    "testCb:sync-enter", "CORE:B", "testCb:sync-exit");
        }
    }

    // =========================================================================
    // TYPE + METHOD merge
    // =========================================================================

    @Nested
    @DisplayName("TYPE + METHOD merge")
    @SpringBootTest(classes = TestConfig.class)
    class Merge {

        @Autowired
        MergeService mergeService;

        @Test
        void method_level_annotations_are_merged_with_type_level() {
            // Given — TYPE: @InqCircuitBreaker("testCb"), METHOD: @InqRetry("testRt")
            TRACE.clear();

            // When
            String result = mergeService.withRetry();

            // Then — CB from TYPE + RT from METHOD, standard order: CB → RT → CORE
            assertThat(result).isEqualTo("merged");
            assertThat(TRACE).containsExactly(
                    "testCb:sync-enter",
                    "testRt:sync-enter",
                    "CORE:merge",
                    "testRt:sync-exit",
                    "testCb:sync-exit"
            );
        }
    }
}
