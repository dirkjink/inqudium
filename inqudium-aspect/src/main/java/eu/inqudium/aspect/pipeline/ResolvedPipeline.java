package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.ResolvedPipelineState;
import eu.inqudium.core.pipeline.Throws;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;

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
 * <h3>Hot-path optimization</h3>
 * <p>The {@link LayerAction} references are extracted from providers
 * <strong>once</strong> at resolution time and stored in a pre-built array.
 * At invocation time, the chain is composed inline via a simple reverse
 * loop over that array — no {@code Function<F,F>} cascade, no
 * {@code ThreadLocal}, no per-call provider access.</p>
 *
 * <p>Each invocation creates N+1 small {@link InternalExecutor} lambdas
 * (one terminal + one per layer). These lambdas are short-lived, do not
 * escape the {@code execute()} call, and are strong candidates for JIT
 * stack-allocation via escape analysis.</p>
 *
 * <h3>Per-call cost comparison</h3>
 * <table>
 *   <tr><th></th><th>JoinPointWrapper chain</th><th>ResolvedPipeline</th></tr>
 *   <tr><td>Filter providers</td><td>every call</td><td>once</td></tr>
 *   <tr><td>Sort providers</td><td>every call</td><td>once</td></tr>
 *   <tr><td>Extract LayerActions</td><td>every call</td><td>once (pre-built array)</td></tr>
 *   <tr><td>Compose chain</td><td>N wrapper objects</td><td>N lambdas (escape-analysable)</td></tr>
 *   <tr><td>Terminal executor</td><td>1 lambda</td><td>1 lambda</td></tr>
 * </table>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are safe for concurrent use. The pre-built action array is
 * immutable; the chain composition is purely stack-local; call-ID generation
 * is delegated to {@link ResolvedPipelineState}, which is thread-safe by
 * construction. No {@code ThreadLocal} or shared mutable state is used —
 * the pipeline is safe for virtual threads, reactive pipelines, and
 * coroutines.</p>
 *
 * @since 0.7.0
 */
public final class ResolvedPipeline {

    /**
     * Sentinel empty action array — avoids null checks in the execution path.
     */
    private static final LayerAction<Void, Object>[] EMPTY_ACTIONS = newActionArray(0);

    /**
     * Sentinel instance for methods with no applicable layers.
     */
    private static final ResolvedPipeline EMPTY = new ResolvedPipeline(
            EMPTY_ACTIONS, ResolvedPipelineState.EMPTY);

    /**
     * Pre-built, immutable array of layer actions in execution order
     * (outermost first). Extracted from providers once during
     * {@link #fromProviders} — no per-call provider access.
     */
    private final LayerAction<Void, Object>[] actions;

    private final ResolvedPipelineState state;

    // ======================== Construction ========================

    private ResolvedPipeline(LayerAction<Void, Object>[] actions,
                             ResolvedPipelineState state) {
        this.actions = actions;
        this.state = state;
    }

    /**
     * Resolves and pre-composes a pipeline from the given providers, filtered
     * by the target method.
     *
     * <p>This is the main factory method. It performs filtering, sorting, and
     * action extraction <strong>once</strong>. The returned instance can then
     * be invoked repeatedly with different {@link JoinPointExecutor}s.</p>
     *
     * @param providers all registered providers (will be filtered and sorted)
     * @param method    the target method for {@code canHandle} filtering
     * @return a pre-composed, reusable pipeline
     * @throws IllegalArgumentException if providers or method is null
     */
    public static ResolvedPipeline resolve(List<? extends AspectLayerProvider<Object>> providers,
                                           Method method) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("Method must not be null");
        }

        List<? extends AspectLayerProvider<Object>> applicable = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AspectLayerProvider::order))
                .toList();

        return fromProviders(applicable);
    }

    /**
     * Resolves and pre-composes a pipeline from all given providers without
     * applying {@code canHandle} filtering — used when no target
     * {@link Method} is available (e.g. non-method join points, or aspects
     * that reuse the same chain for every invocation).
     *
     * <p>Providers are sorted by {@link AspectLayerProvider#order()} once,
     * then composed into the permanent action array. The returned instance
     * can then be invoked repeatedly with different {@link JoinPointExecutor}s.</p>
     *
     * @param providers all registered providers (will be sorted by order)
     * @return a pre-composed, reusable pipeline
     * @throws IllegalArgumentException if {@code providers} is null
     */
    public static ResolvedPipeline resolveAll(
            List<? extends AspectLayerProvider<Object>> providers) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }

        List<? extends AspectLayerProvider<Object>> sorted = providers.stream()
                .sorted(Comparator.comparingInt(AspectLayerProvider::order))
                .toList();

        return fromProviders(sorted);
    }

    /**
     * Builds a {@code ResolvedPipeline} from an already filtered and sorted
     * provider list.
     *
     * <p>Extracts layer actions and names in a single pass. The actions are
     * stored in a permanent array; names go to {@link ResolvedPipelineState}.</p>
     */
    private static ResolvedPipeline fromProviders(
            List<? extends AspectLayerProvider<Object>> providers) {
        if (providers.isEmpty()) {
            return EMPTY;
        }

        int size = providers.size();
        LayerAction<Void, Object>[] acts = newActionArray(size);
        String[] names = new String[size];

        for (int i = 0; i < size; i++) {
            AspectLayerProvider<Object> p = providers.get(i);
            acts[i] = p.layerAction();
            names[i] = p.layerName();
        }

        return new ResolvedPipeline(acts, ResolvedPipelineState.create(List.of(names)));
    }

    @SuppressWarnings("unchecked")
    private static LayerAction<Void, Object>[] newActionArray(int size) {
        return new LayerAction[size];
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed pipeline with the given join point executor.
     *
     * <p>Per-call cost: one reverse loop over the pre-built action array,
     * creating N+1 {@link InternalExecutor} lambdas (one terminal + one per
     * layer). These lambdas capture at most two references each (action + next),
     * are confined to this call's stack frame, and are strong candidates for
     * JIT escape analysis — meaning they are typically stack-allocated rather
     * than heap-allocated.</p>
     *
     * <p>Checked exceptions from the delegate are transported via
     * {@link CompletionException} and unwrapped before re-throwing, preserving
     * the same contract as
     * {@link eu.inqudium.core.pipeline.JoinPointWrapper#proceed()}.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed})
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    public Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        long callId = state.nextCallId();
        long cid = state.chainId();

        // Terminal — wraps the actual method invocation
        InternalExecutor<Void, Object> current = (c, ca, a) -> {
            try {
                return coreExecutor.proceed();
            } catch (Throwable t) {
                throw Throws.wrapChecked(t);
            }
        };

        // Compose chain inside-out from pre-built action array.
        // Each lambda captures only 2 references: the action and next.
        for (int i = actions.length - 1; i >= 0; i--) {
            LayerAction<Void, Object> action = actions[i];
            InternalExecutor<Void, Object> next = current;
            current = (c, ca, a) -> action.execute(c, ca, a, next);
        }

        try {
            return current.execute(cid, callId, null);
        } catch (CompletionException e) {
            throw Throws.unwrapAndRethrow(e);
        }
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the chain ID assigned to this resolved pipeline.
     */
    public long chainId() {
        return state.chainId();
    }

    /**
     * Returns the layer names in order (outermost first).
     */
    public List<String> layerNames() {
        return state.layerNames();
    }

    /**
     * Returns the number of layers in this pipeline.
     */
    public int depth() {
        return state.depth();
    }

    /**
     * Returns a diagnostic string similar to
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()}.
     *
     * @return a formatted hierarchy string
     */
    public String toStringHierarchy() {
        return state.toStringHierarchy();
    }
}
