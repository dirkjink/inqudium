package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.core.pipeline.proxy.InqProxyFactory;
import eu.inqudium.core.pipeline.proxy.PipelineDispatchExtension;
import eu.inqudium.core.pipeline.proxy.ProxyWrapper;
import eu.inqudium.core.pipeline.proxy.SyncDispatchExtension;

import java.util.concurrent.CompletionStage;

/**
 * Factory for creating dynamic proxies that route method invocations through
 * both a sync {@link LayerAction} and an async {@link AsyncLayerAction}.
 *
 * <p>Methods returning {@link CompletionStage} are dispatched through the
 * {@link AsyncLayerAction#executeAsync}, all other methods go through the
 * sync {@link LayerAction#execute}.</p>
 *
 * @since 0.4.0
 */
public interface InqAsyncProxyFactory extends InqProxyFactory {

    @SuppressWarnings("unchecked")
    static InqAsyncProxyFactory of(String name,
                                   LayerAction<?, ?> syncAction,
                                   AsyncLayerAction<?, ?> asyncAction) {
        if (syncAction == null) {
            throw new IllegalArgumentException("syncAction must not be null.");
        }
        if (asyncAction == null) {
            throw new IllegalArgumentException("asyncAction must not be null.");
        }
        // Safe at runtime: AsyncDispatchExtension always passes null as the first
        // generic parameter and forwards the raw Object result. The unchecked casts
        // are unavoidable due to type erasure but guarded by the dispatch contract.
        AsyncDispatchExtension asyncExt =
                new AsyncDispatchExtension((AsyncLayerAction<Void, Object>) asyncAction);
        SyncDispatchExtension syncExt =
                new SyncDispatchExtension((LayerAction<Void, Object>) syncAction);
        return new InqAsyncProxyFactory() {
            @Override
            public <T> T protect(Class<T> serviceInterface, T target) {
                ProxyWrapper.validateInterface(serviceInterface);
                return ProxyWrapper.createProxy(serviceInterface, target, name, asyncExt, syncExt);
            }
        };
    }

    static InqAsyncProxyFactory of(LayerAction<?, ?> syncAction,
                                   AsyncLayerAction<?, ?> asyncAction) {
        return of("proxy", syncAction, asyncAction);
    }

    /**
     * Creates a hybrid factory that routes service-interface methods through
     * the given {@link InqPipeline} with sync- and async-aware dispatch.
     *
     * <p>This is the pipeline-driven counterpart to
     * {@link #of(String, LayerAction, AsyncLayerAction)}. A single pipeline
     * drives both dispatch paths — async methods (returning
     * {@link CompletionStage}) flow through an {@link AsyncPipelineDispatchExtension},
     * sync methods through a {@link PipelineDispatchExtension}. The decision
     * is made per call by the standard extension-priority mechanism, not by
     * a per-method flag: {@code AsyncPipelineDispatchExtension.canHandle}
     * returns {@code true} only for {@code CompletionStage}-returning methods,
     * so it claims those calls; everything else falls through to the catch-all
     * sync extension.</p>
     *
     * <p>Pipeline elements must implement {@link InqDecorator} for sync
     * dispatch <em>and</em> {@link InqAsyncDecorator} for async dispatch.
     * Elements that do not satisfy the required contract are rejected with
     * a {@link ClassCastException} at the first matching dispatch — consistent
     * with the failure mode of the underlying extensions.</p>
     *
     * <p>The async and sync extensions are registered in this exact order:
     * async first (specific, {@code isCatchAll → false}), sync second
     * (catch-all, {@code isCatchAll → true}). The order is enforced by
     * {@link ProxyWrapper#createProxy ProxyWrapper}'s validation rule that the
     * catch-all must be last; reversing it would either fail validation or
     * route every method through the sync chain, breaking async semantics
     * silently.</p>
     *
     * <p>The resulting proxy implements both the service interface and the
     * {@link Wrapper} interface, so {@code chainId()}, {@code inner()}, and
     * {@code toStringHierarchy()} are available on every instance.</p>
     *
     * @param name     a human-readable name for the proxy layer
     * @param pipeline the pre-composed pipeline driving both dispatch paths
     * @return a new hybrid factory instance ready to create proxies
     * @throws IllegalArgumentException if {@code pipeline} is null
     */
    static InqAsyncProxyFactory of(String name, InqPipeline pipeline) {
        if (pipeline == null) {
            throw new IllegalArgumentException("Pipeline must not be null.");
        }

        // Order is critical: async (specific, non-catch-all) must come BEFORE
        // sync (catch-all). ProxyWrapper.validateExtensions enforces the
        // "catch-all must be last" rule, and dispatchServiceMethod iterates
        // extensions in registration order — first match wins. Reversing
        // these would either fail validation or route async methods through
        // the sync chain, breaking CompletionStage semantics silently.
        AsyncPipelineDispatchExtension asyncExt =
                new AsyncPipelineDispatchExtension(pipeline);
        PipelineDispatchExtension syncExt =
                new PipelineDispatchExtension(pipeline);
        return new InqAsyncProxyFactory() {
            @Override
            public <T> T protect(Class<T> serviceInterface, T target) {
                ProxyWrapper.validateInterface(serviceInterface);
                return ProxyWrapper.createProxy(
                        serviceInterface, target, name, asyncExt, syncExt);
            }
        };
    }

    /**
     * Creates a hybrid pipeline-driven factory with the default layer name
     * {@code "InqHybridPipelineProxy"}.
     *
     * <p>The default value matches the prefix that
     * {@code ProxyInvocationSupport.buildSummary(...)} produced for the
     * predecessor terminal-based mechanism, keeping {@code toString()} output
     * familiar to existing diagnostics.</p>
     *
     * @param pipeline the pre-composed pipeline driving both dispatch paths
     * @return a new hybrid factory instance with the default name
     * @throws IllegalArgumentException if {@code pipeline} is null
     */
    static InqAsyncProxyFactory of(InqPipeline pipeline) {
        return of("InqHybridPipelineProxy", pipeline);
    }

    <T> T protect(Class<T> serviceInterface, T target);
}
