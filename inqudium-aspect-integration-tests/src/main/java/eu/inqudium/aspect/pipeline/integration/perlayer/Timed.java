package eu.inqudium.aspect.pipeline.integration.perlayer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates the timing layer for this method.
 * Can be combined with {@link Authorized} and {@link Logged}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Timed {
}
