package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.LayerAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles a {@link JoinPointWrapper} chain from an ordered list of named layers.
 *
 * <p>The builder collects layers via {@link #addLayer(String, LayerAction)} or
 * {@link #addProvider(AspectLayerProvider)}, then constructs the chain inside-out
 * when {@link #buildChain(JoinPointExecutor)} is called. The first added (or
 * lowest-order) layer becomes the outermost wrapper.</p>
 *
 * <h3>Chain construction</h3>
 * <p>The builder iterates the layer list in reverse order, wrapping each layer
 * around the previous result. This produces a chain where the first layer
 * is outermost:</p>
 * <pre>
 *   addLayer("A", actionA)  →  order 0 (outermost)
 *   addLayer("B", actionB)  →  order 1
 *   addLayer("C", actionC)  →  order 2 (innermost, wraps core)
 *
 *   Result chain: A → B → C → coreExecutor
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <p>The builder itself is <strong>not</strong> thread-safe — it is designed for
 * single-threaded construction. However, the resulting {@link JoinPointWrapper}
 * chain is immutable and fully thread-safe once built.</p>
 *
 * @param <R> the return type of the proxied method execution
 */
public class AspectPipelineBuilder<R> {

    /**
     * Ordered list of layers to apply. Maintains insertion order;
     * providers are sorted by {@link AspectLayerProvider#order()} when added
     * via {@link #addProviders(List)}.
     */
    private final List<NamedLayer<R>> layers = new ArrayList<>();

    /**
     * Adds a single layer with an explicit name and action.
     *
     * <p>Layers are applied in insertion order: the first added layer becomes
     * the outermost wrapper in the chain.</p>
     *
     * @param name   human-readable layer name for diagnostics
     * @param action the around-advice for this layer
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if name or action is null
     */
    public AspectPipelineBuilder<R> addLayer(String name, LayerAction<Void, R> action) {
        if (name == null) {
            throw new IllegalArgumentException("Layer name must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("Layer action must not be null");
        }
        layers.add(new NamedLayer<>(name, action));
        return this;
    }

    /**
     * Adds a single layer from an {@link AspectLayerProvider}.
     *
     * <p>The provider's {@link AspectLayerProvider#layerName()} and
     * {@link AspectLayerProvider#layerAction()} are extracted immediately.
     * Note: when using this method, the caller is responsible for ordering.
     * For automatic ordering by priority, use {@link #addProviders(List)}.</p>
     *
     * @param provider the layer provider to add
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if provider is null
     */
    public AspectPipelineBuilder<R> addProvider(AspectLayerProvider<R> provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null");
        }
        return addLayer(provider.layerName(), provider.layerAction());
    }

    /**
     * Adds multiple layer providers, sorted by their {@link AspectLayerProvider#order()}.
     *
     * <p>The providers are sorted using a stable sort, so providers with equal
     * order values retain their relative position from the input list.</p>
     *
     * @param providers the layer providers to add (order is determined by
     *                  each provider's {@code order()} value, not list position)
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if providers is null or contains null elements
     */
    public AspectPipelineBuilder<R> addProviders(List<? extends AspectLayerProvider<R>> providers) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }
        List<? extends AspectLayerProvider<R>> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparingInt(AspectLayerProvider::order));
        for (AspectLayerProvider<R> provider : sorted) {
            addProvider(provider);
        }
        return this;
    }

    /**
     * Returns an unmodifiable view of the currently registered layers.
     *
     * <p>Useful for testing and diagnostics — allows verification of
     * the layer order before building the chain.</p>
     *
     * @return an unmodifiable list of layer name/action pairs
     */
    public List<NamedLayer<R>> layers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * Builds a {@link JoinPointWrapper} chain from the registered layers.
     *
     * <p>If no layers have been registered, a single pass-through wrapper
     * is returned. Otherwise, layers are assembled inside-out: the last
     * layer in the list wraps the core executor directly, and each preceding
     * layer wraps the next one.</p>
     *
     * @param coreExecutor the terminal execution point (typically {@code pjp::proceed})
     * @return the outermost wrapper of the assembled chain
     * @throws IllegalArgumentException if coreExecutor is null
     */
    public JoinPointWrapper<R> buildChain(JoinPointExecutor<R> coreExecutor) {
        if (coreExecutor == null) {
            throw new IllegalArgumentException("Core executor must not be null");
        }

        if (layers.isEmpty()) {
            return new JoinPointWrapper<>("passthrough", coreExecutor);
        }

        // Build inside-out: last registered layer wraps the core executor,
        // each preceding layer wraps the result of the previous iteration.
        JoinPointWrapper<R> current = null;
        for (int i = layers.size() - 1; i >= 0; i--) {
            NamedLayer<R> layer = layers.get(i);
            JoinPointExecutor<R> delegate = (current != null) ? current : coreExecutor;
            current = new JoinPointWrapper<>(layer.name(), delegate, layer.action());
        }
        return current;
    }

    /**
     * A named layer pair — associates a human-readable name with a {@link LayerAction}.
     *
     * @param name   the layer name for diagnostics
     * @param action the around-advice logic
     * @param <R>    the return type of the chain
     */
    public record NamedLayer<R>(String name, LayerAction<Void, R> action) {

        /**
         * Compact constructor with null validation.
         */
        public NamedLayer {
            if (name == null) {
                throw new IllegalArgumentException("Layer name must not be null");
            }
            if (action == null) {
                throw new IllegalArgumentException("Layer action must not be null");
            }
        }
    }
}
