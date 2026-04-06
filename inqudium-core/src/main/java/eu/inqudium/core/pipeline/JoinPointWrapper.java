package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Wrapper for dynamic proxies and Spring AOP join points.
 *
 * <p>Checked exceptions are transported via {@link CompletionException}
 * and unwrapped in {@link #proceed()}.</p>
 *
 * @param <R> the return type of the join point execution
 */
public class JoinPointWrapper<R>
    extends BaseWrapper<JoinPointExecutor<R>, Void, R, JoinPointWrapper<R>>
    implements JoinPointExecutor<R> {

  public JoinPointWrapper(InqDecorator<Void, R> decorator, JoinPointExecutor<R> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  public JoinPointWrapper(String name, JoinPointExecutor<R> delegate, LayerAction<Void, R> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  public JoinPointWrapper(String name, JoinPointExecutor<R> delegate) {
    this(name, delegate, LayerAction.passThrough());
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
    } catch (CompletionException e) {
      throw e.getCause();
    }
  }
}
