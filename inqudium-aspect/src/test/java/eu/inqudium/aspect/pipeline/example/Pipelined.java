package eu.inqudium.aspect.pipeline.example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for pipeline-based cross-cutting concerns.
 *
 * <p>Methods annotated with {@code @Pipelined} are intercepted by the
 * {@link PipelinedAspect}, which assembles a wrapper chain of authorization,
 * logging, and timing layers around the method execution.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pipelined {
}
