package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

/**
 * A homogeneous wrapper for the {@link Callable} interface.
 *
 * <p>{@code Callable} differs from {@code Supplier} in that it declares
 * {@code throws Exception}. This creates a challenge for the pipeline, because
 * {@link LayerAction} and {@link InternalExecutor} do not declare checked
 * exceptions. The solution is a two-phase transport mechanism:</p>
 *
 * <ol>
 *   <li><strong>Wrapping on entry:</strong> The core execution lambda catches
 *       checked exceptions from the delegate and wraps them in
 *       {@link CompletionException} for transport through the chain.</li>
 *   <li><strong>Unwrapping on exit:</strong> The {@link #call()} method catches
 *       {@code CompletionException} and re-throws the original checked exception,
 *       preserving the delegate's declared exception types.</li>
 * </ol>
 *
 * <p>Runtime exceptions and errors are never wrapped — they propagate directly
 * through the chain without any transformation.</p>
 *
 * @param <V> the return type of the callable
 */
public class CallableWrapper<V>
        extends BaseWrapper<Callable<V>, Void, V, CallableWrapper<V>>
        implements Callable<V> {

    /**
     * Creates a wrapper using a decorator's name and around-advice.
     *
     * @param decorator the decorator providing metadata and layer logic
     * @param delegate  the callable to wrap
     */
    public CallableWrapper(InqDecorator<Void, V> decorator, Callable<V> delegate) {
        super(decorator, delegate, coreFor(delegate));
    }

    /**
     * Creates a wrapper with an explicit name and layer action.
     *
     * @param name        human-readable layer name
     * @param delegate    the callable to wrap
     * @param layerAction the around-advice for this layer
     */
    public CallableWrapper(String name, Callable<V> delegate, LayerAction<Void, V> layerAction) {
        super(name, delegate, coreFor(delegate), layerAction);
    }

    /**
     * Creates a pass-through wrapper (structural only, no custom behavior).
     *
     * @param name     human-readable layer name
     * @param delegate the callable to wrap
     */
    public CallableWrapper(String name, Callable<V> delegate) {
        this(name, delegate, LayerAction.passThrough());
    }

    /**
     * Builds the terminal core execution lambda for {@code Callable<V>}.
     *
     * <p>Invokes {@code delegate.call()} and handles the exception contract:
     * runtime exceptions and errors propagate directly, while checked exceptions
     * are wrapped in {@link CompletionException} for safe transport through the
     * chain's {@link LayerAction} layers (which don't declare checked exceptions).</p>
     *
     * @param delegate the real callable to invoke at the end of the chain
     * @param <V>      the callable's return type
     * @return a terminal {@link InternalExecutor} with checked-exception wrapping
     */
    private static <V> InternalExecutor<Void, V> coreFor(Callable<V> delegate) {
        return (chainId, callId, arg) -> {
            try {
                return delegate.call();
            } catch (RuntimeException | Error e) {
                // Runtime exceptions and errors pass through without wrapping —
                // they are already unchecked and safe for the chain
                throw e;
            } catch (Exception e) {
                // Checked exceptions must be wrapped for transport through the chain,
                // since InternalExecutor.execute() does not declare checked exceptions
                throw new CompletionException(e);
            }
        };
    }

    /**
     * Entry point: initiates chain traversal and unwraps transported checked exceptions.
     *
     * <p>If a {@link CompletionException} arrives, its cause is extracted and re-thrown
     * as the original checked exception type. This preserves the {@code Callable}
     * contract — callers see the same exception types they would see from the
     * unwrapped delegate.</p>
     *
     * @return the value produced by the delegate callable
     * @throws Exception the original checked exception from the delegate, if any
     */
    @Override
    public V call() throws Exception {
        try {
            return initiateChain(null);
        } catch (CompletionException e) {
            // Unwrap the transported checked exception
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                // Re-throw the original checked exception, preserving its type
                throw ex;
            }
            // If the cause is a Throwable but not an Exception (e.g. an Error
            // that was accidentally wrapped), re-wrap to maintain the Callable contract
            throw new CompletionException(cause);
        }
    }
}
