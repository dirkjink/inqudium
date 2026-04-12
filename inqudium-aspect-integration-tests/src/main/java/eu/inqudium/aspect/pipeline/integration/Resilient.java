package eu.inqudium.aspect.pipeline.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for resilience pipeline processing.
 *
 * <p>Methods annotated with {@code @Resilient} are intercepted by the
 * {@link ResilienceAspect}, which routes the call through a configurable
 * layer pipeline (authorization, logging, timing, etc.).</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Resilient {
}
