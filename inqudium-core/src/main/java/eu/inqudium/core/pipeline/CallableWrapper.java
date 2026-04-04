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

  public CallableWrapper(String name, Callable<V> delegate) {
    super(name, delegate, (callId, arg) -> {
      try {
        return delegate.call();
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    });
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
