package eu.inqudium.core.element.fallback;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A fallback handler entry that maps a Throwable condition to a recovery function.
 */
public sealed interface FallbackExceptionHandler<T> {

  /**
   * Returns a descriptive name for this handler (used in events/logging).
   */
  String name();

  /**
   * Tests whether this handler matches the given throwable.
   */
  boolean matches(Throwable throwable);

  // ======================== Implementations ========================

  record ForExceptionType<T, E extends Throwable>(
      String name,
      Class<E> exceptionType,
      Function<E, T> fallback
  ) implements FallbackExceptionHandler<T> {

    public ForExceptionType {
      Objects.requireNonNull(name);
      Objects.requireNonNull(exceptionType);
      Objects.requireNonNull(fallback);
    }

    @Override
    public boolean matches(Throwable throwable) {
      return exceptionType.isInstance(throwable);
    }

    @SuppressWarnings("unchecked")
    public T apply(Throwable throwable) {
      return fallback.apply((E) throwable);
    }
  }

  record ForExceptionPredicate<T>(
      String name,
      Predicate<Throwable> predicate,
      Function<Throwable, T> fallback
  ) implements FallbackExceptionHandler<T> {

    public ForExceptionPredicate {
      Objects.requireNonNull(name);
      Objects.requireNonNull(predicate);
      Objects.requireNonNull(fallback);
    }

    @Override
    public boolean matches(Throwable throwable) {
      return predicate.test(throwable);
    }

    public T apply(Throwable throwable) {
      return fallback.apply(throwable);
    }
  }

  record CatchAll<T>(
      String name,
      Function<Throwable, T> fallback
  ) implements FallbackExceptionHandler<T> {

    public CatchAll {
      Objects.requireNonNull(name);
      Objects.requireNonNull(fallback);
    }

    @Override
    public boolean matches(Throwable throwable) {
      return true;
    }

    public T apply(Throwable throwable) {
      return fallback.apply(throwable);
    }
  }

  record ConstantValue<T>(
      String name,
      T value
  ) implements FallbackExceptionHandler<T> {

    public ConstantValue {
      Objects.requireNonNull(name);
    }

    @Override
    public boolean matches(Throwable throwable) {
      return true;
    }

    public T apply() {
      return value;
    }
  }
}
