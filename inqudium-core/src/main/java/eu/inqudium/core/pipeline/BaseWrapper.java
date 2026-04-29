package eu.inqudium.core.pipeline;

/**
 * Abstract base class for all synchronous wrapper layers in the pipeline.
 *
 * <p>Inherits chain structure and ID management from {@link AbstractBaseWrapper}
 * and adds synchronous execution via {@link LayerAction}. This class wires
 * the "next step" reference at construction time — either the delegate wrapper's
 * {@code execute()} method (if the delegate is itself a wrapper) or a terminal
 * "core execution" lambda that invokes the real delegate directly.</p>
 *
 * <h3>Execution flow</h3>
 * <ol>
 *   <li>The concrete wrapper's entry point (e.g. {@code run()}, {@code get()})
 *       calls {@link #initiateChain(Object)}, which generates a call ID and
 *       starts chain traversal.</li>
 *   <li>{@link #execute(long, long, Object)} invokes the configured
 *       {@link LayerAction}, passing it the {@code nextStep} reference.</li>
 *   <li>The {@code LayerAction} decides when/whether to call
 *       {@code next.execute(...)}, propagating the call down the chain.</li>
 *   <li>At the end of the chain, the terminal core execution lambda invokes
 *       the actual delegate (e.g. {@code delegate.run()}).</li>
 * </ol>
 *
 * @param <T> the delegate type this wrapper wraps around (e.g. {@code Runnable})
 * @param <A> the argument type flowing through the chain (e.g. {@code Void} for
 *            {@link Runnable}, or the input type for {@link java.util.function.Function})
 * @param <R> the return type flowing back through the chain
 * @param <S> the concrete self-type (recursive generic bound for {@link Wrapper#inner()})
 */
public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
        extends AbstractBaseWrapper<T, S>
        implements InternalExecutor<A, R> {

    /**
     * Reference to the next step in the chain. This is either:
     * <ul>
     *   <li>The delegate wrapper's {@code execute()} method (if the delegate is a
     *       wrapper — determined at construction time via {@link #isDelegateWrapper()})</li>
     *   <li>The terminal core execution lambda (if the delegate is a plain functional
     *       interface — the lambda that calls {@code delegate.run()}, etc.)</li>
     * </ul>
     *
     * <p>Pre-resolved at construction time to avoid runtime {@code instanceof}
     * checks on the hot path.</p>
     */
    private final InternalExecutor<A, R> nextStep;

    /**
     * The around-advice for this layer. Receives the chain ID, call ID, argument,
     * and {@link #nextStep} reference, and decides how to proceed.
     */
    private final LayerAction<A, R> layerAction;

    /**
     * Full constructor with explicit layer action.
     *
     * <p>Determines the next step at construction time: if the delegate is itself
     * a {@code BaseWrapper} (i.e. part of the same chain), the delegate is cast
     * to {@code InternalExecutor} and used as the next step. Otherwise, the
     * provided {@code coreExecution} lambda is used — this is the terminal step
     * that invokes the real delegate.</p>
     *
     * @param name          human-readable layer name
     * @param delegate      the wrapped target
     * @param coreExecution the terminal execution lambda (used only when the
     *                      delegate is not a wrapper)
     * @param layerAction   the around-advice for this layer
     */
    @SuppressWarnings("unchecked")
    protected BaseWrapper(String name, T delegate,
                          InternalExecutor<A, R> coreExecution,
                          LayerAction<A, R> layerAction) {
        super(name, delegate);
        this.layerAction = layerAction;

        // Pre-resolve the next step: if the delegate is a wrapper, chain into it
        // directly; otherwise use the terminal core execution lambda.
        // The unchecked cast is safe because wrapper chains are homogeneous —
        // all layers share the same A and R type parameters.
        this.nextStep = isDelegateWrapper() ? (InternalExecutor<A, R>) delegate : coreExecution;
    }

    /**
     * Convenience constructor with a pass-through layer action.
     *
     * <p>Creates a structural wrapper layer that does not modify the execution
     * flow. Useful for testing, hierarchy visualization, or as a placeholder
     * in a chain that will be replaced later.</p>
     *
     * @param name          human-readable layer name
     * @param delegate      the wrapped target
     * @param coreExecution the terminal execution lambda
     */
    protected BaseWrapper(String name, T delegate, InternalExecutor<A, R> coreExecution) {
        this(name, delegate, coreExecution, LayerAction.passThrough());
    }

    /**
     * Decorator-based constructor that derives the layer name from the decorator's
     * element metadata (type + name, e.g. "BULKHEAD(pool-A)").
     *
     * @param decorator     the decorator providing both name metadata and around-advice
     * @param delegate      the wrapped target
     * @param coreExecution the terminal execution lambda
     */
    protected BaseWrapper(InqDecorator<A, R> decorator, T delegate,
                          InternalExecutor<A, R> coreExecution) {
        this(newLayerDesc(decorator), delegate, coreExecution, decorator);
    }

    /**
     * Entry point for chain execution: generates a new call ID and starts
     * traversal from this (outermost) layer.
     *
     * <p>This method is called by the concrete wrapper's public entry point
     * (e.g. {@link RunnableWrapper#run()}, {@link SupplierWrapper#get()}).
     * It is the only place where {@link #generateCallId()} is called,
     * ensuring exactly one CAS operation per invocation.</p>
     *
     * @param argument the argument to pass through the chain ({@code null}
     *                 for void-argument wrappers)
     * @return the result produced by the chain
     */
    protected R initiateChain(A argument) {
        return this.execute(chainId(), generateCallId(), argument);
    }

    /**
     * Executes this layer's around-advice, passing the next step reference.
     *
     * <p>This method is both the {@link InternalExecutor} implementation
     * (called by the outer layer's {@code LayerAction} via {@code next.execute(...)})
     * and the internal dispatch mechanism. The {@code layerAction} receives
     * {@link #nextStep} and decides when/whether to invoke it.</p>
     *
     * @param chainId  the chain identifier (passed through unchanged)
     * @param callId   the call identifier (passed through unchanged)
     * @param argument the argument flowing through the chain
     * @return the result produced by this layer or propagated from inner layers
     */
    @Override
    public R execute(long chainId, long callId, A argument) {
        return layerAction.execute(chainId, callId, argument, nextStep);
    }
}
