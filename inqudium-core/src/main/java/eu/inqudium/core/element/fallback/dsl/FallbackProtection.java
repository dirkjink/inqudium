package eu.inqudium.core.element.fallback.dsl;

import java.util.function.Function;
import java.util.function.Supplier;

// Configuration stage: callers refine exception handling here and must
// eventually pick a fallback action.
public interface FallbackProtection<T> {

    @SuppressWarnings("unchecked")
    FallbackProtection<T> handlingExceptions(Class<? extends Throwable>... exceptions);

    @SuppressWarnings("unchecked")
    FallbackProtection<T> ignoringExceptions(Class<? extends Throwable>... exceptions);

    // Calling either fallingBackTo overload transitions the builder to the
    // terminal stage — fallingBackTo is no longer offered after this point.
    TerminalFallbackProtection<T> fallingBackTo(Function<Throwable, T> action);

    TerminalFallbackProtection<T> fallingBackTo(Supplier<T> action);
}