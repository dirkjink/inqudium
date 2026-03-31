package eu.inqudium.core.fallback;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
   * Applies the fallback function to produce a substitute value.
   */
  T apply();

  // ======================== Implementations ========================

  record ForResult<T>(
      String name,
      Predicate<T> predicate,
      Supplier<T> fallback
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
    public T apply() {
      return fallback.get();
    }
  }
}
