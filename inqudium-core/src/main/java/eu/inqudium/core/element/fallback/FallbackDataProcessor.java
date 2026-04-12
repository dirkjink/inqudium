package eu.inqudium.core.element.fallback;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

public class FallbackDataProcessor {

    public Object executeFallback(Method targetMethod, Object[] targetArguments) throws Exception {
        DataProvidedBy config = targetMethod.getAnnotation(DataProvidedBy.class);
        if (config == null) {
            throw new IllegalArgumentException("Target method is not annotated with @DataProvidedBy");
        }

        Class<?> sourceClass = config.source();
        Class<? extends Annotation> markerAnnotation = config.marker();

        Method fallbackMethod = findFallbackMethod(sourceClass, markerAnnotation, targetMethod);
        fallbackMethod.setAccessible(true);

        // Invoke with or without arguments depending on the fallback method's parameter count
        if (fallbackMethod.getParameterCount() == 0) {
            return fallbackMethod.invoke(null);
        } else {
            return fallbackMethod.invoke(null, targetArguments);
        }
    }

    private Method findFallbackMethod(Class<?> sourceClass, Class<? extends Annotation> marker, Method targetMethod) {
        Class<?>[] expectedParameterTypes = targetMethod.getParameterTypes();
        Class<?> currentClass = sourceClass;

        // Traverse up the class hierarchy to include inherited methods
        while (currentClass != null && currentClass != Object.class) {
            for (Method method : currentClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(marker)) {
                    Class<?>[] fallbackParameterTypes = method.getParameterTypes();

                    boolean hasNoParameters = fallbackParameterTypes.length == 0;
                    boolean hasExactSameParameters = Arrays.equals(fallbackParameterTypes, expectedParameterTypes);

                    if (hasNoParameters || hasExactSameParameters) {
                        return method;
                    } else {
                        throw new IllegalStateException(
                                "The fallback method must either have no parameters or exactly match the target method's parameters."
                        );
                    }
                }
            }
            // Move up to the superclass
            currentClass = currentClass.getSuperclass();
        }

        throw new IllegalStateException("No valid fallback method found in the specified class or its superclasses.");
    }
}
