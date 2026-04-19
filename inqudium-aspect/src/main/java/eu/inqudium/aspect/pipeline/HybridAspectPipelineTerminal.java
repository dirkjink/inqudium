package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid AspectJ terminal that automatically dispatches sync and async
 * method calls through the appropriate pipeline chain.
 *
 * <p>When the intercepted method returns {@link CompletionStage}, the call
 * is routed through the async chain (elements use
 * {@link InqAsyncDecorator#decorateAsyncJoinPoint}, resource lifecycle tied
 * to stage completion). All other methods use the sync chain.</p>
 *
 * <h3>Static pre-composition</h3>
 * <p>The pipeline's decorators are extracted into two flat arrays —
 * {@link #syncDecorators} and {@link #asyncDecorators} — <strong>exactly
 * once</strong>, in the constructor. The prior implementation stored a
 * {@code Function<Executor, Executor>} cascade per method: each invocation
 * of {@code factory.apply(terminal)} unwound that cascade recursively,
 * producing {@code N} virtual {@code Function.apply} dispatches on top of
 * the {@code N} wrapper-executor allocations. Since the decorator chain is
 * constant across methods (only the sync/async decision varies), pre-building
 * the arrays at construction eliminates both the cascade and the per-method
 * factory cache.</p>
 *
 * <h3>Per-method caching</h3>
 * <p>The only per-method state still cached is the sync/async flag itself,
 * stored in a {@link ConcurrentHashMap} keyed by {@link Method}. This avoids
 * the {@code Class.isAssignableFrom} check on the hot path after the first
 * invocation.</p>
 *
 * <pre>
 *   First call to placeOrder():
 *     1. MethodSignature.getMethod()                         ← per call
 *     2. cache miss → method.getReturnType() → sync          ← once per method
 *     3. iterate syncDecorators, compose chain               ← per call
 *     4. chain.proceed()                                     ← per call
 *
 *   Subsequent calls to placeOrder():
 *     1. MethodSignature.getMethod()                         ← per call
 *     2. cache hit → Boolean.FALSE                           ← fast path
 *     3. iterate syncDecorators, compose chain               ← per call
 *     4. chain.proceed()                                     ← per call
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
 * <p>Elements must implement <strong>both</strong> {@link InqDecorator} (sync)
 * and {@link InqAsyncDecorator} (async). This is verified eagerly in the
 * constructor — misconfigured pipelines fail fast instead of on first call.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The decorator arrays
 * are private, never modified after construction. The async-flag cache uses
 * {@link ConcurrentHashMap}.</p>
 *
 * @since 0.8.0
 */
public final class HybridAspectPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Pipeline decorators in composition order (element-order from the
     * pipeline fold — innermost first). Immutable after construction.
     */
    private final InqDecorator<Void, Object>[] syncDecorators;
    private final InqAsyncDecorator<Void, Object>[] asyncDecorators;

    /**
     * Cached sync/async decision per {@link Method}. The value is the result
     * of {@code CompletionStage.class.isAssignableFrom(method.getReturnType())}.
     */
    private final ConcurrentHashMap<Method, Boolean> asyncCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private HybridAspectPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;

        // Extract pipeline elements into an ordered list once.
        // The accumulator is the same list on every step; we just append.
        // The resulting order matches the pipeline's native element order —
        // whichever end the loop in executeAround iterates determines
        // innermost vs. outermost wrapping.
        List<InqElement> elements = pipeline.chain(
                new ArrayList<InqElement>(),
                (list, element) -> {
                    list.add(element);
                    return list;
                });

        // Pre-cast to both decorator types at construction time.
        // Misconfigured pipelines fail here, not on the first call.
        int size = elements.size();
        this.syncDecorators = new InqDecorator[size];
        this.asyncDecorators = new InqAsyncDecorator[size];
        for (int i = 0; i < size; i++) {
            InqElement element = elements.get(i);
            this.syncDecorators[i] = (InqDecorator<Void, Object>) asDecorator(element);
            this.asyncDecorators[i] = (InqAsyncDecorator<Void, Object>) asAsyncDecorator(element);
        }
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
     * @throws ClassCastException if any element fails the decorator contracts
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

    /**
     * Returns the underlying pipeline.
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    // ======================== AspectJ execution ========================

    /**
     * Executes the given {@link ProceedingJoinPoint} through the pipeline,
     * using the pre-built decorator arrays and the cached sync/async flag.
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

    // ======================== Generic execution ========================

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
     * without AspectJ weaving. Never throws.
     */
    @SuppressWarnings("unchecked")
    public <R> CompletionStage<R> executeAsync(
            JoinPointExecutor<CompletionStage<R>> executor) {
        return (CompletionStage<R>) executeAsyncChain(
                (JoinPointExecutor<CompletionStage<Object>>) (JoinPointExecutor<?>) executor);
    }

    // ======================== Chain composition (hot path) ========================

    /**
     * Composes the sync chain from the pre-built decorator array and executes it.
     *
     * <p>Single tight loop over {@link #syncDecorators}: each iteration calls
     * {@code decorateJoinPoint} on the decorator, which returns a wrapped
     * executor. No {@code Function.apply()} dispatch, no per-method factory
     * lookup, no cascade unwinding.</p>
     */
    private Object executeSyncChain(JoinPointExecutor<Object> terminal) throws Throwable {
        JoinPointExecutor<Object> current = terminal;
        InqDecorator<Void, Object>[] decorators = syncDecorators;
        // Iterate innermost → outermost (matches the semantics of the prior
        // Function-cascade: first-added element wraps the terminal directly)
        for (int i = 0; i < decorators.length; i++) {
            current = decorators[i].decorateJoinPoint(current);
        }
        return current.proceed();
    }

    /**
     * Composes the async chain from the pre-built decorator array and executes it.
     *
     * <p>Mirrors {@link #executeSyncChain} for the async path. Any synchronous
     * failure during chain composition or start-phase execution is delivered
     * through the returned {@link CompletionStage} — never thrown.</p>
     */
    private CompletionStage<Object> executeAsyncChain(
            JoinPointExecutor<CompletionStage<Object>> terminal) {
        try {
            JoinPointExecutor<CompletionStage<Object>> current = terminal;
            InqAsyncDecorator<Void, Object>[] decorators = asyncDecorators;
            for (int i = 0; i < decorators.length; i++) {
                current = decorators[i].decorateAsyncJoinPoint(current);
            }
            return current.proceed();
        } catch (Throwable e) {
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
