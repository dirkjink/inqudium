package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.AsyncJoinPointWrapper;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Assembles an {@link AsyncJoinPointWrapper} chain from an ordered list of
 * async named layers.
 *
 * <p>The async counterpart to {@link AspectPipelineBuilder}. Collects
 * {@link AsyncLayerAction} instances and constructs the chain inside-out
 * when {@link #buildChain(JoinPointExecutor)} is called.</p>
 *
 * <h3>Chain construction</h3>
 * <p>Identical to the synchronous builder — layers are iterated in reverse
 * order, wrapping each layer around the previous result:</p>
 * <pre>
 *   addLayer("BULKHEAD", bulkheadAction)  →  order 0 (outermost)
 *   addLayer("TIMING",   timingAction)    →  order 1 (innermost, wraps core)
 *
 *   Result chain: BULKHEAD → TIMING → coreExecutor
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <p>The builder itself is <strong>not</strong> thread-safe. The resulting
 * {@link AsyncJoinPointWrapper} chain is immutable and fully thread-safe.</p>
 *
 * @param <R> the result type carried by the {@link CompletionStage}
 */
public class AsyncAspectPipelineBuilder<R> {

    private final List<NamedAsyncLayer<R>> layers = new ArrayList<>();

    /**
     * Adds a single async layer with an explicit name and action.
     *
     * @param name   human-readable layer name for diagnostics
     * @param action the async around-advice for this layer
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if name or action is null
     */
    public AsyncAspectPipelineBuilder<R> addLayer(String name, AsyncLayerAction<Void, R> action) {
        if (name == null) {
            throw new IllegalArgumentException("Layer name must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("Layer action must not be null");
        }
        layers.add(new NamedAsyncLayer<>(name, action));
        return this;
    }

    /**
     * Adds a single layer from an {@link AsyncAspectLayerProvider}.
     *
     * @param provider the async layer provider to add
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if provider is null
     */
    public AsyncAspectPipelineBuilder<R> addProvider(AsyncAspectLayerProvider<R> provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null");
        }
        return addLayer(provider.layerName(), provider.asyncLayerAction());
    }

    /**
     * Adds multiple async layer providers, sorted by their {@link AsyncAspectLayerProvider#order()}.
     *
     * <p>Uses a stable sort — providers with equal order values retain their
     * relative position from the input list.</p>
     *
     * @param providers the async layer providers to add
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if providers is null or contains null elements
     */
    public AsyncAspectPipelineBuilder<R> addProviders(List<? extends AsyncAspectLayerProvider<R>> providers) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i) == null) {
                throw new IllegalArgumentException(
                        "Provider at index " + i + " must not be null");
            }
        }
        List<? extends AsyncAspectLayerProvider<R>> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparingInt(AsyncAspectLayerProvider::order));
        for (AsyncAspectLayerProvider<R> provider : sorted) {
            addProvider(provider);
        }
        return this;
    }

    /**
     * Adds multiple async layer providers filtered by
     * {@link AsyncAspectLayerProvider#canHandle(Method)} and sorted by order.
     *
     * <p>Only providers whose {@code canHandle(method)} returns {@code true} are
     * included in the async pipeline.</p>
     *
     * @param providers the candidate async layer providers
     * @param method    the target method used to filter providers via {@code canHandle}
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if providers or method is null
     */
    public AsyncAspectPipelineBuilder<R> addProviders(
            List<? extends AsyncAspectLayerProvider<R>> providers, Method method) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("Method must not be null");
        }
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i) == null) {
                throw new IllegalArgumentException(
                        "Provider at index " + i + " must not be null");
            }
        }
        List<? extends AsyncAspectLayerProvider<R>> filtered = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AsyncAspectLayerProvider::order))
                .toList();
        for (AsyncAspectLayerProvider<R> provider : filtered) {
            addProvider(provider);
        }
        return this;
    }

    /**
     * Returns an unmodifiable view of the currently registered async layers.
     *
     * @return an unmodifiable list of async layer name/action pairs
     */
    public List<NamedAsyncLayer<R>> layers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * Builds an {@link AsyncJoinPointWrapper} chain from the registered layers.
     *
     * <p>If no layers have been registered, a single pass-through wrapper
     * is returned. Otherwise, layers are assembled inside-out.</p>
     *
     * @param coreExecutor the terminal async execution point (typically
     *                     {@code pjp::proceed} where proceed returns a
     *                     {@link CompletionStage})
     * @return the outermost wrapper of the assembled async chain
     * @throws IllegalArgumentException if coreExecutor is null
     */
    public AsyncJoinPointWrapper<R> buildChain(JoinPointExecutor<CompletionStage<R>> coreExecutor) {
        if (coreExecutor == null) {
            throw new IllegalArgumentException("Core executor must not be null");
        }

        if (layers.isEmpty()) {
            return new AsyncJoinPointWrapper<>("passthrough", coreExecutor);
        }

        // Build inside-out: last registered layer wraps the core executor,
        // each preceding layer wraps the result of the previous iteration.
        AsyncJoinPointWrapper<R> current = null;
        for (int i = layers.size() - 1; i >= 0; i--) {
            NamedAsyncLayer<R> layer = layers.get(i);
            JoinPointExecutor<CompletionStage<R>> delegate =
                    (current != null) ? current : coreExecutor;
            current = new AsyncJoinPointWrapper<>(layer.name(), delegate, layer.action());
        }
        return current;
    }

    /**
     * A named async layer pair.
     *
     * @param name   the layer name for diagnostics
     * @param action the async around-advice logic
     * @param <R>    the result type of the chain
     */
    public record NamedAsyncLayer<R>(String name, AsyncLayerAction<Void, R> action) {

        public NamedAsyncLayer {
            if (name == null) {
                throw new IllegalArgumentException("Layer name must not be null");
            }
            if (action == null) {
                throw new IllegalArgumentException("Layer action must not be null");
            }
        }
    }
}
