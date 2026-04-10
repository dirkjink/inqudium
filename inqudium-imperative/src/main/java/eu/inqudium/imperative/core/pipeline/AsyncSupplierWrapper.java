package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.SupplierWrapper;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Async wrapper for suppliers that return a {@link CompletionStage}.
 *
 * <p>The async counterpart to {@link SupplierWrapper}.</p>
 *
 * @param <T> the result type carried by the CompletionStage
 */
public class AsyncSupplierWrapper<T>
        extends AsyncBaseWrapper<Supplier<CompletionStage<T>>, Void, T, AsyncSupplierWrapper<T>>
        implements Supplier<CompletionStage<T>> {

    public AsyncSupplierWrapper(InqAsyncDecorator<Void, T> decorator,
                                Supplier<CompletionStage<T>> delegate) {
        super(decorator, delegate, coreFor(delegate));
    }

    public AsyncSupplierWrapper(String name, Supplier<CompletionStage<T>> delegate,
                                AsyncLayerAction<Void, T> layerAction) {
        super(name, delegate, coreFor(delegate), layerAction);
    }

    public AsyncSupplierWrapper(String name, Supplier<CompletionStage<T>> delegate) {
        this(name, delegate, AsyncLayerAction.passThrough());
    }

    private static <T> InternalAsyncExecutor<Void, T> coreFor(Supplier<CompletionStage<T>> delegate) {
        return (chainId, callId, arg) -> delegate.get();
    }

    @Override
    public CompletionStage<T> get() {
        return initiateChain(null);
    }
}
