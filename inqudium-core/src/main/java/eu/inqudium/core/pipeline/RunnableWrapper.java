package eu.inqudium.core.pipeline;

/**
 * A homogeneous wrapper for the {@link Runnable} interface.
 *
 * <p>Since {@code Runnable} takes no arguments and returns no value, both the argument
 * type and the return type are {@code Void}. The chain is initiated with a {@code null}
 * argument and the core execution returns {@code null} to satisfy the generic signature.</p>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * Runnable core = () -> System.out.println("Hello");
 * RunnableWrapper logged = new RunnableWrapper("logging", core) {
 *     @Override
 *     protected void handleLayer(String callId, Void argument) {
 *         System.out.println("[" + callId + "] Entering " + getLayerDescription());
 *     }
 * };
 * logged.run();  // prints the callId log line, then "Hello"
 * }</pre>
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
