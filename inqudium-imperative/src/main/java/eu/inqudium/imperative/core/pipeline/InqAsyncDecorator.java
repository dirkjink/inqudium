package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.JoinPointExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A self-describing, pluggable async pipeline element with around-semantics.
 *
 * <p>The async counterpart to {@link InqDecorator InqDecorator}. Combines
 * {@link InqElement} (name, type, event publisher) with {@link AsyncLayerAction}
 * (async around-advice) and provides {@code decorateAsyncXxx()} factory methods
 * that create reusable async wrapper objects for deferred execution.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Bulkhead implements both InqDecorator (sync) and InqAsyncDecorator (async)
 * Bulkhead<Void, String> bulkhead = Bulkhead.of(config);
 *
 * // Async factory methods — delegate returns CompletionStage
 * Supplier<CompletionStage<Void>>   asyncRun  = bulkhead.decorateAsyncRunnable(() -> sendAsync(msg));
 * Supplier<CompletionStage<String>> asyncGet  = bulkhead.decorateAsyncSupplier(() -> fetchAsync());
 * Callable<CompletionStage<String>> asyncCall = bulkhead.decorateAsyncCallable(() -> readAsync());
 * Function<String, CompletionStage<Integer>> asyncFn = bulkhead.decorateAsyncFunction(this::loadAsync);
 * ProxyExecution<CompletionStage<String>>    asyncJp = bulkhead.decorateAsyncJoinPoint(pjp::proceed);
 *
 * // Compose: retry → bulkhead → async core
 * Supplier<CompletionStage<String>> resilient = retry.decorateAsyncSupplier(
 *     bulkhead.decorateAsyncSupplier(() -> callApiAsync())
 * );
 * }</pre>
 *
 * @param <A> the argument type flowing through the chain
 * @param <R> the result type carried by the CompletionStage
 */
public interface InqAsyncDecorator<A, R> extends InqElement, AsyncLayerAction<A, R> {

  /**
   * Wraps an async runnable in an {@link AsyncRunnableWrapper}.
   *
   * @param delegate the async operation to protect
  /**
   * Wraps a {@link Runnable} in an {@link AsyncRunnableWrapper}.
   *
   * <p>The Runnable executes synchronously at the core of the chain. The async
   * behavior comes from the surrounding {@link AsyncLayerAction} layers (e.g.
   * acquire permit synchronously, release on stage completion).</p>
   *
   * @param delegate the operation to protect
   * @return a decorated supplier that returns {@code CompletionStage<Void>} on each {@code get()}
   */
  @SuppressWarnings("unchecked")
  default Supplier<CompletionStage<Void>> decorateAsyncRunnable(Runnable delegate) {
    return new AsyncRunnableWrapper((InqAsyncDecorator<Void, Void>) this, delegate);
  }

  /**
   * Wraps an async supplier in an {@link AsyncSupplierWrapper}.
   *
   * @param delegate the async operation to protect
   * @param <T>      the result type
   * @return a decorated supplier that returns {@code CompletionStage<T>} on each {@code get()}
   */
  @SuppressWarnings("unchecked")
  default <T> Supplier<CompletionStage<T>> decorateAsyncSupplier(
      Supplier<CompletionStage<T>> delegate) {
    return new AsyncSupplierWrapper<>((InqAsyncDecorator<Void, T>) this, delegate);
  }

  /**
   * Wraps an async callable in an {@link AsyncCallableWrapper}.
   *
   * @param delegate the async operation to protect (may throw checked exceptions on start)
   * @param <V>      the result type
   * @return a decorated callable that returns {@code CompletionStage<V>} on each {@code call()}
   */
  @SuppressWarnings("unchecked")
  default <V> Callable<CompletionStage<V>> decorateAsyncCallable(
      Callable<CompletionStage<V>> delegate) {
    return new AsyncCallableWrapper<>((InqAsyncDecorator<Void, V>) this, delegate);
  }

  /**
   * Wraps an async function in an {@link AsyncFunctionWrapper}.
   * Fully type-safe — {@code A} and {@code R} match the function's types.
   *
   * @param delegate the async operation to protect
   * @return a decorated function that returns {@code CompletionStage<R>} on each {@code apply()}
   */
  default Function<A, CompletionStage<R>> decorateAsyncFunction(
      Function<A, CompletionStage<R>> delegate) {
    return new AsyncFunctionWrapper<>(this, delegate);
  }

  /**
   * Wraps an async proxy execution in an {@link AsyncJoinPointWrapper}.
   *
   * @param delegate the async proxy operation to protect
   * @param <T>      the result type
   * @return a decorated proxy execution that returns {@code CompletionStage<T>} on each {@code proceed()}
   */
  @SuppressWarnings("unchecked")
  default <T> JoinPointExecutor<CompletionStage<T>> decorateAsyncJoinPoint(
      JoinPointExecutor<CompletionStage<T>> delegate) {
    return new AsyncJoinPointWrapper<>((InqAsyncDecorator<Void, T>) this, delegate);
  }
}
