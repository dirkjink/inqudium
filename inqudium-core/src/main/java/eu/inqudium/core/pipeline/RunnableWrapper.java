package eu.inqudium.core.pipeline;

/**
 * A homogeneous wrapper for the {@link Runnable} interface.
 *
 * <h3>Usage with Decorator</h3>
 * <pre>{@code
 * BulkheadDecorator<Void, Void> bulkhead = new BulkheadDecorator<>("pool", 5);
 * RunnableWrapper protected = new RunnableWrapper(bulkhead, myRunnable);
 * protected.run();  // limited to 5 concurrent executions
 * }</pre>
 */
public class RunnableWrapper
    extends BaseWrapper<Runnable, Void, Void, RunnableWrapper>
    implements Runnable {

  public RunnableWrapper(InqDecorator<Void, Void> decorator, Runnable delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  public RunnableWrapper(String name, Runnable delegate, LayerAction<Void, Void> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  public RunnableWrapper(String name, Runnable delegate) {
    this(name, delegate, LayerAction.passThrough());
  }

  private static InternalExecutor<Void, Void> coreFor(Runnable delegate) {
    return (chainId, callId, arg) -> {
      delegate.run();
      return null;
    };
  }

  @Override
  public void run() {
    initiateChain(null);
  }
}
