package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Shared base for both synchronous and asynchronous wrapper chains.
 *
 * <p>This abstract class holds the immutable chain structure that all wrapper
 * layers share: the delegate reference, the human-readable layer name, the
 * chain ID, and the per-chain call-ID source. Subclasses only need to wire
 * the execution strategy (sync via {@link BaseWrapper}, or async in the
 * corresponding async module).</p>
 *
 * <h3>Chain ID inheritance</h3>
 * <p>When wrapping another {@code AbstractBaseWrapper}, the outer layer inherits
 * the inner layer's chain ID and call-ID source, ensuring that all layers in
 * the same stack share identical IDs. When wrapping a plain delegate (not a
 * wrapper), a new chain is started with a fresh chain ID from
 * {@link PipelineIds#nextChainId()} and a fresh call-ID source from
 * {@link PipelineIds#newInstanceCallIdSource()}.</p>
 *
 * <h3>Call ID generation</h3>
 * <p>The call-ID source is a {@link LongSupplier} produced by
 * {@link PipelineIds#newInstanceCallIdSource()}. Each chain has its own
 * supplier (and therefore its own private {@link java.util.concurrent.atomic.AtomicLong}),
 * so threads calling different chains never contend on a single counter
 * cache-line. The supplier is invoked once per invocation by the outermost
 * layer; inner layers receive the resulting call ID as a primitive
 * parameter through the {@link InternalExecutor} chain.</p>
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
     * core delegate share the same chain ID. Generated from
     * {@link PipelineIds#nextChainId()} when a new chain is started.
     */
    private final long chainId;

    /**
     * Per-chain call-ID source. All layers in the same chain reference the
     * same supplier instance, so invoking it from any layer yields IDs from
     * the same underlying counter. Obtained from
     * {@link PipelineIds#newInstanceCallIdSource()} for a fresh chain, or
     * inherited from the inner wrapper when extending an existing chain.
     */
    private final LongSupplier callIdSource;

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
     *             and call-ID source are <em>inherited</em> from the inner wrapper.</li>
     *         <li>Otherwise, a new chain ID is generated from
     *             {@link PipelineIds#nextChainId()} and a fresh call-ID source is
     *             obtained from {@link PipelineIds#newInstanceCallIdSource()}.</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param name     a descriptive name for this layer (must not be {@code null})
     * @param delegate the target to wrap (must not be {@code null})
     * @throws NullPointerException if {@code name} or {@code delegate} is {@code null}
     */
    protected AbstractBaseWrapper(String name, T delegate) {
        // Fail-fast null checks — a null name or delegate would cause confusing
        // errors much later (e.g. NPE during dispatch or hierarchy rendering).
        // NPE (not IllegalArgumentException) follows the java.util.Objects convention.
        this.name = Objects.requireNonNull(name, "Name must not be null");
        this.delegate = Objects.requireNonNull(delegate, "Delegate must not be null");

        // Chain structure inheritance: if the delegate is already a wrapper,
        // join its chain rather than starting a new one. This ensures that
        // chainId() and generateCallId() are consistent across all layers.
        if (delegate instanceof AbstractBaseWrapper<?, ?> innerWrapper) {
            this.chainId = innerWrapper.chainId();
            this.callIdSource = innerWrapper.callIdSource;
        } else {
            // New chain: allocate a globally unique chain ID and a fresh
            // instance-local call-ID source. The source is a LongSupplier
            // whose backing AtomicLong is private to this chain — no cross-
            // chain contention.
            this.chainId = PipelineIds.nextChainId();
            this.callIdSource = PipelineIds.newInstanceCallIdSource();
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
        return callIdSource.getAsLong();
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
