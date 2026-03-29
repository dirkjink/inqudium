package eu.inqudium.retry;

import eu.inqudium.core.Invocation;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.InvocationVarargs;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.retry.InqRetryExhaustedException;
import eu.inqudium.core.retry.RetryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Retry — User Perspective")
class RetryUsageTest {

    static class InventoryService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private int failUntilAttempt;

        InventoryService(int failUntilAttempt) {
            this.failUntilAttempt = failUntilAttempt;
        }

        String checkStock(String sku) {
            int attempt = callCount.incrementAndGet();
            if (attempt < failUntilAttempt) {
                throw new RuntimeException("Service temporarily unavailable (attempt " + attempt + ")");
            }
            return "in-stock:" + sku;
        }

        String checkStockDetailed(String sku, String warehouse, int minQuantity, boolean includeReserved) {
            int attempt = callCount.incrementAndGet();
            if (attempt < failUntilAttempt) {
                throw new RuntimeException("Service temporarily unavailable (attempt " + attempt + ")");
            }
            return String.format("%s@%s-min%d-%s", sku, warehouse, minQuantity,
                    includeReserved ? "incl-reserved" : "available-only");
        }

        int getCallCount() { return callCount.get(); }
    }

    @Nested
    @DisplayName("Standalone usage")
    class Standalone {

        @Test
        void should_succeed_on_first_attempt_when_service_is_healthy() {
            // Given
            var service = new InventoryService(1);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            Supplier<String> resilientCheck = retry.decorateSupplier(() -> service.checkStock("SKU-100"));

            // When
            var result = resilientCheck.get();

            // Then
            assertThat(result).isEqualTo("in-stock:SKU-100");
            assertThat(service.getCallCount()).isEqualTo(1);
        }

        @Test
        void should_retry_and_eventually_succeed_after_transient_failures() {
            // Given — fails on first 2 attempts, succeeds on 3rd
            var service = new InventoryService(3);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            Supplier<String> resilientCheck = retry.decorateSupplier(() -> service.checkStock("SKU-200"));

            // When
            var result = resilientCheck.get();

            // Then
            assertThat(result).isEqualTo("in-stock:SKU-200");
            assertThat(service.getCallCount()).isEqualTo(3);
        }

        @Test
        void should_throw_retry_exhausted_when_all_attempts_fail() {
            // Given
            var service = new InventoryService(Integer.MAX_VALUE);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            Supplier<String> resilientCheck = retry.decorateSupplier(() -> service.checkStock("SKU-300"));

            // When / Then
            assertThatThrownBy(resilientCheck::get)
                    .isInstanceOf(InqRetryExhaustedException.class)
                    .satisfies(ex -> {
                        var retryEx = (InqRetryExhaustedException) ex;
                        assertThat(retryEx.getCode()).isEqualTo("INQ-RT-001");
                        assertThat(retryEx.getAttempts()).isEqualTo(3);
                        assertThat(retryEx.getLastCause()).hasMessageContaining("temporarily unavailable");
                    });
        }

        @Test
        void should_allow_catching_exhausted_retries_via_inq_failure() {
            // Given
            var service = new InventoryService(Integer.MAX_VALUE);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(2)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            Supplier<String> resilientCheck = retry.decorateSupplier(() -> service.checkStock("fail"));

            // When
            var handled = new AtomicInteger(0);
            try {
                resilientCheck.get();
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifRetryExhausted(info -> {
                            handled.incrementAndGet();
                            assertThat(info.getAttempts()).isEqualTo(2);
                        })
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);
        }
    }

    @Nested
    @DisplayName("Standalone invocation usage")
    class StandaloneInvocation {

        @Test
        void should_retry_a_single_argument_invocation_with_different_args() throws Exception {
            // Given — fails first call, succeeds second per InventoryService counter
            var service = new InventoryService(2);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            Invocation<String, String> resilientCheck =
                    retry.decorateInvocation(service::checkStock);

            // When — first invoke triggers 1 failure + 1 success (2 calls)
            var r1 = resilientCheck.invoke("SKU-100");

            // Then
            assertThat(r1).isEqualTo("in-stock:SKU-100");
            assertThat(service.getCallCount()).isEqualTo(2);

            // When — second invoke succeeds immediately (service counter past failUntilAttempt)
            var r2 = resilientCheck.invoke("SKU-200");

            // Then
            assertThat(r2).isEqualTo("in-stock:SKU-200");
        }

        @Test
        void should_retry_a_four_argument_invocation_via_varargs() throws Exception {
            // Given
            var service = new InventoryService(1);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            InvocationVarargs<String> resilientCheck = retry.decorateInvocation(
                    (InvocationVarargs<String>) args -> service.checkStockDetailed(
                            (String) args[0], (String) args[1],
                            (Integer) args[2], (Boolean) args[3]));

            // When
            var r1 = resilientCheck.invoke("SKU-100", "warehouse-A", 10, true);
            var r2 = resilientCheck.invoke("SKU-200", "warehouse-B", 5, false);

            // Then
            assertThat(r1).isEqualTo("SKU-100@warehouse-A-min10-incl-reserved");
            assertThat(r2).isEqualTo("SKU-200@warehouse-B-min5-available-only");
        }
    }

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_retry_a_call_through_the_pipeline() {
            // Given
            var service = new InventoryService(2);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            Supplier<String> resilient = InqPipeline.of(() -> service.checkStock("pipeline-1"))
                    .shield(retry)
                    .decorate();

            // When
            var result = resilient.get();

            // Then
            assertThat(result).isEqualTo("in-stock:pipeline-1");
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_carry_a_pipeline_call_id_on_retry_exhausted() {
            // Given
            var service = new InventoryService(Integer.MAX_VALUE);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(2)
                    .initialInterval(Duration.ofMillis(10))
                    .build());
            Supplier<String> resilient = InqPipeline.of(() -> service.checkStock("fail"))
                    .shield(retry)
                    .decorate();

            // When / Then
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqRetryExhaustedException.class)
                    .satisfies(ex -> assertThat(((InqException) ex).getCallId()).isNotEqualTo("None"));
        }
    }

    @Nested
    @DisplayName("Pipeline invocation usage")
    class PipelineInvocation {

        @Test
        void should_compose_pipeline_with_single_argument_invocation() throws Exception {
            // Given
            var service = new InventoryService(1);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            Invocation<String, String> resilientCheck = sku ->
                    InqPipeline.of(() -> service.checkStock(sku))
                            .shield(retry)
                            .decorate()
                            .get();

            // When
            var r1 = resilientCheck.invoke("SKU-100");
            var r2 = resilientCheck.invoke("SKU-200");

            // Then
            assertThat(r1).isEqualTo("in-stock:SKU-100");
            assertThat(r2).isEqualTo("in-stock:SKU-200");
        }

        @Test
        void should_compose_pipeline_with_four_argument_invocation() throws Exception {
            // Given
            var service = new InventoryService(1);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            InvocationVarargs<String> resilientCheck = args ->
                    InqPipeline.of(() -> service.checkStockDetailed(
                                    (String) args[0], (String) args[1],
                                    (Integer) args[2], (Boolean) args[3]))
                            .shield(retry)
                            .decorate()
                            .get();

            // When
            var r1 = resilientCheck.invoke("SKU-100", "warehouse-A", 10, true);
            var r2 = resilientCheck.invoke("SKU-200", "warehouse-B", 5, false);

            // Then
            assertThat(r1).isEqualTo("SKU-100@warehouse-A-min10-incl-reserved");
            assertThat(r2).isEqualTo("SKU-200@warehouse-B-min5-available-only");
        }
    }
}
