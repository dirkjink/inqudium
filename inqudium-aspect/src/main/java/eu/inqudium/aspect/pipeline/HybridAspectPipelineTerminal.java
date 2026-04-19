package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.PipelineDiagnostics;
import eu.inqudium.core.pipeline.Throws;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid AspectJ terminal that automatically dispatches sync and async
 * method calls through the appropriate pipeline chain.
 *
 * <p>When the intercepted method returns {@link CompletionStage}, the call
 * is routed through the async layer-action chain. All other methods use the
 * sync layer-action chain.</p>
 *
 * <h3>Static pre-extraction of layer actions</h3>
 * <p>At construction time, the pipeline's elements are walked exactly once
 * and their around-advice is cached as flat arrays in outermost-first order:</p>
 * <ul>
 *   <li>{@code syncActions}: {@link LayerAction}-references harvested by
 *       casting each {@link InqDecorator} (which extends
 *       {@link LayerAction}).</li>
 *   <li>{@code asyncActions}: {@link AsyncLayerAction}-references harvested
 *       via method reference on each {@link InqAsyncDecorator}'s
 *       {@code executeAsync} method.</li>
 * </ul>
 *
 * <p>The storage order matches the convention used by {@code ResolvedPipeline}
 * and {@code SyncPipelineTerminal}. Per-call composition iterates reverse
 * over the array producing only {@code N+1} escape-analysable
 * {@link InternalExecutor} / {@link InternalAsyncExecutor} lambdas — no heap
 * wrapper objects.</p>
 *
 * <h3>Per-method caching</h3>
 * <p>A {@link ConcurrentHashMap} caches the sync/async decision per
 * {@link Method} — a single {@link Boolean} instead of a whole chain factory.
 * On hot-path invocations, the only per-method overhead is the
 * {@code MethodSignature.getMethod()} call (unavoidable — AspectJ provides
 * a fresh {@link ProceedingJoinPoint} per call) and a {@code get} on the
 * concurrent map.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Aspect
 * public class ResilienceAspect {
 *
 *     private final HybridAspectPipelineTerminal terminal =
 *             HybridAspectPipelineTerminal.of(
 *                     InqPipeline.builder()
 *                             .shield(circuitBreaker)
 *                             .shield(bulkhead)
 *                             .build());
 *
 *     @Around("@annotation(Resilient)")
 *     public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *         return terminal.executeAround(pjp);
 *     }
 * }
 * }</pre>
 *
 * <h3>Element requirements</h3>
 * <p>Elements must implement <strong>both</strong> {@link InqDecorator} and
 * {@link InqAsyncDecorator}. Both contracts are validated eagerly in the
 * constructor — misconfigured pipelines throw {@link ClassCastException} at
 * {@link #of(InqPipeline)} rather than on the first call.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The action arrays
 * are never modified after construction; the call-ID counter in
 * {@link PipelineDiagnostics} is thread-safe; the async-flag cache uses
 * {@link ConcurrentHashMap}. Safe for virtual threads.</p>
 *
 * @since 0.8.0
 */
public final class HybridAspectPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Pre-extracted sync layer actions in outermost-first order. Since
     * {@link InqDecorator} extends {@link LayerAction}, the decorator
     * reference itself serves as the layer action — no extra indirection,
     * no method reference object.
     */
    private final LayerAction<Void, Object>[] syncActions;

    /**
     * Pre-extracted async layer actions in outermost-first order. Captured
     * via method reference on {@link InqAsyncDecorator#executeAsync},
     * allocated once per element at construction time.
     */
    private final AsyncLayerAction<Void, Object>[] asyncActions;

    /**
     * Shared diagnostics state — chain ID, per-invocation call-ID counter,
     * layer names, and formatted hierarchy rendering. Shared between the
     * sync and async paths since any given call traverses exactly one of them.
     */
    private final PipelineDiagnostics diagnostics;

    /**
     * Cached sync/async decision per {@link Method}. The value is the result
     * of {@code CompletionStage.class.isAssignableFrom(method.getReturnType())}.
     */
    private final ConcurrentHashMap<Method, Boolean> asyncCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private HybridAspectPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;

        // Walk the pipeline exactly once and collect elements. Native iteration
        // order is innermost-first; reverse to the outermost-first convention
        // shared with ResolvedPipeline/AsyncResolvedPipeline/SyncPipelineTerminal.
        List<InqElement> elements = pipeline.chain(
                new ArrayList<InqElement>(),
                (list, element) -> {
                    list.add(element);
                    return list;
                });
        Collections.reverse(elements);

        // Pre-extract both action variants. Misconfigured pipelines (element
        // missing one of the two decorator contracts) fail here, not on the
        // first hot-path call. The instanceof + cast each run exactly once.
        int size = elements.size();
        LayerAction<Void, Object>[] syncActs = new LayerAction[size];
        AsyncLayerAction<Void, Object>[] asyncActs = new AsyncLayerAction[size];
        List<String> names = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            InqElement element = elements.get(i);
            // InqDecorator extends LayerAction — the decorator reference IS
            // the layer action, no adapter object needed.
            syncActs[i] = (LayerAction<Void, Object>) asDecorator(element);
            // InqAsyncDecorator.executeAsync matches AsyncLayerAction's
            // functional-interface signature — one bound method reference
            // per element, allocated here at construction.
            InqAsyncDecorator<Void, Object> asyncDec =
                    (InqAsyncDecorator<Void, Object>) asAsyncDecorator(element);
            asyncActs[i] = asyncDec::executeAsync;
            names.add(element.getElementType().name() + "(" + element.getName() + ")");
        }
        this.syncActions = syncActs;
        this.asyncActions = asyncActs;
        this.diagnostics = PipelineDiagnostics.create(Collections.unmodifiableList(names));
    }

    /**
     * Creates a hybrid aspect terminal for the given pipeline.
     *
     * <p>Verifies eagerly that every element in the pipeline implements
     * both {@link InqDecorator} and {@link InqAsyncDecorator} — a
     * {@link ClassCastException} is thrown here rather than at the first
     * call site.</p>
     *
     * @param pipeline the composed pipeline
     * @return the hybrid terminal
     * @throws NullPointerException if pipeline is null
     * @throws ClassCastException   if any element fails the decorator contracts
     */
    public static HybridAspectPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new HybridAspectPipelineTerminal(pipeline);
    }

    // ======================== Static helpers: element validation ========================

    private static InqDecorator<?, ?> asDecorator(InqElement element) {
        if (element instanceof InqDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqDecorator. "
                        + "HybridAspectPipelineTerminal requires all elements to implement "
                        + "InqDecorator for sync methods.");
    }

    private static InqAsyncDecorator<?, ?> asAsyncDecorator(InqElement element) {
        if (element instanceof InqAsyncDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqAsyncDecorator. "
                        + "HybridAspectPipelineTerminal requires all elements to implement "
                        + "InqAsyncDecorator for async methods (returning CompletionStage).");
    }

    // ======================== Accessors ========================

    /**
     * Returns the underlying pipeline.
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    // ======================== AspectJ execution ========================

    /**
     * Executes the given {@link ProceedingJoinPoint} through the pipeline,
     * using the pre-extracted layer-action arrays and the cached sync/async
     * flag.
     *
     * <ul>
     *   <li>Return type is {@link CompletionStage} → async chain
     *       (uniform error channel — never throws)</li>
     *   <li>All other return types → sync chain</li>
     * </ul>
     *
     * @param pjp the proceeding join point provided by AspectJ
     * @return the result — either a direct value (sync) or a
     *         {@link CompletionStage} (async)
     * @throws Throwable any exception from sync methods or pipeline elements
     */
    @SuppressWarnings("unchecked")
    public Object executeAround(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        if (resolveAsync(method)) {
            return executeAsyncChain(() -> (CompletionStage<Object>) pjp.proceed());
        }
        return executeSyncChain(pjp::proceed);
    }

    // ======================== Generic execution (test utility) ========================

    /**
     * Executes a sync call through the pipeline. Useful for unit tests
     * without AspectJ weaving.
     */
    @SuppressWarnings("unchecked")
    public <R> R execute(JoinPointExecutor<R> executor) throws Throwable {
        return (R) executeSyncChain((JoinPointExecutor<Object>) (JoinPointExecutor<?>) executor);
    }

    /**
     * Executes an async call through the pipeline. Useful for unit tests
     * without AspectJ weaving. Never throws — all failures are delivered
     * through the returned {@link CompletionStage}.
     */
    @SuppressWarnings("unchecked")
    public <R> CompletionStage<R> executeAsync(
            JoinPointExecutor<CompletionStage<R>> executor) {
        return (CompletionStage<R>) executeAsyncChain(
                (JoinPointExecutor<CompletionStage<Object>>) (JoinPointExecutor<?>) executor);
    }

    // ======================== Chain composition (hot path) ========================

    /**
     * Composes the sync chain from the pre-built layer-action array and
     * executes it.
     *
     * <p>Actions are stored in outermost-first order; reverse iteration
     * wraps each layer around the prior result. After the loop the final
     * {@code current} is the outermost executor.</p>
     */
    private Object executeSyncChain(JoinPointExecutor<Object> executor) throws Throwable {
        long callId = diagnostics.nextCallId();
        long cid = diagnostics.chainId();

        // Terminal: invokes the executor and wraps checked exceptions for
        // transport through the chain's unchecked-only layer actions.
        InternalExecutor<Void, Object> current = (c, ca, a) -> {
            try {
                return executor.proceed();
            } catch (Throwable t) {
                throw Throws.wrapChecked(t);
            }
        };

        LayerAction<Void, Object>[] acts = syncActions;
        for (int i = acts.length - 1; i >= 0; i--) {
            LayerAction<Void, Object> action = acts[i];
            InternalExecutor<Void, Object> next = current;
            current = (c, ca, a) -> action.execute(c, ca, a, next);
        }

        try {
            return current.execute(cid, callId, null);
        } catch (CompletionException e) {
            // Unwrap the transported throwable, preserving its original type
            // (checked exception, runtime exception, or error) via sneaky throw.
            throw Throws.unwrapAndRethrow(e);
        }
    }

    /**
     * Composes the async chain from the pre-built async layer-action array
     * and executes it. Never throws — all failures are delivered through the
     * returned {@link CompletionStage}.
     */
    private CompletionStage<Object> executeAsyncChain(
            JoinPointExecutor<CompletionStage<Object>> executor) {
        long callId = diagnostics.nextCallId();
        long cid = diagnostics.chainId();

        // Terminal: invokes the executor and bridges checked exceptions
        // through CompletionException. Runtime exceptions and errors propagate
        // directly and are converted to failed futures at the boundary.
        InternalAsyncExecutor<Void, Object> current = (c, ca, a) -> {
            try {
                return executor.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        };

        AsyncLayerAction<Void, Object>[] acts = asyncActions;
        for (int i = acts.length - 1; i >= 0; i--) {
            AsyncLayerAction<Void, Object> action = acts[i];
            InternalAsyncExecutor<Void, Object> next = current;
            current = (c, ca, a) -> action.executeAsync(c, ca, a, next);
        }

        try {
            return current.executeAsync(cid, callId, null);
        } catch (CompletionException e) {
            // Transported checked exception from the terminal — surface the
            // original cause through the failed-future channel.
            Throwable cause = e.getCause();
            return CompletableFuture.failedFuture(cause != null ? cause : e);
        } catch (Throwable e) {
            // Any synchronous failure from a layer's start phase becomes a
            // failed future — uniform error channel for callers.
            return CompletableFuture.failedFuture(e);
        }
    }

    // ======================== Internal: async-flag cache ========================

    /**
     * Returns the cached sync/async decision for the given method, computing
     * it on first access.
     */
    private boolean resolveAsync(Method method) {
        Boolean cached = asyncCache.get(method);
        if (cached != null) {
            return cached;
        }
        return asyncCache.computeIfAbsent(method, this::isAsync);
    }

    /**
     * Computes whether the method returns a {@link CompletionStage}.
     * Extracted as a named method so {@code this::isAsync} produces a stable
     * method reference without per-call lambda allocation inside
     * {@link ConcurrentHashMap#computeIfAbsent}.
     */
    private boolean isAsync(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the chain ID assigned to this terminal instance.
     */
    public long chainId() {
        return diagnostics.chainId();
    }

    /**
     * Returns the most recently generated call ID across all threads.
     * <strong>Informational only.</strong>
     */
    public long currentCallId() {
        return diagnostics.currentCallId();
    }

    /**
     * Returns the layer names in outermost-first order. Each name follows
     * the pattern {@code "ELEMENT_TYPE(name)"}.
     */
    public List<String> layerNames() {
        return diagnostics.layerNames();
    }

    /**
     * Returns the number of layers in this pipeline.
     */
    public int depth() {
        return diagnostics.depth();
    }

    /**
     * Returns a diagnostic string rendering the layer hierarchy.
     */
    public String toStringHierarchy() {
        return diagnostics.toStringHierarchy();
    }
}
