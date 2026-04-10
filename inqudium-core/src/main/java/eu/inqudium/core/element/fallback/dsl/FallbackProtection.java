package eu.inqudium.core.element.fallback.dsl;

import java.util.function.Function;
import java.util.function.Supplier;

// Phase 1: Konfiguration und Zwang zur Entscheidung
public interface FallbackProtection<T> {

  @SuppressWarnings("unchecked")
  FallbackProtection<T> handlingExceptions(Class<? extends Throwable>... exceptions);

  @SuppressWarnings("unchecked")
  FallbackProtection<T> ignoringExceptions(Class<? extends Throwable>... exceptions);

  // Die Weiche: Sobald EINE dieser Methoden gerufen wird, wechseln wir in Phase 2
  TerminalFallbackProtection<T> fallingBackTo(Function<Throwable, T> action);

  TerminalFallbackProtection<T> fallingBackTo(Supplier<T> action);
}