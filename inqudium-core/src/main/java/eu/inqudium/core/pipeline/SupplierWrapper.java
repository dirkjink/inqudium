package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * A homogeneous wrapper for the {@link Supplier} interface.
 *
 * <p>Implements {@code Supplier<T>} itself, making it a transparent drop-in
 * replacement. The chain argument type is {@code Void} (suppliers take no input),
 * and the return type {@code T} flows back through all layers.</p>
 *
 * @param <T> the return type of the supplier
 */
public class SupplierWrapper<T>
        extends BaseWrapper<Supplier<T>, Void, T, SupplierWrapper<T>>
        implements Supplier<T> {

    /**
     * Creates a wrapper using a decorator's name and around-advice.
     *
     * @param decorator the decorator providing metadata and layer logic
     * @param delegate  the supplier to wrap
     */
    public SupplierWrapper(InqDecorator<Void, T> decorator, Supplier<T> delegate) {
        super(decorator, delegate, coreFor(delegate));
    }

    /**
     * Creates a wrapper with an explicit name and layer action.
     *
     * @param name        human-readable layer name
     * @param delegate    the supplier to wrap
     * @param layerAction the around-advice for this layer
     */
    public SupplierWrapper(String name, Supplier<T> delegate, LayerAction<Void, T> layerAction) {
        super(name, delegate, coreFor(delegate), layerAction);
    }

    /**
     * Creates a pass-through wrapper (structural only, no custom behavior).
     *
     * @param name     human-readable layer name
     * @param delegate the supplier to wrap
     */
    public SupplierWrapper(String name, Supplier<T> delegate) {
        this(name, delegate, LayerAction.passThrough());
    }

    /**
     * Builds the terminal core execution lambda for {@code Supplier<T>}.
     *
     * <p>Invokes {@code delegate.get()} and returns its result. The argument
     * parameter is ignored (always {@code null} for suppliers).</p>
     *
     * @param delegate the real supplier to invoke at the end of the chain
     * @param <T>      the supplier's return type
     * @return a terminal {@link InternalExecutor} that calls {@code delegate.get()}
     */
    private static <T> InternalExecutor<Void, T> coreFor(Supplier<T> delegate) {
        return (chainId, callId, arg) -> delegate.get();
    }

    /**
     * Entry point: initiates chain traversal and returns the supplier's result.
     *
     * @return the value produced by the delegate, potentially modified by
     * intermediate layers
     */
    @Override
    public T get() {
        return initiateChain(null);
    }
}
