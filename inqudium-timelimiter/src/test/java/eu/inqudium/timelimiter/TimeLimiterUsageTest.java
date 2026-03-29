package eu.inqudium.timelimiter;

import eu.inqudium.core.Invocation;
import eu.inqudium.core.InvocationVarargs;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.timelimiter.InqTimeLimitExceededException;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TimeLimiter — User Perspective")
class TimeLimiterUsageTest {

    interface ShippingApi {
        String calculateShipping(String orderId);
        String calculateShippingDetailed(String orderId, String destination, int weight, boolean insured);
    }

    static class ShippingService implements ShippingApi {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final long latencyMs;

        ShippingService(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        @Override
        public String calculateShipping(String orderId) {
            callCount.incrementAndGet();
            sleep(latencyMs);
            return "shipping-" + orderId;
        }

        @Override
        public String calculateShippingDetailed(String orderId, String destination, int weight, boolean insured) {
            callCount.incrementAndGet();
            sleep(latencyMs);
            return String.format("%s→%s-%dkg-%s", orderId, destination, weight,
                    insured ? "insured" : "uninsured");
        }

        int getCallCount() { return callCount.get(); }

        private static void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Nested
    @DisplayName("Standalone usage — synchronous supplier")
    class StandaloneSync {

        @Test
        void should_return_result_when_call_completes_within_timeout() {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Supplier<String> resilientCalc = tl.decorateSupplier(
                    () -> service.calculateShipping("order-1"));

            // When
            var result = resilientCalc.get();

            // Then
            assertThat(result).isEqualTo("shipping-order-1");
        }

        @Test
        void should_throw_time_limit_exceeded_when_call_is_too_slow() {
            // Given
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilientCalc = tl.decorateSupplier(
                    () -> service.calculateShipping("slow"));

            // When / Then
            assertThatThrownBy(resilientCalc::get)
                    .isInstanceOf(InqTimeLimitExceededException.class)
                    .satisfies(ex -> {
                        var tlEx = (InqTimeLimitExceededException) ex;
                        assertThat(tlEx.getCode()).isEqualTo("INQ-TL-001");
                        assertThat(tlEx.getConfiguredDuration()).isEqualTo(Duration.ofMillis(100));
                    });
        }

        @Test
        void should_allow_catching_timeouts_via_inq_failure() {
            // Given
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilientCalc = tl.decorateSupplier(
                    () -> service.calculateShipping("timeout"));

            // When
            var handled = new AtomicInteger(0);
            try {
                resilientCalc.get();
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifTimeLimitExceeded(info -> handled.incrementAndGet())
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);
        }
    }

    @Nested
    @DisplayName("Standalone usage — future supplier")
    class StandaloneFuture {

        @Test
        void should_return_result_from_a_decorated_future_supplier() {
            // Given
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Supplier<String> resilientFuture = tl.decorateFutureSupplier(
                    () -> CompletableFuture.completedFuture("immediate"));

            // When / Then
            assertThat(resilientFuture.get()).isEqualTo("immediate");
        }

        @Test
        void should_throw_time_limit_exceeded_for_slow_future() {
            // Given
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilientFuture = tl.decorateFutureSupplier(
                    () -> CompletableFuture.supplyAsync(() -> {
                        ShippingService.sleep(2000);
                        return "too-slow";
                    }));

            // When / Then
            assertThatThrownBy(resilientFuture::get)
                    .isInstanceOf(InqTimeLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("Standalone invocation usage")
    class StandaloneInvocation {

        @Test
        void should_time_limit_a_single_argument_invocation_with_different_orders() throws Exception {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Invocation<String, String> resilientCalc =
                    tl.decorateInvocation(service::calculateShipping);

            // When
            var r1 = resilientCalc.invoke("order-1");
            var r2 = resilientCalc.invoke("order-2");

            // Then
            assertThat(r1).isEqualTo("shipping-order-1");
            assertThat(r2).isEqualTo("shipping-order-2");
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_time_limit_a_four_argument_invocation_via_varargs() throws Exception {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            InvocationVarargs<String> resilientCalc = tl.decorateInvocation(
                    (InvocationVarargs<String>) args -> service.calculateShippingDetailed(
                            (String) args[0], (String) args[1],
                            (Integer) args[2], (Boolean) args[3]));

            // When
            var r1 = resilientCalc.invoke("order-1", "Berlin", 5, true);
            var r2 = resilientCalc.invoke("order-2", "Munich", 12, false);

            // Then
            assertThat(r1).isEqualTo("order-1→Berlin-5kg-insured");
            assertThat(r2).isEqualTo("order-2→Munich-12kg-uninsured");
        }

        @Test
        void should_timeout_invocation_when_service_is_too_slow() {
            // Given
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Invocation<String, String> resilientCalc =
                    tl.decorateInvocation(service::calculateShipping);

            // When / Then — different arguments don't change the timeout behavior
            assertThatThrownBy(() -> resilientCalc.invoke("slow-order"))
                    .isInstanceOf(InqTimeLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_time_limit_a_call_through_the_pipeline() {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Supplier<String> resilient = InqPipeline.of(
                            () -> service.calculateShipping("pipeline-1"))
                    .shield(tl)
                    .decorate();

            // When / Then
            assertThat(resilient.get()).isEqualTo("shipping-pipeline-1");
        }

        @Test
        void should_carry_a_pipeline_call_id_on_timeout() {
            // Given
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilient = InqPipeline.of(
                            () -> service.calculateShipping("slow"))
                    .shield(tl)
                    .decorate();

            // When / Then
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqTimeLimitExceededException.class)
                    .satisfies(ex -> assertThat(((InqException) ex).getCallId()).isNotEqualTo("None"));
        }
    }

    @Nested
    @DisplayName("Pipeline invocation usage")
    class PipelineInvocation {

        @Test
        void should_compose_pipeline_with_single_argument_invocation() throws Exception {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());

            Invocation<String, String> resilientCalc = orderId ->
                    InqPipeline.of(() -> service.calculateShipping(orderId))
                            .shield(tl)
                            .decorate()
                            .get();

            // When
            var r1 = resilientCalc.invoke("order-1");
            var r2 = resilientCalc.invoke("order-2");

            // Then
            assertThat(r1).isEqualTo("shipping-order-1");
            assertThat(r2).isEqualTo("shipping-order-2");
        }

        @Test
        void should_compose_pipeline_with_four_argument_invocation() throws Exception {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());

            InvocationVarargs<String> resilientCalc = args ->
                    InqPipeline.of(() -> service.calculateShippingDetailed(
                                    (String) args[0], (String) args[1],
                                    (Integer) args[2], (Boolean) args[3]))
                            .shield(tl)
                            .decorate()
                            .get();

            // When
            var r1 = resilientCalc.invoke("order-1", "Berlin", 5, true);
            var r2 = resilientCalc.invoke("order-2", "Munich", 12, false);

            // Then
            assertThat(r1).isEqualTo("order-1→Berlin-5kg-insured");
            assertThat(r2).isEqualTo("order-2→Munich-12kg-uninsured");
        }
    }

    // ── Pipeline — Proxy pattern ──

    @Nested
    @DisplayName("Pipeline proxy usage")
    class PipelineProxy {

        @Test
        void should_create_a_typed_proxy_that_time_limits_single_argument_calls() {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());

            ShippingApi resilient = InqPipeline.of(service, ShippingApi.class)
                    .shield(tl)
                    .decorate();

            // When
            var r1 = resilient.calculateShipping("order-1");
            var r2 = resilient.calculateShipping("order-2");

            // Then
            assertThat(r1).isEqualTo("shipping-order-1");
            assertThat(r2).isEqualTo("shipping-order-2");
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_create_a_typed_proxy_that_time_limits_four_argument_calls() {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());

            ShippingApi resilient = InqPipeline.of(service, ShippingApi.class)
                    .shield(tl)
                    .decorate();

            // When
            var r1 = resilient.calculateShippingDetailed("order-1", "Berlin", 5, true);
            var r2 = resilient.calculateShippingDetailed("order-2", "Munich", 12, false);

            // Then
            assertThat(r1).isEqualTo("order-1→Berlin-5kg-insured");
            assertThat(r2).isEqualTo("order-2→Munich-12kg-uninsured");
        }

        @Test
        void should_timeout_proxy_calls_when_service_is_too_slow() {
            // Given
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());

            ShippingApi resilient = InqPipeline.of(service, ShippingApi.class)
                    .shield(tl)
                    .decorate();

            // When / Then
            assertThatThrownBy(() -> resilient.calculateShipping("slow"))
                    .isInstanceOf(InqTimeLimitExceededException.class);
        }
    }
}
