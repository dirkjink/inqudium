package eu.inqudium.core.pipeline;

import java.util.UUID;

public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalExecutor<A, R> {

  private final T delegate;
  private final String name;
  private final String chainId;

  protected BaseWrapper(String name, T delegate) {
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;
    if (delegate instanceof BaseWrapper) {
      this.chainId = ((BaseWrapper<?, ?, ?, ?>) delegate).getChainId();
    } else {
      this.chainId = UUID.randomUUID().toString();
    }
  }

  /**
   * Initiates the chain starting from this layer and propagating inward.
   * Each call receives a unique call ID for tracing purposes.
   */
  protected R initiateChain(A argument) {
    return this.executeWithId(UUID.randomUUID().toString(), argument);
  }

  @Override
  public R executeWithId(String callId, A argument) {
    handleLayer(callId, argument);
    if (delegate instanceof InternalExecutor) {
      @SuppressWarnings("unchecked")
      InternalExecutor<A, R> internalInner = (InternalExecutor<A, R>) delegate;
      return internalInner.executeWithId(callId, argument);
    }
    return invokeCore(argument);
  }

  protected abstract void handleLayer(String callId, A argument);
  protected abstract R invokeCore(A argument);

  @Override public String getChainId() { return chainId; }
  @Override public String getLayerDescription() { return name; }

  @SuppressWarnings("unchecked")
  @Override public S getInner() {
    return (delegate instanceof BaseWrapper) ? (S) delegate : null;
  }

  protected T getDelegate() { return delegate; }
}
