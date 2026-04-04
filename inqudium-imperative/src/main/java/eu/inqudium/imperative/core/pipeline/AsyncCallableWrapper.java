package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.CallableWrapper;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Async wrapper for callables that return a {@link CompletionStage}.
 *
 * <p>The async counterpart to {@link CallableWrapper}. Handles the case where starting
 * the async operation itself throws a checked exception (e.g. connection refused before
 * the Future is created). Such exceptions are wrapped in {@link CompletionException} for
 * transport through the chain and unwrapped in {@link #call()}.</p>
 *
 * @param <V> the result type carried by the CompletionStage
 */
public class AsyncCallableWrapper<V>
    extends AsyncBaseWrapper<Callable<CompletionStage<V>>, Void, V, AsyncCallableWrapper<V>>
    implements Callable<CompletionStage<V>> {

  /**
   * Creates a wrapper with an {@link InqAsyncDecorator} providing name and around-advice.
   */
  public AsyncCallableWrapper(InqAsyncDecorator<Void, V> decorator,
                              Callable<CompletionStage<V>> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /**
   * Creates a wrapper with a custom {@link AsyncLayerAction}.
   */
  public AsyncCallableWrapper(String name, Callable<CompletionStage<V>> delegate,
                              AsyncLayerAction<Void, V> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior.
   */
  public AsyncCallableWrapper(String name, Callable<CompletionStage<V>> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  private static <V> InternalAsyncExecutor<Void, V> coreFor(Callable<CompletionStage<V>> delegate) {
    return (chainId, callId, arg) -> {
      try {
        return delegate.call();
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    };
  }

  @Override
  public CompletionStage<V> call() throws Exception {
    try {
      return initiateChain(null);
    } catch (RuntimeException e) {
      if (e instanceof CompletionException) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) throw (Exception) cause;
        throw new CompletionException(cause);
      }
      throw e;
    }
  }
}
