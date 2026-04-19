package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Throws;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static eu.inqudium.core.pipeline.ChainIdGenerator.CHAIN_ID_COUNTER;

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
 * and their around-advice is cached as flat arrays:</p>
 * <ul>
 *   <li>{@code syncActions}: {@link LayerAction}-references harvested by
 *       casting each {@link InqDecorator} (which extends
 *       {@link LayerAction}).</li>
 *   <li>{@code asyncActions}: {@link AsyncLayerAction}-references harvested
 *       via method reference on each {@link InqAsyncDecorator}'s
 *       {@code executeAsync} method.</li>
 * </ul>
 *
 * <p>The previous implementation stored {@code InqDecorator[]} and
 * {@code InqAsyncDecorator[]} and called {@code decorateJoinPoint} /
 * {@code decorateAsyncJoinPoint} on every call — producing {@code N}
 * {@link eu.inqudium.core.pipeline.JoinPointWrapper} /
 * {@code AsyncJoinPointWrapper} objects per invocation. With layer actions
 * stored directly, per-call composition produces only {@code N+1}
 * escape-analysable {@link InternalExecutor} /
 * {@link InternalAsyncExecutor} lambdas — no heap wrapper objects.</p>
 *
 * <h3>Per-method caching</h3>
 * <p>A {@link ConcurrentHashMap} caches the sync/async decision per
 * {@link Method} — a single {@link Boolean} instead of a whole chain factory.
 * On hot-path invocations, the only per-method overhead is the
 * {@code MethodSignature.getMethod()} call (unavoidable — AspectJ provides
 * a fresh {@link ProceedingJoinPoint} per call) and a {@code get} on the
 * concurrent map.</p>
 *
 * <pre>
 *   First call to placeOrder():
 *     1. MethodSignature.getMethod()                  ← per call
 *     2. cache miss → isAssignableFrom check          ← once per method
 *     3. iterate syncActions, compose chain           ← per call, stack-lokal
 *     4. chain.execute(...)                           ← per call
 *
 *   Subsequent calls to placeOrder():
 *     1. MethodSignature.getMethod()                  ← per call
 *     2. cache hit → Boolean.FALSE                    ← fast path
 *     3. iterate syncActions, compose chain           ← per call, stack-lokal
 *     4. chain.execute(...)                           ← per call
 * </pre>
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
 * are never modified after construction; the call-ID counter is a thread-safe
 * {@link AtomicLong}; the async-flag cache uses {@link ConcurrentHashMap}.
 * Safe for virtual threads.</p>
 *
 * @since 0.8.0
 */
public final class HybridAspectPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Pre-extracted sync layer actions in pipeline-element order (innermost
     * first). Since {@link InqDecorator} extends {@link LayerAction}, the
     * decorator reference itself serves as the layer action — no extra
     * indirection, no method reference object.
     */
    private final LayerAction<Void, Object>[] syncActions;

    /**
     * Pre-extracted async layer actions in pipeline-element order (innermost
     * first). Captured via method reference on
     * {@link InqAsyncDecorator#executeAsync}, allocated once per element at
     * construction time.
     */
    private final AsyncLayerAction<Void, Object>[] asyncActions;

    /**
     * Chain identifier for this terminal instance. Allocated once from the
     * global {@link eu.inqudium.core.pipeline.ChainIdGenerator} and used for
     * every {@code execute*} invocation.
     */
    private final long chainId;

    /**
     * Per-invocation call-ID counter. Shared by the sync and async paths —
     * any given call goes through exactly one path, so a single counter
     * yields globally unique IDs within this terminal's lifetime.
     */
    private final AtomicLong callIdCounter = new AtomicLong();

    /**
     * Cached sync/async decision per {@link Method}. The value is the result
     * of {@code CompletionStage.class.isAssignableFrom(method.getReturnType())}.
     */
    private final ConcurrentHashMap<Method, Boolean> asyncCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private HybridAspectPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;

        // Walk the pipeline exactly once and collect elements in their native
        // order (innermost first — confirmed by the decorator fold semantics:
        // the first element applied to the seed wraps the seed directly).
        List<InqElement> elements = pipeline.chain(
                new ArrayList<InqElement>(),
                (list, element) -> {
                    list.add(element);
                    return list;
                });

        // Pre-extract both action variants. Misconfigured pipelines (element
        // missing one of the two decorator contracts) fail here, not on the
        // first hot-path call. The instanceof + cast each run exactly once.
        int size = elements.size();
        LayerAction<Void, Object>[] syncActs = new LayerAction[size];
        AsyncLayerAction<Void, Object>[] asyncActs = new AsyncLayerAction[size];
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
        }
        this.syncActions = syncActs;
        this.asyncActions = asyncActs;
        this.chainId = CHAIN_ID_COUNTER.incrementAndGet();
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
     * {@link CompletionStage} (async)
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
     * <p>Per-call cost: one {@link AtomicLong} CAS for the call ID, one
     * {@link InternalExecutor} terminal lambda binding the executor,
     * {@code N} wrapper lambdas composed in a tight loop, and a single
     * {@link CompletionException}-unwrapping try/catch at the boundary.
     * No wrapper objects, no {@code instanceof} checks, no
     * {@code Function.apply} cascade.</p>
     */
    private Object executeSyncChain(JoinPointExecutor<Object> executor) throws Throwable {
        long callId = callIdCounter.incrementAndGet();
        long cid = chainId;

        // Terminal: invokes the executor and wraps checked exceptions for
        // transport through the chain's unchecked-only layer actions.
        InternalExecutor<Void, Object> current = (c, ca, a) -> {
            try {
                return executor.proceed();
            } catch (Throwable t) {
                throw Throws.wrapChecked(t);
            }
        };

        // Compose chain from the pre-cached action array. Actions are stored
        // in innermost-first order: forward iteration wraps each prior result
        // as an outer layer, so the final `current` is the outermost executor.
        LayerAction<Void, Object>[] acts = syncActions;
        for (int i = 0; i < acts.length; i++) {
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
     *
     * <p>Per-call cost mirrors the sync path: one CAS, one terminal lambda,
     * {@code N} wrapper lambdas. No {@link eu.inqudium.imperative.core.pipeline.AsyncJoinPointWrapper}
     * allocations.</p>
     */
    private CompletionStage<Object> executeAsyncChain(
            JoinPointExecutor<CompletionStage<Object>> executor) {
        long callId = callIdCounter.incrementAndGet();
        long cid = chainId;

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

        // Compose chain from the pre-cached action array (same iteration
        // pattern as the sync path).
        AsyncLayerAction<Void, Object>[] acts = asyncActions;
        for (int i = 0; i < acts.length; i++) {
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
}
