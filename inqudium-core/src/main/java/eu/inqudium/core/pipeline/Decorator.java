package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A self-describing, pluggable pipeline element with around-semantics.
 *
 * <p>{@code Decorator} unifies two concerns into a single interface:</p>
 * <ul>
 *   <li>{@link InqElement} — provides metadata: name, element type, and event publisher</li>
 *   <li>{@link LayerAction} — provides the around-advice for chain interception</li>
 * </ul>
 *
 * <h3>Factory Methods</h3>
 * <p>Every decorator comes with factory methods that wrap a delegate directly,
 * without requiring the caller to know about wrapper constructors:</p>
 * <pre>{@code
 * BulkheadDecorator<Void, String> bulkhead = new BulkheadDecorator<>("pool", 10);
 *
 * // Factory methods — one line per wrapper type
 * Runnable       protectedRun  = bulkhead.decorateRunnable(() -> sendEmail());
 * Supplier<String> protectedGet  = bulkhead.decorateSupplier(() -> fetchData());
 * Callable<String> protectedCall = bulkhead.decorateCallable(() -> readFile());
 * Function<String, Integer> protectedFn = bulkhead.decorateFunction(Integer::parseInt);
 * }</pre>
 *
 * <h3>Chaining Multiple Decorators</h3>
 * <pre>{@code
 * BulkheadDecorator<Void, String> bulkhead = new BulkheadDecorator<>("bulkhead", 10);
 * RetryDecorator<Void, String> retry = new RetryDecorator<>("retry", 3);
 *
 * // Compose: retry wraps bulkhead wraps core
 * Supplier<String> protected = retry.decorateSupplier(
 *     bulkhead.decorateSupplier(() -> callApi())
 * );
 * }</pre>
 *
 * <h3>Type Safety</h3>
 * <p>The factory methods use the decorator's type parameters where possible.
 * {@link #decorateFunction} is fully type-safe. The Void-argument methods
 * ({@link #decorateRunnable}, {@link #decorateSupplier}, {@link #decorateCallable},
 * {@link #decorateJoinPoint}) work correctly when the decorator's argument type
 * is {@code Void}, which is the case for all standard resilience decorators.</p>
 *
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 */
public interface Decorator<A, R> extends InqElement, LayerAction<A, R> {

  /**
   * Wraps a {@link Runnable} in a {@link RunnableWrapper} using this decorator's
   * name and around-advice.
   *
   * <p>Intended for decorators with type parameters {@code <Void, Void>}.</p>
   *
   * @param delegate the runnable to protect
   * @return a decorated runnable that applies this decorator's logic on every {@code run()}
   */
  @SuppressWarnings("unchecked")
  default Runnable decorateRunnable(Runnable delegate) {
    return new RunnableWrapper((Decorator<Void, Void>) this, delegate);
  }

  /**
   * Wraps a {@link Supplier} in a {@link SupplierWrapper} using this decorator's
   * name and around-advice.
   *
   * <p>Intended for decorators with type parameters {@code <Void, T>}.</p>
   *
   * @param delegate the supplier to protect
   * @param <T>      the return type of the supplier
   * @return a decorated supplier that applies this decorator's logic on every {@code get()}
   */
  @SuppressWarnings("unchecked")
  default <T> Supplier<T> decorateSupplier(Supplier<T> delegate) {
    return new SupplierWrapper<>((Decorator<Void, T>) this, delegate);
  }

  /**
   * Wraps a {@link Callable} in a {@link CallableWrapper} using this decorator's
   * name and around-advice.
   *
   * <p>Intended for decorators with type parameters {@code <Void, V>}.</p>
   *
   * @param delegate the callable to protect
   * @param <V>      the return type of the callable
   * @return a decorated callable that applies this decorator's logic on every {@code call()}
   */
  @SuppressWarnings("unchecked")
  default <V> Callable<V> decorateCallable(Callable<V> delegate) {
    return new CallableWrapper<>((Decorator<Void, V>) this, delegate);
  }

  /**
   * Wraps a {@link Function} in a {@link FunctionWrapper} using this decorator's
   * name and around-advice.
   *
   * <p>This method is fully type-safe — the decorator's type parameters {@code <A, R>}
   * naturally match the function's input and output types.</p>
   *
   * @param delegate the function to protect
   * @return a decorated function that applies this decorator's logic on every {@code apply()}
   */
  default Function<A, R> decorateFunction(Function<A, R> delegate) {
    return new FunctionWrapper<>(this, delegate);
  }

  /**
   * Wraps a {@link ProxyExecution} (e.g. a Spring AOP join point) in a
   * {@link JoinPointWrapper} using this decorator's name and around-advice.
   *
   * <p>Intended for decorators with type parameters {@code <Void, T>}.</p>
   *
   * @param delegate the proxy execution to protect
   * @param <T>      the return type of the proxy execution
   * @return a decorated join point that applies this decorator's logic on every {@code proceed()}
   */
  @SuppressWarnings("unchecked")
  default <T> ProxyExecution<T> decorateJoinPoint(ProxyExecution<T> delegate) {
    return new JoinPointWrapper<>((Decorator<Void, T>) this, delegate);
  }
}
