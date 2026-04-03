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
 *   <li><strong>Wrapping in {@link #invokeCore}:</strong> Checked exceptions from the
 *       delegate are wrapped in a {@link CompletionException} for transport through
 *       the chain. {@link RuntimeException} and {@link Error} pass through unwrapped.</li>
 *   <li><strong>Unwrapping in {@link #call()}:</strong> {@code CompletionException} is
 *       caught and its cause (the original checked exception) is re-thrown, preserving
 *       the delegate's original exception contract.</li>
 * </ol>
 *
 * <h3>Why CompletionException?</h3>
 * <p>{@code CompletionException} is a JDK standard class designed exactly for this
 * purpose: transporting checked exceptions through APIs that only support unchecked ones.
 * Using a JDK type avoids introducing a custom marker exception.</p>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * Callable<Integer> core = () -> {
 *     // may throw IOException (checked)
 *     return Files.readAllLines(path).size();
 * };
 * CallableWrapper<Integer> wrapper = new CallableWrapper<>("file-reader", core);
 * int lineCount = wrapper.call();  // IOException propagates correctly
 * }</pre>
 *
 * @param <V> the return type of the callable
 */
public class CallableWrapper<V>
    extends BaseWrapper<Callable<V>, Void, V, CallableWrapper<V>>
    implements Callable<V> {

  /**
   * Creates a new wrapper layer around the given {@link Callable}.
   *
   * @param name     a descriptive name for this layer
   * @param delegate the callable to wrap (another wrapper or the core target)
   */
  public CallableWrapper(String name, Callable<V> delegate) {
    super(name, delegate);
  }

  /**
   * Implements {@link Callable#call()} by initiating the wrapper chain and
   * restoring the original exception type on the way out.
   *
   * <p>If the core delegate throws a checked exception, it arrives here as a
   * {@link CompletionException} (wrapped by {@link #invokeCore}). This method
   * extracts the original cause and re-throws it as an {@link Exception}, preserving
   * the caller's expectation that {@code call()} may throw checked exceptions.
   * All other {@link RuntimeException}s are re-thrown as-is.</p>
   *
   * @return the value produced by the core callable
   * @throws Exception the original checked exception from the delegate, if any
   */
  @Override
  public V call() throws Exception {
    try {
      // Void argument type — pass null to start the chain
      return initiateChain(null);
    } catch (RuntimeException e) {
      if (e instanceof CompletionException) {
        // Unwrap the checked exception that was wrapped by invokeCore for transport
        Throwable cause = e.getCause();
        if (cause instanceof Exception) {
          throw (Exception) cause;
        }
        // Defensive fallback — should not happen since invokeCore only wraps Exceptions
        throw new CompletionException(cause);
      }
      // All other RuntimeExceptions pass through unchanged
      throw e;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Invokes the core delegate's {@code call()} method. Exception handling strategy:</p>
   * <ul>
   *   <li>{@link RuntimeException} — re-thrown directly (no wrapping needed)</li>
   *   <li>{@link Error} — re-thrown directly (must never be swallowed)</li>
   *   <li>Checked {@link Exception} — wrapped in {@link CompletionException} for
   *       transport through the unchecked-only {@link InternalExecutor} chain</li>
   * </ul>
   */
  @Override
  protected V invokeCore(Void argument) {
    try {
      return getDelegate().call();
    } catch (RuntimeException e) {
      // Runtime exceptions propagate naturally through the chain
      throw e;
    } catch (Error e) {
      // Errors (OutOfMemoryError, StackOverflowError, etc.) must never be wrapped
      throw e;
    } catch (Exception e) {
      // Checked exceptions need wrapping for transport, unwrapped in call()
      throw new CompletionException(e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Override this method in a subclass to add cross-cutting behavior
   * (e.g. retry logic, timeout handling, or exception translation) around
   * callable executions.</p>
   */
  @Override
  protected void handleLayer(String callId, Void argument) {
    // No-op by default — extend and override to add behavior
  }
}
