package eu.inqudium.core.fallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Immutable configuration for a fallback provider instance.
 */
public record FallbackConfig<T>(
    String name,
    List<FallbackExceptionHandler<T>> exceptionHandlers,
    List<FallbackResultHandler<T>> resultHandlers
) {

  public FallbackConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(exceptionHandlers, "exceptionHandlers must not be null");
    Objects.requireNonNull(resultHandlers, "resultHandlers must not be null");
    exceptionHandlers = List.copyOf(exceptionHandlers);
    resultHandlers = List.copyOf(resultHandlers);

    if (exceptionHandlers.isEmpty() && resultHandlers.isEmpty()) {
      throw new IllegalArgumentException("At least one fallback handler must be registered");
    }
  }

  public static <T> Builder<T> builder(String name) {
    return new Builder<>(name);
  }

  public FallbackExceptionHandler<T> findHandlerForException(Throwable throwable) {
    for (FallbackExceptionHandler<T> handler : exceptionHandlers) {
      if (handler.matches(throwable)) {
        return handler;
      }
    }
    return null;
  }

  public FallbackResultHandler<T> findHandlerForResult(T result) {
    for (FallbackResultHandler<T> handler : resultHandlers) {
      if (handler.matches(result)) {
        return handler;
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

    public Builder<T> onResult(Predicate<T> predicate, Supplier<T> fallback) {
      resultHandlers.add(new FallbackResultHandler.ForResult<>(
          "result-" + resultHandlers.size(), predicate, fallback));
      return this;
    }

    public Builder<T> onResult(String handlerName, Predicate<T> predicate, Supplier<T> fallback) {
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