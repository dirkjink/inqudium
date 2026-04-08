package eu.inqudium.core.pipeline;

/**
 * A homogeneous wrapper for the {@link Runnable} interface.
 *
 * <p>This wrapper implements {@code Runnable} itself, making it a drop-in
 * replacement for any {@code Runnable} delegate. When {@link #run()} is called,
 * the invocation passes through the configured {@link LayerAction} chain
 * before reaching the actual delegate's {@code run()} method.</p>
 *
 * <p>Since {@code Runnable} has no input arguments and no return value, both
 * the argument type {@code A} and return type {@code R} are {@code Void}.
 * The chain passes {@code null} as the argument and ignores the return value.</p>
 *
 * <h3>Usage with Decorator</h3>
 * <pre>{@code
 * BulkheadDecorator<Void, Void> bulkhead = new BulkheadDecorator<>("pool", 5);
 * RunnableWrapper protected = new RunnableWrapper(bulkhead, myRunnable);
 * protected.run();  // limited to 5 concurrent executions
 * }</pre>
 *
 * <h3>Usage with explicit LayerAction</h3>
 * <pre>{@code
 * RunnableWrapper logged = new RunnableWrapper("logging", myRunnable,
 *     (chainId, callId, arg, next) -> {
 *         log.info("Before run [chain={}, call={}]", chainId, callId);
 *         next.execute(chainId, callId, arg);
 *         log.info("After run");
 *         return null;
 *     });
 * }</pre>
 */
public class RunnableWrapper
    extends BaseWrapper<Runnable, Void, Void, RunnableWrapper>
    implements Runnable {

  /**
   * Creates a wrapper using a decorator's name and around-advice.
   *
   * @param decorator the decorator providing metadata and layer logic
   * @param delegate  the runnable to wrap
   */
  public RunnableWrapper(InqDecorator<Void, Void> decorator, Runnable delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /**
   * Creates a wrapper with an explicit name and layer action.
   *
   * @param name        human-readable layer name
   * @param delegate    the runnable to wrap
   * @param layerAction the around-advice for this layer
   */
  public RunnableWrapper(String name, Runnable delegate, LayerAction<Void, Void> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a pass-through wrapper (structural only, no custom behavior).
   *
   * @param name     human-readable layer name
   * @param delegate the runnable to wrap
   */
  public RunnableWrapper(String name, Runnable delegate) {
    this(name, delegate, LayerAction.passThrough());
  }

  /**
   * Builds the terminal core execution lambda for {@code Runnable}.
   *
   * <p>This lambda sits at the end of the chain and performs the actual
   * {@code delegate.run()} call. It returns {@code null} because
   * {@code Runnable} has no return value (the chain uses {@code Void}).</p>
   *
   * @param delegate the real runnable to invoke at the end of the chain
   * @return a terminal {@link InternalExecutor} that calls {@code delegate.run()}
   */
  private static InternalExecutor<Void, Void> coreFor(Runnable delegate) {
    return (chainId, callId, arg) -> {
      delegate.run();
      return null; // Void return type — no value to propagate back
    };
  }

  /**
   * Entry point: initiates chain traversal with a {@code null} argument.
   *
   * <p>Callers invoke this method exactly as they would on a plain
   * {@code Runnable}. The wrapper infrastructure is invisible.</p>
   */
  @Override
  public void run() {
    initiateChain(null);
  }
}
