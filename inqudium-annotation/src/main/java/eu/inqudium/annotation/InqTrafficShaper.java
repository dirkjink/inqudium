package eu.inqudium.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates traffic shaping for this method or all public methods of this class.
 *
 * <p>The traffic shaper smooths bursts into a steady flow before rate
 * tokens are consumed. The {@link #value()} identifies the traffic shaper
 * instance in the element registry.</p>
 *
 * <h3>Pipeline position</h3>
 * <p>In the standard Inqudium ordering (ADR-017), the traffic shaper sits
 * inside the time limiter (shaping delays are covered by the caller's time
 * budget) and outside the rate limiter (smoothed flow consumes tokens at
 * a predictable rate).</p>
 *
 * @since 0.8.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface InqTrafficShaper {

    /** The name of the traffic shaper instance to resolve from the registry. */
    String value();

    /** @return the fallback method name, or empty string for no fallback */
    String fallbackMethod() default "";
}
