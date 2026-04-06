package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.concurrent.atomic.AtomicLong;

import static eu.inqudium.core.pipeline.ChainIdGenerator.CHAIN_ID_COUNTER;

/**
 * Shared base for both synchronous and asynchronous wrapper chains.
 *
 * <p>Holds the immutable chain structure, zero-allocation ID generation,
 * and all common accessors. Subclasses only need to wire the execution
 * strategy (sync or async).</p>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class AbstractBaseWrapper<T, S extends AbstractBaseWrapper<T, S>>
    implements Wrapper<S> {

  private final T delegate;
  private final String name;
  private final long chainId;
  private final AtomicLong callIdCounter;

  /**
   * Core constructor that wires the chain structure.
   *
   * <p>If the delegate is itself an {@code AbstractBaseWrapper}, the chain ID
   * and call-ID counter are inherited. Otherwise a new chain is started.</p>
   *
   * @param name     a descriptive name for this layer (must not be {@code null})
   * @param delegate the target to wrap (must not be {@code null})
   */
  protected AbstractBaseWrapper(String name, T delegate) {
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;

    if (delegate instanceof AbstractBaseWrapper<?, ?> innerWrapper) {
      this.chainId = innerWrapper.chainId();
      this.callIdCounter = innerWrapper.callIdCounter;
    } else {
      this.chainId = CHAIN_ID_COUNTER.incrementAndGet();
      this.callIdCounter = new AtomicLong();
    }
  }

  /**
   * Builds a layer description from a decorator's element type and name.
   */
  protected static String newLayerDesc(InqElement decorator) {
    return decorator.getElementType().name() + "(" + decorator.getName() + ")";
  }

  /**
   * Returns the wrapped delegate.
   */
  protected T delegate() {
    return delegate;
  }

  /**
   * Returns {@code true} if the delegate is itself a wrapper in the same chain.
   */
  protected boolean isDelegateWrapper() {
    return delegate instanceof AbstractBaseWrapper;
  }

  protected long generateCallId() {
    return callIdCounter.incrementAndGet();
  }

  @Override
  public long currentCallId() {
    return callIdCounter.get();
  }

  @Override
  public long chainId() {
    return chainId;
  }

  @Override
  public String layerDescription() {
    return name;
  }

  /**
   * Returns the next inner wrapper in the chain, or {@code null} if the
   * delegate is not a wrapper or is not assignment-compatible with this
   * wrapper's concrete type.
   *
   * <p>Uses {@code this.getClass().isInstance(delegate)} to verify at
   * runtime that the delegate can safely be viewed as {@code S}, preventing
   * deferred {@code ClassCastException}s when chains mix different
   * {@code AbstractBaseWrapper} subclasses. This is slightly more
   * conservative than the erased {@code (S)} cast — it may return
   * {@code null} in rare subtype constellations where the cast would
   * technically succeed — but it is always safe and requires no changes
   * to the constructor hierarchy.</p>
   */
  @SuppressWarnings("unchecked")
  @Override
  public S inner() {
    return this.getClass().isInstance(delegate) ? (S) delegate : null;
  }
}
