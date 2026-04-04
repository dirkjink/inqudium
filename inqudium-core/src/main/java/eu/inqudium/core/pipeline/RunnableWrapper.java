package eu.inqudium.core.pipeline;

/**
 * A homogeneous wrapper for the {@link Runnable} interface.
 *
 * <p>Since {@code Runnable} takes no arguments and returns no value, both the argument
 * type and the return type are {@code Void}.</p>
 */
public class RunnableWrapper
    extends BaseWrapper<Runnable, Void, Void, RunnableWrapper>
    implements Runnable {

  public RunnableWrapper(String name, Runnable delegate) {
    super(name, delegate, (callId, arg) -> { delegate.run(); return null; });
  }

  @Override
  public void run() {
    initiateChain(null);
  }
}
