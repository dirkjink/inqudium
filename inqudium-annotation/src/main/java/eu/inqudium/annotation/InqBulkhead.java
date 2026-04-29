package eu.inqudium.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates bulkhead isolation for this method or all public methods of this class.
 *
 * <p>The bulkhead limits the number of concurrent calls to a service,
 * preventing resource exhaustion. The {@link #value()} identifies the
 * bulkhead instance in the element registry.</p>
 *
 * <h3>Pipeline position</h3>
 * <p>In the standard Inqudium ordering (ADR-017), the bulkhead sits
 * inside the rate limiter and outside the circuit breaker: concurrency
 * is bounded at the pipeline level, not just the call level.</p>
 *
 * @since 0.8.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface InqBulkhead {

    /**
     * The name of the bulkhead instance to resolve from the registry.
     */
    String value();

    /**
     * @return the fallback method name, or empty string for no fallback
     */
    String fallbackMethod() default "";
}
