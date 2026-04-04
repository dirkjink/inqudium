package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Wrapper for dynamic proxies and Spring AOP join points.
 *
 * <p>Integrates AOP proxy executions into the wrapper chain. Checked exceptions
 * are transported via {@link CompletionException} and unwrapped in {@link #proceed()}.</p>
 *
 * @param <R> the return type of the join point execution
 */
public class JoinPointWrapper<R>
    extends BaseWrapper<JoinPointExecutor<R>, Void, R, JoinPointWrapper<R>>
    implements JoinPointExecutor<R> {

  /**
   * Creates a wrapper with a {@link InqDecorator} providing name and around-advice.
   */
  public JoinPointWrapper(InqDecorator<Void, R> decorator, JoinPointExecutor<R> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /**
   * Creates a wrapper with a custom {@link LayerAction}.
   */
  public JoinPointWrapper(String name, JoinPointExecutor<R> delegate, LayerAction<Void, R> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior.
   */
  public JoinPointWrapper(String name, JoinPointExecutor<R> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  private static <R> InternalExecutor<Void, R> coreFor(JoinPointExecutor<R> delegate) {
    return (chainId, callId, arg) -> {
      try {
        return delegate.proceed();
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new CompletionException(t);
      }
    };
  }

  @Override
  public R proceed() throws Throwable {
    try {
      return initiateChain(null);
    } catch (RuntimeException e) {
      if (e instanceof CompletionException) {
        throw e.getCause();
      }
      throw e;
    }
  }
}
