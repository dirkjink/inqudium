package eu.inqudium.core.element.fallback;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A fallback handler entry that evaluates the result of a successful execution.
 */
public sealed interface FallbackResultHandler<T> {

    /**
     * Returns a descriptive name for this handler (used in events/logging).
     */
    String name();

    /**
     * Tests whether this handler matches the given result (i.e. if a fallback is needed).
     */
    boolean matches(T result);

    /**
     * Applies the fallback function to produce a substitute value based on the rejected result.
     */
    T apply(T result);

    // ======================== Implementations ========================

    record ForResult<T>(
            String name,
            Predicate<T> predicate,
            Function<T, T> fallback // Fix 1: Nutzt nun Function statt Supplier
    ) implements FallbackResultHandler<T> {

        public ForResult {
            Objects.requireNonNull(name);
            Objects.requireNonNull(predicate);
            Objects.requireNonNull(fallback);
        }

        @Override
        public boolean matches(T result) {
            return predicate.test(result);
        }

        @Override
        public T apply(T result) {
            return fallback.apply(result); // Fix 1: Übergibt das Original-Ergebnis
        }
    }
}