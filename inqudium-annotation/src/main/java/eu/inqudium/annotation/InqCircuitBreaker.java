package eu.inqudium.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates a circuit breaker for this method or all public methods of this class.
 *
 * <p>The circuit breaker monitors failure rates and short-circuits calls to
 * a failing downstream service. The {@link #value()} identifies the circuit
 * breaker instance in the element registry (ADR-015).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // On a method:
 * @InqCircuitBreaker("paymentCb")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 *
 * // On a class (applies to all public methods):
 * @InqCircuitBreaker("paymentCb")
 * public class PaymentService { ... }
 *
 * // With fallback:
 * @InqCircuitBreaker(value = "paymentCb", fallbackMethod = "onCircuitOpen")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 *
 * public PaymentResult onCircuitOpen(PaymentRequest request, Throwable t) {
 *     return PaymentResult.unavailable();
 * }
 * }</pre>
 *
 * <h3>Pipeline position</h3>
 * <p>In the standard Inqudium ordering (ADR-017), the circuit breaker sits
 * inside the bulkhead and outside the retry: each individual retry attempt
 * is recorded in the sliding window, enabling fast failure detection.</p>
 *
 * @since 0.8.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface InqCircuitBreaker {

    /**
     * The name of the circuit breaker instance to resolve from the registry.
     *
     * @return the instance name
     */
    String value();

    /**
     * The name of the fallback method to invoke when the circuit breaker
     * rejects a call or when the protected method throws an exception.
     *
     * <p>The fallback method must be in the same class and have a compatible
     * signature: same parameters as the protected method, with an additional
     * {@link Throwable} parameter at the end.</p>
     *
     * @return the fallback method name, or empty string for no fallback
     */
    String fallbackMethod() default "";
}
