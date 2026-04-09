package eu.inqudium.core.element.fallback.dsl;

// Phase 2: Der Abschluss (Hier gibt es kein fallingBackTo mehr!)
public interface TerminalFallbackProtection<T> {

  FallbackConfig<T> applyUniversalProfile();
  FallbackConfig<T> applySafeProfile();
  FallbackConfig<T> apply();
}
