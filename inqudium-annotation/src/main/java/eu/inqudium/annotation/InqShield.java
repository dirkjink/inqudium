package eu.inqudium.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the pipeline ordering for Inqudium element annotations on this
 * method or class.
 *
 * <p>{@code @InqShield} is <strong>optional</strong> for the default case.
 * The presence of any Inqudium element annotation (e.g. {@link InqCircuitBreaker},
 * {@link InqRetry}) on a method or class activates the pipeline with
 * {@code INQUDIUM} ordering by default. {@code @InqShield} is only required
 * when a non-default ordering is needed.</p>
 *
 * <h3>When @InqShield is needed vs. optional</h3>
 * <table>
 *   <tr><th>Scenario</th><th>{@code @InqShield} needed?</th></tr>
 *   <tr><td>Canonical order (default)</td><td>No — element annotations alone are sufficient</td></tr>
 *   <tr><td>Canonical order (explicit)</td><td>Optional — equivalent to absent</td></tr>
 *   <tr><td>Resilience4J order</td><td>Yes — {@code @InqShield(order = "RESILIENCE4J")}</td></tr>
 *   <tr><td>Custom order</td><td>Yes — {@code @InqShield(order = "CUSTOM")}</td></tr>
 * </table>
 *
 * <h3>Usage examples</h3>
 * <pre>{@code
 * // Simplest form — no @InqShield needed for canonical order:
 * @InqCircuitBreaker("paymentCb")
 * @InqRetry("paymentRetry")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 *
 * // Explicit Resilience4J ordering:
 * @InqShield(order = "RESILIENCE4J")
 * @InqCircuitBreaker("paymentCb")
 * @InqRetry("paymentRetry")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 *
 * // Custom ordering — elements applied in annotation declaration order:
 * @InqShield(order = "CUSTOM")
 * @InqRetry("rt")
 * @InqCircuitBreaker("cb")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 * }</pre>
 *
 * <h3>TYPE-level usage</h3>
 * <p>When placed on a class, applies to all public methods of that class.
 * Method-level {@code @InqShield} overrides the class-level ordering.</p>
 *
 * @since 0.8.0
 * @see InqCircuitBreaker
 * @see InqRetry
 * @see InqRateLimiter
 * @see InqBulkhead
 * @see InqTimeLimiter
 * @see InqTrafficShaper
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface InqShield {

    /**
     * The pipeline ordering to use.
     *
     * <ul>
     *   <li>{@code "INQUDIUM"} (default) — elements sorted into canonical order
     *       (ADR-017): TimeLimiter → TrafficShaper → RateLimiter → Bulkhead →
     *       CircuitBreaker → Retry</li>
     *   <li>{@code "RESILIENCE4J"} — elements sorted into R4J-compatible order:
     *       Retry → CircuitBreaker → TrafficShaper → RateLimiter → TimeLimiter →
     *       Bulkhead</li>
     *   <li>{@code "CUSTOM"} — elements applied in annotation declaration order
     *       on the method</li>
     * </ul>
     *
     * <p>String values are used instead of an enum to keep this annotation
     * artifact free of runtime dependencies.</p>
     *
     * @return the ordering name
     */
    String order() default "INQUDIUM";
}
