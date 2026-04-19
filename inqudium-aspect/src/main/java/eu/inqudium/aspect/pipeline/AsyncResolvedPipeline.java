package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * A pre-composed, immutable async pipeline that can be executed repeatedly
 * with different {@link JoinPointExecutor} instances — without rebuilding
 * the chain structure on every call.
 *
 * <p>Async counterpart to {@link ResolvedPipeline}, using the <strong>same
 * array-based composition pattern</strong>. The prior implementation stored
 * the chain as a nested {@code Function<Executor, Executor>} cascade: each
 * invocation of {@code chainFactory.apply(terminal)} unwound that cascade
 * recursively, producing {@code N} virtual {@code Function.apply} dispatches
 * on top of the {@code N} executor-lambda allocations. This class extracts
 * the {@link AsyncLayerAction} references <strong>once</strong> at
 * resolution time into a flat array; the per-call cost is a single reverse
 * loop over that array.</p>
 *
 * <h3>Hot-path cost</h3>
 * <p>Per invocation:</p>
 * <ol>
 *   <li>One {@link InternalAsyncExecutor} for the terminal (captures the
 *       caller-supplied {@link JoinPointExecutor}).</li>
 *   <li>{@code N} {@link InternalAsyncExecutor} lambdas for the layer wrappers
 *       — captured in a tight loop, each closing over only two references
 *       ({@code action} + {@code next}), short-lived, and excellent
 *       candidates for JIT escape analysis.</li>
 * </ol>
 * <p>No {@code Function.apply()} dispatch, no recursive cascade unwinding.</p>
 *
 * <h3>Two-phase execution semantics</h3>
 * <p>Each async layer has two phases:</p>
 * <ul>
 *   <li><strong>Start phase</strong> (synchronous): runs on the calling
 *       thread before {@code next.executeAsync()} returns.</li>
 *   <li><strong>End phase</strong> (asynchronous): attached via
 *       {@code whenComplete()}, {@code thenApply()}, etc. — runs when the
 *       downstream stage completes.</li>
 * </ul>
 *
 * <h3>Error channel</h3>
 * <p>{@link #execute(JoinPointExecutor)} <strong>never throws</strong>. All
 * failures — whether from a layer's synchronous start phase or from the
 * asynchronous completion — are delivered through the returned
 * {@link CompletionStage}. This gives callers a uniform error channel for
 * {@code .exceptionally()}, {@code .handle()}, and {@code .whenComplete()}.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are safe for concurrent use. The action array is immutable;
 * chain composition is purely stack-local; the call-ID counter lives in
 * {@link PipelineDiagnostics} and is backed by an {@link java.util.concurrent.atomic.AtomicLong}.
 * No {@code ThreadLocal} or shared mutable state is used — safe for virtual
 * threads and reactive pipelines.</p>
 *
 * @since 0.7.0
 */
public final class AsyncResolvedPipeline {

    /**
     * Sentinel empty action array — avoids null checks on the execution path.
     */
    private static final AsyncLayerAction<Void, Object>[] EMPTY_ACTIONS = newActionArray(0);

    /**
     * Sentinel instance for methods with no applicable async layers.
     *
     * <p>Shares the global {@link PipelineDiagnostics#EMPTY} sentinel so that
     * all empty async pipelines across the JVM do not each reserve a chain ID
     * or share a single counter.</p>
     */
    private static final AsyncResolvedPipeline EMPTY = new AsyncResolvedPipeline(
            EMPTY_ACTIONS, PipelineDiagnostics.EMPTY);

    /**
     * Pre-built, immutable array of async layer actions in execution order
     * (outermost first). Extracted from providers once during {@link #fromProviders}
     * — no per-call provider access, no per-call filtering, no per-call sort.
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
     * <p>Filter, sort, and action extraction happen exactly once. The returned
     * instance can then be invoked repeatedly with different
     * {@link JoinPointExecutor}s at negligible additional cost.</p>
     *
     * @param providers all registered async providers (will be filtered and sorted)
     * @param method    the target method for {@code canHandle} filtering
     * @return a pre-composed, reusable async pipeline
     * @throws IllegalArgumentException if providers or method is null
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

        // Filter and sort in a single stream pass
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
     * <p>Extracts layer actions and names in a single pass. Actions go into
     * a permanent array on this instance; names go to {@link PipelineDiagnostics}.</p>
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

        return new AsyncResolvedPipeline(acts, PipelineDiagnostics.create(List.of(names)));
    }

    @SuppressWarnings("unchecked")
    private static AsyncLayerAction<Void, Object>[] newActionArray(int size) {
        return new AsyncLayerAction[size];
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed async pipeline with the given join point
     * executor.
     *
     * <p>Never throws — all failures are delivered through the returned
     * {@link CompletionStage}. See class-level Javadoc for the full error
     * contract.</p>
     *
     * @param coreExecutor the join point execution (typically
     *                     {@code () -> (CompletionStage<Object>) pjp.proceed()})
     * @return a {@link CompletionStage} carrying the result or the failure —
     *         never {@code null}, never throws
     */
    public CompletionStage<Object> execute(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor) {
        long callId = diagnostics.nextCallId();
        long cid = diagnostics.chainId();

        // Terminal: invokes the typed executor and bridges into the async chain.
        // CompletionStage return type is enforced by the method signature —
        // no runtime instanceof check needed.
        InternalAsyncExecutor<Void, Object> current = (c, ca, a) -> {
            try {
                return coreExecutor.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                // Transport checked exceptions via CompletionException; unwrapped below
                throw new CompletionException(t);
            }
        };

        // Compose chain inside-out from the pre-built action array.
        // Each lambda captures only 2 references: the action and next.
        // This is a tight loop — no Function.apply dispatch, no cascade unwinding.
        for (int i = actions.length - 1; i >= 0; i--) {
            AsyncLayerAction<Void, Object> action = actions[i];
            InternalAsyncExecutor<Void, Object> next = current;
            current = (c, ca, a) -> action.executeAsync(c, ca, a, next);
        }

        try {
            return current.executeAsync(cid, callId, null);
        } catch (CompletionException e) {
            // Unwrap transported checked exceptions from the terminal
            Throwable cause = e.getCause();
            return CompletableFuture.failedFuture(cause != null ? cause : e);
        } catch (Throwable e) {
            // Any synchronous exception from a layer's start phase becomes
            // a failed future — uniform error channel for callers.
            return CompletableFuture.failedFuture(e);
        }
    }

    // ======================== Diagnostics ========================

    /** Returns the chain ID assigned to this resolved pipeline. */
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

    /** Returns the layer names in order (outermost first). */
    public List<String> layerNames() {
        return diagnostics.layerNames();
    }

    /** Returns the number of layers in this pipeline. */
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
