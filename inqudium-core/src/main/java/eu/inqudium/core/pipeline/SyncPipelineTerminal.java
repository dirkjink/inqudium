package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Synchronous terminal for an {@link InqPipeline}.
 *
 * <p>Takes a paradigm-agnostic pipeline and provides sync execution methods.
 * Two execution paths with different allocation profiles coexist:</p>
 *
 * <h3>Hot path: {@link #execute(JoinPointExecutor)}</h3>
 * <p>Intended for callers where the {@link JoinPointExecutor} changes on every
 * invocation — notably AspectJ's {@code pjp::proceed}. The pipeline's layer
 * actions are extracted <strong>once at construction</strong> into a flat
 * array; per invocation the chain is composed via a single loop producing
 * {@code N+1} escape-analysable {@link InternalExecutor} lambdas. No wrapper
 * objects, no {@code instanceof} checks, no {@code Function.apply} cascade.</p>
 *
 * <h3>Reusable chain: {@link #decorateJoinPoint(JoinPointExecutor)}</h3>
 * <p>Intended for callers that build a decorated chain once and invoke it
 * repeatedly. Returns a fully-constructed {@link JoinPointWrapper} chain
 * with introspection support ({@link Wrapper#inner()},
 * {@link Wrapper#chainId()}, {@link Wrapper#toStringHierarchy()}). Caching
 * the returned executor is recommended — rebuilding it per invocation
 * allocates {@code N} wrapper objects. Callers that need to handle a new
 * executor on every call should use {@link #execute(JoinPointExecutor)}
 * instead.</p>
 *
 * <h3>Storage convention</h3>
 * <p>Layer actions are stored in <strong>outermost-first</strong> order —
 * consistent with {@code ResolvedPipeline} and {@code AsyncResolvedPipeline}.
 * The {@link ResolvedPipelineState#layerNames()} returned by {@link #layerNames()}
 * follows the same convention.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(circuitBreaker)
 *         .shield(retry)
 *         .build();
 *
 * SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);
 *
 * // Hot path — no allocation overhead beyond the stack-local lambdas
 * String result = terminal.execute(() -> service.call());
 *
 * // Build-once, reuse — introspection preserved
 * JoinPointExecutor<String> chain = terminal.decorateJoinPoint(() -> service.call());
 * chain.proceed();
 *
 * // Supplier wrapper — builds chain once, reuses on every get()
 * Supplier<String> decorated = terminal.decorateSupplier(() -> service.call());
 * decorated.get();
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The action array
 * is populated once at construction; the call-ID counter in
 * {@link ResolvedPipelineState} is thread-safe. No {@code ThreadLocal} or
 * shared mutable state — safe for virtual threads.</p>
 *
 * @since 0.8.0
 */
public final class SyncPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Pre-extracted layer actions in outermost-first order.
     * {@code InqDecorator} extends {@link LayerAction}, so the decorator
     * references from the pipeline serve directly as layer actions — no
     * per-call extraction or casting needed on the hot path.
     */
    private final LayerAction<Void, Object>[] actions;

    /**
     * Shared diagnostics state — chain ID, per-invocation call-ID counter,
     * layer names, and formatted hierarchy rendering.
     */
    private final ResolvedPipelineState pipelineState;

    @SuppressWarnings("unchecked")
    private SyncPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;

        // Extract the pipeline's elements exactly once using chain() as an
        // element-collecting fold. Native iteration order is innermost-first;
        // we reverse to the outermost-first convention shared with
        // ResolvedPipeline/AsyncResolvedPipeline.
        List<InqElement> elements = pipeline.chain(
                new ArrayList<InqElement>(),
                (list, element) -> {
                    list.add(element);
                    return list;
                });
        Collections.reverse(elements);

        // Pre-cast every element to LayerAction once. InqDecorator extends
        // LayerAction, so the single instanceof check in asDecorator() also
        // validates the LayerAction contract. Misconfigured pipelines fail
        // here rather than on the first hot-path call.
        int size = elements.size();
        LayerAction<Void, Object>[] acts = new LayerAction[size];
        List<String> names = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            InqElement element = elements.get(i);
            acts[i] = (LayerAction<Void, Object>) asDecorator(element);
            names.add(element.getElementType().name() + "(" + element.getName() + ")");
        }
        this.actions = acts;
        this.pipelineState = ResolvedPipelineState.create(Collections.unmodifiableList(names));
    }

    /**
     * Creates a sync terminal for the given pipeline.
     *
     * <p>Eagerly validates that every pipeline element implements
     * {@link InqDecorator} — a {@link ClassCastException} is thrown here
     * rather than at the first call site. Misconfigured pipelines fail fast.</p>
     *
     * @param pipeline the composed pipeline
     * @return the sync terminal
     * @throws NullPointerException if pipeline is null
     * @throws ClassCastException   if any element does not implement {@link InqDecorator}
     */
    public static SyncPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new SyncPipelineTerminal(pipeline);
    }

    /**
     * Casts an {@link InqElement} to {@link InqDecorator}, providing a
     * descriptive error if the element does not implement the sync decorator.
     */
    private static InqDecorator<?, ?> asDecorator(InqElement element) {
        if (element instanceof InqDecorator<?, ?> decorator) {
            return decorator;
        }
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqDecorator. "
                        + "SyncPipelineTerminal requires all pipeline elements to "
                        + "implement InqDecorator<A, R>. For async elements, use "
                        + "AsyncPipelineTerminal instead.");
    }

    // ======================== Accessors ========================

    /**
     * Returns the underlying pipeline.
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    // ======================== Hot path: execute ========================

    /**
     * Executes the given executor through the cached layer-action chain.
     *
     * <p>Per-call cost: one {@link java.util.concurrent.atomic.AtomicLong}
     * CAS for the call ID, one {@link InternalExecutor} terminal lambda
     * binding the executor, {@code N} wrapper lambdas composed in a tight
     * loop, and a single {@link CompletionException}-unwrapping try/catch at
     * the boundary. The wrapper lambdas capture at most two references each
     * ({@code action} + {@code next}) and do not escape this method's stack
     * frame — strong candidates for JIT escape analysis.</p>
     *
     * <p>Checked exceptions from the executor are transported through the
     * chain via {@link Throws#wrapChecked(Throwable)} and unwrapped at the
     * boundary via {@link Throws#unwrapAndRethrow(CompletionException)},
     * preserving the executor's original exception contract.</p>
     *
     * @param executor the core execution (e.g. {@code () -> service.call()},
     *                 {@code pjp::proceed}, or {@code () -> method.invoke(target, args)})
     * @param <R>      the return type
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the core or from pipeline elements
     */
    @SuppressWarnings("unchecked")
    public <R> R execute(JoinPointExecutor<R> executor) throws Throwable {
        long callId = pipelineState.nextCallId();
        long cid = pipelineState.chainId();

        // Terminal: invokes the executor and wraps checked exceptions for
        // transport through the chain's unchecked-only layer actions.
        InternalExecutor<Void, R> current = (c, ca, a) -> {
            try {
                return executor.proceed();
            } catch (Throwable t) {
                throw Throws.wrapChecked(t);
            }
        };

        // Compose chain from the pre-cached action array. Actions are stored
        // in outermost-first order; reverse iteration wraps each layer around
        // the prior result. After the loop `current` is the outermost executor.
        LayerAction<Void, Object>[] acts = actions;
        for (int i = acts.length - 1; i >= 0; i--) {
            LayerAction<Void, R> action = (LayerAction<Void, R>) (LayerAction<?, ?>) acts[i];
            InternalExecutor<Void, R> next = current;
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

    // ======================== Build-once path: decorate ========================

    /**
     * Builds a decorator chain around the given {@link JoinPointExecutor} as
     * a reusable {@link JoinPointWrapper} stack.
     *
     * <p>Each element in the pipeline is folded via
     * {@link InqDecorator#decorateJoinPoint}, producing a nested chain
     * where the outermost element intercepts first:</p>
     * <pre>
     *   outermost.decorateJoinPoint(
     *       middle.decorateJoinPoint(
     *           innermost.decorateJoinPoint(executor)))
     * </pre>
     *
     * <p>The returned executor is backed by a full {@link JoinPointWrapper}
     * chain and supports {@link Wrapper} introspection. This path allocates
     * {@code N} wrapper objects — cache the result if you will invoke it
     * repeatedly. For one-shot invocations with a per-call executor, use
     * {@link #execute(JoinPointExecutor)} instead.</p>
     *
     * @param executor the core execution at the bottom of the chain
     * @param <R>      the return type
     * @return the decorated executor
     */
    @SuppressWarnings("unchecked")
    public <R> JoinPointExecutor<R> decorateJoinPoint(JoinPointExecutor<R> executor) {
        return pipeline.chain(executor, (downstream, element) ->
                asDecorator(element).decorateJoinPoint(downstream));
    }

    /**
     * Builds a decorator chain and wraps it as a {@link Supplier}.
     *
     * <p>The chain is constructed once via
     * {@link #decorateJoinPoint(JoinPointExecutor)} and reused on every
     * {@link Supplier#get()} call — no per-call allocation beyond the
     * invocation overhead itself. Checked exceptions from the pipeline are
     * wrapped in {@link RuntimeException}; unchecked exceptions propagate
     * unchanged.</p>
     *
     * @param supplier the core supplier
     * @param <R>      the return type
     * @return a decorated supplier
     */
    public <R> Supplier<R> decorateSupplier(Supplier<R> supplier) {
        JoinPointExecutor<R> chain = decorateJoinPoint(supplier::get);
        return () -> {
            try {
                return chain.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        };
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the chain ID assigned to this terminal instance.
     *
     * <p>Unique across the JVM, allocated once at construction time.
     * Used as the chain ID for every {@link #execute(JoinPointExecutor)}
     * invocation.</p>
     */
    public long chainId() {
        return pipelineState.chainId();
    }

    /**
     * Returns the layer names in outermost-first order. Each name follows
     * the pattern {@code "ELEMENT_TYPE(name)"}, e.g. {@code "CIRCUIT_BREAKER(cb)"}.
     */
    public List<String> layerNames() {
        return pipelineState.layerNames();
    }

    /**
     * Returns the number of layers in this pipeline.
     */
    public int depth() {
        return pipelineState.depth();
    }

    /**
     * Returns a diagnostic string rendering the layer hierarchy.
     */
    public String toStringHierarchy() {
        return pipelineState.toStringHierarchy();
    }
}
