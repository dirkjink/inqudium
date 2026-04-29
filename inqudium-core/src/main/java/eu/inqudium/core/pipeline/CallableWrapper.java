package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

/**
 * A homogeneous wrapper for the {@link Callable} interface.
 *
 * <p>{@code Callable} differs from {@code Supplier} in that it declares
 * {@code throws Exception}. This creates a challenge for the pipeline, because
 * {@link LayerAction} and {@link InternalExecutor} do not declare checked
 * exceptions. The solution is a two-phase transport mechanism implemented
 * entirely via {@link Throws}:</p>
 *
 * <ol>
 *   <li><strong>Wrapping on entry:</strong> the core execution lambda uses
 *       {@link Throws#wrapChecked(Throwable)} to transport checked exceptions
 *       through the chain as {@link CompletionException} while letting
 *       {@link RuntimeException}/{@link Error} pass through unchanged.</li>
 *   <li><strong>Unwrapping on exit:</strong> {@link #call()} uses
 *       {@link Throws#unwrapAndRethrow(CompletionException)} to sneaky-throw
 *       the original cause, preserving the delegate's declared exception types
 *       (and any {@link Error} unchanged — no double-wrapping).</li>
 * </ol>
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
     * <p>Invokes {@code delegate.call()} and delegates exception transport to
     * {@link Throws#wrapChecked(Throwable)}: runtime exceptions and errors
     * propagate directly, while checked exceptions are wrapped in
     * {@link CompletionException} for safe transport through the chain.</p>
     *
     * @param delegate the real callable to invoke at the end of the chain
     * @param <V>      the callable's return type
     * @return a terminal {@link InternalExecutor} with checked-exception wrapping
     */
    private static <V> InternalExecutor<Void, V> coreFor(Callable<V> delegate) {
        return (chainId, callId, arg) -> {
            try {
                return delegate.call();
            } catch (Throwable t) {
                // Throws.wrapChecked always throws — the signature return type
                // RuntimeException is a fiction used only to help control-flow analysis.
                throw Throws.wrapChecked(t);
            }
        };
    }

    /**
     * Entry point: initiates chain traversal and unwraps transported throwables.
     *
     * <p>If a {@link CompletionException} arrives, its cause is sneaky-thrown
     * via {@link Throws#unwrapAndRethrow(CompletionException)} — preserving
     * the original type (checked exception, runtime exception, or error)
     * without double-wrapping.</p>
     *
     * @return the value produced by the delegate callable
     * @throws Exception the original checked exception from the delegate, if any
     */
    @Override
    public V call() throws Exception {
        try {
            return initiateChain(null);
        } catch (CompletionException e) {
            throw Throws.unwrapAndRethrow(e);
        }
    }
}
