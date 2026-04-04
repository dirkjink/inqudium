package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.ProxyExecution;

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
    extends AsyncBaseWrapper<ProxyExecution<CompletionStage<R>>, Void, R, AsyncJoinPointWrapper<R>>
    implements ProxyExecution<CompletionStage<R>> {

  private static <R> InternalAsyncExecutor<Void, R> coreFor(ProxyExecution<CompletionStage<R>> delegate) {
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

  /** Creates a wrapper with an {@link InqAsyncDecorator} providing name and around-advice. */
  public AsyncJoinPointWrapper(InqAsyncDecorator<Void, R> decorator,
                                ProxyExecution<CompletionStage<R>> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /** Creates a wrapper with a custom {@link AsyncLayerAction}. */
  public AsyncJoinPointWrapper(String name, ProxyExecution<CompletionStage<R>> delegate,
                                AsyncLayerAction<Void, R> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /** Creates a wrapper with pass-through behavior. */
  public AsyncJoinPointWrapper(String name, ProxyExecution<CompletionStage<R>> delegate) {
    super(name, delegate, coreFor(delegate));
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
