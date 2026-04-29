package eu.inqudium.spring.benchmark;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for methods that participate in the benchmark. Both the
 * baseline and the Inqudium aspects match their pointcut on this annotation,
 * so the same target method triggers each variant depending on which aspect
 * is bound to the proxy.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Benchmarked {
}
