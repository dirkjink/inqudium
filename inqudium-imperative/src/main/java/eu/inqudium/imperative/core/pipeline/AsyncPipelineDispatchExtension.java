package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.proxy.DispatchExtension;
import eu.inqudium.core.pipeline.proxy.MethodHandleCache;
import eu.inqudium.core.pipeline.proxy.MethodInvoker;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Dispatch extension for asynchronous method calls driven by an
 * {@link InqPipeline}.
 *
 * <p>This is the pipeline-based counterpart to {@link AsyncDispatchExtension}.
 * Where {@code AsyncDispatchExtension} wraps a single {@link AsyncLayerAction},
 * {@code AsyncPipelineDispatchExtension} folds an entire {@link InqPipeline}
 * into the async decorator chain at construction time and reuses the resulting
 * chain factory for every method invocation.</p>
 *
 * <p>This extension is <strong>not</strong> a catch-all — {@link #canHandle}
 * returns {@code true} only for methods whose return type is assignable to
 * {@link CompletionStage}, and {@link #isCatchAll()} returns {@code false}.
 * When composing extensions in a {@link eu.inqudium.core.pipeline.proxy.ProxyWrapper ProxyWrapper},
 * this extension must be registered <strong>before</strong> the catch-all
 * (e.g. {@link eu.inqudium.core.pipeline.proxy.PipelineDispatchExtension PipelineDispatchExtension})
 * so that async methods are routed through this extension and sync-only methods
 * fall through to the catch-all.</p>
 *
 * <h3>Dispatch flow</h3>
 * <p>At construction, every pipeline element is folded via
 * {@link InqAsyncDecorator#decorateAsyncJoinPoint} into a single async chain
 * factory. Each service-method invocation builds a method-specific terminal
 * (a {@link MethodInvoker} captured in an {@link InternalAsyncExecutor} lambda),
 * adapts it to a {@link JoinPointExecutor}, and runs it through the cached
 * factory. Synchronous failures during chain composition or terminal invocation
 * are lifted into a failed {@link CompletionStage} so the caller's error
 * channel is uniform.</p>
 *
 * <h3>Chain-walk optimization</h3>
 * <p>When {@link #linkInner} finds a type-compatible
 * {@code AsyncPipelineDispatchExtension} in the inner proxy, the two extensions
 * are wired into a direct chain walk. The outer extension's terminal calls the
 * inner extension's {@link #executeChain} directly. The inner extension's
 * terminal then invokes the deep {@code realTarget} — the actual business
 * object — bypassing all intermediate proxy layers.</p>
 *
 * <p>Mixed-extension stacks (e.g. an {@link AsyncDispatchExtension} inner
 * wrapped by an {@code AsyncPipelineDispatchExtension} outer) are
 * <strong>not</strong> optimized via chain-walk. {@link #linkInner} requires
 * an exact-type match — different chain-composition strategies cannot share
 * a chain walk safely. Such stacks dispatch through normal proxy re-entry,
 * preserving correctness at the cost of one extra invocation handler call.</p>
 *
 * @since 0.8.0
 */
public class AsyncPipelineDispatchExtension implements DispatchExtension {

    /**
     * The pipeline that drives this extension's dispatch chain.
     * Stored to allow the linked constructor to rebuild the chain factory
     * without altering the pipeline composition.
     */
    private final InqPipeline pipeline;

    /**
     * Pre-composed async chain factory built once from {@link #pipeline} via
     * {@link #buildAsyncChainFactory()}. Reused across all method invocations
     * on this extension instance — pipeline folding happens exactly once.
     */
    private final Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> chainFactory;

    /**
     * Factory function that wraps the per-call terminal with the inner
     * extension's chain (if linked). For unlinked extensions this is
     * {@link Function#identity()} — the terminal is used as-is. For linked
     * extensions, this replaces the terminal with a lambda that invokes the
     * inner extension's {@link #executeChain}, effectively chaining the two
     * pipelines together without proxy re-entry.
     */
    private final Function<InternalAsyncExecutor<Void, Object>,
            InternalAsyncExecutor<Void, Object>> nextStepFactory;

    /**
     * When non-null, overrides the target passed to {@link #dispatch} for the
     * terminal invocation. Set only when this extension was successfully
     * linked with an inner {@code AsyncPipelineDispatchExtension} — the chain
     * walk handles intermediate layers, so the terminal can jump straight to
     * the deep real target without going through intermediate proxies.
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
     * Creates a new standalone (root) async pipeline-driven dispatch extension.
     *
     * <p>This is the constructor used by application code and by factory
     * methods such as {@code InqAsyncProxyFactory.of(pipeline)}. The extension
     * starts unlinked — chain-walk linking happens later during
     * {@link eu.inqudium.core.pipeline.proxy.ProxyWrapper ProxyWrapper}
     * construction if an inner proxy is detected.</p>
     *
     * <p>The chain factory is built eagerly here. Misconfigured pipelines
     * (e.g. an element that does not implement {@link InqAsyncDecorator}) will
     * fail at the first dispatch rather than at construction, since the
     * fold inside {@link #buildAsyncChainFactory()} is lazy — it produces a
     * function whose body invokes {@code asAsyncDecorator(...)} per call.</p>
     *
     * @param pipeline the composed pipeline (must not be {@code null})
     * @throws NullPointerException if {@code pipeline} is {@code null}
     */
    public AsyncPipelineDispatchExtension(InqPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "Pipeline must not be null");
        this.chainFactory = buildAsyncChainFactory();
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
    private AsyncPipelineDispatchExtension(InqPipeline pipeline,
                                           AsyncPipelineDispatchExtension inner,
                                           Object realTarget,
                                           MethodHandleCache handleCache) {
        this.pipeline = pipeline;
        this.chainFactory = buildAsyncChainFactory();

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
     * Casts an {@link InqElement} to {@link InqAsyncDecorator}, providing a
     * descriptive error if the element does not implement the async decorator.
     */
    private static InqAsyncDecorator<?, ?> asAsyncDecorator(InqElement element) {
        if (element instanceof InqAsyncDecorator<?, ?> decorator) {
            return decorator;
        }
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.name()
                        + "', type=" + element.elementType()
                        + ") does not implement InqAsyncDecorator. "
                        + "AsyncPipelineDispatchExtension requires all pipeline elements to "
                        + "implement InqAsyncDecorator<A, R> for async method dispatch "
                        + "(methods returning CompletionStage).");
    }

    /**
     * Searches an extension array for the first
     * {@code AsyncPipelineDispatchExtension}.
     *
     * <p>The match is <strong>exact-type</strong> — other async extensions
     * (notably {@link AsyncDispatchExtension}) are NOT considered compatible
     * counterparts. Different classes have different chain-composition
     * strategies and cannot share a chain walk.</p>
     *
     * @param extensions the inner proxy's extension array
     * @return the first {@code AsyncPipelineDispatchExtension} found, or
     * {@code null}
     */
    private static AsyncPipelineDispatchExtension findInner(DispatchExtension[] extensions) {
        for (int i = 0; i < extensions.length; i++) {
            if (extensions[i] instanceof AsyncPipelineDispatchExtension a) {
                return a;
            }
        }
        return null;
    }

    /**
     * Folds the pipeline into an async decorator chain factory.
     *
     * <p>The returned function takes a terminal {@link JoinPointExecutor}
     * (returning {@link CompletionStage}) and produces the fully-decorated
     * chain in a single {@link Function#apply} call. Each fold step wraps
     * the accumulated chain with the next outer element's
     * {@link InqAsyncDecorator#decorateAsyncJoinPoint}.</p>
     *
     * @return the async chain factory; reused for every dispatch on this
     * extension
     */
    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> buildAsyncChainFactory() {
        return pipeline.chain(
                Function.<JoinPointExecutor<CompletionStage<Object>>>identity(),
                (accFn, element) -> executor ->
                        ((InqAsyncDecorator<Void, Object>) asAsyncDecorator(element))
                                .decorateAsyncJoinPoint(accFn.apply(executor)));
    }

    /**
     * Builds the terminal async executor lambda for a specific method
     * invocation.
     *
     * <p>The terminal is the innermost step in the chain — it invokes the
     * actual method on the target via a pre-built, arity-specialized
     * {@link MethodInvoker}. The invoker is resolved <strong>once</strong>
     * here (the result is captured by the returned lambda), so the hot-path
     * call is a direct {@code invoker.invoke(target, args)} with no per-call
     * map lookup and no arity switch.</p>
     *
     * <p>Validates that the returned value is a non-null
     * {@link CompletionStage} (defensive, in case an implementation violates
     * the contract guaranteed by {@link #canHandle}).</p>
     *
     * @param method the service method to invoke
     * @param args   the method arguments
     * @param target the object to invoke the method on
     * @return an {@link InternalAsyncExecutor} that invokes the method when
     * executed
     */
    @SuppressWarnings("unchecked")
    private InternalAsyncExecutor<Void, Object> buildTerminal(Method method,
                                                              Object[] args,
                                                              Object target) {
        // Resolve the pre-built, arity-specialized invoker once. The returned
        // lambda captures the invoker itself — not the raw method — so the
        // hot-path call is a direct invoker.invoke(target, args) with no
        // per-call map lookup and no arity switch.
        MethodInvoker invoker = handleCache.resolveInvoker(method);
        return (chainId, callId, arg) -> {
            try {
                Object result = invoker.invoke(target, args);
                if (result == null) {
                    throw new IllegalStateException(
                            "Method " + method.getName() + " returned null, expected a CompletionStage. "
                                    + "Async-dispatched methods must never return null.");
                }
                if (!(result instanceof CompletionStage)) {
                    throw new IllegalStateException(
                            "Method " + method.getName() + " returned "
                                    + result.getClass().getName() + ", expected a CompletionStage. "
                                    + "This method should not be routed through AsyncPipelineDispatchExtension.");
                }
                return (CompletionStage<Object>) result;
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
     * Returns {@code true} only for methods whose return type is assignable
     * to {@link CompletionStage}.
     *
     * <p>Async dispatch only handles methods returning {@code CompletionStage}.
     * Sync-only methods must fall through to the catch-all extension
     * registered after this one (e.g. {@code PipelineDispatchExtension}).</p>
     */
    @Override
    public boolean canHandle(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Returns {@code false} — this extension is <strong>not</strong> a
     * catch-all.
     *
     * <p>Explicitly declared (the default in {@link DispatchExtension} is
     * also {@code false}) so a reader sees the intent without consulting the
     * SPI default. If this ever flipped to {@code true}, sync-only methods
     * would be routed through the async chain and fail the runtime
     * {@code CompletionStage}-instanceof check in {@link #buildTerminal}.</p>
     */
    @Override
    public boolean isCatchAll() {
        return false;
    }

    /**
     * Dispatches the async method call through the cached pipeline chain.
     *
     * <p>Determines the effective target for the terminal invocation:</p>
     * <ul>
     *   <li>If this extension was linked (has an {@code overrideTarget}), the
     *       terminal invokes the deep real target directly. The chain walk
     *       already covers intermediate layers.</li>
     *   <li>If this extension is unlinked ({@code overrideTarget} is null),
     *       the terminal invokes whatever target the caller (typically
     *       {@link eu.inqudium.core.pipeline.proxy.ProxyWrapper ProxyWrapper})
     *       provides — which is the delegate proxy for correct composition
     *       through the inner proxy's extension pipeline.</li>
     * </ul>
     *
     * @param chainId the chain identifier
     * @param callId  the per-call identifier
     * @param method  the service method
     * @param args    the method arguments
     * @param target  the default invocation target (from ProxyWrapper)
     * @return the {@link CompletionStage} produced by the chain
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
     * {@code AsyncPipelineDispatchExtension} (exact-type match — see
     * {@link #findInner}). If found, returns a new linked instance that
     * chains directly into the inner extension and uses {@code realTarget}
     * as the terminal invocation target. The new instance inherits this
     * extension's handle cache to avoid re-resolving already-cached
     * handles.</p>
     *
     * <p>If no type-compatible inner extension is found, returns a fresh
     * standalone instance that preserves the existing handle cache but
     * performs no chain-walk optimization. This matches the behavior of
     * {@link AsyncDispatchExtension#linkInner}.</p>
     *
     * @param innerExtensions the extensions registered on the inner proxy
     * @param realTarget      the deepest non-proxy target
     * @return a new extension instance, potentially linked into the inner
     * chain
     */
    @Override
    public DispatchExtension linkInner(DispatchExtension[] innerExtensions,
                                       Object realTarget) {
        AsyncPipelineDispatchExtension inner = findInner(innerExtensions);
        if (inner != null) {
            // Found a compatible inner extension — create a linked instance
            // that chains directly into it, bypassing proxy re-entry.
            return new AsyncPipelineDispatchExtension(
                    this.pipeline, inner, realTarget, this.handleCache);
        }
        // No type-compatible inner found — return a standalone instance that
        // preserves the existing handle cache to avoid re-resolving handles.
        return new AsyncPipelineDispatchExtension(
                this.pipeline, null, null, this.handleCache);
    }

    @Override
    public DispatchExtension linkInner(DispatchExtension[] innerExtensions) {
        return linkInner(innerExtensions, null);
    }

    // ======================== Internal ========================

    /**
     * Executes the async pipeline chain for a single invocation.
     *
     * <p>This method bridges the {@link InternalAsyncExecutor} contract used
     * for chain-walk linking with the {@link JoinPointExecutor} contract used
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
     * <p>Synchronous failures during chain composition or terminal invocation
     * are lifted into a failed {@link CompletionStage} via
     * {@link CompletableFuture#failedFuture(Throwable)}. This preserves the
     * uniform-error-channel semantics for the async dispatch path: the caller
     * always observes failures via the returned stage, never as a synchronous
     * throw.</p>
     *
     * @param chainId  the chain identifier
     * @param callId   the call identifier
     * @param terminal the terminal executor that invokes the actual method
     * @return the {@link CompletionStage} produced by the fully-composed
     * chain, or a failed stage if anything threw synchronously
     */
    CompletionStage<Object> executeChain(long chainId, long callId,
                                         InternalAsyncExecutor<Void, Object> terminal) {
        // Apply the next-step factory: for linked extensions, this wraps the
        // terminal with the inner extension's executeChain(); for unlinked
        // extensions, this returns the terminal unchanged (identity).
        InternalAsyncExecutor<Void, Object> wrappedTerminal = nextStepFactory.apply(terminal);

        // Adapt the InternalAsyncExecutor terminal to a JoinPointExecutor so
        // the pipeline fold can decorate it.
        JoinPointExecutor<CompletionStage<Object>> joinPoint =
                () -> wrappedTerminal.executeAsync(chainId, callId, null);

        try {
            return chainFactory.apply(joinPoint).proceed();
        } catch (Throwable t) {
            // Uniform error channel: lift synchronous failures into a failed
            // stage so callers always observe failures via the returned stage,
            // never as a synchronous throw.
            return CompletableFuture.failedFuture(t);
        }
    }
}
