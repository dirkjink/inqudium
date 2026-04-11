package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static eu.inqudium.core.pipeline.ChainIdGenerator.CHAIN_ID_COUNTER;

/**
 * A pre-composed, immutable pipeline that can be executed repeatedly with
 * different {@link JoinPointExecutor} instances — without rebuilding the
 * chain structure on every call.
 *
 * <h3>Motivation</h3>
 * <p>The original {@link AspectPipelineBuilder} creates a full
 * {@link eu.inqudium.core.pipeline.JoinPointWrapper} chain on every
 * invocation. This is wasteful: the layer structure (which providers, in
 * what order, with what actions) is <strong>deterministic per Method</strong>.
 * Only the terminal executor ({@code pjp::proceed}) changes between calls.</p>
 *
 * <p>This class applies the same optimization used by
 * {@code SyncDispatchExtension} in the proxy module: it pre-composes the
 * {@link LayerAction} chain into a single
 * {@code Function<InternalExecutor, InternalExecutor>} at resolution time.
 * At invocation time, only the terminal executor is created (capturing
 * {@code pjp::proceed}), applied to the pre-built factory, and executed.</p>
 *
 * <h3>Per-call cost comparison</h3>
 * <table>
 *   <tr><th></th><th>JoinPointWrapper chain</th><th>ResolvedPipeline</th></tr>
 *   <tr><td>Filter providers</td><td>every call</td><td>once</td></tr>
 *   <tr><td>Sort providers</td><td>every call</td><td>once</td></tr>
 *   <tr><td>Extract LayerActions</td><td>every call</td><td>once</td></tr>
 *   <tr><td>Compose chain</td><td>N wrapper objects</td><td>0 objects (pre-composed)</td></tr>
 *   <tr><td>Terminal executor</td><td>1 lambda</td><td>1 lambda</td></tr>
 *   <tr><td>Chain/call IDs</td><td>via AtomicLong in wrapper</td><td>via AtomicLong here</td></tr>
 * </table>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The only mutable
 * state is the {@link AtomicLong} call-ID counter, which is thread-safe
 * by design.</p>
 *
 * @since 0.7.0
 */
public final class ResolvedPipeline {

    /**
     * The pre-composed chain factory. Takes a terminal executor (the actual
     * method invocation) and returns the fully composed chain that traverses
     * all layers before reaching the terminal.
     *
     * <p>For a three-layer pipeline [A, B, C] (outermost to innermost), the
     * factory produces:</p>
     * <pre>
     *   terminal → A.execute(chainId, callId, null,
     *                B.execute(chainId, callId, null,
     *                  C.execute(chainId, callId, null, terminal)))
     * </pre>
     */
    private final Function<InternalExecutor<Void, Object>,
            InternalExecutor<Void, Object>> chainFactory;

    /**
     * Unique chain ID for this resolved pipeline, drawn from the same global
     * counter as wrapper chains to avoid ID collisions.
     */
    private final long chainId;

    /**
     * Shared call-ID counter. Incremented once per invocation — same semantics
     * as {@link eu.inqudium.core.pipeline.AbstractBaseWrapper#generateCallId()}.
     */
    private final AtomicLong callIdCounter = new AtomicLong();

    /**
     * The layer names in order (outermost first), retained for diagnostics.
     */
    private final List<String> layerNames;

    // ======================== Construction ========================

    private ResolvedPipeline(
            Function<InternalExecutor<Void, Object>, InternalExecutor<Void, Object>> chainFactory,
            long chainId,
            List<String> layerNames) {
        this.chainFactory = chainFactory;
        this.chainId = chainId;
        this.layerNames = layerNames;
    }

    /**
     * Resolves and pre-composes a pipeline from the given providers, filtered
     * by the target method.
     *
     * <p>This is the main factory method. It performs filtering, sorting, and
     * chain composition <strong>once</strong>. The returned instance can then
     * be invoked repeatedly with different {@link JoinPointExecutor}s.</p>
     *
     * @param providers all registered providers (will be filtered and sorted)
     * @param method    the target method for {@code canHandle} filtering
     * @return a pre-composed, reusable pipeline
     */
    public static ResolvedPipeline resolve(List<? extends AspectLayerProvider<Object>> providers,
                                           Method method) {
        // Resolve applicable providers once — filter and sort in a single pass
        List<? extends AspectLayerProvider<Object>> applicable = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AspectLayerProvider::order))
                .toList();

        return fromProviders(applicable);
    }

    /**
     * Builds a {@code ResolvedPipeline} from an already filtered and sorted
     * provider list. Extracts actions and names in separate passes over the
     * same list — no redundant filtering or sorting.
     */
    private static ResolvedPipeline fromProviders(
            List<? extends AspectLayerProvider<Object>> providers) {
        List<LayerAction<Void, Object>> actions = providers.stream()
                .map(AspectLayerProvider::layerAction)
                .toList();

        List<String> names = providers.stream()
                .map(AspectLayerProvider::layerName)
                .toList();

        return new ResolvedPipeline(
                composeFactory(actions), CHAIN_ID_COUNTER.incrementAndGet(), names);
    }

    /**
     * Composes the chain factory inside-out from the sorted list of actions.
     *
     * <p>Walks the action list in reverse: the innermost action wraps the
     * terminal first, each outer action wraps the result of the previous
     * iteration.</p>
     */
    private static Function<InternalExecutor<Void, Object>,
            InternalExecutor<Void, Object>> composeFactory(
            List<LayerAction<Void, Object>> actions) {
        Function<InternalExecutor<Void, Object>,
                InternalExecutor<Void, Object>> factory = Function.identity();

        for (int i = actions.size() - 1; i >= 0; i--) {
            LayerAction<Void, Object> action = actions.get(i);
            Function<InternalExecutor<Void, Object>,
                    InternalExecutor<Void, Object>> outer = factory;

            factory = terminal -> {
                InternalExecutor<Void, Object> next = outer.apply(terminal);
                return (chainId, callId, arg) -> action.execute(chainId, callId, arg, next);
            };
        }
        return factory;
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed pipeline with the given join point executor.
     *
     * <p>Per-call cost: one terminal lambda creation, one {@code AtomicLong.incrementAndGet()},
     * and the chain traversal itself. No filtering, sorting, or wrapper-object allocation.</p>
     *
     * <p>Checked exceptions from the delegate are transported via
     * {@link CompletionException} and unwrapped before re-throwing, preserving
     * the same contract as {@link eu.inqudium.core.pipeline.JoinPointWrapper#proceed()}.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed})
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    public Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        long callId = callIdCounter.incrementAndGet();

        // Build the terminal executor — the only per-call allocation
        InternalExecutor<Void, Object> terminal = (cid, caid, arg) -> {
            try {
                return coreExecutor.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        };

        try {
            // Apply the pre-composed chain factory and execute
            return chainFactory.apply(terminal).execute(chainId, callId, null);
        } catch (CompletionException e) {
            throw e.getCause();
        }
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the chain ID assigned to this resolved pipeline.
     *
     * @return the chain ID (unique, from the global counter)
     */
    public long chainId() {
        return chainId;
    }

    /**
     * Returns the current (most recently generated) call ID.
     *
     * @return the latest call ID, or 0 if never executed
     */
    public long currentCallId() {
        return callIdCounter.get();
    }

    /**
     * Returns the layer names in order (outermost first).
     *
     * @return an unmodifiable list of layer names
     */
    public List<String> layerNames() {
        return layerNames;
    }

    /**
     * Returns the number of layers in this pipeline.
     *
     * @return the layer count
     */
    public int depth() {
        return layerNames.size();
    }

    /**
     * Returns a diagnostic string similar to
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()}.
     *
     * @return a formatted hierarchy string
     */
    public String toStringHierarchy() {
        StringBuilder sb = new StringBuilder();
        sb.append("Chain-ID: ").append(chainId)
                .append(" (current call-ID: ").append(callIdCounter.get())
                .append(")\n");

        for (int i = 0; i < layerNames.size(); i++) {
            if (i > 0) {
                sb.repeat("  ", i - 1).append("  └── ");
            }
            sb.append(layerNames.get(i)).append("\n");
        }
        return sb.toString();
    }
}
