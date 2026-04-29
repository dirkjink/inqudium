package eu.inqudium.core.element.fallback.dsl;

// Terminal stage: a fallback action has already been chosen, so only profile
// or apply terminators are exposed — no more fallingBackTo.
public interface TerminalFallbackProtection<T> {

    FallbackConfig<T> applyUniversalProfile();

    FallbackConfig<T> applySafeProfile();

    FallbackConfig<T> apply();
}
