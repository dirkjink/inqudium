package eu.inqudium.core.element.fallback;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

public class FallbackProviderProcessor {

    public Object resolveAndInvoke(Method targetMethod, Object[] args) throws Exception {
        DataProvidedBy config = targetMethod.getAnnotation(DataProvidedBy.class);
        if (config == null) {
            throw new IllegalArgumentException("Method is not annotated with @DataProvidedBy");
        }

        Method fallbackMethod = findFallbackInHierarchy(
                config.source(),
                config.marker(),
                targetMethod.getParameterTypes()
        );

        fallbackMethod.setAccessible(true);

        // Decide whether to pass arguments or invoke without parameters
        if (fallbackMethod.getParameterCount() == 0) {
            return fallbackMethod.invoke(null);
        } else {
            return fallbackMethod.invoke(null, args);
        }
    }

    private Method findFallbackInHierarchy(Class<?> sourceClass, Class<? extends Annotation> marker, Class<?>[] targetParamTypes) {
        Class<?> current = sourceClass;

        // Traverse the class hierarchy to include inherited methods
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.isAnnotationPresent(marker)) {
                    Class<?>[] fallbackParams = m.getParameterTypes();

                    // Rule: 0 parameters OR exact parameter match
                    if (fallbackParams.length == 0 || Arrays.equals(fallbackParams, targetParamTypes)) {
                        return m;
                    }
                }
            }
            current = current.getSuperclass();
        }

        throw new IllegalStateException("No valid fallback method found with marker " + marker.getSimpleName());
    }
}
