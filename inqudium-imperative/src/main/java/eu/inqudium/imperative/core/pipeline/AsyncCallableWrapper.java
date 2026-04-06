package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.CallableWrapper;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Async wrapper for callables that return a {@link CompletionStage}.
 *
 * <p>The async counterpart to {@link CallableWrapper}. Checked exceptions from starting
 * the async operation are wrapped in {@link CompletionException} for chain transport
 * and unwrapped in {@link #call()}.</p>
 *
 * @param <V> the result type carried by the CompletionStage
 */
public class AsyncCallableWrapper<V>
    extends AsyncBaseWrapper<Callable<CompletionStage<V>>, Void, V, AsyncCallableWrapper<V>>
    implements Callable<CompletionStage<V>> {

  public AsyncCallableWrapper(InqAsyncDecorator<Void, V> decorator,
                              Callable<CompletionStage<V>> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  public AsyncCallableWrapper(String name, Callable<CompletionStage<V>> delegate,
                              AsyncLayerAction<Void, V> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  public AsyncCallableWrapper(String name, Callable<CompletionStage<V>> delegate) {
    this(name, delegate, AsyncLayerAction.passThrough());
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
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception ex) {
        throw ex;
      }
      throw new CompletionException(cause);
    }
  }
}
