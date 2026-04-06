package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

/**
 * A homogeneous wrapper for the {@link Callable} interface.
 *
 * <p>Checked exceptions from the delegate are wrapped in {@link CompletionException}
 * for transport through the chain and unwrapped in {@link #call()}.</p>
 *
 * @param <V> the return type of the callable
 */
public class CallableWrapper<V>
    extends BaseWrapper<Callable<V>, Void, V, CallableWrapper<V>>
    implements Callable<V> {

  public CallableWrapper(InqDecorator<Void, V> decorator, Callable<V> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  public CallableWrapper(String name, Callable<V> delegate, LayerAction<Void, V> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  public CallableWrapper(String name, Callable<V> delegate) {
    this(name, delegate, LayerAction.passThrough());
  }

  private static <V> InternalExecutor<Void, V> coreFor(Callable<V> delegate) {
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
  public V call() throws Exception {
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
