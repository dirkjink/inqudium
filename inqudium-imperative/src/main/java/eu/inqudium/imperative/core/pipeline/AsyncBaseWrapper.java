package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.AbstractBaseWrapper;

import java.util.concurrent.CompletionStage;

/**
 * Abstract base class for all asynchronous wrapper layers in the pipeline.
 *
 * <p>Inherits chain structure and ID management from {@link AbstractBaseWrapper}
 * and adds asynchronous execution via {@link AsyncLayerAction}.</p>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <A> the argument type flowing through the chain
 * @param <R> the result type carried by the CompletionStage
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class AsyncBaseWrapper<T, A, R, S extends AsyncBaseWrapper<T, A, R, S>>
        extends AbstractBaseWrapper<T, S>
        implements InternalAsyncExecutor<A, R> {

    private final InternalAsyncExecutor<A, R> nextStep;
    private final AsyncLayerAction<A, R> layerAction;

    @SuppressWarnings("unchecked")
    protected AsyncBaseWrapper(String name, T delegate,
                               InternalAsyncExecutor<A, R> coreExecution,
                               AsyncLayerAction<A, R> layerAction) {
        super(name, delegate);
        this.layerAction = layerAction;
        this.nextStep = isDelegateWrapper() ? (InternalAsyncExecutor<A, R>) delegate : coreExecution;
    }

    protected AsyncBaseWrapper(String name, T delegate,
                               InternalAsyncExecutor<A, R> coreExecution) {
        this(name, delegate, coreExecution, AsyncLayerAction.passThrough());
    }

    protected AsyncBaseWrapper(InqAsyncDecorator<A, R> decorator, T delegate,
                               InternalAsyncExecutor<A, R> coreExecution) {
        this(newLayerDesc(decorator), delegate, coreExecution, decorator);
    }

    /**
     * Entry point: generates a call ID and starts async chain traversal.
     */
    protected CompletionStage<R> initiateChain(A argument) {
        return this.executeAsync(chainId(), generateCallId(), argument);
    }

    @Override
    public CompletionStage<R> executeAsync(long chainId, long callId, A argument) {
        return layerAction.executeAsync(chainId, callId, argument, nextStep);
    }
}
