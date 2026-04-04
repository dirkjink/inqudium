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

  /** Creates a wrapper with a {@link Decorator} providing name and around-advice. */
  public CallableWrapper(Decorator<Void, V> decorator, Callable<V> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /** Creates a wrapper with a custom {@link LayerAction}. */
  public CallableWrapper(String name, Callable<V> delegate, LayerAction<Void, V> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /** Creates a wrapper with pass-through behavior. */
  public CallableWrapper(String name, Callable<V> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  @Override
  public V call() throws Exception {
    try {
      return initiateChain(null);
    } catch (RuntimeException e) {
      if (e instanceof CompletionException) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) {
          throw (Exception) cause;
        }
        throw new CompletionException(cause);
      }
      throw e;
    }
  }
}
