package eu.inqudium.core.element.fallback.dsl;

import java.util.List;
import java.util.function.Function;

/**
 * The immutable configuration and action for a Fallback strategy.
 *
 * @param <T> The expected return type of the protected method.
 */
public record FallbackConfig<T>(
        List<Class<? extends Throwable>> handledExceptions,
        List<Class<? extends Throwable>> ignoredExceptions,
        Function<Throwable, T> fallbackAction
) {
}
