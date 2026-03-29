package eu.inqudium.circuitbreaker;

import eu.inqudium.core.Invocation;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.InvocationVarargs;
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.circuitbreaker.CircuitBreakerState;
import eu.inqudium.core.circuitbreaker.InqCallNotPermittedException;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.exception.InqRuntimeException;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InqPipelineProxy;
import eu.inqudium.core.pipeline.PipelineOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CircuitBreaker — User Perspective")
class CircuitBreakerUsageTest {

    // ── Service interface for proxy-based decoration ──

    interface PaymentApi {
        String charge(String orderId);
        String chargeDetailed(String orderId, String currency, int amount, boolean express);
    }

    static class PaymentService implements PaymentApi {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private boolean failing = false;

        @Override
        public String charge(String orderId) {
            callCount.incrementAndGet();
            if (failing) throw new RuntimeException("Payment gateway unavailable");
            return "receipt-" + orderId;
        }

        @Override
        public String chargeDetailed(String orderId, String currency, int amount, boolean express) {
            callCount.incrementAndGet();
            if (failing) throw new RuntimeException("Payment gateway unavailable");
            return String.format("%s-%s-%d-%s", orderId, currency, amount, express ? "express" : "standard");
        }

        void setFailing(boolean failing) { this.failing = failing; }
        int getCallCount() { return callCount.get(); }
    }

    // ── Standalone — Supplier/Callable pattern ──

    @Nested
    @DisplayName("Standalone usage")
    class Standalone {

        @Test
        void should_let_calls_through_when_circuit_is_closed() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.of("paymentService", CircuitBreakerConfig.builder()
                    .failureRateThreshold(50)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .build());
            Supplier<String> resilientCharge = cb.decorateSupplier(() -> service.charge("order-1"));

            // When
            var result = resilientCharge.get();

            // Then
            assertThat(result).isEqualTo("receipt-order-1");
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void should_open_after_failure_rate_threshold_is_exceeded() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.of("paymentService", CircuitBreakerConfig.builder()
                    .failureRateThreshold(50)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .build());
            Supplier<String> resilientCharge = cb.decorateSupplier(() -> service.charge("order"));

            // When — 2 successes, then 2 failures → 50% failure rate
            resilientCharge.get();
            resilientCharge.get();
            service.setFailing(true);
            catchThrowable(resilientCharge::get);
            catchThrowable(resilientCharge::get);

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
            var beforeCount = service.getCallCount();
            assertThatThrownBy(resilientCharge::get)
                    .isInstanceOf(InqCallNotPermittedException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqCallNotPermittedException) ex;
                        assertThat(inqEx.getCode()).isEqualTo("INQ-CB-001");
                        assertThat(inqEx.getElementName()).isEqualTo("paymentService");
                        assertThat(inqEx.getState()).isEqualTo(CircuitBreakerState.OPEN);
                    });
            assertThat(service.getCallCount()).isEqualTo(beforeCount);
        }

        @Test
        void should_wrap_checked_exceptions_in_inq_runtime_exception() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");
            Supplier<String> resilient = cb.decorateCallable(() -> {
                throw new java.io.IOException("disk full");
            });

            // When / Then
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqRuntimeException.class)
                    .hasCauseInstanceOf(java.io.IOException.class)
                    .satisfies(ex -> {
                        var ire = (InqRuntimeException) ex;
                        assertThat(ire.getCode()).isEqualTo("INQ-CB-000");
                        assertThat(ire.getElementType()).isEqualTo(InqElementType.CIRCUIT_BREAKER);
                    });
        }

        @Test
        void should_allow_catching_interventions_via_inq_failure() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();
            Supplier<String> resilient = cb.decorateSupplier(() -> "should not reach");

            // When
            var handled = new AtomicInteger(0);
            try {
                resilient.get();
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifCircuitBreakerOpen(info -> {
                            handled.incrementAndGet();
                            assertThat(info.getElementName()).isEqualTo("paymentService");
                        })
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);
        }
    }

    // ── Standalone — Invocation pattern ──

    @Nested
    @DisplayName("Standalone invocation usage")
    class StandaloneInvocation {

        @Test
        void should_decorate_a_single_argument_invocation_and_call_with_different_args() throws Exception {
            // Given — decorate once, reuse with different arguments
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            Invocation<String, String> resilientCharge =
                    cb.decorateInvocation(service::charge);

            // When
            var r1 = resilientCharge.invoke("order-1");
            var r2 = resilientCharge.invoke("order-2");
            var r3 = resilientCharge.invoke("order-3");

            // Then — same wrapper, different arguments, 3 calls
            assertThat(r1).isEqualTo("receipt-order-1");
            assertThat(r2).isEqualTo("receipt-order-2");
            assertThat(r3).isEqualTo("receipt-order-3");
            assertThat(service.getCallCount()).isEqualTo(3);
        }

        @Test
        void should_decorate_a_four_argument_invocation_via_varargs() throws Exception {
            // Given — 4 args, use InvocationVarargs
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            InvocationVarargs<String> resilientCharge = cb.decorateInvocation(
                    (InvocationVarargs<String>) args -> service.chargeDetailed(
                            (String) args[0], (String) args[1],
                            (Integer) args[2], (Boolean) args[3]));

            // When
            var r1 = resilientCharge.invoke("order-1", "EUR", 1000, true);
            var r2 = resilientCharge.invoke("order-2", "USD", 2500, false);

            // Then
            assertThat(r1).isEqualTo("order-1-EUR-1000-express");
            assertThat(r2).isEqualTo("order-2-USD-2500-standard");
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_reject_invocation_when_circuit_is_open() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();
            Invocation<String, String> resilientCharge =
                    cb.decorateInvocation(service::charge);

            // When / Then — different arguments don't bypass the open circuit
            assertThatThrownBy(() -> resilientCharge.invoke("order-1"))
                    .isInstanceOf(InqCallNotPermittedException.class);
            assertThatThrownBy(() -> resilientCharge.invoke("order-2"))
                    .isInstanceOf(InqCallNotPermittedException.class);
            assertThat(service.getCallCount()).isZero();
        }
    }

    // ── Pipeline — Supplier/Callable pattern ──

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_protect_a_call_through_the_pipeline() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            Supplier<String> resilient = InqPipeline.of(() -> service.charge("pipeline-1"))
                    .shield(cb)
                    .decorate();

            // When
            var result = resilient.get();

            // Then
            assertThat(result).isEqualTo("receipt-pipeline-1");
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void should_carry_a_pipeline_call_id_on_rejection() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();
            Supplier<String> resilient = InqPipeline.of(() -> "unreachable")
                    .shield(cb)
                    .decorate();

            // When / Then
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId())
                                .isNotNull()
                                .isNotEqualTo("None")
                                .hasSizeGreaterThan(8);
                    });
        }

        @Test
        void should_expose_pipeline_info_on_supplier_based_pipeline() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");
            Supplier<String> resilient = InqPipeline.of(() -> "ok")
                    .shield(cb)
                    .decorate();

            // When — supplier also implements InqPipelineProxy
            assertThat(resilient).isInstanceOf(InqPipelineProxy.class);
            var info = ((InqPipelineProxy) resilient).getPipelineInfo();

            // Then
            assertThat(info.decorators()).hasSize(1);
            assertThat(info.decorators().getFirst()).isSameAs(cb);
            assertThat(info.interfaceType()).isNull();
            assertThat(info.target()).isNull();
            assertThat(info.toChainDescription()).contains("CIRCUIT_BREAKER", "paymentService");
        }
    }

    // ── Pipeline — Invocation pattern ──

    @Nested
    @DisplayName("Pipeline invocation usage")
    class PipelineInvocation {

        @Test
        void should_compose_pipeline_with_single_argument_invocation() throws Exception {
            // Given — elements are shared singletons, pipeline chain is built per call
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");

            Invocation<String, String> resilientCharge = orderId ->
                    InqPipeline.of(() -> service.charge(orderId))
                            .shield(cb)
                            .decorate()
                            .get();

            // When
            var r1 = resilientCharge.invoke("order-1");
            var r2 = resilientCharge.invoke("order-2");

            // Then — each call goes through the pipeline with its own callId
            assertThat(r1).isEqualTo("receipt-order-1");
            assertThat(r2).isEqualTo("receipt-order-2");
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_compose_pipeline_with_four_argument_invocation() throws Exception {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");

            InvocationVarargs<String> resilientCharge = args ->
                    InqPipeline.of(() -> service.chargeDetailed(
                                    (String) args[0], (String) args[1],
                                    (Integer) args[2], (Boolean) args[3]))
                            .shield(cb)
                            .decorate()
                            .get();

            // When
            var r1 = resilientCharge.invoke("order-1", "EUR", 1000, true);
            var r2 = resilientCharge.invoke("order-2", "USD", 2500, false);

            // Then
            assertThat(r1).isEqualTo("order-1-EUR-1000-express");
            assertThat(r2).isEqualTo("order-2-USD-2500-standard");
        }
    }

    // ── Pipeline — Proxy pattern ──

    @Nested
    @DisplayName("Pipeline proxy usage")
    class PipelineProxy {

        @Test
        void should_create_a_typed_proxy_that_protects_single_argument_calls() {
            // Given — create a resilient proxy from the service interface
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");

            PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
                    .shield(cb)
                    .decorate();

            // When — call the proxy like a normal service with different arguments
            var r1 = resilient.charge("order-1");
            var r2 = resilient.charge("order-2");
            var r3 = resilient.charge("order-3");

            // Then
            assertThat(r1).isEqualTo("receipt-order-1");
            assertThat(r2).isEqualTo("receipt-order-2");
            assertThat(r3).isEqualTo("receipt-order-3");
            assertThat(service.getCallCount()).isEqualTo(3);
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void should_create_a_typed_proxy_that_protects_four_argument_calls() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");

            PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
                    .shield(cb)
                    .decorate();

            // When — 4-arg method, different arguments each time
            var r1 = resilient.chargeDetailed("order-1", "EUR", 1000, true);
            var r2 = resilient.chargeDetailed("order-2", "USD", 2500, false);

            // Then
            assertThat(r1).isEqualTo("order-1-EUR-1000-express");
            assertThat(r2).isEqualTo("order-2-USD-2500-standard");
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_reject_all_proxy_methods_when_circuit_is_open() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();

            PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
                    .shield(cb)
                    .decorate();

            // When / Then — both methods rejected, no service calls
            assertThatThrownBy(() -> resilient.charge("order-1"))
                    .isInstanceOf(InqCallNotPermittedException.class);
            assertThatThrownBy(() -> resilient.chargeDetailed("order-2", "EUR", 100, false))
                    .isInstanceOf(InqCallNotPermittedException.class);
            assertThat(service.getCallCount()).isZero();
        }

        @Test
        void should_carry_pipeline_call_id_on_proxy_exceptions() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();

            PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
                    .shield(cb)
                    .decorate();

            // When / Then
            assertThatThrownBy(() -> resilient.charge("rejected"))
                    .isInstanceOf(InqException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId())
                                .isNotNull()
                                .isNotEqualTo("None");
                    });
        }

        @Test
        void should_delegate_to_string_to_target_with_proxy_wrapper() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");

            PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
                    .shield(cb)
                    .decorate();

            // When / Then
            assertThat(resilient.toString()).contains("PaymentApi");
        }

        @Test
        void should_expose_pipeline_info_via_inq_pipeline_proxy() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.of("paymentService", CircuitBreakerConfig.builder()
                    .failureRateThreshold(50)
                    .slidingWindowSize(10)
                    .build());

            PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
                    .shield(cb)
                    .decorate();

            // When — proxy implements InqPipelineProxy
            assertThat(resilient).isInstanceOf(InqPipelineProxy.class);
            var info = ((InqPipelineProxy) resilient).getPipelineInfo();

            // Then — full introspection of the pipeline composition
            assertThat(info.decorators()).hasSize(1);
            assertThat(info.decorators().get(0)).isSameAs(cb);
            assertThat(info.order()).isEqualTo(PipelineOrder.INQUDIUM);
            assertThat(info.interfaceType()).isEqualTo(PaymentApi.class);
            assertThat(info.target()).isSameAs(service);
            assertThat(info.toChainDescription()).contains("CIRCUIT_BREAKER", "paymentService");
        }

        @Test
        void should_find_specific_decorator_by_type_in_pipeline_info() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");

            PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
                    .shield(cb)
                    .decorate();

            var info = ((InqPipelineProxy) resilient).getPipelineInfo();

            // When / Then — find by type
            assertThat(info.findDecorator(CircuitBreaker.class))
                    .isPresent()
                    .hasValueSatisfying(found -> {
                        assertThat(found.getName()).isEqualTo("paymentService");
                        assertThat(found.getState()).isEqualTo(CircuitBreakerState.CLOSED);
                    });
        }
    }
}
