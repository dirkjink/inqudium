package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.Throws;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * A pre-composed, immutable async pipeline that can be executed repeatedly with
 * different {@link JoinPointExecutor} instances — without rebuilding the chain
 * structure on every call.
 *
 * <p>The async counterpart to {@link ResolvedPipeline}. Applies the same
 * optimization: {@link AsyncLayerAction} references are extracted from
 * providers once at resolution time and stored in a pre-built array. At
 * invocation time, the chain is composed inline via a simple reverse loop.</p>
 *
 * <h3>Two-phase execution</h3>
 * <p>Each async layer has two phases:</p>
 * <ul>
 *   <li><strong>Start phase</strong> (synchronous): code before
 *       {@code next.executeAsync()} runs on the calling thread.</li>
 *   <li><strong>End phase</strong> (asynchronous): code attached via
 *       {@code whenComplete()}, {@code thenApply()}, etc. runs when the
 *       async operation completes.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are safe for concurrent use. The pre-built action array is
 * immutable; the chain composition is purely stack-local; the call-ID
 * counter is an {@link java.util.concurrent.atomic.AtomicLong}. No
 * {@code ThreadLocal} or shared mutable state is used — the pipeline is
 * safe for virtual threads, reactive pipelines, and coroutines.</p>
 *
 * @since 0.7.0
 */
public final class AsyncResolvedPipeline {

    /**
     * Sentinel empty action array.
     */
    private static final AsyncLayerAction<Void, Object>[] EMPTY_ACTIONS = newActionArray(0);

    /**
     * Sentinel instance for methods with no applicable async layers.
     */
    private static final AsyncResolvedPipeline EMPTY = new AsyncResolvedPipeline(
            EMPTY_ACTIONS, PipelineDiagnostics.EMPTY);

    /**
     * Pre-built, immutable array of async layer actions in execution order
     * (outermost first). Extracted from providers once during
     * {@link #fromProviders}.
     */
    private final AsyncLayerAction<Void, Object>[] actions;

    private final PipelineDiagnostics diagnostics;

    // ======================== Construction ========================

    private AsyncResolvedPipeline(AsyncLayerAction<Void, Object>[] actions,
                                  PipelineDiagnostics diagnostics) {
        this.actions = actions;
        this.diagnostics = diagnostics;
    }

    /**
     * Resolves and pre-composes an async pipeline from the given providers,
     * filtered by the target method.
     *
     * <p>In addition to each provider's {@code canHandle(method)} check, this
     * method enforces that the target method returns a {@link CompletionStage}.
     * This prevents misuse when a subclass overrides {@code canHandle()} without
     * retaining the return-type guard.</p>
     *
     * @param providers all registered async providers (will be filtered and sorted)
     * @param method    the target method for {@code canHandle} filtering
     * @return a pre-composed, reusable async pipeline
     * @throws IllegalArgumentException if providers or method is null
     * @throws IllegalStateException    if the method does not return a CompletionStage
     */
    public static AsyncResolvedPipeline resolve(
            List<? extends AsyncAspectLayerProvider<Object>> providers,
            Method method) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("Method must not be null");
        }

        // Guard: async pipelines are only meaningful for CompletionStage-returning methods
        if (!CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException(
                    "AsyncResolvedPipeline.resolve() requires a method returning "
                            + "CompletionStage, but " + method.getDeclaringClass().getSimpleName()
                            + "#" + method.getName() + " returns "
                            + method.getReturnType().getSimpleName()
                            + ". Use ResolvedPipeline for synchronous methods.");
        }

        List<? extends AsyncAspectLayerProvider<Object>> applicable = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AsyncAspectLayerProvider::order))
                .toList();

        return fromProviders(applicable);
    }

    /**
     * Builds an {@code AsyncResolvedPipeline} from an already filtered and
     * sorted provider list.
     *
     * <p>Extracts async layer actions and names in a single pass.</p>
     */
    private static AsyncResolvedPipeline fromProviders(
            List<? extends AsyncAspectLayerProvider<Object>> providers) {
        if (providers.isEmpty()) {
            return EMPTY;
        }

        int size = providers.size();
        AsyncLayerAction<Void, Object>[] acts = newActionArray(size);
        String[] names = new String[size];

        for (int i = 0; i < size; i++) {
            AsyncAspectLayerProvider<Object> p = providers.get(i);
            acts[i] = p.asyncLayerAction();
            names[i] = p.layerName();
        }

        return new AsyncResolvedPipeline(acts,
                PipelineDiagnostics.create(List.of(names)));
    }

    @SuppressWarnings("unchecked")
    private static AsyncLayerAction<Void, Object>[] newActionArray(int size) {
        return new AsyncLayerAction[size];
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed async pipeline with the given join point executor.
     *
     * <p>This method <strong>never throws</strong>. All exceptions — whether from the
     * synchronous start phase or the asynchronous completion phase — are delivered
     * through the returned {@link CompletionStage}.</p>
     *
     * <p>Per-call cost: one reverse loop creating N+1 lambdas (terminal + one
     * per layer), then chain traversal. No {@code ThreadLocal}, no shared
     * mutable state.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed}),
     *                     whose return value must be a {@link CompletionStage}
     * @return a {@link CompletionStage} carrying the result or the failure —
     * never {@code null}, never throws
     */
    public CompletionStage<Object> execute(JoinPointExecutor<Object> coreExecutor) {
        long callId = diagnostics.nextCallId();
        long cid = diagnostics.chainId();

        // Terminal — wraps the actual method invocation, validates return type
        InternalAsyncExecutor<Void, Object> current = (c, ca, a) -> {
            Object result;
            try {
                result = coreExecutor.proceed();
            } catch (Throwable t) {
                throw Throws.wrapChecked(t);
            }

            if (result == null) {
                return CompletableFuture.completedFuture(null);
            }

            if (result instanceof CompletionStage<?> stage) {
                @SuppressWarnings("unchecked")
                CompletionStage<Object> typed = (CompletionStage<Object>) stage;
                return typed;
            }

            throw new IllegalStateException(
                    "AsyncResolvedPipeline expected the proxied method to return a "
                            + "CompletionStage, but received: "
                            + result.getClass().getName()
                            + ". Ensure this aspect is only applied to methods returning "
                            + "CompletionStage or CompletableFuture. Use canHandle(Method) "
                            + "to restrict the async pipeline to compatible methods.");
        };

        // Compose chain inside-out from pre-built action array
        for (int i = actions.length - 1; i >= 0; i--) {
            AsyncLayerAction<Void, Object> action = actions[i];
            InternalAsyncExecutor<Void, Object> next = current;
            current = (c, ca, a) -> action.executeAsync(c, ca, a, next);
        }

        try {
            return current.executeAsync(cid, callId, null);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            return CompletableFuture.failedFuture(cause != null ? cause : e);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the chain ID assigned to this resolved pipeline.
     */
    public long chainId() {
        return diagnostics.chainId();
    }

    /**
     * Returns the most recently generated call ID across all threads.
     *
     * <p><strong>Informational only.</strong> In concurrent environments this
     * value does not correspond to any specific thread's call — see
     * {@link PipelineDiagnostics#currentCallId()} for details.</p>
     */
    public long currentCallId() {
        return diagnostics.currentCallId();
    }

    /**
     * Returns the layer names in order (outermost first).
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
     * Returns a diagnostic string similar to
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()}.
     */
    public String toStringHierarchy() {
        return diagnostics.toStringHierarchy();
    }
}
