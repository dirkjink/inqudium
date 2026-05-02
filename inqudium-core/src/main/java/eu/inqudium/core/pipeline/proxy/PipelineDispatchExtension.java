package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Dispatch extension for synchronous method calls driven by an {@link InqPipeline}.
 *
 * <p>This is the pipeline-based counterpart to {@link SyncDispatchExtension}.
 * Where {@code SyncDispatchExtension} wraps a single {@link eu.inqudium.core.pipeline.LayerAction},
 * {@code PipelineDispatchExtension} folds an entire {@link InqPipeline} into the
 * decorator chain at construction time and reuses the resulting chain factory
 * for every method invocation.</p>
 *
 * <p>This is the standard catch-all sync extension — {@link #canHandle} always
 * returns {@code true}, and {@link #isCatchAll()} returns {@code true}. When
 * composing extensions in a {@link ProxyWrapper}, this extension must be
 * registered <strong>last</strong> so that more specific extensions (e.g.
 * async) get first match.</p>
 *
 * <h3>Dispatch flow</h3>
 * <p>At construction, every pipeline element is folded via
 * {@link InqDecorator#decorateJoinPoint} into a single chain factory. Each
 * service-method invocation builds a method-specific terminal (a
 * {@link MethodInvoker} captured in an {@link InternalExecutor} lambda),
 * adapts it to a {@link JoinPointExecutor}, and runs it through the cached
 * factory. The hot path is one map lookup (for the invoker), one
 * {@code factory.apply(terminal)} call, and one {@code chain.proceed()}.</p>
 *
 * <h3>Chain-walk optimization</h3>
 * <p>When {@link #linkInner} finds a type-compatible
 * {@code PipelineDispatchExtension} in the inner proxy, the two extensions are
 * wired into a direct chain walk. The outer extension's terminal (instead of
 * invoking the inner JDK proxy via {@code MethodInvoker}) calls the inner
 * extension's {@link #executeChain} directly. The inner extension's terminal
 * then invokes the deep {@code realTarget} — the actual business object —
 * bypassing all intermediate proxy layers.</p>
 *
 * <p>Mixed-extension stacks (e.g. a {@link SyncDispatchExtension} inner wrapped
 * by a {@code PipelineDispatchExtension} outer) are <strong>not</strong>
 * optimized via chain-walk. {@link #linkInner} requires an exact-type match —
 * different chain-composition strategies cannot share a chain walk safely.
 * Such stacks dispatch through normal proxy re-entry, preserving correctness
 * at the cost of one extra invocation handler call.</p>
 *
 * @since 0.8.0
 */
public class PipelineDispatchExtension implements DispatchExtension {

    /**
     * The pipeline that drives this extension's dispatch chain.
     * Stored to allow the linked constructor to rebuild the chain factory
     * without altering the pipeline composition.
     */
    private final InqPipeline pipeline;

    /**
     * Pre-composed chain factory built once from {@link #pipeline} via
     * {@link #buildChainFactory()}. Reused across all method invocations
     * on this extension instance — pipeline folding happens exactly once.
     */
    private final Function<JoinPointExecutor<Object>,
            JoinPointExecutor<Object>> chainFactory;

    /**
     * Factory function that wraps the per-call terminal with the inner
     * extension's chain (if linked). For unlinked extensions this is
     * {@link Function#identity()} — the terminal is used as-is. For linked
     * extensions, this replaces the terminal with a lambda that invokes the
     * inner extension's {@link #executeChain}, effectively chaining the two
     * pipelines together without proxy re-entry.
     */
    private final Function<InternalExecutor<Void, Object>,
            InternalExecutor<Void, Object>> nextStepFactory;

    /**
     * When non-null, overrides the target passed to {@link #dispatch} for the
     * terminal invocation. Set only when this extension was successfully
     * linked with an inner {@code PipelineDispatchExtension} — the chain walk
     * handles intermediate layers, so the terminal can jump straight to the
     * deep real target without going through intermediate proxies.
     */
    private final Object overrideTarget;

    /**
     * Per-extension handle cache — avoids a global singleton and keeps cache
     * sizes proportional to the methods actually dispatched through this
     * extension. When extensions are linked, the outer extension's cache is
     * inherited by the linked instance to reuse already-resolved handles
     * and invokers.
     */
    private final MethodHandleCache handleCache;

    // ======================== Public constructor (root / standalone) ========================

    /**
     * Creates a new standalone (root) pipeline-driven dispatch extension.
     *
     * <p>This is the constructor used by application code and by factory
     * methods such as {@code InqProxyFactory.of(pipeline)}. The extension
     * starts unlinked — chain-walk linking happens later during
     * {@link ProxyWrapper} construction if an inner proxy is detected.</p>
     *
     * <p>The chain factory is built eagerly here. Misconfigured pipelines
     * (e.g. an element that does not implement {@link InqDecorator}) will
     * fail at the first dispatch rather than at construction, since the
     * fold inside {@link #buildChainFactory()} is lazy — it produces a
     * function whose body invokes {@code asDecorator(...)} per call.</p>
     *
     * @param pipeline the composed pipeline (must not be {@code null})
     * @throws NullPointerException if {@code pipeline} is {@code null}
     */
    public PipelineDispatchExtension(InqPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "Pipeline must not be null");
        this.chainFactory = buildChainFactory();
        this.nextStepFactory = Function.identity(); // No inner chain — terminal is used as-is
        this.overrideTarget = null;                 // No override — use whatever target ProxyWrapper provides
        this.handleCache = new MethodHandleCache(); // Fresh cache for this extension
    }

    // ======================== Internal constructors ========================

    /**
     * Linked constructor — wires a direct chain walk to the inner extension
     * (if non-null) and uses {@code realTarget} as the terminal override.
     *
     * <p>Inherits the outer extension's handle cache so that already-resolved
     * handles and invokers are reused across the linked pair. The chain
     * factory is rebuilt from the pipeline (the pipeline composition does
     * not change between root and linked instances; only the chain-walk
     * wiring does).</p>
     *
     * @param pipeline    the pipeline driving this extension's chain
     * @param inner       the inner extension to chain into (or {@code null}
     *                    if no type-compatible match was found)
     * @param realTarget  the deep non-proxy target for terminal invocation
     *                    ({@code null} when no inner match exists)
     * @param handleCache the handle cache to inherit from the outer extension
     */
    private PipelineDispatchExtension(InqPipeline pipeline,
                                      PipelineDispatchExtension inner,
                                      Object realTarget,
                                      MethodHandleCache handleCache) {
        this.pipeline = pipeline;
        this.chainFactory = buildChainFactory();

        // If an inner extension was found, create a next-step factory that
        // chains into the inner extension's executeChain(). This replaces
        // normal proxy re-entry with a direct method call.
        // If no inner was found, use identity — the terminal is used as-is.
        this.nextStepFactory = (inner != null)
                ? terminal -> (cid, caid, a) -> inner.executeChain(cid, caid, terminal)
                : Function.identity();

        this.overrideTarget = realTarget;
        this.handleCache = handleCache;
    }

    // ======================== Helpers ========================

    /**
     * Casts an {@link InqElement} to {@link InqDecorator}, providing a
     * descriptive error if the element does not implement the sync decorator.
     *
     * <p>Mirrors the message form used by {@code SyncPipelineTerminal} and
     * the (deprecated) hybrid terminal so that diagnostics remain consistent
     * across the proxy stack.</p>
     */
    private static InqDecorator<?, ?> asDecorator(InqElement element) {
        if (element instanceof InqDecorator<?, ?> decorator) {
            return decorator;
        }
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.name()
                        + "', type=" + element.elementType()
                        + ") does not implement InqDecorator. "
                        + "PipelineDispatchExtension requires all pipeline elements to "
                        + "implement InqDecorator<A, R> for sync method dispatch.");
    }

    /**
     * Searches an extension array for the first
     * {@code PipelineDispatchExtension}.
     *
     * <p>The match is <strong>exact-type</strong> — other catch-all sync
     * extensions (notably {@link SyncDispatchExtension}) are NOT considered
     * compatible counterparts. Different classes have different
     * chain-composition strategies and cannot share a chain walk.</p>
     *
     * @param extensions the inner proxy's extension array
     * @return the first {@code PipelineDispatchExtension} found, or {@code null}
     */
    private static PipelineDispatchExtension findInner(DispatchExtension[] extensions) {
        for (int i = 0; i < extensions.length; i++) {
            if (extensions[i] instanceof PipelineDispatchExtension p) {
                return p;
            }
        }
        return null;
    }

    /**
     * Folds the pipeline into a sync decorator chain factory.
     *
     * <p>The returned function takes a terminal {@link JoinPointExecutor}
     * and produces the fully-decorated chain in a single
     * {@link Function#apply} call. Each fold step wraps the accumulated
     * chain with the next outer element's
     * {@link InqDecorator#decorateJoinPoint}.</p>
     *
     * @return the chain factory; reused for every dispatch on this extension
     */
    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> buildChainFactory() {
        return pipeline.chain(
                Function.<JoinPointExecutor<Object>>identity(),
                (accFn, element) -> executor ->
                        ((InqDecorator<Void, Object>) asDecorator(element))
                                .decorateJoinPoint(accFn.apply(executor)));
    }

    /**
     * Builds the terminal executor lambda for a specific method invocation.
     *
     * <p>The terminal is the innermost step in the chain — it invokes the
     * actual method on the target via a pre-built, arity-specialized
     * {@link MethodInvoker}. The invoker is resolved <strong>once</strong>
     * here (the result is captured by the returned lambda), so the hot-path
     * call is a direct {@code invoker.invoke(target, args)} with no per-call
     * map lookup and no arity switch.</p>
     *
     * <p>Exceptions from the method invocation are processed through
     * {@link DispatchExtension#handleException} to unwrap reflection artifacts
     * and classify the exception type. After this conversion the terminal
     * itself only ever throws unchecked exceptions, which is what the chain
     * composition expects.</p>
     *
     * @param method the service method to invoke
     * @param args   the method arguments
     * @param target the object to invoke the method on
     * @return an {@link InternalExecutor} that invokes the method when executed
     */
    private InternalExecutor<Void, Object> buildTerminal(Method method,
                                                         Object[] args,
                                                         Object target) {
        // Resolve the pre-built, arity-specialized invoker once. The returned
        // lambda captures the invoker itself — not the raw method — so the
        // hot-path call is a direct invoker.invoke(target, args) with no
        // per-call map lookup and no arity switch.
        MethodInvoker invoker = handleCache.resolveInvoker(method);
        return (chainId, callId, arg) -> {
            try {
                return invoker.invoke(target, args);
            } catch (Throwable e) {
                // Unwrap reflection wrappers and classify the exception
                throw handleException(method, e);
            }
        };
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the descriptions of the pipeline elements in outermost-first
     * order, formatted as {@code ELEMENT_TYPE(name)}.
     *
     * <p>For a pipeline built as
     * {@code builder.shield(bulkhead).shield(circuitBreaker).build()} where
     * the bulkhead is named {@code "orderBh"} and the circuit breaker named
     * {@code "orderCb"}, this method returns
     * {@code [BULKHEAD(orderBh), CIRCUIT_BREAKER(orderCb)]} (with the standard
     * pipeline ordering, BH at order 400 sits outside CB at order 500).</p>
     *
     * <p>The returned list is unmodifiable and uses the element ordering
     * already produced by {@link InqPipeline#elements()} — outermost first
     * (lowest {@code orderFor} value). The first entry is the layer a call
     * enters first; the last entry is the layer immediately above the
     * service-method invocation.</p>
     *
     * <p>Cold-path diagnostic only — intended for startup logging, topology
     * inspection, and tests. The list is rebuilt on every call (the pipeline
     * is immutable, so the result is stable, but this method should not be
     * called on the dispatch hot path).</p>
     *
     * @return the layer descriptions in outermost-first order; never
     * {@code null}, possibly empty for a no-element pipeline
     */
    public List<String> layerDescriptions() {
        return pipeline.elements().stream()
                .map(element -> element.elementType() + "(" + element.name() + ")")
                .toList();
    }

    // ======================== DispatchExtension SPI ========================

    /**
     * Always returns {@code true} — this extension handles every method.
     *
     * <p>As a catch-all, it must be registered last in the extension array
     * so that more specific extensions get first match.</p>
     */
    @Override
    public boolean canHandle(Method method) {
        return true;
    }

    /**
     * Returns {@code true} — marks this extension as the catch-all fallback.
     */
    @Override
    public boolean isCatchAll() {
        return true;
    }

    /**
     * Dispatches the method call through the cached pipeline chain.
     *
     * <p>Determines the effective target for the terminal invocation:</p>
     * <ul>
     *   <li>If this extension was linked (has an {@code overrideTarget}), the
     *       terminal invokes the deep real target directly. The chain walk
     *       already covers intermediate layers.</li>
     *   <li>If this extension is unlinked ({@code overrideTarget} is null),
     *       the terminal invokes whatever target the caller (typically
     *       {@link ProxyWrapper}) provides — which is the delegate proxy for
     *       correct composition through the inner proxy's extension
     *       pipeline.</li>
     * </ul>
     *
     * @param chainId the chain identifier
     * @param callId  the per-call identifier
     * @param method  the service method
     * @param args    the method arguments
     * @param target  the default invocation target (from ProxyWrapper)
     * @return the method's return value
     */
    @Override
    public Object dispatch(long chainId, long callId,
                           Method method, Object[] args, Object target) {
        // Use the override target (deep real target) if linked, otherwise
        // fall back to the target provided by ProxyWrapper (the inner proxy).
        Object effectiveTarget = (overrideTarget != null) ? overrideTarget : target;
        return executeChain(chainId, callId, buildTerminal(method, args, effectiveTarget));
    }

    // ======================== Chain walk ========================

    /**
     * Links with a type-compatible inner extension for optimized chain walk.
     *
     * <p>Searches the inner proxy's extensions for another
     * {@code PipelineDispatchExtension} (exact-type match — see
     * {@link #findInner}). If found, returns a new linked instance that
     * chains directly into the inner extension and uses {@code realTarget}
     * as the terminal invocation target. The new instance inherits this
     * extension's handle cache to avoid re-resolving already-cached
     * handles.</p>
     *
     * <p>If no type-compatible inner extension is found, returns a fresh
     * standalone instance that preserves the existing handle cache but
     * performs no chain-walk optimization. This matches the behavior of
     * {@link SyncDispatchExtension#linkInner}.</p>
     *
     * @param innerExtensions the extensions registered on the inner proxy
     * @param realTarget      the deepest non-proxy target
     * @return a new extension instance, potentially linked into the inner
     * chain
     */
    @Override
    public DispatchExtension linkInner(DispatchExtension[] innerExtensions,
                                       Object realTarget) {
        PipelineDispatchExtension inner = findInner(innerExtensions);
        if (inner != null) {
            // Found a compatible inner extension — create a linked instance
            // that chains directly into it, bypassing proxy re-entry.
            return new PipelineDispatchExtension(
                    this.pipeline, inner, realTarget, this.handleCache);
        }
        // No type-compatible inner found — return a standalone instance that
        // preserves the existing handle cache to avoid re-resolving handles.
        return new PipelineDispatchExtension(
                this.pipeline, null, null, this.handleCache);
    }

    // ======================== Internal ========================

    /**
     * Executes the pipeline chain for a single invocation.
     *
     * <p>This method bridges the {@link InternalExecutor} contract used for
     * chain-walk linking with the {@link JoinPointExecutor} contract used
     * by the pipeline fold. The flow is:</p>
     * <ol>
     *   <li>{@link #nextStepFactory} wraps the supplied terminal with the
     *       inner extension's chain (linked) or returns it unchanged
     *       (unlinked).</li>
     *   <li>The wrapped terminal is adapted to a
     *       {@link JoinPointExecutor} so it can be folded by the pipeline
     *       chain factory.</li>
     *   <li>The pre-composed {@link #chainFactory} produces the fully
     *       decorated chain.</li>
     *   <li>{@code chain.proceed()} drives the invocation.</li>
     * </ol>
     *
     * <p>The terminal supplied to this method is built via
     * {@link #buildTerminal} (or, in the linked case, the next-step lambda
     * recursively delegating to an inner extension), which already
     * classifies any {@link Throwable} from the method invocation into a
     * {@link RuntimeException} via {@link DispatchExtension#handleException}.
     * In practice, therefore, {@code chain.proceed()} only surfaces unchecked
     * exceptions. The defensive {@code catch (Throwable)} below converts
     * any other throwable type (e.g. a checked exception sneaky-thrown by a
     * pipeline element's around-advice) into a {@link RuntimeException} so
     * this method conforms to the same no-checked-exception contract that
     * {@link SyncDispatchExtension#executeChain} provides.</p>
     *
     * @param chainId  the chain identifier
     * @param callId   the call identifier
     * @param terminal the terminal executor that invokes the actual method
     * @return the method's return value, potentially modified by the
     * pipeline elements
     */
    Object executeChain(long chainId, long callId,
                        InternalExecutor<Void, Object> terminal) {
        // Apply the next-step factory: for linked extensions, this wraps
        // the terminal with the inner extension's executeChain(); for
        // unlinked extensions, this returns the terminal unchanged (identity).
        InternalExecutor<Void, Object> wrappedTerminal = nextStepFactory.apply(terminal);

        // Adapt the InternalExecutor terminal to a JoinPointExecutor so the
        // pipeline fold can decorate it. wrappedTerminal.execute(...) only
        // throws unchecked exceptions; the broader proceed() throws Throwable
        // signature is satisfied trivially.
        JoinPointExecutor<Object> joinPoint =
                () -> wrappedTerminal.execute(chainId, callId, null);

        try {
            return chainFactory.apply(joinPoint).proceed();
        } catch (RuntimeException | Error e) {
            // Unchecked throwables propagate as-is — this is the dominant path.
            throw e;
        } catch (Throwable t) {
            // Defensive: a pipeline element could sneaky-throw a checked
            // exception. Wrap so executeChain conforms to the same
            // no-checked-exception contract as SyncDispatchExtension.
            throw new RuntimeException(t);
        }
    }
}
