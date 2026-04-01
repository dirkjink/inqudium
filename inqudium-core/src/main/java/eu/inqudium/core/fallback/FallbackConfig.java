package eu.inqudium.core.fallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable configuration for a fallback provider instance.
 */
public record FallbackConfig<T>(
    String name,
    FallbackExceptionHandler<T>[] exceptionHandlers,
    FallbackResultHandler<T>[] resultHandlers
) {

  public FallbackConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(exceptionHandlers, "exceptionHandlers must not be null");
    Objects.requireNonNull(resultHandlers, "resultHandlers must not be null");

    // Defensive copy to maintain immutability
    exceptionHandlers = exceptionHandlers.clone();
    resultHandlers = resultHandlers.clone();

    if (exceptionHandlers.length == 0 && resultHandlers.length == 0) {
      throw new IllegalArgumentException("At least one fallback handler must be registered");
    }

    // Validate that catch-all / default handlers do not shadow subsequent handlers.
    for (int i = 0; i < exceptionHandlers.length - 1; i++) {
      FallbackExceptionHandler<T> handler = exceptionHandlers[i];
      if (handler instanceof FallbackExceptionHandler.CatchAll
          || handler instanceof FallbackExceptionHandler.ConstantValue) {
        throw new IllegalArgumentException(
            "Catch-all handler '%s' at position %d would shadow all subsequent handlers. "
                .formatted(handler.name(), i)
                + "Move it to the end of the handler chain or use a more specific handler.");
      }
    }
  }

  public static <T> Builder<T> builder(String name) {
    return new Builder<>(name);
  }

  public FallbackExceptionHandler<T> findHandlerForException(Throwable throwable) {
    // Zero iterator allocation: using raw array iteration
    for (int i = 0; i < exceptionHandlers.length; i++) {
      if (exceptionHandlers[i].matches(throwable)) {
        return exceptionHandlers[i];
      }
    }
    return null;
  }

  public FallbackResultHandler<T> findHandlerForResult(T result) {
    // Zero iterator allocation: using raw array iteration
    for (int i = 0; i < resultHandlers.length; i++) {
      if (resultHandlers[i].matches(result)) {
        return resultHandlers[i];
      }
    }
    return null;
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

    @SuppressWarnings("unchecked")
    public FallbackConfig<T> build() {
      return new FallbackConfig<>(
          name,
          exceptionHandlers.toArray(new FallbackExceptionHandler[0]),
          resultHandlers.toArray(new FallbackResultHandler[0])
      );
    }
  }
}