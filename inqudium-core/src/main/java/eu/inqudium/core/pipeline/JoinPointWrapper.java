package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Wrapper for dynamic proxies and Spring AOP join points.
 *
 * <p>Works identically to {@link CallableWrapper} in terms of checked-exception
 * transport, but wraps a {@link JoinPointExecutor} instead of a {@link java.util.concurrent.Callable}.
 * The key difference is the exception contract: {@code JoinPointExecutor.proceed()}
 * declares {@code throws Throwable} (not just {@code throws Exception}), so the
 * unwrapping in {@link #proceed()} re-throws the full {@link Throwable} type.</p>
 *
 * <h3>Checked exception transport</h3>
 * <ol>
 *   <li>The core execution lambda catches any checked {@code Throwable} from the
 *       delegate and wraps it in {@link CompletionException}.</li>
 *   <li>{@link #proceed()} catches {@code CompletionException} and re-throws the
 *       original cause directly, preserving the full exception type hierarchy.</li>
 * </ol>
 *
 * @param <R> the return type of the join point execution
 */
public class JoinPointWrapper<R>
        extends BaseWrapper<JoinPointExecutor<R>, Void, R, JoinPointWrapper<R>>
        implements JoinPointExecutor<R> {

    /**
     * Creates a wrapper using a decorator's name and around-advice.
     *
     * @param decorator the decorator providing metadata and layer logic
     * @param delegate  the join point executor to wrap
     */
    public JoinPointWrapper(InqDecorator<Void, R> decorator, JoinPointExecutor<R> delegate) {
        super(decorator, delegate, coreFor(delegate));
    }

    /**
     * Creates a wrapper with an explicit name and layer action.
     *
     * @param name        human-readable layer name
     * @param delegate    the join point executor to wrap
     * @param layerAction the around-advice for this layer
     */
    public JoinPointWrapper(String name, JoinPointExecutor<R> delegate, LayerAction<Void, R> layerAction) {
        super(name, delegate, coreFor(delegate), layerAction);
    }

    /**
     * Creates a pass-through wrapper (structural only, no custom behavior).
     *
     * @param name     human-readable layer name
     * @param delegate the join point executor to wrap
     */
    public JoinPointWrapper(String name, JoinPointExecutor<R> delegate) {
        this(name, delegate, LayerAction.passThrough());
    }

    /**
     * Builds the terminal core execution lambda for {@code JoinPointExecutor<R>}.
     *
     * <p>Runtime exceptions and errors propagate directly. All other throwables
     * (checked exceptions) are wrapped in {@link CompletionException} for
     * transport through the chain.</p>
     *
     * @param delegate the real join point executor to invoke at the end of the chain
     * @param <R>      the return type
     * @return a terminal {@link InternalExecutor} with throwable wrapping
     */
    private static <R> InternalExecutor<Void, R> coreFor(JoinPointExecutor<R> delegate) {
        return (chainId, callId, arg) -> {
            try {
                return delegate.proceed();
            } catch (RuntimeException | Error e) {
                // Unchecked — propagate directly, no wrapping needed
                throw e;
            } catch (Throwable t) {
                // Checked throwable — wrap for transport through the chain.
                // Unlike CallableWrapper which only wraps Exception, this catches
                // the full Throwable hierarchy to match JoinPointExecutor's contract.
                throw new CompletionException(t);
            }
        };
    }

    /**
     * Entry point: initiates chain traversal and unwraps transported throwables.
     *
     * <p>If a {@link CompletionException} arrives, its cause is re-thrown directly.
     * This preserves the {@code JoinPointExecutor} contract — callers see the
     * same throwable types they would see from the unwrapped delegate.</p>
     *
     * @return the result produced by the join point execution
     * @throws Throwable the original throwable from the delegate, if any
     */
    @Override
    public R proceed() throws Throwable {
        try {
            return initiateChain(null);
        } catch (CompletionException e) {
            // Unwrap and re-throw the original throwable (could be a checked
            // exception, an Error, or any other Throwable subclass)
            throw e.getCause();
        }
    }
}
