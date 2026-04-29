package eu.inqudium.core.element.fallback.dsl;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

// Die Klasse implementiert beide States des Builders
class DefaultFallbackProtection<T> implements FallbackNaming<T>, FallbackProtection<T>, TerminalFallbackProtection<T> {

    private List<Class<? extends Throwable>> handledExceptions = List.of(Exception.class);
    private List<Class<? extends Throwable>> ignoredExceptions = List.of();
    private Function<Throwable, T> fallbackAction;
    private String name;

    @Override
    public FallbackProtection named(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Bulkhead name must not be blank");
        }
        this.name = name;
        return this;
    }

    @SafeVarargs
    @Override
    public final FallbackProtection<T> handlingExceptions(Class<? extends Throwable>... exceptions) {
        this.handledExceptions = Arrays.asList(exceptions);
        return this;
    }

    @SafeVarargs
    @Override
    public final FallbackProtection<T> ignoringExceptions(Class<? extends Throwable>... exceptions) {
        this.ignoredExceptions = Arrays.asList(exceptions);
        return this;
    }

    @Override
    public TerminalFallbackProtection<T> fallingBackTo(Function<Throwable, T> action) {
        this.fallbackAction = action;
        return this; // Wir geben 'this' als TerminalFallbackProtection zurück
    }

    @Override
    public TerminalFallbackProtection<T> fallingBackTo(Supplier<T> action) {
        this.fallbackAction = throwable -> action.get();
        return this; // Wir geben 'this' als TerminalFallbackProtection zurück
    }

    @Override
    public FallbackConfig<T> applyUniversalProfile() {
        return new FallbackConfig<>(name, List.of(Throwable.class), List.of(), fallbackAction);
    }

    @Override
    public FallbackConfig<T> applySafeProfile() {
        return new FallbackConfig<>(name,
                List.of(Exception.class),
                List.of(IllegalArgumentException.class, IllegalStateException.class),
                fallbackAction
        );
    }

    @Override
    public FallbackConfig<T> apply() {
        return new FallbackConfig<>(name, List.copyOf(handledExceptions), List.copyOf(ignoredExceptions), fallbackAction);
    }
}
