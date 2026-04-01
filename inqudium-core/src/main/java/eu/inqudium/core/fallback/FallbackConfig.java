package eu.inqudium.core.fallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable configuration for a fallback provider instance.
 *
 * <p><strong>Fix 1:</strong> Handlers are stored in immutable {@link List}s for
 * safe public access and correct {@code equals}/{@code hashCode} semantics.
 * Internally, cached arrays are used for zero-allocation iteration at runtime.
 *
 * <p><strong>Fix 2:</strong> The public API uses {@code List} instead of generic
 * arrays, eliminating heap pollution from unchecked array creation.
 *
 * <p>Use {@link #builder(String)} to construct.
 */
public final class FallbackConfig<T> {

  private final String name;

  // Immutable lists for public API, equals/hashCode, and serialization
  private final List<FallbackExceptionHandler<T>> exceptionHandlers;
  private final List<FallbackResultHandler<T>> resultHandlers;

  // Cached arrays for zero-allocation iteration at runtime (never exposed)
  private final FallbackExceptionHandler<T>[] exceptionHandlerArray;
  private final FallbackResultHandler<T>[] resultHandlerArray;

  @SuppressWarnings("unchecked")
  private FallbackConfig(
      String name,
      List<FallbackExceptionHandler<T>> exceptionHandlers,
      List<FallbackResultHandler<T>> resultHandlers) {

    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(exceptionHandlers, "exceptionHandlers must not be null");
    Objects.requireNonNull(resultHandlers, "resultHandlers must not be null");

    if (exceptionHandlers.isEmpty() && resultHandlers.isEmpty()) {
      throw new IllegalArgumentException("At least one fallback handler must be registered");
    }

    // Fix 7: Validate that catch-all / default / overly broad handlers do not shadow subsequent handlers.
    validateExceptionHandlerOrdering(exceptionHandlers);
    validateResultHandlerOrdering(resultHandlers);

    // Fix 1: Store as immutable lists — safe to expose, correct equals/hashCode
    this.name = name;
    this.exceptionHandlers = List.copyOf(exceptionHandlers);
    this.resultHandlers = List.copyOf(resultHandlers);

    // Cache as arrays for zero-allocation iteration in hot paths
    this.exceptionHandlerArray = this.exceptionHandlers.toArray(new FallbackExceptionHandler[0]);
    this.resultHandlerArray = this.resultHandlers.toArray(new FallbackResultHandler[0]);
  }

  // ======================== Fix 7: Shadowing validation ========================

  private static <T> void validateExceptionHandlerOrdering(List<FallbackExceptionHandler<T>> handlers) {
    for (int i = 0; i < handlers.size() - 1; i++) {
      FallbackExceptionHandler<T> handler = handlers.get(i);

      // CatchAll and ConstantValue match everything — must be last
      if (handler instanceof FallbackExceptionHandler.CatchAll
          || handler instanceof FallbackExceptionHandler.ConstantValue) {
        throw new IllegalArgumentException(
            "Catch-all handler '%s' at position %d would shadow all subsequent handlers. "
                .formatted(handler.name(), i)
                + "Move it to the end of the handler chain or use a more specific handler.");
      }

      // ForExceptionType with Throwable.class is effectively a catch-all
      if (handler instanceof FallbackExceptionHandler.ForExceptionType<T, ?> typed
          && typed.exceptionType() == Throwable.class) {
        throw new IllegalArgumentException(
            "Handler '%s' at position %d catches Throwable.class, which shadows all subsequent handlers. "
                .formatted(handler.name(), i)
                + "Move it to the end of the handler chain or use a more specific exception type.");
      }
    }
  }

  private static <T> void validateResultHandlerOrdering(List<FallbackResultHandler<T>> handlers) {
    // Predicate-based handlers cannot be validated at compile time, but we can
    // at least warn in documentation. No structural shadowing is detectable here.
    // This method is a hook for future validation if typed result handlers are added.
  }

  // ======================== Public accessors ========================

  public static <T> Builder<T> builder(String name) {
    return new Builder<>(name);
  }

  public String name() {
    return name;
  }

  /**
   * Returns an immutable view of the exception handlers.
   */
  public List<FallbackExceptionHandler<T>> exceptionHandlers() {
    return exceptionHandlers;
  }

  // ======================== Runtime lookup (array-based, zero allocation) ========================

  /**
   * Returns an immutable view of the result handlers.
   */
  public List<FallbackResultHandler<T>> resultHandlers() {
    return resultHandlers;
  }

  /**
   * Finds the first matching exception handler using raw array iteration.
   *
   * @return the matching handler, or {@code null} if no handler matches
   */
  public FallbackExceptionHandler<T> findHandlerForException(Throwable throwable) {
    for (int i = 0; i < exceptionHandlerArray.length; i++) {
      if (exceptionHandlerArray[i].matches(throwable)) {
        return exceptionHandlerArray[i];
      }
    }
    return null;
  }

  // ======================== equals / hashCode / toString ========================

  /**
   * Finds the first matching result handler using raw array iteration.
   *
   * @return the matching handler, or {@code null} if no handler matches
   */
  public FallbackResultHandler<T> findHandlerForResult(T result) {
    for (int i = 0; i < resultHandlerArray.length; i++) {
      if (resultHandlerArray[i].matches(result)) {
        return resultHandlerArray[i];
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FallbackConfig<?> that)) return false;
    return name.equals(that.name)
        && exceptionHandlers.equals(that.exceptionHandlers)
        && resultHandlers.equals(that.resultHandlers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, exceptionHandlers, resultHandlers);
  }

  // ======================== Builder ========================

  @Override
  public String toString() {
    return "FallbackConfig[name=%s, exceptionHandlers=%d, resultHandlers=%d]"
        .formatted(name, exceptionHandlers.size(), resultHandlers.size());
  }

  public static final class Builder<T> {
    private final String name;
    private final List<FallbackExceptionHandler<T>> exceptionHandlers = new ArrayList<>();
    private final List<FallbackResultHandler<T>> resultHandlers = new ArrayList<>();

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    public <E extends Throwable> Builder<T> onException(
        Class<E> exceptionType, Function<E, T> fallback) {
      exceptionHandlers.add(new FallbackExceptionHandler.ForExceptionType<>(
          exceptionType.getSimpleName(), exceptionType, fallback));
      return this;
    }

    public <E extends Throwable> Builder<T> onException(
        String handlerName, Class<E> exceptionType, Function<E, T> fallback) {
      exceptionHandlers.add(new FallbackExceptionHandler.ForExceptionType<>(
          handlerName, exceptionType, fallback));
      return this;
    }

    public Builder<T> onExceptionMatching(
        Predicate<Throwable> predicate, Function<Throwable, T> fallback) {
      exceptionHandlers.add(new FallbackExceptionHandler.ForExceptionPredicate<>(
          "predicate-" + exceptionHandlers.size(), predicate, fallback));
      return this;
    }

    public Builder<T> onExceptionMatching(
        String handlerName, Predicate<Throwable> predicate, Function<Throwable, T> fallback) {
      exceptionHandlers.add(new FallbackExceptionHandler.ForExceptionPredicate<>(
          handlerName, predicate, fallback));
      return this;
    }

    public Builder<T> onAnyException(Function<Throwable, T> fallback) {
      exceptionHandlers.add(new FallbackExceptionHandler.CatchAll<>("catch-all", fallback));
      return this;
    }

    public Builder<T> onAnyException(String handlerName, Function<Throwable, T> fallback) {
      exceptionHandlers.add(new FallbackExceptionHandler.CatchAll<>(handlerName, fallback));
      return this;
    }

    public Builder<T> onResult(Predicate<T> predicate, Function<T, T> fallback) {
      resultHandlers.add(new FallbackResultHandler.ForResult<>(
          "result-" + resultHandlers.size(), predicate, fallback));
      return this;
    }

    public Builder<T> onResult(String handlerName, Predicate<T> predicate, Function<T, T> fallback) {
      resultHandlers.add(new FallbackResultHandler.ForResult<>(handlerName, predicate, fallback));
      return this;
    }

    public Builder<T> withDefault(T value) {
      exceptionHandlers.add(new FallbackExceptionHandler.ConstantValue<>("default", value));
      return this;
    }

    public Builder<T> withDefault(String handlerName, T value) {
      exceptionHandlers.add(new FallbackExceptionHandler.ConstantValue<>(handlerName, value));
      return this;
    }

    public FallbackConfig<T> build() {
      return new FallbackConfig<>(name, exceptionHandlers, resultHandlers);
    }
  }
}
