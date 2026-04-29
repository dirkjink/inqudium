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

        // Entscheiden, ob Argumente übergeben werden müssen oder nicht
        if (fallbackMethod.getParameterCount() == 0) {
            return fallbackMethod.invoke(null);
        } else {
            return fallbackMethod.invoke(null, args);
        }
    }

    private Method findFallbackInHierarchy(Class<?> sourceClass, Class<? extends Annotation> marker, Class<?>[] targetParamTypes) {
        Class<?> current = sourceClass;

        // Die Hierarchie hinaufklettern
        while (current != null && current != Object.class) {
            Method fallbackWithZeroParams = null;

            for (Method m : current.getDeclaredMethods()) {
                if (m.isAnnotationPresent(marker)) {
                    Class<?>[] fallbackParams = m.getParameterTypes();

                    // Priorität 1: Ein exakter Match wird sofort zurückgegeben
                    if (Arrays.equals(fallbackParams, targetParamTypes)) {
                        return m;
                    }

                    // Priorität 2: 0-Parameter Match merken, falls kein exakter Match existiert
                    if (fallbackParams.length == 0) {
                        fallbackWithZeroParams = m;
                    }
                }
            }

            // Wenn die Schleife durch ist: Gab es keinen exakten Match, aber einen 0-Parameter Match?
            if (fallbackWithZeroParams != null) {
                return fallbackWithZeroParams;
            }

            // Weder noch gefunden -> in der Oberklasse weitersuchen
            current = current.getSuperclass();
        }

        throw new IllegalStateException("No valid fallback method found with marker " + marker.getSimpleName());
    }
}