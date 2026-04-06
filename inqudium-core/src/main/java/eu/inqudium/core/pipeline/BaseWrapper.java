package eu.inqudium.core.pipeline;

/**
 * Abstract base class for all synchronous wrapper layers in the pipeline.
 *
 * <p>Inherits chain structure and ID management from {@link AbstractBaseWrapper}
 * and adds synchronous execution via {@link LayerAction}.</p>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    extends AbstractBaseWrapper<T, S>
    implements InternalExecutor<A, R> {

  private final InternalExecutor<A, R> nextStep;
  private final LayerAction<A, R> layerAction;

  @SuppressWarnings("unchecked")
  protected BaseWrapper(String name, T delegate,
                        InternalExecutor<A, R> coreExecution,
                        LayerAction<A, R> layerAction) {
    super(name, delegate);
    this.layerAction = layerAction;
    this.nextStep = isDelegateWrapper() ? (InternalExecutor<A, R>) delegate : coreExecution;
  }

  protected BaseWrapper(String name, T delegate, InternalExecutor<A, R> coreExecution) {
    this(name, delegate, coreExecution, LayerAction.passThrough());
  }

  protected BaseWrapper(InqDecorator<A, R> decorator, T delegate,
                        InternalExecutor<A, R> coreExecution) {
    this(newLayerDesc(decorator), delegate, coreExecution, decorator);
  }

  /** Entry point: generates a call ID and starts chain traversal. */
  protected R initiateChain(A argument) {
    return this.execute(chainId(), generateCallId(), argument);
  }

  @Override
  public R execute(long chainId, long callId, A argument) {
    return layerAction.execute(chainId, callId, argument, nextStep);
  }
}
