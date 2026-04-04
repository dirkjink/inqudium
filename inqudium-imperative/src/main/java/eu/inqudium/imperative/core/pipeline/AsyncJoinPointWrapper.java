package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.JoinPointExecutor;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Async wrapper for proxy executions that return a {@link CompletionStage}.
 *
 * <p>The async counterpart to {@link JoinPointWrapper}. Checked throwables from
 * starting the async operation are wrapped in {@link CompletionException} for transport
 * and unwrapped in {@link #proceed()}.</p>
 *
 * @param <R> the result type carried by the CompletionStage
 */
public class AsyncJoinPointWrapper<R>
    extends AsyncBaseWrapper<JoinPointExecutor<CompletionStage<R>>, Void, R, AsyncJoinPointWrapper<R>>
    implements JoinPointExecutor<CompletionStage<R>> {

  /**
   * Creates a wrapper with an {@link InqAsyncDecorator} providing name and around-advice.
   */
  public AsyncJoinPointWrapper(InqAsyncDecorator<Void, R> decorator,
                               JoinPointExecutor<CompletionStage<R>> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /**
   * Creates a wrapper with a custom {@link AsyncLayerAction}.
   */
  public AsyncJoinPointWrapper(String name, JoinPointExecutor<CompletionStage<R>> delegate,
                               AsyncLayerAction<Void, R> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior.
   */
  public AsyncJoinPointWrapper(String name, JoinPointExecutor<CompletionStage<R>> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  private static <R> InternalAsyncExecutor<Void, R> coreFor(JoinPointExecutor<CompletionStage<R>> delegate) {
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
  public CompletionStage<R> proceed() throws Throwable {
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
