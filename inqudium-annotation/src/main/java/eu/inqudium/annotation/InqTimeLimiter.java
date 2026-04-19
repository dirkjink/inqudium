package eu.inqudium.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates a time limiter for this method or all public methods of this class.
 *
 * <p>The time limiter bounds the total caller wait time — including shaping
 * delays, rate limiter waits, and all retry attempts (ADR-010). The
 * {@link #value()} identifies the time limiter instance in the element registry.</p>
 *
 * <h3>Pipeline position</h3>
 * <p>In the standard Inqudium ordering (ADR-017), the time limiter is the
 * <strong>outermost</strong> element: it bounds the total time the caller
 * waits, regardless of how many retries or delays happen inside.</p>
 *
 * @since 0.8.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface InqTimeLimiter {

    /**
     * The name of the time limiter instance to resolve from the registry.
     */
    String value();

    /**
     * @return the fallback method name, or empty string for no fallback
     */
    String fallbackMethod() default "";
}
