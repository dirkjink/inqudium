package eu.inqudium.core.element.fallback;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Configures the data source for a method.
 * If the method's logic should be supplemented or replaced by a fallback,
 * this annotation defines where to find that fallback.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DataProvidedBy {

    // The class containing the fallback method
    Class<?> source();

    // The annotation marking the fallback method (defaults to @FallbackData)
    Class<? extends Annotation> marker() default FallbackData.class;
}
