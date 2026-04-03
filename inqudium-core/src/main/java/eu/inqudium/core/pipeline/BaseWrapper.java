package eu.inqudium.core.pipeline;

import java.util.UUID;

public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalExecutor<A, R> {

  private final T delegate;
  private final String name;
  private final String chainId;
  private volatile S outer;

  @SuppressWarnings("unchecked")
  protected BaseWrapper(String name, T delegate) {
    this.name = name;
    this.delegate = delegate;
    if (delegate instanceof BaseWrapper) {
      S inner = (S) delegate;
      this.chainId = inner.getChainId();
      inner.setOuter((S) this);
    } else {
      this.chainId = UUID.randomUUID().toString();
    }
  }

  /**
   * Initiates the chain and passes the argument top-down.
   */
  protected R initiateChain(A argument) {
    S root = getOutermost();
    return root.executeWithId(UUID.randomUUID().toString(), argument);
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
  @Override public S getInner() { return (delegate instanceof BaseWrapper) ? (S) delegate : null; }
  @Override public S getOuter() { return outer; }
  @Override public void setOuter(S outer) { this.outer = outer; }
  protected T getDelegate() { return delegate; }
}