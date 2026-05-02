package eu.inqudium.bulkhead.integration.aspectj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation that selects the methods AspectJ should route through the example's
 * bulkhead-bearing aspect.
 *
 * <p>The annotation is module-local on purpose. A reader sees a self-contained pattern: this
 * module declares the marker, this module's {@code OrderBulkheadAspect} matches on it, this
 * module's compile-time weaver wires the two together. Nothing crosses module boundaries.
 *
 * <p>Retention is {@link RetentionPolicy#RUNTIME} so AspectJ's annotation pointcut can read
 * it from compiled bytecode after weaving. Target is method-level only — the example
 * deliberately does not support type-level coverage; a reader who needs that pattern in a
 * real application picks a wider target on their own marker.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BulkheadProtected {
}
