package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;

import java.util.*;
import java.util.function.BiFunction;

/**
 * An immutable, ordered composition of {@link InqElement} instances that form
 * a resilience pipeline.
 *
 * <p>{@code InqPipeline} is a <strong>pure composition object</strong> — it
 * collects elements, sorts them by {@link PipelineOrdering}, and exposes the
 * ordered list. It knows nothing about execution paradigms (sync, async,
 * reactive) or dispatch mechanisms (functions, dynamic proxy, AspectJ).
 * Terminal operations live in paradigm-specific adapters.</p>
 *
 * <h3>Terminal landscape</h3>
 * <pre>
 *                              InqPipeline
 *                                  │
 *               ┌──────────────────┼───────────────────────┐
 *               │                  │                       │
 *          Sync only           Async only              Hybrid (auto)
 *               │                  │                       │
 *     ┌─────────┴──────┐           │          ┌────────────┴────────────┐
 *     │                │           │          │                         │
 * SyncPipeline   ProxyPipeline     │    HybridProxy              HybridAspect
 *  Terminal       Terminal         │    PipelineTerminal          PipelineTerminal
 *  (core)         (core)           │    (imperative)              (aspect)
 *     │                │           │         │                         │
 *     ▼                ▼           │    ┌────┴────┐              ┌────┴────┐
 *  execute()     protect()         │  sync?  async?            sync?  async?
 *                (Proxy)           │    │       │                │       │
 *                                  │    ▼       ▼                ▼       ▼
 *                           AsyncPipeline  Sync    Async     Sync    Async
 *                            Terminal     Term.    Term.     Term.    Term.
 *                           (imperative)
 * </pre>
 *
 * <h3>Terminal matrix</h3>
 * <pre>
 *                     Functions       Dynamic Proxy        AspectJ
 *                  ┌──────────────┬──────────────────┬──────────────────┐
 *   Sync           │ SyncPipeline │ ProxyPipeline    │ AspectPipeline   │
 *                  │ Terminal     │ Terminal         │ Terminal         │
 *                  ├──────────────┼──────────────────┼──────────────────┤
 *   Async          │ AsyncPipeline│       —          │       —          │
 *                  │ Terminal     │                  │                  │
 *                  ├──────────────┼──────────────────┼──────────────────┤
 *   Hybrid         │      —¹      │ HybridProxy      │ HybridAspect     │
 *   (Sync+Async)   │              │ PipelineTerminal │ PipelineTerminal │
 *                  ├──────────────┼──────────────────┼──────────────────┤
 *   Reactive       │      —²      │       —²         │       —²         │
 *   Kotlin Coro.   │      —²      │       —²         │       —²         │
 *                  └──────────────┴──────────────────┴──────────────────┘
 *
 *   ¹ Not needed — the caller chooses sync or async terminal explicitly.
 *   ² Future paradigm modules; same pattern: new decorator + new terminal.
 * </pre>
 *
 * <p>All terminals use the same {@link #chain} fold mechanism — the only
 * difference is which {@code decorateXxx()} method they invoke on each
 * element.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(circuitBreaker)
 *         .shield(retry)
 *         .shield(rateLimiter)
 *         .order(PipelineOrdering.standard())
 *         .build();
 *
 * // Sync terminal (inqudium-core)
 * SyncPipelineTerminal.of(pipeline).execute(() -> service.call());
 *
 * // Dynamic proxy terminal (inqudium-core)
 * ProxyPipelineTerminal.of(pipeline).protect(MyService.class, target);
 *
 * // Async terminal (inqudium-imperative)
 * AsyncPipelineTerminal.of(pipeline).execute(() -> service.callAsync());
 *
 * // Hybrid proxy — auto-dispatches sync vs async (inqudium-imperative)
 * HybridProxyPipelineTerminal.of(pipeline).protect(MyService.class, target);
 *
 * // AspectJ terminal (inqudium-aspect)
 * AspectPipelineTerminal.of(pipeline).executeAround(pjp);
 *
 * // Hybrid AspectJ — auto-dispatches sync vs async (inqudium-aspect)
 * HybridAspectPipelineTerminal.of(pipeline).executeAround(pjp);
 * }</pre>
 *
 * <h3>Ordering</h3>
 * <p>Elements are sorted by {@link PipelineOrdering#orderFor(InqElementType)}.
 * Lower order values become outermost layers (first to intercept, last to
 * release). The default ordering is {@link PipelineOrdering#standard()},
 * which implements ADR-017's canonical sequence:</p>
 * <pre>
 *   TimeLimiter → TrafficShaper → RateLimiter → Bulkhead → CircuitBreaker → Retry
 *   (outermost)                                                         (innermost)
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <p>{@code InqPipeline} is immutable after construction. The builder is
 * not thread-safe, but the built pipeline can be shared freely.</p>
 *
 * @since 0.8.0
 */
public final class InqPipeline {

    private final List<InqElement> elements;
    private final PipelineOrdering ordering;

    private InqPipeline(List<InqElement> elements, PipelineOrdering ordering) {
        this.elements = List.copyOf(elements);
        this.ordering = ordering;
    }

    // ======================== Factory ========================

    /**
     * Creates a new pipeline builder.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ======================== Composition result ========================

    /**
     * Returns the ordered, immutable list of elements in this pipeline.
     *
     * <p>Elements are sorted outermost-first (lowest
     * {@link PipelineOrdering#orderFor} value first). The outermost element
     * intercepts the call first and releases last.</p>
     *
     * @return the ordered element list, never {@code null}, may be empty
     */
    public List<InqElement> elements() {
        return elements;
    }

    /**
     * Returns the ordering used to sort the elements.
     *
     * @return the pipeline ordering
     */
    public PipelineOrdering ordering() {
        return ordering;
    }

    /**
     * Returns the number of elements in this pipeline.
     *
     * @return the element count
     */
    public int depth() {
        return elements.size();
    }

    /**
     * Returns {@code true} if this pipeline contains no elements.
     *
     * <p>An empty pipeline is valid — terminal operations pass through
     * directly to the core executor.</p>
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    // ======================== Generic fold ========================

    /**
     * Folds the pipeline elements into a decorator chain, starting from the
     * innermost element and working outward.
     *
     * <p>This is the paradigm-agnostic building block that all terminals use.
     * The {@code folder} function receives the accumulated chain and the
     * current element, returning the next chain link:</p>
     *
     * <pre>{@code
     * // Sync: fold InqDecorator.decorateJoinPoint
     * JoinPointExecutor<R> chain = pipeline.chain(coreExecutor,
     *         (downstream, element) -> ((InqDecorator<Void, R>) element)
     *                 .decorateJoinPoint(downstream));
     *
     * // Async: fold InqAsyncDecorator.decorateAsyncJoinPoint
     * JoinPointExecutor<CompletionStage<R>> chain = pipeline.chain(asyncExecutor,
     *         (downstream, element) -> ((InqAsyncDecorator<Void, R>) element)
     *                 .decorateAsyncJoinPoint(downstream));
     * }</pre>
     *
     * <p>The iteration starts at the <strong>innermost</strong> element (highest
     * order value) and works outward. This produces the correct nesting:
     * the outermost element wraps all others.</p>
     *
     * @param seed   the core executor at the bottom of the chain
     * @param folder combines an element with the accumulated chain;
     *               receives {@code (accumulatedChain, currentElement)}
     * @param <T>    the chain type (e.g. {@code JoinPointExecutor<R>})
     * @return the fully composed chain, with the outermost element on top
     */
    public <T> T chain(T seed, BiFunction<T, InqElement, T> folder) {
        T result = seed;
        // Iterate innermost-first: reverse of the outermost-first list
        ListIterator<InqElement> it = elements.listIterator(elements.size());
        while (it.hasPrevious()) {
            result = folder.apply(result, it.previous());
        }
        return result;
    }

    // ======================== Diagnostics ========================

    /**
     * Returns a human-readable summary of the pipeline structure.
     *
     * <pre>
     * InqPipeline [3 elements, standard ordering]
     *   TIME_LIMITER  "paymentTl"      (order=100)
     *   CIRCUIT_BREAKER "paymentCb"    (order=500)
     *   RETRY         "paymentRetry"   (order=600)
     * </pre>
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("InqPipeline [")
                .append(elements.size())
                .append(elements.size() == 1 ? " element" : " elements")
                .append("]\n");

        for (InqElement element : elements) {
            InqElementType type = element.getElementType();
            sb.append("  ")
                    .append(String.format("%-18s", type))
                    .append('"').append(element.getName()).append('"')
                    .append("  (order=").append(ordering.orderFor(type)).append(")")
                    .append('\n');
        }
        return sb.toString();
    }

    // ======================== Builder ========================

    /**
     * Mutable builder for constructing an {@link InqPipeline}.
     *
     * <p>Elements can be added in any order — the pipeline sorts them
     * according to the configured {@link PipelineOrdering} at build time.
     * If no ordering is set, {@link PipelineOrdering#standard()} is used.</p>
     *
     * <p>The builder is not thread-safe. The built pipeline is immutable.</p>
     */
    public static final class Builder {

        private final List<InqElement> elements = new ArrayList<>();
        private PipelineOrdering ordering;

        private Builder() {
        }

        /**
         * Adds a resilience element to the pipeline.
         *
         * <p>The element's position in the final pipeline is determined by
         * the {@link PipelineOrdering} at build time — the order of
         * {@code shield()} calls is irrelevant when using a predefined
         * ordering.</p>
         *
         * @param element the element to add
         * @return this builder
         * @throws NullPointerException if element is null
         */
        public Builder shield(InqElement element) {
            Objects.requireNonNull(element, "Element must not be null");
            elements.add(element);
            return this;
        }

        /**
         * Adds multiple elements at once.
         *
         * @param elements the elements to add
         * @return this builder
         */
        public Builder shieldAll(InqElement... elements) {
            for (InqElement element : elements) {
                shield(element);
            }
            return this;
        }

        /**
         * Adds multiple elements from a collection.
         *
         * @param elements the elements to add
         * @return this builder
         */
        public Builder shieldAll(Iterable<? extends InqElement> elements) {
            for (InqElement element : elements) {
                shield(element);
            }
            return this;
        }

        /**
         * Sets the pipeline ordering.
         *
         * <p>Determines how elements are sorted in the built pipeline.
         * If not called, {@link PipelineOrdering#standard()} is used.</p>
         *
         * @param ordering the ordering strategy
         * @return this builder
         * @throws NullPointerException if ordering is null
         */
        public Builder order(PipelineOrdering ordering) {
            this.ordering = Objects.requireNonNull(ordering, "Ordering must not be null");
            return this;
        }

        /**
         * Builds the immutable pipeline.
         *
         * <p>Elements are sorted by the configured ordering. Elements with
         * equal order values retain their {@code shield()} registration
         * order (stable sort).</p>
         *
         * @return the built pipeline
         */
        public InqPipeline build() {
            PipelineOrdering effectiveOrdering = ordering != null
                    ? ordering
                    : PipelineOrdering.standard();

            List<InqElement> sorted = new ArrayList<>(elements);
            sorted.sort(Comparator.comparingInt(
                    e -> effectiveOrdering.orderFor(e.getElementType())));

            return new InqPipeline(sorted, effectiveOrdering);
        }
    }
}
