package eu.inqudium.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates rate limiting for this method or all public methods of this class.
 *
 * <p>The rate limiter controls the rate at which calls enter the pipeline
 * via a token bucket algorithm. The {@link #value()} identifies the rate
 * limiter instance in the element registry.</p>
 *
 * <h3>Pipeline position</h3>
 * <p>In the standard Inqudium ordering (ADR-017), the rate limiter sits
 * outside the bulkhead and circuit breaker. Retries don't consume additional
 * rate limit permits since retry is innermost.</p>
 *
 * @since 0.8.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface InqRateLimiter {

    /**
     * The name of the rate limiter instance to resolve from the registry.
     */
    String value();

    /**
     * @return the fallback method name, or empty string for no fallback
     */
    String fallbackMethod() default "";
}
