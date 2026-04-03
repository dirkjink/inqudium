package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

/**
 * A homogeneous wrapper for the {@link Callable} interface.
 * Takes no arguments (Void) but returns a value and may throw checked exceptions.
 *
 * @param <V> the return type of the callable
 */
public class CallableWrapper<V>
    extends BaseWrapper<Callable<V>, Void, V, CallableWrapper<V>>
    implements Callable<V> {

  public CallableWrapper(String name, Callable<V> delegate) {
    super(name, delegate);
  }

  @Override
  public V call() throws Exception {
    try {
      return initiateChain(null);
    } catch (CompletionException e) {
      // Only unwrap exceptions that were explicitly wrapped by invokeCore
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      // Should not happen, but handle gracefully
      throw new RuntimeException(cause);
    }
  }

  @Override
  protected V invokeCore(Void argument) {
    try {
      return getDelegate().call();
    } catch (RuntimeException e) {
      // Let runtime exceptions pass through unwrapped
      throw e;
    } catch (Error e) {
      // Let errors pass through unwrapped
      throw e;
    } catch (Exception e) {
      // Wrap only checked exceptions for transport through the InternalExecutor chain
      throw new CompletionException(e);
    }
  }

  @Override
  protected void handleLayer(String callId, Void argument) {
    // Layer-specific logic with access to the call ID
  }
}
