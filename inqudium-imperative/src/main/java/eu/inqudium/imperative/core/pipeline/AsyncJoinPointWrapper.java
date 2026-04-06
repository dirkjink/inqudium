package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Async wrapper for proxy executions that return a {@link CompletionStage}.
 *
 * <p>The async counterpart to {@link JoinPointWrapper}.</p>
 *
 * @param <R> the result type carried by the CompletionStage
 */
public class AsyncJoinPointWrapper<R>
    extends AsyncBaseWrapper<JoinPointExecutor<CompletionStage<R>>, Void, R, AsyncJoinPointWrapper<R>>
    implements JoinPointExecutor<CompletionStage<R>> {

  public AsyncJoinPointWrapper(InqAsyncDecorator<Void, R> decorator,
                               JoinPointExecutor<CompletionStage<R>> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  public AsyncJoinPointWrapper(String name, JoinPointExecutor<CompletionStage<R>> delegate,
                               AsyncLayerAction<Void, R> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  public AsyncJoinPointWrapper(String name, JoinPointExecutor<CompletionStage<R>> delegate) {
    this(name, delegate, AsyncLayerAction.passThrough());
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
    } catch (CompletionException e) {
      throw e.getCause();
    }
  }
}
