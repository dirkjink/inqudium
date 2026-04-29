package eu.inqudium.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates retry logic for this method or all public methods of this class.
 *
 * <p>The retry element re-executes a failed call with configurable backoff.
 * The {@link #value()} identifies the retry instance in the element registry.</p>
 *
 * <h3>Pipeline position</h3>
 * <p>In the standard Inqudium ordering (ADR-017), retry is the
 * <strong>innermost</strong> element: it retries only the actual call.
 * The circuit breaker counts each attempt individually, and the time limiter
 * bounds total wait time across all attempts.</p>
 *
 * @since 0.8.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface InqRetry {

    /**
     * The name of the retry instance to resolve from the registry.
     */
    String value();

    /**
     * The name of the fallback method to invoke when all retry attempts
     * are exhausted.
     *
     * @return the fallback method name, or empty string for no fallback
     */
    String fallbackMethod() default "";
}
