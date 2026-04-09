package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.concurrent.atomic.AtomicLong;

import static eu.inqudium.core.pipeline.ChainIdGenerator.CHAIN_ID_COUNTER;

/**
 * Shared base for both synchronous and asynchronous wrapper chains.
 *
 * <p>This abstract class holds the immutable chain structure that all wrapper
 * layers share: the delegate reference, the human-readable layer name, the
 * chain ID, and the per-chain call-ID counter. Subclasses only need to wire
 * the execution strategy (sync via {@link BaseWrapper}, or async in the
 * corresponding async module).</p>
 *
 * <h3>Chain ID inheritance</h3>
 * <p>When wrapping another {@code AbstractBaseWrapper}, the outer layer inherits
 * the inner layer's chain ID and call-ID counter, ensuring that all layers in
 * the same stack share identical IDs. When wrapping a plain delegate (not a
 * wrapper), a new chain is started with a fresh chain ID from the global counter.</p>
 *
 * <h3>Call ID generation</h3>
 * <p>The shared {@link AtomicLong} call-ID counter is incremented once per
 * invocation by the outermost layer. Inner layers do not generate new call IDs —
 * they receive the same call ID through the {@link InternalExecutor} chain.
 * This ensures exactly one CAS operation per invocation regardless of chain depth.</p>
 *
 * @param <T> the delegate type this wrapper wraps around (e.g. {@code Runnable},
 *            {@code Supplier<T>}, or another wrapper)
 * @param <S> the concrete self-type (recursive generic bound), used by
 *            {@link Wrapper#inner()} to return the correct type without casting
 */
public abstract class AbstractBaseWrapper<T, S extends AbstractBaseWrapper<T, S>>
    implements Wrapper<S> {

  /**
   * The wrapped delegate — either a plain functional interface or another wrapper layer.
   */
  private final T delegate;

  /**
   * Human-readable name for this layer, used in diagnostics and hierarchy visualization.
   */
  private final String name;

  /**
   * Unique identifier for this wrapper chain. All layers wrapping the same
   * core delegate share the same chain ID. Generated from the global
   * {@link ChainIdGenerator#CHAIN_ID_COUNTER} when a new chain is started.
   */
  private final long chainId;

  /**
   * Shared call-ID counter for this chain. Incremented once by the outermost
   * layer per invocation. All layers in the same chain reference the same
   * {@link AtomicLong} instance, so the counter is consistent across the
   * entire stack and safe for concurrent access.
   */
  private final AtomicLong callIdCounter;

  /**
   * Core constructor that wires the chain structure.
   *
   * <p>Performs two critical setup steps:</p>
   * <ol>
   *   <li>Validates that both {@code name} and {@code delegate} are non-null
   *       (fail-fast to prevent obscure errors later in the chain).</li>
   *   <li>Determines whether this is a new chain or an extension of an existing one:
   *       <ul>
   *         <li>If the delegate is itself an {@code AbstractBaseWrapper}, the chain ID
   *             and call-ID counter are <em>inherited</em> from the inner wrapper.</li>
   *         <li>Otherwise, a new chain ID is generated from the global counter and
   *             a fresh call-ID counter is created.</li>
   *       </ul>
   *   </li>
   * </ol>
   *
   * @param name     a descriptive name for this layer (must not be {@code null})
   * @param delegate the target to wrap (must not be {@code null})
   * @throws IllegalArgumentException if {@code name} or {@code delegate} is {@code null}
   */
  protected AbstractBaseWrapper(String name, T delegate) {
    // Fail-fast null checks — a null name or delegate would cause confusing
    // errors much later (e.g. NPE during dispatch or hierarchy rendering)
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;

    // Chain structure inheritance: if the delegate is already a wrapper,
    // join its chain rather than starting a new one. This ensures that
    // chainId() and currentCallId() are consistent across all layers.
    if (delegate instanceof AbstractBaseWrapper<?, ?> innerWrapper) {
      this.chainId = innerWrapper.chainId();
      this.callIdCounter = innerWrapper.callIdCounter;
    } else {
      // New chain: allocate a globally unique chain ID and a fresh counter
      this.chainId = CHAIN_ID_COUNTER.incrementAndGet();
      this.callIdCounter = new AtomicLong();
    }
  }

  /**
   * Builds a layer description string from a decorator's element type and name.
   *
   * <p>Produces strings like {@code "BULKHEAD(pool-A)"} or {@code "RETRY(default)"}.
   * Used by subclass constructors that accept an {@link InqDecorator} to
   * automatically derive a meaningful layer name.</p>
   *
   * @param decorator the decorator element providing name and type metadata
   * @return a formatted layer description string
   */
  protected static String newLayerDesc(InqElement decorator) {
    return decorator.getElementType().name() + "(" + decorator.getName() + ")";
  }

  /**
   * Returns the wrapped delegate.
   *
   * <p>Subclasses use this to access the delegate for core execution (e.g.
   * calling {@code delegate.run()} or {@code delegate.get()}) or for
   * chain structure inspection.</p>
   *
   * @return the wrapped delegate instance
   */
  protected T delegate() {
    return delegate;
  }

  /**
   * Returns {@code true} if the delegate is itself a wrapper in the same chain.
   *
   * <p>Used by {@link BaseWrapper} to determine whether the next step in the
   * chain is another wrapper (whose {@code execute()} method should be called)
   * or the terminal core execution lambda.</p>
   *
   * @return {@code true} if the delegate extends {@code AbstractBaseWrapper}
   */
  protected boolean isDelegateWrapper() {
    return delegate instanceof AbstractBaseWrapper;
  }

  /**
   * Generates and returns the next call ID for this chain.
   *
   * <p>Called once per invocation by the outermost wrapper's entry point
   * (e.g. {@code run()}, {@code get()}, {@code apply()}). The returned
   * ID is then passed through the entire chain as a primitive parameter.</p>
   *
   * @return a new, unique call ID for the current invocation
   */
  protected long generateCallId() {
    return callIdCounter.incrementAndGet();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Returns the most recently generated call ID. This is the value
   * set by the last {@link #generateCallId()} call, which occurs at the
   * start of each invocation through the outermost wrapper.</p>
   */
  @Override
  public long currentCallId() {
    return callIdCounter.get();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long chainId() {
    return chainId;
  }

  /**
   * {@inheritDoc}
   */
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
   * runtime that the delegate can safely be viewed as {@code S}. This
   * prevents deferred {@code ClassCastException}s when chains mix different
   * {@code AbstractBaseWrapper} subclasses (e.g. a {@code RunnableWrapper}
   * wrapping a {@code SupplierWrapper} — which should not happen but is
   * guarded against).</p>
   *
   * <p>This approach is slightly more conservative than a bare {@code (S)} cast:
   * it may return {@code null} in rare subtype constellations where the cast
   * would technically succeed, but it is always safe and requires no changes
   * to the constructor hierarchy.</p>
   *
   * @return the next inner wrapper of the same concrete type, or {@code null}
   */
  @SuppressWarnings("unchecked")
  @Override
  public S inner() {
    return this.getClass().isInstance(delegate) ? (S) delegate : null;
  }
}
