package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

/**
 * A homogeneous wrapper for the {@link Callable} interface.
 *
 * <p>Like {@link SupplierWrapper}, this takes no arguments ({@code Void}) but returns
 * a value. The key difference is that {@code Callable} supports checked exceptions.
 * Since the internal {@link InternalExecutor} chain can only propagate unchecked
 * exceptions, this wrapper uses a two-phase exception strategy:</p>
 *
 * <ol>
 *   <li><strong>Wrapping at the core:</strong> Checked exceptions from the delegate
 *       are wrapped in a {@link CompletionException} for transport through the chain.
 *       {@link RuntimeException} and {@link Error} pass through unwrapped.</li>
 *   <li><strong>Unwrapping in {@link #call()}:</strong> {@code CompletionException} is
 *       caught and its cause (the original checked exception) is re-thrown, preserving
 *       the delegate's original exception contract.</li>
 * </ol>
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
        // Runtime exceptions and errors pass through unwrapped
        throw e;
      } catch (Exception e) {
        // Checked exceptions need wrapping for transport, unwrapped in call()
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
